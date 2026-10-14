from __future__ import annotations

import gzip
import json
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.main import create_app as create_sync_app
from publish.main import create_app

ADMIN_TOKEN = "test-admin-token"


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLISH_ADMIN_TOKEN", ADMIN_TOKEN)
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
    detail = client.get(f"/api/public/v1/lessons/{result['lessonId']}").json()
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
    assert admin(client, "GET", "/summary") == {"pendingCourses": 2, "withdrawRequests": 0}
    approve(client, first["courseId"])
    client.post(f"/api/publish/v1/lessons/{first['lessonId']}/withdraw-request", headers=auth(token), json={"reason": "录错了"})
    assert admin(client, "GET", "/summary") == {"pendingCourses": 1, "withdrawRequests": 1}
    row = admin(client, "GET", "/uploaders")["uploaders"][0]
    assert sorted(row["courseNames"]) == sorted(["甲课", "乙课"])
    assert row["lastUploadAt"] is not None


def test_course_takedown_hides_all_its_lessons_and_approve_restores(client):
    _, result = publish_one(client)
    admin(client, "POST", f"/courses/{result['courseId']}/takedown")
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
    hits = client.get("/api/public/v1/search", params={"q": "ε-δ"}).json()["hits"]
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
    first = client.get("/api/public/v1/search", params={"q": "导数", "limit": 3}).json()
    second = client.get("/api/public/v1/search", params={"q": "导数", "limit": 3, "cursor": first["nextCursor"]}).json()
    assert [h["seq"] for h in first["hits"] + second["hits"]] == [0, 1, 2, 3, 4]
    assert second["nextCursor"] is None


def test_export_txt_parts(client):
    _, result = publish_one(client)
    url = f"/api/public/v1/lessons/{result['lessonId']}/export.txt"
    both = client.get(url)
    assert both.headers["content-type"] == "text/plain; charset=utf-8"
    assert "filename*=UTF-8''" in both.headers["content-disposition"]
    text = both.content.decode("utf-8")
    assert "高等数学 · 张老师" in text and "主题：函数极限" in text
    assert "★ ε-δ 语言" in text and "==== 笔记 1：课堂笔记 ====" in text
    transcript = client.get(url, params={"parts": "transcript"}).text
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
    assert page.status_code == 200 and "公开后台" in page.text


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
    hit = client.get("/api/public/v1/search", params={"q": "洛必达"}).json()["hits"][0]
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
