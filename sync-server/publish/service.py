from __future__ import annotations

import hashlib
import json
import re
import secrets
import time
import unicodedata
from uuid import uuid4

from sqlalchemy import func, select, update
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
    retried = db.scalar(select(Revision).where(Revision.clientSubmissionId == request.clientSubmissionId))
    if retried is not None:
        lesson = db.get(Lesson, retried.lessonId)
        if lesson.uploaderId != uploader.id:
            raise ServiceError(409, "clientSubmissionId 已被使用")
        return SubmissionResult(lessonId=lesson.id, revisionId=retried.id, revision=retried.number, status="pending")

    hour_ago = now_ms() - 3_600_000
    recent = db.scalar(
        select(func.count(Revision.id))
        .join(Lesson, Lesson.id == Revision.lessonId)
        .where(Lesson.uploaderId == uploader.id, Revision.submittedAt > hour_ago)
    )
    if recent >= SUBMISSIONS_PER_HOUR:
        raise ServiceError(429, "提交过于频繁，请稍后再试")

    digest = content_hash(request)
    course = get_or_create_course(db, request)
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
            db.rollback()
            return SubmissionResult(
                lessonId=lesson.id, revisionId=published.id, revision=published.number, status="unchanged"
            )
        pending = db.scalar(select(Revision).where(Revision.lessonId == lesson.id, Revision.status == "pending"))
        if pending is not None and pending.contentHash == digest:
            db.rollback()
            return SubmissionResult(lessonId=lesson.id, revisionId=pending.id, revision=pending.number, status="pending")
        # One waiting version per lesson: the newest replaces the one the admin has not reached yet.
        db.execute(
            update(Revision)
            .where(Revision.lessonId == lesson.id, Revision.status == "pending")
            .values(status="superseded")
        )

    number = (db.scalar(select(func.max(Revision.number)).where(Revision.lessonId == lesson.id)) or 0) + 1
    session = request.session
    revision = Revision(
        lessonId=lesson.id,
        number=number,
        status="pending",
        clientSubmissionId=request.clientSubmissionId,
        contentHash=digest,
        courseId=course.id,
        title=session.name,
        topic=session.topic,
        startedAt=session.startedAt,
        endedAt=session.endedAt,
        submittedAt=now,
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
    lesson.updatedAt = now
    audit(db, f"uploader:{uploader.id}", "revision.submit", f"lesson:{lesson.id}", f"revision {number}")
    db.commit()
    return SubmissionResult(lessonId=lesson.id, revisionId=revision.id, revision=number, status="pending")


def get_or_create_course(db: Session, request: SubmissionRequest) -> Course:
    c = request.course
    key = course_key(c.schoolName, c.term, c.courseName, c.teacherName)
    course = db.scalar(select(Course).where(Course.normKey == key))
    if course is None:
        course = Course(
            id=str(uuid4()),
            schoolName=c.schoolName,
            term=c.term,
            courseName=c.courseName,
            teacherName=c.teacherName,
            normKey=key,
            createdAt=now_ms(),
        )
        db.add(course)
        db.flush()
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


def pending_revision(db: Session, revision_id: int) -> Revision:
    revision = require(db.get(Revision, revision_id), "版本不存在")
    if revision.status != "pending":
        raise ServiceError(409, f"该版本状态为 {revision.status}，不能再审核")
    return revision


def approve(db: Session, revision_id: int, note: str | None) -> Revision:
    revision = pending_revision(db, revision_id)
    lesson = db.get(Lesson, revision.lessonId)
    if lesson.publishedRevisionId is not None:
        previous = db.get(Revision, lesson.publishedRevisionId)
        previous.status = "superseded"
    now = now_ms()
    revision.status = "approved"
    revision.reviewedAt = now
    revision.reviewNote = note
    lesson.publishedRevisionId = revision.id
    lesson.courseId = revision.courseId
    lesson.updatedAt = now
    audit(db, "admin", "revision.approve", f"lesson:{lesson.id}", f"revision {revision.number}")
    db.commit()
    return revision


def reject(db: Session, revision_id: int, note: str | None) -> Revision:
    revision = pending_revision(db, revision_id)
    revision.status = "rejected"
    revision.reviewedAt = now_ms()
    revision.reviewNote = note
    audit(db, "admin", "revision.reject", f"lesson:{revision.lessonId}", note)
    db.commit()
    return revision


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
