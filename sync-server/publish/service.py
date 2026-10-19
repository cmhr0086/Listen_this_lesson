from __future__ import annotations

import hashlib
import json
import re
import secrets
import time
import unicodedata
from uuid import uuid4

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from .models import AuditLog, Course, Lesson, Revision, RevisionNote, RevisionSegment, Uploader
from .schemas import SubmissionRequest, SubmissionResult

SUBMISSIONS_PER_HOUR = 30
TOKEN_PREFIX = "lp_"


class ServiceError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


def now_ms() -> int:
    return time.time_ns() // 1_000_000


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value)).strip().casefold()


def course_key(school: str, term: str, course: str, teacher: str) -> str:
    # \x1f cannot appear in typed text, so "a|b" + "c" never collides with "a" + "b|c".
    return "\x1f".join(normalize(part) for part in (school, term, course, teacher))


def content_hash(request: SubmissionRequest) -> str:
    c = request.course
    payload = {
        "course": course_key(c.schoolName, c.term, c.courseName, c.teacherName),
        "session": request.session.model_dump(exclude={"sessionId"}),
        "segments": [segment.model_dump() for segment in ordered_segments(request)],
        "notes": [note.model_dump() for note in request.notes],
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode()).hexdigest()


def ordered_segments(request: SubmissionRequest):
    return sorted(request.segments, key=lambda segment: (segment.startTime, segment.endTime))


def audit(db: Session, actor: str, action: str, target: str, detail: str | None = None) -> None:
    db.add(AuditLog(at=now_ms(), actor=actor, action=action, target=target, detail=detail))


# ---- uploaders -------------------------------------------------------------------------


def authenticate_uploader(db: Session, token: str) -> Uploader | None:
    uploader = db.scalar(select(Uploader).where(Uploader.tokenHash == hash_token(token)))
    if uploader is None or uploader.status != "active":
        return None
    return uploader


def create_uploader(db: Session, display_name: str) -> tuple[Uploader, str]:
    token = TOKEN_PREFIX + secrets.token_urlsafe(32)
    uploader = Uploader(
        displayName=display_name,
        tokenHash=hash_token(token),
        tokenHint=token[-4:],
        status="active",
        createdAt=now_ms(),
    )
    db.add(uploader)
    db.flush()
    audit(db, "admin", "uploader.create", f"uploader:{uploader.id}", display_name)
    db.commit()
    return uploader, token


def rotate_uploader_token(db: Session, uploader_id: int) -> tuple[Uploader, str]:
    uploader = require(db.get(Uploader, uploader_id), "上传者不存在")
    token = TOKEN_PREFIX + secrets.token_urlsafe(32)
    uploader.tokenHash = hash_token(token)
    uploader.tokenHint = token[-4:]
    audit(db, "admin", "uploader.rotate", f"uploader:{uploader.id}")
    db.commit()
    return uploader, token


def set_uploader_status(db: Session, uploader_id: int, status: str) -> Uploader:
    uploader = require(db.get(Uploader, uploader_id), "上传者不存在")
    uploader.status = status
    audit(db, "admin", f"uploader.{status}", f"uploader:{uploader.id}")
    db.commit()
    return uploader


def require(value, message: str):
    if value is None:
        raise ServiceError(404, message)
    return value


# ---- submissions -----------------------------------------------------------------------


def submit(db: Session, uploader: Uploader, request: SubmissionRequest) -> SubmissionResult:
    """
    Stores a lesson version and makes it the public one at once. Whether readers see it depends
    on its course: lessons of a course that is not approved yet stay hidden until it is.
    """
    retried = db.scalar(select(Revision).where(Revision.clientSubmissionId == request.clientSubmissionId))
    if retried is not None:
        lesson = db.get(Lesson, retried.lessonId)
        if lesson.uploaderId != uploader.id:
            raise ServiceError(409, "clientSubmissionId 已被使用")
        return result(db, lesson, retried, "published")

    hour_ago = now_ms() - 3_600_000
    recent = db.scalar(
        select(func.count(Revision.id))
        .join(Lesson, Lesson.id == Revision.lessonId)
        .where(Lesson.uploaderId == uploader.id, Revision.submittedAt > hour_ago)
    )
    if recent >= SUBMISSIONS_PER_HOUR:
        raise ServiceError(429, "提交过于频繁，请稍后再试")

    digest = content_hash(request)
    course = get_or_create_course(db, uploader, request)
    now = now_ms()
    lesson = db.scalar(
        select(Lesson).where(Lesson.uploaderId == uploader.id, Lesson.sessionId == request.session.sessionId)
    )
    if lesson is None:
        lesson = Lesson(
            id=str(uuid4()),
            uploaderId=uploader.id,
            sessionId=request.session.sessionId,
            courseId=course.id,
            status="active",
            createdAt=now,
            updatedAt=now,
        )
        db.add(lesson)
        db.flush()
    else:
        published = db.get(Revision, lesson.publishedRevisionId) if lesson.publishedRevisionId else None
        if published is not None and published.contentHash == digest:
            db.commit()  # keeps a course created by this call, so its status can be reported
            return result(db, lesson, published, "unchanged")
        if published is not None:
            published.status = "superseded"

    number = (db.scalar(select(func.max(Revision.number)).where(Revision.lessonId == lesson.id)) or 0) + 1
    session = request.session
    revision = Revision(
        lessonId=lesson.id,
        number=number,
        status="approved",
        clientSubmissionId=request.clientSubmissionId,
        contentHash=digest,
        courseId=course.id,
        title=session.name,
        topic=session.topic,
        startedAt=session.startedAt,
        endedAt=session.endedAt,
        submittedAt=now,
        reviewedAt=now,
    )
    db.add(revision)
    db.flush()
    db.add_all(
        RevisionSegment(
            revisionId=revision.id,
            seq=seq,
            startTime=segment.startTime,
            endTime=segment.endTime,
            text=segment.text,
            correctedText=segment.correctedText,
            marked=segment.marked,
        )
        for seq, segment in enumerate(ordered_segments(request))
    )
    db.add_all(
        RevisionNote(
            revisionId=revision.id,
            position=position,
            noteId=note.noteId,
            title=note.title,
            content=note.content,
            format=note.format,
        )
        for position, note in enumerate(request.notes)
    )
    lesson.publishedRevisionId = revision.id
    lesson.courseId = course.id
    lesson.updatedAt = now
    audit(db, f"uploader:{uploader.id}", "lesson.publish", f"lesson:{lesson.id}", f"revision {number}")
    db.commit()
    return result(db, lesson, revision, "published")


def result(db: Session, lesson: Lesson, revision: Revision, status: str) -> SubmissionResult:
    course = db.get(Course, lesson.courseId)
    return SubmissionResult(
        lessonId=lesson.id,
        revisionId=revision.id,
        revision=revision.number,
        status=status,
        courseId=course.id,
        courseStatus=course.status,
        courseReviewNote=course.reviewNote,
    )


def get_or_create_course(db: Session, uploader: Uploader, request: SubmissionRequest) -> Course:
    c = request.course
    key = course_key(c.schoolName, c.term, c.courseName, c.teacherName)
    course = db.scalar(select(Course).where(Course.normKey == key))
    if course is None:
        now = now_ms()
        course = Course(
            id=str(uuid4()),
            schoolName=c.schoolName,
            term=c.term,
            courseName=c.courseName,
            teacherName=c.teacherName,
            normKey=key,
            createdAt=now,
            status="pending",
            requestedBy=uploader.id,
            requestedAt=now,
        )
        db.add(course)
        db.flush()
        audit(db, f"uploader:{uploader.id}", "course.request", f"course:{course.id}", c.courseName)
    return course


def request_course_review(db: Session, uploader: Uploader, course_id: str) -> Course:
    """A rejected course goes back to the queue once the uploader has fixed what was asked."""
    course = require(db.get(Course, course_id), "课程不存在")
    owns = db.scalar(select(func.count()).where(Lesson.courseId == course.id, Lesson.uploaderId == uploader.id))
    if not owns and course.requestedBy != uploader.id:
        raise ServiceError(404, "课程不存在")
    if course.status != "rejected":
        raise ServiceError(409, f"课程当前状态为 {course.status}，无需重新申请")
    course.status = "pending"
    course.requestedAt = now_ms()
    audit(db, f"uploader:{uploader.id}", "course.request", f"course:{course.id}", "again")
    db.commit()
    return course


def request_withdraw(db: Session, uploader: Uploader, lesson_id: str, reason: str) -> Lesson:
    lesson = db.get(Lesson, lesson_id)
    if lesson is None or lesson.uploaderId != uploader.id:
        raise ServiceError(404, "课堂不存在")
    lesson.withdrawRequestedAt = now_ms()
    lesson.withdrawReason = reason
    audit(db, f"uploader:{uploader.id}", "lesson.withdraw_request", f"lesson:{lesson.id}", reason)
    db.commit()
    return lesson


# ---- review ----------------------------------------------------------------------------


def review_course(db: Session, course_id: str, status: str, note: str | None = None) -> Course:
    allowed = {
        "approved": {"pending", "rejected", "taken_down"},
        "rejected": {"pending"},
        "taken_down": {"approved"},
    }
    course = require(db.get(Course, course_id), "课程不存在")
    if course.status not in allowed[status]:
        raise ServiceError(409, f"课程当前状态为 {course.status}，不能改为 {status}")
    course.status = status
    course.reviewedAt = now_ms()
    course.reviewNote = note
    audit(db, "admin", f"course.{status}", f"course:{course.id}", note)
    db.commit()
    return course


def delete_course(db: Session, course_id: str) -> None:
    """Removes a course that is no longer public, with every lesson and version in it."""
    course = require(db.get(Course, course_id), "课程不存在")
    if course.status not in ("taken_down", "rejected"):
        raise ServiceError(409, "只能删除已下架或已驳回的课程，请先下架")
    lesson_ids = select(Lesson.id).where(Lesson.courseId == course.id)
    revision_ids = select(Revision.id).where(Revision.lessonId.in_(lesson_ids))
    lesson_count = db.scalar(select(func.count()).select_from(Lesson).where(Lesson.courseId == course.id))
    db.execute(delete(RevisionNote).where(RevisionNote.revisionId.in_(revision_ids)))
    db.execute(delete(RevisionSegment).where(RevisionSegment.revisionId.in_(revision_ids)))
    db.execute(delete(Revision).where(Revision.lessonId.in_(lesson_ids)))
    db.execute(delete(Revision).where(Revision.courseId == course.id))
    db.execute(delete(Lesson).where(Lesson.courseId == course.id))
    audit(db, "admin", "course.deleted", f"course:{course.id}", f"{course.courseName} · {course.teacherName}，{lesson_count} 节")
    db.delete(course)
    db.commit()


def set_lesson_status(db: Session, lesson_id: str, status: str, reason: str | None = None) -> Lesson:
    lesson = require(db.get(Lesson, lesson_id), "课堂不存在")
    lesson.status = status
    lesson.updatedAt = now_ms()
    if status == "taken_down":
        # Taking a lesson down settles any withdraw request the uploader made.
        lesson.withdrawRequestedAt = None
        lesson.withdrawReason = None
    audit(db, "admin", f"lesson.{status}", f"lesson:{lesson.id}", reason)
    db.commit()
    return lesson
