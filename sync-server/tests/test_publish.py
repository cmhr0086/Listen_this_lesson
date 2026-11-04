from __future__ import annotations

import gzip
import json
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.main import create_app as create_sync_app
from publish import readers
from publish.main import create_app

ADMIN_TOKEN = "test-admin-token"


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLISH_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WECHAT_APPID", "wx-test")
    monkeypatch.setenv("WECHAT_SECRET", "secret")
    monkeypatch.setenv("PUBLISH_MATERIALS_DIR", str(tmp_path / "materials"))
    # wx.login codes stand in for openids, so a test can sign the same person in twice.
    monkeypatch.setattr(readers, "code_to_openid", lambda appid, secret, code: f"openid-{code}")
    app = create_app(f"sqlite:///{tmp_path / 'publish-test.db'}")
    with TestClient(app) as value:
        yield value


def admin(client, method, path, **kwargs):
    response = client.request(method, "/api/admin/v1" + path, headers={"Authorization": f"Bearer {ADMIN_TOKEN}"}, **kwargs)
    assert response.status_code == 200, response.text
    return response.json()


def new_uploader(client, name="小林"):
    return admin(client, "POST", "/uploaders", json={"displayName": name})["token"]


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def sign_in(client, code="me"):
    response = client.post("/api/public/v1/auth/wechat", json={"code": code})
    assert response.status_code == 200, response.text
    return response.json()


def transcript_reader(client, code="me"):
    """Signs a reader in, requests transcript access and has the admin approve it."""
    login = sign_in(client, code)
    client.post("/api/public/v1/me/transcript-access", headers=auth(login["token"]), json={"note": "我"})
    admin(client, "POST", f"/readers/{login['reader']['readerId']}/approve")
    return auth(login["token"])


def submission(*, session_id=None, title="第一课 极限", segments=None, notes=None, course=None, **extra):
    return {
        "clientSubmissionId": str(uuid4()),
        "course": course or {"schoolName": "某某大学", "term": "2026-2027 第一学期", "courseName": "高等数学", "teacherName": "张老师"},
        "session": {"sessionId": session_id or str(uuid4()), "name": title, "topic": "函数极限", "startedAt": 1_760_000_000_000, "endedAt": 1_760_005_400_000},
        "segments": segments if segments is not None else [
            {"startTime": 1_760_000_010_000, "endTime": 1_760_000_020_000, "text": "今天讲极限的定义", "correctedText": None, "marked": False},
            {"startTime": 1_760_000_030_000, "endTime": 1_760_000_040_000, "text": "一普西龙德尔塔语言", "correctedText": "ε-δ 语言", "marked": True},
        ],
        "notes": notes if notes is not None else [{"noteId": "s:1", "title": "课堂笔记", "content": "# 极限\n- ε-δ 定义", "format": "markdown"}],
        **extra,
    }


def submit(client, token, body):
    response = client.post("/api/publish/v1/submissions", headers=auth(token), json=body)
    assert response.status_code == 200, response.text
    return response.json()


def approve(client, course_id, note=None):
    return admin(client, "POST", f"/courses/{course_id}/approve", json={"note": note})


def publish_one(client, token=None, **kwargs):
    token = token or new_uploader(client)
    result = submit(client, token, submission(**kwargs))
    if result["courseStatus"] != "approved":
        approve(client, result["courseId"])
    return token, result


# ---- auth --------------------------------------------------------------------------------


def test_upload_and_admin_need_their_own_tokens(client):
    body = submission()
    assert client.post("/api/publish/v1/submissions", json=body).status_code == 401
    assert client.post("/api/publish/v1/submissions", headers=auth("lp_wrong"), json=body).status_code == 401
    assert client.get("/api/admin/v1/courses").status_code == 401
    token = new_uploader(client)
    # An upload token is not an admin token.
    assert client.get("/api/admin/v1/courses", headers=auth(token)).status_code == 401
    assert client.get("/api/publish/v1/me", headers=auth(token)).json() == {"displayName": "小林", "status": "active"}


def test_admin_api_fails_closed_without_configured_token(tmp_path, monkeypatch):
    monkeypatch.delenv("PUBLISH_ADMIN_TOKEN", raising=False)
    with TestClient(create_app(f"sqlite:///{tmp_path / 'no-admin.db'}")) as value:
        assert value.get("/api/admin/v1/courses", headers=auth("")).status_code == 401
        assert value.get("/api/admin/v1/courses", headers=auth("anything")).status_code == 401


def test_disabled_and_rotated_tokens_stop_working(client):
    created = admin(client, "POST", "/uploaders", json={"displayName": "小林"})
    old = created["token"]
    assert created["tokenHint"] == old[-4:]
    rotated = admin(client, "POST", f"/uploaders/{created['id']}/rotate-token")["token"]
    assert client.get("/api/publish/v1/me", headers=auth(old)).status_code == 401
    assert client.get("/api/publish/v1/me", headers=auth(rotated)).status_code == 200
    admin(client, "POST", f"/uploaders/{created['id']}/disable")
    assert client.get("/api/publish/v1/me", headers=auth(rotated)).status_code == 401
    # The token itself is never listed again.
    assert "token" not in admin(client, "GET", "/uploaders")["uploaders"][0]


# ---- review flow -------------------------------------------------------------------------


def test_lessons_stay_hidden_until_their_course_is_approved(client):
    token = new_uploader(client)
    result = submit(client, token, submission())
    assert (result["status"], result["courseStatus"]) == ("published", "pending")
    assert client.get("/api/public/v1/courses").json() == {"courses": []}
    assert client.get(f"/api/public/v1/lessons/{result['lessonId']}").status_code == 404
    assert client.get("/api/public/v1/search", params={"q": "极限"}).json()["hits"] == []
    assert client.get(f"/api/public/v1/lessons/{result['lessonId']}/export.txt").status_code == 404
    queue = admin(client, "GET", "/courses")["courses"]
    assert [(c["courseName"], c["requestedBy"], c["lessonCount"]) for c in queue] == [("高等数学", "小林", 1)]

    approve(client, result["courseId"])
    courses = client.get("/api/public/v1/courses").json()["courses"]
    assert [(c["courseName"], c["teacherName"], c["lessonCount"]) for c in courses] == [("高等数学", "张老师", 1)]
    detail = client.get(f"/api/public/v1/lessons/{result['lessonId']}", headers=transcript_reader(client)).json()
    assert detail["title"] == "第一课 极限"
    assert detail["uploader"] == "小林"
    assert [s["displayText"] for s in detail["segments"]] == ["今天讲极限的定义", "ε-δ 语言"]
    assert [s["marked"] for s in detail["segments"]] == [False, True]
    assert detail["notes"][0]["content"].startswith("# 极限")


def test_approved_course_takes_new_lessons_and_updates_at_once(client):
    token, first = publish_one(client)
    second = submit(client, token, submission(title="第二课 导数"))
    assert second["courseStatus"] == "approved"
    assert client.get(f"/api/public/v1/lessons/{second['lessonId']}").status_code == 200
    session_id = client.get(f"/api/publish/v1/submissions", headers=auth(token), params={"sessionId": "x"}).json()
    assert session_id["lessonId"] is None


def test_resubmitting_a_lesson_replaces_it_for_readers(client):
    session_id = str(uuid4())
    token, first = publish_one(client, session_id=session_id)
    again = submit(client, token, submission(session_id=session_id, title="第一课 极限（修订）"))
    assert again["lessonId"] == first["lessonId"] and again["revision"] == 2
    assert client.get(f"/api/public/v1/lessons/{first['lessonId']}").json()["title"] == "第一课 极限（修订）"
    statuses = [r["status"] for r in client.get(
        "/api/publish/v1/submissions", headers=auth(token), params={"sessionId": session_id}
    ).json()["revisions"]]
    assert statuses == ["approved", "superseded"]


def test_rejected_course_reaches_uploader_and_can_be_requested_again(client):
    token = new_uploader(client)
    result = submit(client, token, submission())
    admin(client, "POST", f"/courses/{result['courseId']}/reject", json={"note": "教师姓名请写全"})
    status = client.get(f"/api/publish/v1/courses/{result['courseId']}", headers=auth(token)).json()
    assert (status["status"], status["reviewNote"]) == ("rejected", "教师姓名请写全")
    # Later uploads into a rejected course say so, and stay hidden.
    later = submit(client, token, submission(title="第二课"))
    assert (later["courseStatus"], later["courseReviewNote"]) == ("rejected", "教师姓名请写全")
    assert client.get(f"/api/public/v1/lessons/{later['lessonId']}").status_code == 404

    again = client.post(f"/api/publish/v1/courses/{result['courseId']}/request-review", headers=auth(token))
    assert again.json()["status"] == "pending"
    assert client.post(f"/api/publish/v1/courses/{result['courseId']}/request-review", headers=auth(token)).status_code == 409
    other = new_uploader(client, "乙")
    assert client.post(f"/api/publish/v1/courses/{result['courseId']}/request-review", headers=auth(other)).status_code == 404
    # The admin sees it is back with the note it was rejected with.
    queued = admin(client, "GET", "/courses")["courses"][0]
    assert (queued["reRequested"], queued["reviewNote"]) == (True, "教师姓名请写全")
    approve(client, result["courseId"])
    assert admin(client, "GET", f"/courses/{result['courseId']}")["course"]["reRequested"] is False


def test_admin_queue_is_first_come_first_served_with_tab_counts(client):
    token = new_uploader(client)
    first = submit(client, token, submission(course={"schoolName": "某某大学", "term": "T", "courseName": "甲课", "teacherName": "张"}))
    submit(client, token, submission(course={"schoolName": "某某大学", "term": "T", "courseName": "乙课", "teacherName": "张"}))
    assert [c["courseName"] for c in admin(client, "GET", "/courses")["courses"]] == ["甲课", "乙课"]
    summary = admin(client, "GET", "/summary")
    assert {k: summary[k] for k in ("pendingCourses", "pendingReaders", "withdrawRequests")} == {"pendingCourses": 2, "pendingReaders": 0, "withdrawRequests": 0}
    approve(client, first["courseId"])
    client.post(f"/api/publish/v1/lessons/{first['lessonId']}/withdraw-request", headers=auth(token), json={"reason": "录错了"})
    summary = admin(client, "GET", "/summary")
    assert {k: summary[k] for k in ("pendingCourses", "pendingReaders", "withdrawRequests")} == {"pendingCourses": 1, "pendingReaders": 0, "withdrawRequests": 1}
    row = admin(client, "GET", "/uploaders")["uploaders"][0]
    assert sorted(row["courseNames"]) == sorted(["甲课", "乙课"])
    assert row["lastUploadAt"] is not None


def test_course_takedown_hides_all_its_lessons_and_approve_restores(client):
    _, result = publish_one(client)
    admin(client, "POST", f"/courses/{result['courseId']}/takedown", json={"note": "内容不符"})
    assert client.get(f"/api/public/v1/lessons/{result['lessonId']}").status_code == 404
    assert client.get("/api/public/v1/courses").json()["courses"] == []
    response = client.post(f"/api/admin/v1/courses/{result['courseId']}/reject", headers=auth(ADMIN_TOKEN))
    assert response.status_code == 409  # only pending courses can be rejected
    approve(client, result["courseId"])
    assert client.get(f"/api/public/v1/lessons/{result['lessonId']}").status_code == 200


def test_admin_course_detail_lists_lessons_with_content(client):
    _, result = publish_one(client)
    detail = admin(client, "GET", f"/courses/{result['courseId']}")
    assert [(l["title"], l["segmentCount"], l["noteCount"]) for l in detail["lessons"]] == [("第一课 极限", 2, 1)]
    lesson = admin(client, "GET", f"/lessons/{result['lessonId']}")
    assert lesson["segments"][1]["displayText"] == "ε-δ 语言"


def test_retry_and_identical_content_do_not_create_versions(client):
    token, first = publish_one(client)
    body = submission()
    body["session"]["sessionId"] = str(uuid4())
    created = submit(client, token, body)
    assert submit(client, token, body) == created  # same clientSubmissionId: a network retry
    unchanged = submit(client, token, {**body, "clientSubmissionId": str(uuid4())})
    assert unchanged["status"] == "unchanged" and unchanged["revisionId"] == created["revisionId"]


def test_same_session_from_two_uploaders_are_separate_lessons(client):
    session_id = str(uuid4())
    _, a = publish_one(client, token=new_uploader(client, "甲"), session_id=session_id)
    _, b = publish_one(client, token=new_uploader(client, "乙"), session_id=session_id)
    assert a["lessonId"] != b["lessonId"]


def test_submission_id_cannot_be_reused_by_another_uploader(client):
    body = submission()
    submit(client, new_uploader(client, "甲"), body)
    response = client.post("/api/publish/v1/submissions", headers=auth(new_uploader(client, "乙")), json=body)
    assert response.status_code == 409


def test_lesson_takedown_hides_everywhere_and_restore_brings_it_back(client):
    token, result = publish_one(client)
    lesson_id = result["lessonId"]
    response = client.post(f"/api/publish/v1/lessons/{lesson_id}/withdraw-request", headers=auth(token), json={"reason": "录错课了"})
    assert response.status_code == 200
    assert admin(client, "GET", "/lessons?withdrawRequested=true")["lessons"][0]["withdrawReason"] == "录错课了"

    admin(client, "POST", f"/lessons/{lesson_id}/takedown")
    assert client.get(f"/api/public/v1/lessons/{lesson_id}").status_code == 404
    assert client.get("/api/public/v1/courses").json()["courses"] == []
    assert client.get("/api/public/v1/search", params={"q": "极限"}).json()["hits"] == []
    assert client.get(f"/api/public/v1/lessons/{lesson_id}/export.txt").status_code == 404
    assert admin(client, "GET", "/lessons?withdrawRequested=true")["lessons"] == []

    admin(client, "POST", f"/lessons/{lesson_id}/restore")
    assert client.get(f"/api/public/v1/lessons/{lesson_id}").status_code == 200


def test_uploader_cannot_withdraw_someone_elses_lesson(client):
    _, result = publish_one(client)
    other = new_uploader(client, "乙")
    response = client.post(f"/api/publish/v1/lessons/{result['lessonId']}/withdraw-request", headers=auth(other), json={"reason": "x"})
    assert response.status_code == 404


# ---- validation ------------------------------------------------------------------------


@pytest.mark.parametrize(
    "change",
    [
        lambda b: b["course"].update(teacherName="  "),
        lambda b: b["session"].update(sessionId="not-a-uuid"),
        lambda b: b.update(segments=[], notes=[]),
        lambda b: b["segments"][0].update(endTime=0),
        lambda b: b.update(notes=[b["notes"][0], b["notes"][0]]),
        lambda b: b["session"].update(deviceId="leak"),
        lambda b: b["segments"][0].update(asrJobId="job-1"),
    ],
)
def test_bad_submissions_are_rejected(client, change):
    body = submission()
    change(body)
    response = client.post("/api/publish/v1/submissions", headers=auth(new_uploader(client)), json=body)
    assert response.status_code == 422, response.text


def test_gzip_submission_is_accepted(client):
    token = new_uploader(client)
    raw = gzip.compress(json.dumps(submission()).encode())
    response = client.post(
        "/api/publish/v1/submissions",
        headers={**auth(token), "Content-Encoding": "gzip", "Content-Type": "application/json"},
        content=raw,
    )
    assert response.status_code == 200, response.text


def test_courses_group_by_all_four_fields_after_normalizing(client):
    token = new_uploader(client)
    base = {"schoolName": "某某大学", "term": "2026-2027 第一学期", "courseName": "高等数学", "teacherName": "张老师"}
    spaced = {**base, "courseName": " 高等数学 ", "term": "2026-2027  第一学期"}
    other_teacher = {**base, "teacherName": "李老师"}
    for course in (base, spaced, other_teacher):
        result = submit(client, token, submission(course=course))
        if result["courseStatus"] == "pending":
            approve(client, result["courseId"])
    courses = client.get("/api/public/v1/courses").json()["courses"]
    counts = sorted((c["teacherName"], c["lessonCount"]) for c in courses)
    assert counts == [("张老师", 2), ("李老师", 1)]
    known = client.get("/api/publish/v1/courses", headers=auth(token)).json()["courses"]
    assert len(known) == 2


# ---- public reading --------------------------------------------------------------------


def test_lessons_page_newest_first_with_cursor(client):
    token = new_uploader(client)
    ids = []
    for day in range(3):
        body = submission(title=f"第{day + 1}课")
        body["session"]["startedAt"] += day * 86_400_000
        result = submit(client, token, body)
        if result["courseStatus"] == "pending":
            approve(client, result["courseId"])
        ids.append(result["lessonId"])
    course_id = client.get("/api/public/v1/courses").json()["courses"][0]["courseId"]
    first = client.get(f"/api/public/v1/courses/{course_id}/lessons", params={"limit": 2}).json()
    assert [lesson["title"] for lesson in first["lessons"]] == ["第3课", "第2课"]
    assert first["lessons"][0]["markedCount"] == 1
    assert first["lessons"][0]["noteCount"] == 1
    second = client.get(f"/api/public/v1/courses/{course_id}/lessons", params={"limit": 2, "cursor": first["nextCursor"]}).json()
    assert [lesson["title"] for lesson in second["lessons"]] == ["第1课"]
    assert second["nextCursor"] is None
    assert client.get("/api/public/v1/courses/missing/lessons").status_code == 404
    assert client.get(f"/api/public/v1/courses/{course_id}/lessons", params={"cursor": "%%%"}).status_code == 400


def test_search_finds_corrected_text_and_notes_and_escapes_wildcards(client):
    _, result = publish_one(client)
    hits = client.get("/api/public/v1/search", params={"q": "ε-δ"}, headers=transcript_reader(client)).json()["hits"]
    assert {hit["kind"] for hit in hits} == {"segment", "note"}
    segment = next(hit for hit in hits if hit["kind"] == "segment")
    assert segment["seq"] == 1 and segment["lessonId"] == result["lessonId"]
    assert segment["snippet"][segment["matchOffset"]:segment["matchOffset"] + segment["matchLength"]] == "ε-δ"
    # The raw ASR text was replaced by the correction, so it is no longer what readers search.
    assert client.get("/api/public/v1/search", params={"q": "普西龙"}).json()["hits"] == []
    assert client.get("/api/public/v1/search", params={"q": "%"}).json()["hits"] == []
    assert client.get("/api/public/v1/search", params={"q": "  "}).status_code == 400


def test_search_pages_with_cursor(client):
    segments = [{"startTime": 1_760_000_000_000 + i * 1000, "endTime": 1_760_000_000_500 + i * 1000, "text": f"导数 第{i}段", "marked": False} for i in range(5)]
    publish_one(client, segments=segments, notes=[])
    reader = transcript_reader(client)
    first = client.get("/api/public/v1/search", params={"q": "导数", "limit": 3}, headers=reader).json()
    second = client.get("/api/public/v1/search", params={"q": "导数", "limit": 3, "cursor": first["nextCursor"]}, headers=reader).json()
    assert [h["seq"] for h in first["hits"] + second["hits"]] == [0, 1, 2, 3, 4]
    assert second["nextCursor"] is None


def test_export_txt_parts(client):
    _, result = publish_one(client)
    url = f"/api/public/v1/lessons/{result['lessonId']}/export.txt"
    reader = transcript_reader(client)
    both = client.get(url, headers=reader)
    assert both.headers["content-type"] == "text/plain; charset=utf-8"
    assert "filename*=UTF-8''" in both.headers["content-disposition"]
    text = both.content.decode("utf-8")
    assert "高等数学 · 张老师" in text and "主题：函数极限" in text
    assert "★ ε-δ 语言" in text and "==== 笔记 1：课堂笔记 ====" in text
    transcript = client.get(url, params={"parts": "transcript"}, headers=reader).text
    assert "转写文字" in transcript and "笔记 1" not in transcript
    notes = client.get(url, params={"parts": "notes"}).text
    assert "课堂笔记" in notes and "今天讲极限" not in notes
    assert client.get(url, params={"parts": "audio"}).status_code == 400


def test_export_notes_only_without_notes_is_refused(client):
    _, result = publish_one(client, notes=[])
    url = f"/api/public/v1/lessons/{result['lessonId']}/export.txt"
    assert client.get(url, params={"parts": "notes"}).status_code == 409
    assert "这节课暂无公开笔记" in client.get(url).text


def test_public_responses_carry_etag(client):
    publish_one(client)
    first = client.get("/api/public/v1/courses")
    etag = first.headers["etag"]
    again = client.get("/api/public/v1/courses", headers={"If-None-Match": etag})
    assert again.status_code == 304


def test_public_api_is_read_only(client):
    assert client.post("/api/public/v1/courses").status_code == 405
    assert client.delete("/api/public/v1/lessons/x").status_code == 405


def test_admin_page_is_served(client):
    page = client.get("/admin")
    assert page.status_code == 200 and "听这节课后台" in page.text and "原文权限" in page.text


def test_private_sync_data_never_reaches_the_public_api(tmp_path, monkeypatch, client):
    # A lesson synced privately lives in a different database the publish service never opens.
    monkeypatch.setenv("SYNC_API_TOKEN", "sync")
    session_id = str(uuid4())
    with TestClient(create_sync_app(f"sqlite:///{tmp_path / 'sync.db'}")) as sync_client:
        response = sync_client.post("/api/v1/sync", headers=auth("sync"), json={
            "deviceId": str(uuid4()), "lastSyncAt": 0, "segments": [],
            "sessions": [{"sessionId": session_id, "courseName": "私人课程", "name": "私人课堂", "startedAt": 1,
                          "endedAt": None, "createdAt": 1, "updatedAt": 2, "deleted": False, "topic": None}],
        })
        assert response.status_code == 200
    assert client.get("/api/public/v1/courses").json()["courses"] == []
    assert client.get("/api/public/v1/search", params={"q": "私人"}).json()["hits"] == []


def test_snippet_offset_points_into_the_snippet_for_long_text(client):
    long_text = "前" * 100 + "洛必达" + "后" * 100
    publish_one(client, segments=[{"startTime": 1, "endTime": 2, "text": long_text, "marked": False}], notes=[])
    hit = client.get("/api/public/v1/search", params={"q": "洛必达"}, headers=transcript_reader(client)).json()["hits"][0]
    assert hit["snippet"].startswith("…") and hit["snippet"].endswith("…")
    assert hit["snippet"][hit["matchOffset"]:hit["matchOffset"] + 3] == "洛必达"


def test_upgrade_from_lesson_review_keeps_public_courses_visible(tmp_path, monkeypatch):
    # A 0.1.0 database: no course status columns, one lesson already public.
    import sqlite3
    path = tmp_path / "old.db"
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE courses (id TEXT PRIMARY KEY, schoolName TEXT NOT NULL, term TEXT NOT NULL, courseName TEXT NOT NULL,
            teacherName TEXT NOT NULL, normKey TEXT NOT NULL UNIQUE, createdAt INTEGER NOT NULL);
        CREATE TABLE lessons (id TEXT PRIMARY KEY, uploaderId INTEGER NOT NULL, sessionId TEXT NOT NULL, courseId TEXT NOT NULL,
            publishedRevisionId INTEGER, status TEXT NOT NULL, withdrawRequestedAt INTEGER, withdrawReason TEXT,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, UNIQUE (uploaderId, sessionId));
        INSERT INTO courses VALUES ('c1', 's', 't', '公开的课', 'x', 'k1', 1), ('c2', 's', 't', '没公开的课', 'x', 'k2', 1);
        INSERT INTO lessons VALUES ('l1', 1, 's1', 'c1', 5, 'active', NULL, NULL, 1, 1);
    """)
    db.commit()
    db.close()
    monkeypatch.setenv("PUBLISH_ADMIN_TOKEN", ADMIN_TOKEN)
    with TestClient(create_app(f"sqlite:///{path}")) as value:
        courses = value.get("/api/admin/v1/courses?status=all", headers=auth(ADMIN_TOKEN)).json()["courses"]
    assert {c["courseName"]: c["status"] for c in courses} == {"公开的课": "approved", "没公开的课": "pending"}


def test_uploader_sees_own_lessons_in_a_course(client):
    session_id = str(uuid4())
    token, result = publish_one(client, session_id=session_id)
    publish_one(client, token=new_uploader(client, "乙"))  # someone else's lesson, same course
    client.post(f"/api/publish/v1/lessons/{result['lessonId']}/withdraw-request", headers=auth(token), json={"reason": "录错了"})
    lessons = client.get(f"/api/publish/v1/courses/{result['courseId']}/lessons", headers=auth(token)).json()["lessons"]
    assert [(l["sessionId"], l["status"], l["withdrawRequested"]) for l in lessons] == [(session_id, "active", True)]


# ---- readers and transcript access -----------------------------------------------------


def test_anonymous_readers_get_notes_but_no_transcript(client):
    _, result = publish_one(client)
    lesson = f"/api/public/v1/lessons/{result['lessonId']}"
    detail = client.get(lesson)
    assert detail.headers["vary"] == "Authorization" and detail.headers["cache-control"] == "private, no-cache"
    body = detail.json()
    assert body["segments"] == [] and body["segmentCount"] == 2 and body["transcriptHidden"] is True
    assert body["notes"][0]["content"].startswith("# 极限")
    # Search only finds notes, and the export holds notes only.
    assert {hit["kind"] for hit in client.get("/api/public/v1/search", params={"q": "ε-δ"}).json()["hits"]} == {"note"}
    assert client.get("/api/public/v1/search", params={"q": "今天讲"}).json()["hits"] == []
    text = client.get(f"{lesson}/export.txt").text
    assert "笔记 1" in text and "转写文字" not in text and "今天讲极限" not in text
    assert client.get(f"{lesson}/export.txt", params={"parts": "transcript"}).status_code == 403
    # Course lists still show how much transcript a lesson has, but never its text.
    summary = client.get(f"/api/public/v1/courses/{result['courseId']}/lessons").json()["lessons"][0]
    assert summary["segmentCount"] == 2


def test_signed_in_reader_sees_transcripts_only_after_approval(client):
    _, result = publish_one(client)
    lesson = f"/api/public/v1/lessons/{result['lessonId']}"
    login = sign_in(client)
    assert login["token"].startswith("lr_") and login["reader"]["transcriptAccess"] == "none"
    headers = auth(login["token"])
    assert client.get(lesson, headers=headers).json()["segments"] == []

    pending = client.post("/api/public/v1/me/transcript-access", headers=headers, json={"note": "我是小林"}).json()
    assert pending["transcriptAccess"] == "pending"
    assert client.get(lesson, headers=headers).json()["segments"] == []
    assert admin(client, "GET", "/summary")["pendingReaders"] == 1
    row = admin(client, "GET", "/readers", params={"status": "pending"})["readers"][0]
    assert row["requestNote"] == "我是小林" and "openid-me" not in json.dumps(row)

    admin(client, "POST", f"/readers/{row['readerId']}/approve")
    assert client.get("/api/public/v1/me", headers=headers).json()["transcriptAccess"] == "approved"
    assert len(client.get(lesson, headers=headers).json()["segments"]) == 2
    # Signing in again from another phone finds the same reader, still approved.
    again = sign_in(client)
    assert again["reader"] == {**login["reader"], "role": "transcript", "transcriptAccess": "approved", "requestedAt": pending["requestedAt"]}
    assert again["reader"]["isAdmin"] is False and again["reader"]["nickname"] is None
    assert len(client.get(lesson, headers=auth(again["token"])).json()["segments"]) == 2

    admin(client, "POST", f"/readers/{row['readerId']}/revoke")
    assert client.get(lesson, headers=headers).json()["segments"] == []
    assert client.get("/api/public/v1/me", headers=headers).json()["transcriptAccess"] == "revoked"


def test_bad_or_expired_reader_tokens_read_as_anonymous(client, monkeypatch):
    _, result = publish_one(client)
    lesson = f"/api/public/v1/lessons/{result['lessonId']}"
    assert client.get(lesson, headers=auth("lr_nonsense")).json()["segments"] == []
    assert client.get("/api/public/v1/me", headers=auth("lr_nonsense")).status_code == 401
    headers = transcript_reader(client)
    monkeypatch.setattr(readers, "now_ms", lambda: 10**15)
    assert client.get(lesson, headers=headers).json()["segments"] == []
    assert client.get("/api/public/v1/me", headers=headers).status_code == 401


def test_wechat_login_needs_server_config(tmp_path, monkeypatch):
    monkeypatch.delenv("WECHAT_APPID", raising=False)
    monkeypatch.delenv("WECHAT_SECRET", raising=False)
    with TestClient(create_app(f"sqlite:///{tmp_path / 'p.db'}")) as bare:
        assert bare.post("/api/public/v1/auth/wechat", json={"code": "x"}).status_code == 503


def test_admin_reader_routes_need_the_admin_token(client):
    login = sign_in(client)
    assert client.get("/api/admin/v1/readers").status_code == 401
    assert client.post(f"/api/admin/v1/readers/{login['reader']['readerId']}/approve", headers=auth(login["token"])).status_code == 401


def test_timetable_follows_the_reader_and_newer_copy_wins(client):
    assert client.get("/api/public/v1/me/timetable").status_code == 401
    phone_a = auth(sign_in(client, "a")["token"])
    assert client.get("/api/public/v1/me/timetable", headers=phone_a).json() == {"timetable": None, "updatedAt": None}
    table = {"settings": {"termStart": "2026-09-07", "weeks": 18}, "courses": [{"id": "x", "name": "材料力学", "slots": []}]}
    saved = client.put("/api/public/v1/me/timetable", headers=phone_a, json={"timetable": table, "updatedAt": 100})
    assert saved.status_code == 200 and saved.json() == {"updatedAt": 100}

    # The same person on another phone (same openid) gets it back.
    phone_b = auth(sign_in(client, "a")["token"])
    got = client.get("/api/public/v1/me/timetable", headers=phone_b)
    assert got.json() == {"timetable": table, "updatedAt": 100} and got.headers["cache-control"] == "no-store"
    # Someone else does not.
    other = auth(sign_in(client, "b")["token"])
    assert client.get("/api/public/v1/me/timetable", headers=other).json()["timetable"] is None

    newer = {**table, "courses": []}
    assert client.put("/api/public/v1/me/timetable", headers=phone_b, json={"timetable": newer, "updatedAt": 200}).status_code == 200
    stale = client.put("/api/public/v1/me/timetable", headers=phone_a, json={"timetable": table, "updatedAt": 150})
    assert stale.status_code == 409 and stale.json()["timetable"] == newer and stale.json()["updatedAt"] == 200


def test_oversized_timetable_is_refused(client):
    headers = auth(sign_in(client)["token"])
    big = {"courses": [{"name": "课" * 1000} for _ in range(200)]}
    assert client.put("/api/public/v1/me/timetable", headers=headers, json={"timetable": big, "updatedAt": 1}).status_code == 413


def test_admin_reader_reviews_from_the_mini_program(client):
    token, result = publish_one(client)
    login = sign_in(client, "boss")
    reader = auth(login["token"])
    assert client.get("/api/admin/v1/summary", headers=reader).status_code == 401
    admin(client, "POST", f"/readers/{login['reader']['readerId']}/make-admin")
    assert client.get("/api/public/v1/me", headers=reader).json()["isAdmin"] is True
    # An admin reader uses the same admin API as the page, and sees transcripts without asking.
    assert client.get("/api/admin/v1/summary", headers=reader).status_code == 200
    takedown = client.post(f"/api/admin/v1/courses/{result['courseId']}/takedown", headers=reader, json={"note": "测试"})
    assert takedown.status_code == 200
    approve(client, result["courseId"])
    assert len(client.get(f"/api/public/v1/lessons/{result['lessonId']}", headers=reader).json()["segments"]) == 2
    admin(client, "POST", f"/readers/{login['reader']['readerId']}/remove-admin")
    assert client.get("/api/admin/v1/summary", headers=reader).status_code == 401
    # Uploader tokens never count as admin.
    assert client.get("/api/admin/v1/summary", headers=auth(token)).status_code == 401


def test_reader_sets_nickname_and_avatar(client):
    login = sign_in(client)
    headers = auth(login["token"])
    avatar = "data:image/jpeg;base64," + "A" * 2000
    me = client.put("/api/public/v1/me/profile", headers=headers, json={"nickname": "  初梦浩然 ", "avatar": avatar}).json()
    assert me["nickname"] == "初梦浩然" and me["avatar"] == avatar
    # Leaving a field out keeps it; the admin list shows both.
    assert client.put("/api/public/v1/me/profile", headers=headers, json={"nickname": "小林"}).json()["avatar"] == avatar
    row = admin(client, "GET", "/readers")["readers"][0]
    assert row["nickname"] == "小林" and row["avatar"] == avatar
    bad = client.put("/api/public/v1/me/profile", headers=headers, json={"avatar": "https://example.com/a.png"})
    assert bad.status_code == 422
    assert client.put("/api/public/v1/me/profile", headers=headers, json={"nickname": "名" * 21}).status_code == 422


def test_only_courses_off_the_reader_can_be_deleted(client):
    token, result = publish_one(client)
    url = f"/api/admin/v1/courses/{result['courseId']}"
    headers = {"Authorization": f"Bearer {ADMIN_TOKEN}"}
    assert client.delete(url, headers=headers).status_code == 409
    admin(client, "POST", f"/courses/{result['courseId']}/takedown", json={"note": "删掉"})
    assert client.delete(url, headers=headers).json() == {"courseId": result["courseId"], "deleted": True}
    assert client.get(url, headers=headers).status_code == 404
    assert admin(client, "GET", "/courses", params={"status": "all"})["courses"] == []
    assert any(e["action"] == "course.deleted" for e in admin(client, "GET", "/audit")["entries"])
    # Uploading the same class again starts the course over, waiting for review.
    again = submit(client, token, submission())
    assert again["courseStatus"] == "pending" and again["courseId"] != result["courseId"]


# ---- course materials --------------------------------------------------------------------

DOCX = b"PK\x03\x04 pretend docx"


def upload_material(client, course_id, file_name, body=DOCX, headers=None, **params):
    return client.post(
        f"/api/admin/v1/courses/{course_id}/materials",
        params={"fileName": file_name, **params},
        content=body,
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"} if headers is None else headers,
    )


def test_admin_uploads_and_readers_open_course_materials(client, tmp_path):
    _, result = publish_one(client)
    course_id = result["courseId"]
    uploaded = upload_material(client, course_id, "期末复习.docx")
    assert uploaded.status_code == 200, uploaded.text
    material = uploaded.json()
    assert (material["title"], material["ext"], material["viewer"], material["size"]) == ("期末复习", "docx", "document", len(DOCX))
    upload_material(client, course_id, "公式表.PNG", body=b"\x89PNG")
    upload_material(client, course_id, "习题.zip", body=b"PK")

    listed = client.get(f"/api/public/v1/courses/{course_id}/materials").json()
    assert [(m["fileName"], m["viewer"]) for m in listed["materials"]] == [
        ("期末复习.docx", "document"), ("公式表.png", "image"), ("习题.zip", "none")]
    file = client.get(f"/api/public/v1/materials/{material['materialId']}/file")
    assert file.status_code == 200 and file.content == DOCX
    assert file.headers["content-type"].startswith("application/vnd.openxmlformats-officedocument.wordprocessingml")


def test_readers_cannot_upload_or_change_materials(client):
    _, result = publish_one(client)
    course_id = result["courseId"]
    reader = auth(sign_in(client, "reader")["token"])
    assert upload_material(client, course_id, "a.docx", headers=reader).status_code == 401
    assert upload_material(client, course_id, "a.docx", headers={}).status_code == 401
    material = upload_material(client, course_id, "a.docx").json()
    assert client.patch(f"/api/admin/v1/materials/{material['materialId']}", headers=reader, json={"title": "x"}).status_code == 401
    assert client.delete(f"/api/admin/v1/materials/{material['materialId']}", headers=reader).status_code == 401
    # An admin reader may manage them from the mini program.
    login = sign_in(client, "boss")
    admin(client, "POST", f"/readers/{login['reader']['readerId']}/make-admin")
    assert upload_material(client, course_id, "b.pdf", headers=auth(login["token"])).status_code == 200


def test_material_formats_and_size_are_checked(client):
    _, result = publish_one(client)
    course_id = result["courseId"]
    refused = upload_material(client, course_id, "病毒.exe")
    assert refused.status_code == 415 and "不支持 .exe" in refused.json()["detail"]
    assert upload_material(client, course_id, "没有扩展名").status_code == 415
    assert upload_material(client, course_id, "空.docx", body=b"").status_code == 400
    from publish import materials
    big = upload_material(client, course_id, "大.pdf", body=b"0" * (materials.MAX_MATERIAL_BYTES + 1))
    assert big.status_code == 413


def test_materials_are_renamed_ordered_moved_and_deleted(client, tmp_path):
    _, first = publish_one(client)
    _, second = publish_one(client, course={"schoolName": "某某大学", "term": "2026-2027 第一学期", "courseName": "线性代数", "teacherName": "李老师"})
    a = upload_material(client, first["courseId"], "a.docx").json()
    b = upload_material(client, first["courseId"], "b.pdf").json()
    admin(client, "PATCH", f"/materials/{a['materialId']}", json={"title": "  第一章  讲义 "})
    ordered = admin(client, "PUT", f"/courses/{first['courseId']}/materials/order", json={"materialIds": [b["materialId"], a["materialId"]]})
    assert [m["title"] for m in ordered["materials"]] == ["b", "第一章 讲义"]
    stale = client.put(f"/api/admin/v1/courses/{first['courseId']}/materials/order", headers=auth(ADMIN_TOKEN), json={"materialIds": [a["materialId"]]})
    assert stale.status_code == 409

    admin(client, "PATCH", f"/materials/{b['materialId']}", json={"courseId": second["courseId"]})
    assert [m["title"] for m in client.get(f"/api/public/v1/courses/{second['courseId']}/materials").json()["materials"]] == ["b"]

    admin(client, "DELETE", f"/materials/{b['materialId']}")
    assert client.get(f"/api/public/v1/materials/{b['materialId']}/file").status_code == 404
    assert not (tmp_path / "materials" / f"{b['materialId']}.pdf").exists()


def test_materials_follow_the_course_review_state(client, tmp_path):
    token = new_uploader(client)
    result = submit(client, token, submission())
    course_id = result["courseId"]
    material = upload_material(client, course_id, "a.docx").json()
    # Pending: the admin sees it, readers do not.
    assert client.get(f"/api/public/v1/courses/{course_id}/materials").status_code == 404
    assert client.get(f"/api/public/v1/materials/{material['materialId']}/file").status_code == 404
    assert client.get(f"/api/admin/v1/materials/{material['materialId']}/file", headers=auth(ADMIN_TOKEN)).content == DOCX
    approve(client, course_id)
    assert client.get(f"/api/public/v1/materials/{material['materialId']}/file").status_code == 200
    admin(client, "POST", f"/courses/{course_id}/takedown", json={"note": "下架"})
    assert client.get(f"/api/public/v1/courses/{course_id}/materials").status_code == 404
    admin(client, "DELETE", f"/courses/{course_id}")
    assert not (tmp_path / "materials" / f"{material['materialId']}.docx").exists()


# ---- device binding and cloud config --------------------------------------------------------

CONFIG_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
STT = {"provider": "SELF_HOSTED", "baseUrl": "http://mac.local:8765", "model": "", "apiKey": "stt-secret"}
AI = {"provider": "DEEPSEEK", "baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-flash", "apiKey": "ai-secret"}


@pytest.fixture
def device_client(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLISH_ADMIN_TOKEN", ADMIN_TOKEN)
    monkeypatch.setenv("WECHAT_APPID", "wx-test")
    monkeypatch.setenv("WECHAT_SECRET", "secret")
    monkeypatch.setenv("PUBLISH_MATERIALS_DIR", str(tmp_path / "materials"))
    monkeypatch.setenv("PUBLISH_CONFIG_KEY", CONFIG_KEY)
    monkeypatch.setenv("PUBLISH_SYNC_BASE_URL", "https://sync.example.com")
    monkeypatch.setenv("SYNC_API_TOKEN", "sync-secret")
    monkeypatch.setattr(readers, "code_to_openid", lambda appid, secret, code: f"openid-{code}")
    app = create_app(f"sqlite:///{tmp_path / 'publish-test.db'}")
    with TestClient(app) as value:
        value.db_path = tmp_path / "publish-test.db"
        yield value


def admin_reader(client, code="me"):
    login = sign_in(client, code)
    admin(client, "POST", f"/readers/{login['reader']['readerId']}/make-admin")
    return auth(login["token"])


def start_pairing(client, name="Mate 9"):
    response = client.post("/api/public/v1/devices/pairings", json={"name": name})
    assert response.status_code == 200, response.text
    return response.json()


def claim(client, pairing):
    return client.post("/api/public/v1/devices/pairings/claim", json={"claim": pairing["claim"]})


def bind(client, headers, name="Mate 9"):
    pairing = start_pairing(client, name)
    approved = client.post(f"/api/public/v1/me/device-pairings/{pairing['code']}/approve", headers=headers)
    assert approved.status_code == 200, approved.text
    return auth(claim(client, pairing).json()["deviceToken"])


def sync_config(client, headers, **body):
    response = client.post("/api/public/v1/devices/config/sync", headers=headers, json=body)
    assert response.status_code == 200, response.text
    return response.json()


def test_phone_binds_with_a_code_and_collects_its_token(device_client):
    headers = admin_reader(device_client)
    pairing = start_pairing(device_client)
    assert len(pairing["code"]) == 6 and pairing["code"].isdigit()
    assert claim(device_client, pairing).json() == {"status": "pending"}

    preview = device_client.get(f"/api/public/v1/me/device-pairings/{pairing['code']}", headers=headers)
    assert preview.json()["name"] == "Mate 9"
    assert device_client.post(f"/api/public/v1/me/device-pairings/{pairing['code']}/approve", headers=headers).status_code == 200

    claimed = claim(device_client, pairing).json()
    assert claimed["status"] == "approved" and claimed["deviceToken"].startswith("ld_")
    # The claim works once, and the used code is gone.
    assert claim(device_client, pairing).status_code == 410
    assert device_client.get(f"/api/public/v1/me/device-pairings/{pairing['code']}", headers=headers).status_code == 404
    listed = device_client.get("/api/public/v1/me/devices", headers=headers).json()["devices"]
    assert [d["name"] for d in listed] == ["Mate 9"]


def test_only_admin_readers_can_approve_a_code(device_client):
    pairing = start_pairing(device_client)
    other = auth(sign_in(device_client, "stranger")["token"])
    url = f"/api/public/v1/me/device-pairings/{pairing['code']}"
    assert device_client.get(url, headers=other).status_code == 403
    assert device_client.post(url + "/approve", headers=other).status_code == 403
    assert device_client.post(url + "/approve").status_code == 401
    assert claim(device_client, pairing).json() == {"status": "pending"}


def test_expired_code_cannot_be_approved_or_claimed(device_client, monkeypatch):
    from publish import devices

    headers = admin_reader(device_client)
    pairing = start_pairing(device_client)
    real_now = devices.now_ms
    monkeypatch.setattr(devices, "now_ms", lambda: real_now() + devices.PAIRING_TTL_MS + 1000)
    assert device_client.post(f"/api/public/v1/me/device-pairings/{pairing['code']}/approve", headers=headers).status_code == 404
    assert claim(device_client, pairing).status_code == 410


def test_wrong_codes_are_rate_limited(device_client):
    headers = admin_reader(device_client)
    statuses = [device_client.get("/api/public/v1/me/device-pairings/000000", headers=headers).status_code for _ in range(11)]
    assert statuses[:10] == [404] * 10 and statuses[10] == 429


def test_config_follows_the_account_to_a_new_device(device_client):
    headers = admin_reader(device_client)
    first = bind(device_client, headers, "旧手机")
    saved = sync_config(device_client, first, offered={"stt": STT, "ai": AI})
    assert saved["config"]["stt"] == STT and saved["version"] == 1

    second = bind(device_client, headers, "新手机")
    pulled = sync_config(device_client, second)
    assert pulled["config"]["ai"]["apiKey"] == "ai-secret" and pulled["version"] == 1

    # An edit reaches the other phone; a section nobody edited is left alone.
    edited = sync_config(device_client, second, changed={"ai": {**AI, "apiKey": "new-secret"}})
    assert edited["version"] == 2
    latest = sync_config(device_client, first)
    assert latest["config"]["ai"]["apiKey"] == "new-secret" and latest["config"]["stt"] == STT


def test_cloud_wins_over_what_a_phone_offers(device_client):
    headers = admin_reader(device_client)
    first = bind(device_client, headers)
    sync_config(device_client, first, offered={"ai": AI})
    second = bind(device_client, headers, "另一台")
    result = sync_config(device_client, second, offered={"ai": {**AI, "apiKey": "stale"}})
    assert result["config"]["ai"]["apiKey"] == "ai-secret" and result["version"] == 1


def test_config_is_encrypted_at_rest(device_client):
    import sqlite3

    headers = admin_reader(device_client)
    sync_config(device_client, bind(device_client, headers), offered={"ai": AI})
    with sqlite3.connect(device_client.db_path) as db:
        sealed = db.execute("select sealed from account_configs").fetchone()[0]
        log = str(db.execute("select * from audit_log").fetchall())
    assert sealed.startswith("v1:") and "ai-secret" not in sealed and "ai-secret" not in log


def test_sealed_config_only_opens_for_its_account():
    from publish.devices import ConfigCipher
    from publish.service import ServiceError

    cipher = ConfigCipher(CONFIG_KEY)
    sealed = cipher.seal(1, {"a": 1})
    assert cipher.open(1, sealed) == {"a": 1}
    with pytest.raises(ServiceError):
        cipher.open(2, sealed)


def test_sync_settings_come_from_the_server_until_edited(device_client):
    headers = admin_reader(device_client)
    device = bind(device_client, headers)
    assert sync_config(device_client, device)["config"]["sync"] == {"baseUrl": "https://sync.example.com", "apiToken": "sync-secret"}
    own = {"baseUrl": "https://other.example.com", "apiToken": "mine"}
    assert sync_config(device_client, device, changed={"sync": own})["config"]["sync"] == own


def test_existing_uploader_is_not_replaced_before_its_phone_syncs(device_client):
    real_token = new_uploader(device_client)
    headers = admin_reader(device_client)
    newcomer = bind(device_client, headers, "测试机")
    assert "publish" not in sync_config(device_client, newcomer)["config"]
    assert len(admin(device_client, "GET", "/uploaders")["uploaders"]) == 1
    # The phone holding the real token supplies it, and everyone gets that one.
    owner = bind(device_client, headers, "主力机")
    sync_config(device_client, owner, offered={"publish": {"token": real_token, "schoolName": "西建大"}})
    assert sync_config(device_client, newcomer)["config"]["publish"]["token"] == real_token


def test_removed_device_and_demoted_account_lose_access(device_client):
    headers = admin_reader(device_client)
    device = bind(device_client, headers)
    device_id = device_client.get("/api/public/v1/me/devices", headers=headers).json()["devices"][0]["deviceId"]
    assert device_client.delete(f"/api/public/v1/me/devices/{device_id}", headers=headers).status_code == 200
    assert device_client.post("/api/public/v1/devices/config/sync", headers=device, json={}).status_code == 401

    again = bind(device_client, headers)
    reader_id = device_client.get("/api/public/v1/me", headers=headers).json()["readerId"]
    admin(device_client, "POST", f"/readers/{reader_id}/remove-admin")
    assert device_client.post("/api/public/v1/devices/config/sync", headers=again, json={}).status_code == 401


def test_a_reader_cannot_remove_someone_elses_device(device_client):
    headers = admin_reader(device_client)
    bind(device_client, headers)
    device_id = device_client.get("/api/public/v1/me/devices", headers=headers).json()["devices"][0]["deviceId"]
    other = auth(sign_in(device_client, "stranger")["token"])
    assert device_client.delete(f"/api/public/v1/me/devices/{device_id}", headers=other).status_code == 404


def test_binding_is_refused_without_a_config_key(client):
    assert client.post("/api/public/v1/devices/pairings", json={"name": "x"}).status_code == 503


def test_admin_names_readers_with_a_remark(client):
    reader = sign_in(client)["reader"]
    row = admin(client, "GET", "/readers")["readers"][0]
    assert row["displayName"] is None
    saved = admin(client, "POST", f"/readers/{reader['readerId']}/remark", json={"remark": "  小林 "})
    assert saved["displayName"] == "小林" and saved["remark"] == "小林"
    # Clearing it goes back to having no name, and readers never see the admin's remark.
    assert admin(client, "POST", f"/readers/{reader['readerId']}/remark", json={"remark": ""})["displayName"] is None
    assert "remark" not in client.get("/api/public/v1/me", headers=auth(sign_in(client)["token"])).json()


# ---- admin rules ----------------------------------------------------------------------------


def published_course(client, token=None):
    token = token or new_uploader(client)
    result = client.post("/api/publish/v1/submissions", headers=auth(token), json=submission()).json()
    return token, result


def test_reject_and_course_takedown_need_a_reason(client):
    _token, result = published_course(client)
    course = result["courseId"]
    response = client.post(f"/api/admin/v1/courses/{course}/reject", headers=auth(ADMIN_TOKEN), json={"note": "  "})
    assert response.status_code == 422 and response.json()["detail"] == "请填写原因"
    assert admin(client, "GET", f"/courses/{course}")["course"]["status"] == "pending"
    approve(client, course)
    assert client.post(f"/api/admin/v1/courses/{course}/takedown", headers=auth(ADMIN_TOKEN), json={}).status_code == 422
    assert admin(client, "GET", f"/courses/{course}")["course"]["status"] == "approved"


def test_lesson_status_changes_must_change_something(client):
    _token, result = published_course(client)
    lesson = result["lessonId"]
    headers = auth(ADMIN_TOKEN)
    assert client.post(f"/api/admin/v1/lessons/{lesson}/restore", headers=headers).status_code == 409
    assert client.post(f"/api/admin/v1/lessons/{lesson}/takedown", headers=headers, json={}).status_code == 200
    assert client.post(f"/api/admin/v1/lessons/{lesson}/takedown", headers=headers, json={}).status_code == 409


def test_withdraw_request_can_be_declined_and_asked_again(client):
    token, result = published_course(client)
    lesson = result["lessonId"]
    url = f"/api/publish/v1/lessons/{lesson}/withdraw-request"
    assert client.post(url, headers=auth(token), json={"reason": "讲错了"}).status_code == 200
    headers = auth(ADMIN_TOKEN)
    dismiss = f"/api/admin/v1/lessons/{lesson}/withdraw-dismiss"
    assert client.post(dismiss, headers=headers, json={}).status_code == 422
    assert client.post(dismiss, headers=headers, json={"note": "内容没问题"}).status_code == 200
    assert admin(client, "GET", "/summary")["withdrawRequests"] == 0
    # Declined, not taken down, and a closed request cannot be declined twice.
    assert admin(client, "GET", f"/lessons/{lesson}")["status"] == "active"
    assert client.post(dismiss, headers=headers, json={"note": "x"}).status_code == 409
    assert client.post(url, headers=auth(token), json={"reason": "还是想撤"}).status_code == 200
    assert admin(client, "GET", "/summary")["withdrawRequests"] == 1


def test_audit_names_the_admin_reader_and_the_target(client):
    _token, result = published_course(client)
    login = sign_in(client)
    reader_id = login["reader"]["readerId"]
    admin(client, "POST", f"/readers/{reader_id}/make-admin")
    admin(client, "POST", f"/readers/{reader_id}/remark", json={"remark": "我"})
    response = client.post(f"/api/admin/v1/courses/{result['courseId']}/approve", headers=auth(login["token"]), json={})
    assert response.status_code == 200
    entry = admin(client, "GET", "/audit?limit=1")["entries"][0]
    assert entry["action"] == "course.approved" and entry["actor"] == f"reader:{reader_id}"
    assert entry["actorName"] == "我" and "·" in entry["targetName"]


def test_audit_pages_backwards(client):
    for name in ("甲", "乙", "丙"):
        admin(client, "POST", "/uploaders", json={"displayName": name})
    first = admin(client, "GET", "/audit?limit=2")["entries"]
    rest = admin(client, "GET", f"/audit?limit=2&before={first[-1]['id']}")["entries"]
    assert [e["detail"] for e in first + rest] == ["丙", "乙", "甲"]


def test_admin_cannot_remove_their_own_admin_role(client):
    login = sign_in(client)
    reader_id = login["reader"]["readerId"]
    admin(client, "POST", f"/readers/{reader_id}/make-admin")
    response = client.post(f"/api/admin/v1/readers/{reader_id}/remove-admin", headers=auth(login["token"]))
    assert response.status_code == 409
    assert admin(client, "POST", f"/readers/{reader_id}/remove-admin")["isAdmin"] is False


def test_rotating_a_token_updates_the_cloud_copy(device_client):
    token = new_uploader(device_client)
    uploader_id = admin(device_client, "GET", "/uploaders")["uploaders"][0]["id"]
    headers = admin_reader(device_client)
    device = bind(device_client, headers)
    sync_config(device_client, device, offered={"publish": {"token": token, "schoolName": "西建大"}})
    version = sync_config(device_client, device)["version"]

    fresh = admin(device_client, "POST", f"/uploaders/{uploader_id}/rotate-token")["token"]
    latest = sync_config(device_client, device)
    assert latest["config"]["publish"] == {"token": fresh, "schoolName": "西建大"} and latest["version"] == version + 1
    assert device_client.get("/api/publish/v1/me", headers=auth(fresh)).status_code == 200


def test_rotating_another_token_leaves_the_cloud_copy_alone(device_client):
    token = new_uploader(device_client, "我")
    other = new_uploader(device_client, "别人")
    assert other
    other_id = next(u["id"] for u in admin(device_client, "GET", "/uploaders")["uploaders"] if u["displayName"] == "别人")
    device = bind(device_client, admin_reader(device_client))
    sync_config(device_client, device, offered={"publish": {"token": token, "schoolName": ""}})
    admin(device_client, "POST", f"/uploaders/{other_id}/rotate-token")
    assert sync_config(device_client, device)["config"]["publish"]["token"] == token


# ---- courses have one sent-back state ---------------------------------------------------------


def test_status_migration_merges_rejected_and_taken_down(tmp_path, monkeypatch):
    import sqlite3
    from publish.database import initialize_database
    from sqlalchemy import create_engine

    path = tmp_path / "old.db"
    engine = create_engine(f"sqlite:///{path}")
    initialize_database(engine)
    now = 1
    with sqlite3.connect(path) as db:
        for key, status in (("a", "rejected"), ("b", "taken_down"), ("c", "approved"), ("d", "pending")):
            db.execute("insert into courses(id,schoolName,term,courseName,teacherName,normKey,createdAt,status) values(?,?,?,?,?,?,?,?)", (key, "s", "t", key, "x", key, now, status))
    initialize_database(engine)
    with sqlite3.connect(path) as db:
        assert dict(db.execute("select id,status from courses")) == {"a": "returned", "b": "returned", "c": "approved", "d": "pending"}


def test_sent_back_course_reads_as_returned_for_admins_and_rejected_for_old_apps(client):
    token, result = published_course(client)
    course = result["courseId"]
    assert admin(client, "POST", f"/courses/{course}/return", json={"note": "请写全称"})["status"] == "returned"
    assert [c["courseId"] for c in admin(client, "GET", "/courses?status=returned")["courses"]] == [course]
    seen = client.get(f"/api/publish/v1/courses/{course}", headers=auth(token)).json()
    assert (seen["status"], seen["reviewNote"]) == ("rejected", "请写全称")
    # Sent back after going public: the uploader can ask for review again all the same.
    assert client.post(f"/api/publish/v1/courses/{course}/request-review", headers=auth(token)).json()["status"] == "pending"
    approve(client, course)
    admin(client, "POST", f"/courses/{course}/return", json={"note": "下架"})
    assert client.post(f"/api/publish/v1/courses/{course}/request-review", headers=auth(token)).json()["status"] == "pending"


def test_only_returned_courses_can_be_deleted(client):
    _token, result = published_course(client)
    course = result["courseId"]
    url = f"/api/admin/v1/courses/{course}"
    assert client.delete(url, headers=auth(ADMIN_TOKEN)).status_code == 409
    admin(client, "POST", f"/courses/{course}/return", json={"note": "x"})
    assert client.delete(url, headers=auth(ADMIN_TOKEN)).status_code == 200


# ---- publishing through the account -----------------------------------------------------------


def uploader_account(client, code="classmate", name="小林"):
    login = sign_in(client, code)
    reader_id = login["reader"]["readerId"]
    row = admin(client, "POST", f"/readers/{reader_id}/upload-enable", json={"displayName": name})
    assert row["canUpload"] is True and row["uploaderName"] == name
    return auth(login["token"]), reader_id


def test_a_bound_phone_uploads_without_any_uploader_token(device_client):
    headers, _reader = uploader_account(device_client)
    device = bind(device_client, headers)
    assert device_client.get("/api/publish/v1/me", headers=device).json() == {"displayName": "小林", "status": "active"}
    response = device_client.post("/api/publish/v1/submissions", headers=device, json=submission())
    assert response.status_code == 200, response.text
    assert response.json()["courseStatus"] == "pending"


def test_enabling_upload_needs_a_public_name_the_first_time(client):
    reader_id = sign_in(client)["reader"]["readerId"]
    url = f"/api/admin/v1/readers/{reader_id}/upload-enable"
    assert client.post(url, headers=auth(ADMIN_TOKEN), json={}).status_code == 422
    assert client.post(url, headers=auth(ADMIN_TOKEN), json={"displayName": "小林"}).status_code == 200
    admin(client, "POST", f"/readers/{reader_id}/upload-disable")
    # Back on with the same identity, so its lessons stay its own.
    assert client.post(url, headers=auth(ADMIN_TOKEN), json={}).json()["uploaderName"] == "小林"
    assert len(admin(client, "GET", "/uploaders")["uploaders"]) == 1


def test_disabling_upload_stops_the_phone_at_once(device_client):
    headers, reader_id = uploader_account(device_client)
    device = bind(device_client, headers)
    admin(device_client, "POST", f"/readers/{reader_id}/upload-disable")
    assert device_client.get("/api/publish/v1/me", headers=device).status_code == 401
    assert device_client.post("/api/public/v1/devices/config/sync", headers=device, json={}).status_code == 401


def test_admin_without_upload_rights_hears_why_not_a_bad_token(device_client):
    device = bind(device_client, admin_reader(device_client))
    response = device_client.get("/api/publish/v1/me", headers=device)
    assert response.status_code == 403 and "上传权限" in response.json()["detail"]


def test_uploader_can_bind_but_never_gets_the_sync_token(device_client):
    headers, _reader = uploader_account(device_client)
    device = bind(device_client, headers)
    assert "sync" not in sync_config(device_client, device)["config"]


def test_an_old_uploader_token_ties_itself_to_the_account(device_client):
    token = new_uploader(device_client)
    published_course(device_client, token)
    headers = admin_reader(device_client)
    device = bind(device_client, headers)
    sync_config(device_client, device, offered={"publish": {"token": token, "schoolName": ""}})
    row = next(r for r in admin(device_client, "GET", "/readers")["readers"] if r["isAdmin"])
    assert row["canUpload"] is True and row["uploaderName"] == "小林"
    # The device token now publishes as the same uploader that owns the earlier lessons.
    assert device_client.get("/api/publish/v1/me", headers=device).json()["displayName"] == "小林"


def test_web_console_signs_in_with_a_device_token(device_client):
    headers = admin_reader(device_client)
    console = bind(device_client, headers, "网页后台 · Chrome")
    assert device_client.get("/api/admin/v1/summary", headers=console).status_code == 200
    uploader_headers, _reader = uploader_account(device_client)
    phone = bind(device_client, uploader_headers)
    assert device_client.get("/api/admin/v1/summary", headers=phone).status_code == 401
    listed = admin(device_client, "GET", "/devices")["devices"]
    assert {d["name"] for d in listed} == {"网页后台 · Chrome", "Mate 9"}


def test_storage_report_adds_up_without_a_sync_service(client):
    report = admin(client, "GET", "/storage")
    assert report["sync"] is None and report["disk"]["totalBytes"] > 0
    assert report["totalBytes"] == sum(part["bytes"] for part in report["parts"]) > 0


def test_admin_page_revalidates_with_an_etag(client):
    first = client.get("/admin")
    assert first.status_code == 200 and first.headers["cache-control"] == "no-cache"
    assert client.get("/admin", headers={"If-None-Match": first.headers["etag"]}).status_code == 304


def test_existing_uploader_can_be_tied_to_an_account_with_its_lessons(device_client):
    token = new_uploader(device_client)
    _token, result = published_course(device_client, token)
    uploader = admin(device_client, "GET", "/uploaders")["uploaders"][0]
    assert uploader["linked"] is False
    login = sign_in(device_client, "owner")
    reader_id = login["reader"]["readerId"]
    row = admin(device_client, "POST", f"/readers/{reader_id}/upload-enable", json={"uploaderId": uploader["id"]})
    assert row["canUpload"] is True and row["uploaderName"] == "小林"
    assert admin(device_client, "GET", "/uploaders")["uploaders"][0]["linked"] is True
    device = bind(device_client, auth(login["token"]))
    # The same identity: its earlier lesson comes back as its own.
    seen = device_client.get(f"/api/publish/v1/courses/{result['courseId']}/lessons", headers=device).json()
    assert [l["lessonId"] for l in seen["lessons"]] == [result["lessonId"]]
    # An uploader belongs to one account.
    other = sign_in(device_client, "someone")["reader"]["readerId"]
    response = device_client.post(f"/api/admin/v1/readers/{other}/upload-enable", headers=auth(ADMIN_TOKEN), json={"uploaderId": uploader["id"]})
    assert response.status_code == 409


# ---- one name, one role -----------------------------------------------------------------------


def set_role(client, reader_id, role, name=None):
    return client.post(f"/api/admin/v1/readers/{reader_id}/role", headers=auth(ADMIN_TOKEN), json={"role": role, "name": name})


@pytest.mark.parametrize("role,transcripts,upload,is_admin", [
    ("reader", False, False, False),
    ("transcript", True, False, False),
    ("uploader", True, True, False),
    ("admin", True, True, True),
])
def test_a_role_grants_everything_below_it(client, role, transcripts, upload, is_admin):
    login = sign_in(client)
    row = set_role(client, login["reader"]["readerId"], role, "小林").json()
    assert row["role"] == role and row["displayName"] == "小林"
    assert (row["transcriptAccess"] == "approved", row["canUpload"], row["isAdmin"]) == (transcripts, upload, is_admin)


def test_uploader_and_admin_need_a_name_because_it_is_public(client):
    reader_id = sign_in(client)["reader"]["readerId"]
    for role in ("uploader", "admin"):
        response = set_role(client, reader_id, role)
        assert response.status_code == 422 and "名称" in response.json()["detail"]
    assert set_role(client, reader_id, "transcript").status_code == 200


def test_going_down_a_role_takes_the_rights_away(device_client):
    login = sign_in(device_client)
    reader_id = login["reader"]["readerId"]
    set_role(device_client, reader_id, "uploader", "小林")
    device = bind(device_client, auth(login["token"]))
    assert device_client.get("/api/publish/v1/me", headers=device).status_code == 200
    row = set_role(device_client, reader_id, "reader").json()
    assert row["role"] == "reader" and row["transcriptAccess"] == "revoked"
    assert device_client.get("/api/publish/v1/me", headers=device).status_code == 401
    # Back up: the same uploader, so its lessons are still its own.
    set_role(device_client, reader_id, "uploader")
    assert len(admin(device_client, "GET", "/uploaders")["uploaders"]) == 1


def test_one_name_is_also_the_public_uploader_name(client):
    reader_id = sign_in(client)["reader"]["readerId"]
    set_role(client, reader_id, "uploader", "小林")
    set_role(client, reader_id, "uploader", "林同学")
    assert admin(client, "GET", "/uploaders")["uploaders"][0]["displayName"] == "林同学"
    admin(client, "POST", f"/readers/{reader_id}/remark", json={"remark": "老林"})
    assert admin(client, "GET", "/uploaders")["uploaders"][0]["displayName"] == "老林"


def test_admin_cannot_change_their_own_role(client):
    login = sign_in(client)
    reader_id = login["reader"]["readerId"]
    set_role(client, reader_id, "admin", "我")
    url = f"/api/admin/v1/readers/{reader_id}/role"
    assert client.post(url, headers=auth(login["token"]), json={"role": "reader"}).status_code == 409
    assert client.post(url, headers=auth(login["token"]), json={"role": "admin", "name": "新名字"}).json()["displayName"] == "新名字"


def test_notes_and_materials_can_belong_to_one_lesson(client):
    _token, result = published_course(client)
    course, lesson = result["courseId"], result["lessonId"]
    url = f"/api/admin/v1/courses/{course}/materials"
    whole = client.post(url, params={"fileName": "复习.pdf", "category": "note"}, headers=auth(ADMIN_TOKEN), content=b"x").json()
    assert (whole["category"], whole["lessonId"]) == ("note", None)
    one = client.post(url, params={"fileName": "补充.pdf", "lessonId": lesson}, headers=auth(ADMIN_TOKEN), content=b"x").json()
    assert (one["category"], one["lessonId"]) == ("material", lesson)
    assert one["lessonTitle"] == "第一课 极限"
    assert client.post(url, params={"fileName": "a.pdf", "lessonId": "nope"}, headers=auth(ADMIN_TOKEN), content=b"x").status_code == 404
    assert client.post(url, params={"fileName": "a.pdf", "category": "x"}, headers=auth(ADMIN_TOKEN), content=b"x").status_code == 422
