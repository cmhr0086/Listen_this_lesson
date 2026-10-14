from __future__ import annotations

import base64
import json
import os
from datetime import datetime
from zoneinfo import ZoneInfo

from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from .models import Course, Lesson, Revision, RevisionNote, RevisionSegment, Uploader

# Everything here reads only lessons that are active and have an approved revision; the
# helper below is the single place that rule lives.

SNIPPET_CONTEXT = 30


def zone() -> ZoneInfo:
    return ZoneInfo(os.getenv("PUBLISH_TIMEZONE", "Asia/Shanghai"))


def display_text(segment: RevisionSegment) -> str:
    corrected = (segment.correctedText or "").strip()
    return corrected or segment.text


def public_lessons():
    return (
        select(Lesson, Revision)
        .join(Revision, Revision.id == Lesson.publishedRevisionId)
        .where(Lesson.status == "active")
    )


def public_lesson_rows():
    return (
        select(Lesson.id.label("lessonId"), Lesson.courseId.label("courseId"), Revision.startedAt.label("startedAt"))
        .join(Revision, Revision.id == Lesson.publishedRevisionId)
        .where(Lesson.status == "active")
        .subquery()
    )


def encode_cursor(value: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")


def decode_cursor(cursor: str | None) -> dict | None:
    if not cursor:
        return None
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        return json.loads(base64.urlsafe_b64decode(padded))
    except (ValueError, json.JSONDecodeError):
        raise ValueError("invalid cursor") from None


def course_dict(course: Course) -> dict:
    return {
        "courseId": course.id,
        "schoolName": course.schoolName,
        "term": course.term,
        "courseName": course.courseName,
        "teacherName": course.teacherName,
    }


def list_courses(db: Session, school: str | None, term: str | None) -> list[dict]:
    published = public_lesson_rows()
    stmt = (
        select(Course, func.count(published.c.lessonId), func.max(published.c.startedAt))
        .join(published, published.c.courseId == Course.id)
        .group_by(Course.id)
        .order_by(func.max(published.c.startedAt).desc())
    )
    if school:
        stmt = stmt.where(Course.schoolName == school)
    if term:
        stmt = stmt.where(Course.term == term)
    return [
        {**course_dict(course), "lessonCount": count, "lastLessonAt": last}
        for course, count, last in db.execute(stmt)
    ]


def lesson_summary(db: Session, lesson: Lesson, revision: Revision) -> dict:
    segments = db.execute(
        select(func.count(), func.coalesce(func.sum(RevisionSegment.marked), 0)).where(
            RevisionSegment.revisionId == revision.id
        )
    ).one()
    notes = db.scalar(select(func.count()).where(RevisionNote.revisionId == revision.id))
    return {
        "lessonId": lesson.id,
        "title": revision.title,
        "topic": revision.topic,
        "startedAt": revision.startedAt,
        "endedAt": revision.endedAt,
        "uploader": db.get(Uploader, lesson.uploaderId).displayName,
        "segmentCount": segments[0],
        "markedCount": int(segments[1]),
        "noteCount": notes,
        "updatedAt": revision.reviewedAt,
    }


def list_lessons(db: Session, course_id: str, cursor: str | None, limit: int) -> dict | None:
    course = db.get(Course, course_id)
    published = public_lesson_rows()
    # A course only exists for readers once it has a public lesson.
    if course is None or not db.scalar(select(func.count()).where(published.c.courseId == course_id)):
        return None
    stmt = public_lessons().where(Lesson.courseId == course_id)
    after = decode_cursor(cursor)
    if after:
        # Newest first; ties on start time fall back to the id so pages never overlap.
        stmt = stmt.where(
            (Revision.startedAt < after["s"]) | ((Revision.startedAt == after["s"]) & (Lesson.id > after["i"]))
        )
    rows = db.execute(stmt.order_by(Revision.startedAt.desc(), Lesson.id).limit(limit + 1)).all()
    page = rows[:limit]
    next_cursor = None
    if len(rows) > limit:
        lesson, revision = page[-1]
        next_cursor = encode_cursor({"s": revision.startedAt, "i": lesson.id})
    return {
        "course": course_dict(course),
        "lessons": [lesson_summary(db, lesson, revision) for lesson, revision in page],
        "nextCursor": next_cursor,
    }


def revision_content(db: Session, revision: Revision) -> dict:
    segments = db.scalars(
        select(RevisionSegment).where(RevisionSegment.revisionId == revision.id).order_by(RevisionSegment.seq)
    ).all()
    notes = db.scalars(
        select(RevisionNote).where(RevisionNote.revisionId == revision.id).order_by(RevisionNote.position)
    ).all()
    return {
        "title": revision.title,
        "topic": revision.topic,
        "startedAt": revision.startedAt,
        "endedAt": revision.endedAt,
        "segments": [
            {
                "seq": segment.seq,
                "startTime": segment.startTime,
                "endTime": segment.endTime,
                "displayText": display_text(segment),
                "corrected": bool((segment.correctedText or "").strip()),
                "marked": segment.marked,
            }
            for segment in segments
        ],
        "notes": [
            {"noteId": note.noteId, "title": note.title, "content": note.content, "format": note.format}
            for note in notes
        ],
    }


def public_lesson(db: Session, lesson_id: str) -> tuple[Lesson, Revision] | None:
    row = db.execute(public_lessons().where(Lesson.id == lesson_id)).first()
    return (row[0], row[1]) if row else None


def lesson_detail(db: Session, lesson_id: str) -> dict | None:
    found = public_lesson(db, lesson_id)
    if found is None:
        return None
    lesson, revision = found
    return {
        "lessonId": lesson.id,
        "course": course_dict(db.get(Course, lesson.courseId)),
        "uploader": db.get(Uploader, lesson.uploaderId).displayName,
        "updatedAt": revision.reviewedAt,
        **revision_content(db, revision),
    }


# ---- search ----------------------------------------------------------------------------

SEARCH_SQL = """
SELECT * FROM (
    SELECT l.id AS lessonId, r.startedAt AS startedAt, 'segment' AS kind, s.seq AS seq,
           NULL AS noteId, COALESCE(NULLIF(TRIM(s.correctedText), ''), s.text) AS body
    FROM lessons l
    JOIN revisions r ON r.id = l.publishedRevisionId
    JOIN revision_segments s ON s.revisionId = r.id
    WHERE l.status = 'active' {course_filter}
    UNION ALL
    SELECT l.id, r.startedAt, 'note', n.position, n.noteId, n.content
    FROM lessons l
    JOIN revisions r ON r.id = l.publishedRevisionId
    JOIN revision_notes n ON n.revisionId = r.id
    WHERE l.status = 'active' {course_filter}
)
WHERE body LIKE :pattern ESCAPE '\\'
ORDER BY startedAt DESC, lessonId, kind DESC, seq
LIMIT :limit OFFSET :offset
"""


def search(db: Session, query: str, course_id: str | None, cursor: str | None, limit: int) -> dict:
    offset = (decode_cursor(cursor) or {}).get("o", 0)
    escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    course_filter = "AND l.courseId = :course" if course_id else ""
    rows = db.execute(
        text(SEARCH_SQL.format(course_filter=course_filter)),
        {"pattern": f"%{escaped}%", "limit": limit + 1, "offset": offset, "course": course_id},
    ).all()
    lessons: dict[str, tuple[Lesson, Revision]] = {}
    hits = []
    for row in rows[:limit]:
        if row.lessonId not in lessons:
            lessons[row.lessonId] = public_lesson(db, row.lessonId)
        lesson, revision = lessons[row.lessonId]
        course = db.get(Course, lesson.courseId)
        hit = {
            "lessonId": lesson.id,
            "lessonTitle": revision.title,
            "courseId": course.id,
            "courseName": course.courseName,
            "teacherName": course.teacherName,
            "startedAt": revision.startedAt,
            "kind": row.kind,
            **snippet(row.body, query),
        }
        if row.kind == "segment":
            hit["seq"] = row.seq
        else:
            hit["noteId"] = row.noteId
        hits.append(hit)
    next_cursor = encode_cursor({"o": offset + limit}) if len(rows) > limit else None
    return {"query": query, "hits": hits, "nextCursor": next_cursor}


def snippet(body: str, query: str) -> dict:
    index = body.casefold().find(query.casefold())
    if index < 0:  # LIKE matched through a case rule casefold() does not share; show the start.
        index = 0
    start = max(0, index - SNIPPET_CONTEXT)
    end = min(len(body), index + len(query) + SNIPPET_CONTEXT)
    prefix = "…" if start > 0 else ""
    return {
        "snippet": prefix + body[start:end] + ("…" if end < len(body) else ""),
        # Offset within the snippet, so clients can highlight without searching again.
        "matchOffset": len(prefix) + index - start,
        "matchLength": len(query),
    }


# ---- TXT export ------------------------------------------------------------------------


def export_txt(db: Session, lesson_id: str, parts: set[str]) -> tuple[str, str] | None:
    detail = lesson_detail(db, lesson_id)
    if detail is None:
        return None
    if parts == {"notes"} and not detail["notes"]:
        raise LookupError("这节课暂无公开笔记")
    tz = zone()
    course = detail["course"]

    def clock(ms: int, fmt: str) -> str:
        return datetime.fromtimestamp(ms / 1000, tz).strftime(fmt)

    when = clock(detail["startedAt"], "%Y-%m-%d %H:%M")
    if detail["endedAt"]:
        when += "–" + clock(detail["endedAt"], "%H:%M")
    lines = [
        f"{course['courseName']} · {course['teacherName']}",
        f"{course['schoolName']} · {course['term']}",
        f"{detail['title']}　{when}",
    ]
    if detail["topic"]:
        lines.append(f"主题：{detail['topic']}")
    lines.append(f"整理：{detail['uploader']}")

    if "transcript" in parts:
        lines += ["", "==== 转写文字 ====", "（★ 为重点）", ""]
        for segment in detail["segments"]:
            star = "★ " if segment["marked"] else ""
            lines.append(f"[{clock(segment['startTime'], '%H:%M:%S')}] {star}{segment['displayText']}")
        if not detail["segments"]:
            lines.append("这节课暂无转写文字")
    if "notes" in parts:
        if not detail["notes"]:
            lines += ["", "==== 笔记 ====", "这节课暂无公开笔记"]
        for number, note in enumerate(detail["notes"], 1):
            lines += ["", f"==== 笔记 {number}：{note['title']} ====", "", note["content"]]
    filename = f"{course['courseName']}-{detail['title']}-{clock(detail['startedAt'], '%Y%m%d')}.txt"
    return "\n".join(lines) + "\n", filename
