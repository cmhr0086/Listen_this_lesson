from __future__ import annotations

import hashlib
import json
import os
import secrets
from collections.abc import Iterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.security.utils import get_authorization_scheme_param
from pydantic import ValidationError
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session
from starlette.middleware.gzip import GZipMiddleware

from app.compression import GzipRequestMiddleware
from app.database import create_sync_engine

from . import queries, service
from .database import initialize_database
from .models import AuditLog, Course, Lesson, Revision, RevisionNote, RevisionSegment, Uploader
from .schemas import (
    CourseStatus,
    LessonSubmissions,
    ReviewDecision,
    RevisionStatus,
    SubmissionRequest,
    SubmissionResult,
    UploaderCreate,
    WithdrawRequest,
)

MAX_SUBMISSION_BYTES = 8 * 1024 * 1024
ADMIN_PAGE = Path(__file__).with_name("admin.html")


def create_app(database_url: str | None = None) -> FastAPI:
    engine = create_sync_engine(database_url or os.getenv("PUBLISH_DATABASE_URL", "sqlite:////data/listen-publish.db"))
    admin_token = os.getenv("PUBLISH_ADMIN_TOKEN")

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        initialize_database(engine)
        yield
        engine.dispose()

    app = FastAPI(title="Listen Publish Server", version="0.1.0", lifespan=lifespan)
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    app.add_middleware(GzipRequestMiddleware)
    origins = [origin.strip() for origin in os.getenv("PUBLISH_CORS_ORIGINS", "").split(",") if origin.strip()]
    if origins:
        # Only the web reader needs this; the mini program is not subject to CORS.
        app.add_middleware(CORSMiddleware, allow_origins=origins, allow_methods=["GET"], allow_headers=["If-None-Match"])

    @app.exception_handler(service.ServiceError)
    async def service_error(_request: Request, error: service.ServiceError) -> JSONResponse:
        return JSONResponse(status_code=error.status, content={"detail": error.message})

    def get_db() -> Iterator[Session]:
        with Session(engine) as session:
            yield session

    def bearer(authorization: str | None) -> str | None:
        scheme, credentials = get_authorization_scheme_param(authorization)
        return credentials if scheme.lower() == "bearer" and credentials else None

    def unauthorized() -> HTTPException:
        return HTTPException(401, "Invalid or missing bearer token.", headers={"WWW-Authenticate": "Bearer"})

    def require_uploader(
        authorization: Annotated[str | None, Header()] = None, db: Session = Depends(get_db)
    ) -> Uploader:
        token = bearer(authorization)
        uploader = service.authenticate_uploader(db, token) if token else None
        if uploader is None:
            raise unauthorized()
        return uploader

    def require_admin(authorization: Annotated[str | None, Header()] = None) -> None:
        token = bearer(authorization)
        if not (admin_token and token and secrets.compare_digest(token, admin_token)):
            raise unauthorized()

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    # ---- public, anonymous, read only ---------------------------------------------------

    public = APIRouter(prefix="/api/public/v1")

    def cached(request: Request, payload) -> Response:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        etag = 'W/"' + hashlib.sha256(body).hexdigest()[:32] + '"'
        headers = {"ETag": etag, "Cache-Control": "public, max-age=30"}
        if request.headers.get("if-none-match") == etag:
            return Response(status_code=304, headers=headers)
        return Response(body, media_type="application/json", headers=headers)

    def not_found(message: str) -> HTTPException:
        return HTTPException(404, message)

    @public.get("/courses")
    def courses(request: Request, school: str | None = None, term: str | None = None, db: Session = Depends(get_db)):
        return cached(request, {"courses": queries.list_courses(db, school, term)})

    @public.get("/courses/{course_id}/lessons")
    def course_lessons(
        request: Request,
        course_id: str,
        cursor: str | None = None,
        limit: Annotated[int, Query(ge=1, le=100)] = 50,
        db: Session = Depends(get_db),
    ):
        try:
            page = queries.list_lessons(db, course_id, cursor, limit)
        except ValueError as error:
            raise HTTPException(400, str(error)) from error
        if page is None:
            raise not_found("课程不存在")
        return cached(request, page)

    @public.get("/lessons/{lesson_id}")
    def lesson(request: Request, lesson_id: str, db: Session = Depends(get_db)):
        detail = queries.lesson_detail(db, lesson_id)
        if detail is None:
            raise not_found("课堂不存在或已下架")
        return cached(request, detail)

    @public.get("/search")
    def search(
        request: Request,
        q: Annotated[str, Query(max_length=50)],
        courseId: str | None = None,
        cursor: str | None = None,
        limit: Annotated[int, Query(ge=1, le=50)] = 20,
        db: Session = Depends(get_db),
    ):
        query = q.strip()
        if not query:
            raise HTTPException(400, "请输入关键词")
        try:
            return cached(request, queries.search(db, query, courseId, cursor, limit))
        except ValueError as error:
            raise HTTPException(400, str(error)) from error

    @public.get("/lessons/{lesson_id}/export.txt")
    def export(lesson_id: str, parts: str = "transcript,notes", db: Session = Depends(get_db)):
        wanted = {part.strip() for part in parts.split(",") if part.strip()}
        if not wanted or not wanted <= {"transcript", "notes"}:
            raise HTTPException(400, "parts 只能是 transcript、notes 或两者")
        try:
            result = queries.export_txt(db, lesson_id, wanted)
        except LookupError as error:
            raise HTTPException(409, str(error)) from error
        if result is None:
            raise not_found("课堂不存在或已下架")
        body, filename = result
        return Response(
            body.encode("utf-8"),
            media_type="text/plain; charset=utf-8",
            headers={
                "Content-Disposition": f"attachment; filename=\"lesson.txt\"; filename*=UTF-8''{quote(filename)}",
                "Cache-Control": "public, max-age=30",
            },
        )

    # ---- uploaders ----------------------------------------------------------------------

    publish = APIRouter(prefix="/api/publish/v1")

    @publish.get("/me")
    def me(uploader: Uploader = Depends(require_uploader)):
        return {"displayName": uploader.displayName, "status": uploader.status}

    @publish.get("/courses")
    def known_courses(
        q: str | None = None, uploader: Uploader = Depends(require_uploader), db: Session = Depends(get_db)
    ):
        # Public courses plus the uploader's own, so the picker offers names already in use.
        own = select(Lesson.courseId).where(Lesson.uploaderId == uploader.id)
        visible = select(queries.public_lesson_rows().c.courseId)
        stmt = select(Course).where(or_(Course.id.in_(own), Course.id.in_(visible)))
        if q and q.strip():
            stmt = stmt.where(Course.courseName.contains(q.strip()))
        rows = db.scalars(stmt.order_by(Course.createdAt.desc()).limit(100)).all()
        return {"courses": [queries.course_dict(course) for course in rows]}

    @publish.post("/submissions", response_model=SubmissionResult)
    async def submissions(request: Request, uploader: Uploader = Depends(require_uploader), db: Session = Depends(get_db)):
        body = await request.body()
        if len(body) > MAX_SUBMISSION_BYTES:
            raise HTTPException(413, "提交内容过大")
        try:
            payload = SubmissionRequest.model_validate_json(body)
        except ValidationError as error:
            raise HTTPException(422, json.loads(error.json(include_url=False))) from error
        return service.submit(db, uploader, payload)

    @publish.get("/submissions", response_model=LessonSubmissions)
    def submission_status(sessionId: str, uploader: Uploader = Depends(require_uploader), db: Session = Depends(get_db)):
        lesson = db.scalar(select(Lesson).where(Lesson.uploaderId == uploader.id, Lesson.sessionId == sessionId))
        if lesson is None:
            return LessonSubmissions(lessonId=None, lessonStatus=None, published=False, withdrawRequested=False, revisions=[])
        revisions = db.scalars(select(Revision).where(Revision.lessonId == lesson.id).order_by(Revision.number.desc()))
        return LessonSubmissions(
            lessonId=lesson.id,
            lessonStatus=lesson.status,
            published=lesson.publishedRevisionId is not None and lesson.status == "active",
            withdrawRequested=lesson.withdrawRequestedAt is not None,
            revisions=[revision_status(revision) for revision in revisions],
        )

    def course_status(db: Session, course: Course, uploader_id: int | None = None) -> CourseStatus:
        lessons = select(func.count()).where(Lesson.courseId == course.id, Lesson.publishedRevisionId.is_not(None))
        if uploader_id is not None:
            lessons = lessons.where(Lesson.uploaderId == uploader_id)
        return CourseStatus(**queries.course_dict(course), status=course.status, reviewNote=course.reviewNote, lessonCount=db.scalar(lessons))

    @publish.get("/courses/{course_id}", response_model=CourseStatus)
    def uploader_course(course_id: str, uploader: Uploader = Depends(require_uploader), db: Session = Depends(get_db)):
        return course_status(db, service.require(db.get(Course, course_id), "课程不存在"), uploader.id)

    @publish.get("/courses/{course_id}/lessons")
    def uploader_course_lessons(course_id: str, uploader: Uploader = Depends(require_uploader), db: Session = Depends(get_db)):
        # The uploader's own lessons in this course, so the app can show each one's public state.
        service.require(db.get(Course, course_id), "课程不存在")
        rows = db.execute(
            select(Lesson, Revision).join(Revision, Revision.id == Lesson.publishedRevisionId)
            .where(Lesson.courseId == course_id, Lesson.uploaderId == uploader.id)
        ).all()
        return {"lessons": [
            {
                "lessonId": lesson.id,
                "sessionId": lesson.sessionId,
                "status": lesson.status,
                "revision": revision.number,
                "updatedAt": revision.submittedAt,
                "withdrawRequested": lesson.withdrawRequestedAt is not None,
            }
            for lesson, revision in rows
        ]}

    @publish.post("/courses/{course_id}/request-review", response_model=CourseStatus)
    def request_review(course_id: str, uploader: Uploader = Depends(require_uploader), db: Session = Depends(get_db)):
        return course_status(db, service.request_course_review(db, uploader, course_id), uploader.id)

    @publish.post("/lessons/{lesson_id}/withdraw-request")
    def withdraw(
        lesson_id: str, body: WithdrawRequest, uploader: Uploader = Depends(require_uploader), db: Session = Depends(get_db)
    ):
        service.request_withdraw(db, uploader, lesson_id, body.reason)
        return {"status": "requested"}

    # ---- admin --------------------------------------------------------------------------

    admin = APIRouter(prefix="/api/admin/v1", dependencies=[Depends(require_admin)])

    def lesson_row(db: Session, lesson: Lesson) -> dict:
        revision = db.get(Revision, lesson.publishedRevisionId)
        return {
            "lessonId": lesson.id,
            "status": lesson.status,
            "title": revision.title,
            "topic": revision.topic,
            "startedAt": revision.startedAt,
            "endedAt": revision.endedAt,
            "revision": revision.number,
            "updatedAt": revision.submittedAt,
            "uploader": db.get(Uploader, lesson.uploaderId).displayName,
            "segmentCount": db.scalar(select(func.count()).where(RevisionSegment.revisionId == revision.id)),
            "noteCount": db.scalar(select(func.count()).where(RevisionNote.revisionId == revision.id)),
            "withdrawRequestedAt": lesson.withdrawRequestedAt,
            "withdrawReason": lesson.withdrawReason,
        }

    def course_row(db: Session, course: Course) -> dict:
        requester = db.get(Uploader, course.requestedBy) if course.requestedBy else None
        last = db.scalar(
            select(func.max(Revision.submittedAt))
            .join(Lesson, Lesson.publishedRevisionId == Revision.id)
            .where(Lesson.courseId == course.id)
        )
        return {
            **course_status(db, course).model_dump(),
            "requestedBy": requester.displayName if requester else None,
            "requestedAt": course.requestedAt,
            "reviewedAt": course.reviewedAt,
            "lastUploadAt": last,
            # A pending course keeps the note it was rejected with until the admin decides again.
            "reRequested": course.status == "pending" and course.reviewNote is not None,
        }

    @admin.get("/courses")
    def admin_courses(status: str = "pending", db: Session = Depends(get_db)):
        stmt = select(Course)
        if status != "all":
            stmt = stmt.where(Course.status == status)
        # The review queue is first come, first served; other lists show the newest first.
        order = (Course.requestedAt.asc(), Course.createdAt.asc()) if status == "pending" else (
            Course.requestedAt.desc().nulls_last(), Course.createdAt.desc())
        rows = db.scalars(stmt.order_by(*order).limit(200))
        return {"courses": [course_row(db, course) for course in rows]}

    @admin.get("/summary")
    def admin_summary(db: Session = Depends(get_db)):
        """Counts behind the badges on the admin tabs."""
        return {
            "pendingCourses": db.scalar(select(func.count()).select_from(Course).where(Course.status == "pending")),
            "withdrawRequests": db.scalar(
                select(func.count()).select_from(Lesson)
                .where(Lesson.withdrawRequestedAt.is_not(None), Lesson.publishedRevisionId.is_not(None))
            ),
        }

    @admin.get("/courses/{course_id}")
    def admin_course(course_id: str, db: Session = Depends(get_db)):
        course = service.require(db.get(Course, course_id), "课程不存在")
        lessons = db.scalars(
            select(Lesson).join(Revision, Revision.id == Lesson.publishedRevisionId)
            .where(Lesson.courseId == course.id).order_by(Revision.startedAt.desc())
        )
        return {"course": course_row(db, course), "lessons": [lesson_row(db, lesson) for lesson in lessons]}

    def decide(course_id: str, status: str, body: ReviewDecision | None, db: Session) -> dict:
        return course_row(db, service.review_course(db, course_id, status, body.note if body else None))

    @admin.post("/courses/{course_id}/approve")
    def admin_course_approve(course_id: str, body: ReviewDecision | None = None, db: Session = Depends(get_db)):
        return decide(course_id, "approved", body, db)

    @admin.post("/courses/{course_id}/reject")
    def admin_course_reject(course_id: str, body: ReviewDecision | None = None, db: Session = Depends(get_db)):
        return decide(course_id, "rejected", body, db)

    @admin.post("/courses/{course_id}/takedown")
    def admin_course_takedown(course_id: str, body: ReviewDecision | None = None, db: Session = Depends(get_db)):
        return decide(course_id, "taken_down", body, db)

    @admin.get("/lessons/{lesson_id}")
    def admin_lesson(lesson_id: str, db: Session = Depends(get_db)):
        lesson = service.require(db.get(Lesson, lesson_id), "课堂不存在")
        if lesson.publishedRevisionId is None:
            raise HTTPException(404, "课堂还没有内容")
        content = queries.revision_content(db, db.get(Revision, lesson.publishedRevisionId))
        return {**lesson_row(db, lesson), **content, "course": queries.course_dict(db.get(Course, lesson.courseId))}

    @admin.get("/lessons")
    def admin_lessons(withdrawRequested: bool = False, db: Session = Depends(get_db)):
        stmt = select(Lesson).where(Lesson.publishedRevisionId.is_not(None))
        if withdrawRequested:
            stmt = stmt.where(Lesson.withdrawRequestedAt.is_not(None))
        result = []
        for lesson in db.scalars(stmt.order_by(Lesson.updatedAt.desc()).limit(200)):
            revision = db.get(Revision, lesson.publishedRevisionId)
            result.append({
                "lessonId": lesson.id,
                "status": lesson.status,
                "title": revision.title,
                "startedAt": revision.startedAt,
                "course": queries.course_dict(db.get(Course, lesson.courseId)),
                "uploader": db.get(Uploader, lesson.uploaderId).displayName,
                "withdrawRequestedAt": lesson.withdrawRequestedAt,
                "withdrawReason": lesson.withdrawReason,
            })
        return {"lessons": result}

    @admin.post("/lessons/{lesson_id}/takedown")
    def admin_takedown(lesson_id: str, body: ReviewDecision | None = None, db: Session = Depends(get_db)):
        lesson = service.set_lesson_status(db, lesson_id, "taken_down", body.note if body else None)
        return {"lessonId": lesson.id, "status": lesson.status}

    @admin.post("/lessons/{lesson_id}/restore")
    def admin_restore(lesson_id: str, db: Session = Depends(get_db)):
        lesson = service.set_lesson_status(db, lesson_id, "active")
        return {"lessonId": lesson.id, "status": lesson.status}

    def uploader_dict(uploader: Uploader) -> dict:
        return {
            "id": uploader.id,
            "displayName": uploader.displayName,
            "status": uploader.status,
            "tokenHint": uploader.tokenHint,
            "createdAt": uploader.createdAt,
        }

    def uploader_row(db: Session, uploader: Uploader) -> dict:
        courses = db.scalars(
            select(Course.courseName).join(Lesson, Lesson.courseId == Course.id)
            .where(Lesson.uploaderId == uploader.id).distinct().order_by(Course.courseName)
        ).all()
        last = db.scalar(
            select(func.max(Revision.submittedAt)).join(Lesson, Lesson.id == Revision.lessonId)
            .where(Lesson.uploaderId == uploader.id)
        )
        return {**uploader_dict(uploader), "courseNames": list(courses), "lastUploadAt": last}

    @admin.get("/uploaders")
    def admin_uploaders(db: Session = Depends(get_db)):
        return {"uploaders": [uploader_row(db, u) for u in db.scalars(select(Uploader).order_by(Uploader.id.desc()))]}

    @admin.post("/uploaders")
    def admin_create_uploader(body: UploaderCreate, db: Session = Depends(get_db)):
        uploader, token = service.create_uploader(db, body.displayName)
        # The only time the token is visible; the server keeps just its hash.
        return {**uploader_dict(uploader), "token": token}

    @admin.post("/uploaders/{uploader_id}/rotate-token")
    def admin_rotate(uploader_id: int, db: Session = Depends(get_db)):
        uploader, token = service.rotate_uploader_token(db, uploader_id)
        return {**uploader_dict(uploader), "token": token}

    @admin.post("/uploaders/{uploader_id}/disable")
    def admin_disable(uploader_id: int, db: Session = Depends(get_db)):
        return uploader_dict(service.set_uploader_status(db, uploader_id, "disabled"))

    @admin.post("/uploaders/{uploader_id}/enable")
    def admin_enable(uploader_id: int, db: Session = Depends(get_db)):
        return uploader_dict(service.set_uploader_status(db, uploader_id, "active"))

    @admin.get("/audit")
    def admin_audit(db: Session = Depends(get_db)):
        rows = db.scalars(select(AuditLog).order_by(AuditLog.id.desc()).limit(100))
        return {"entries": [{"at": r.at, "actor": r.actor, "action": r.action, "target": r.target, "detail": r.detail} for r in rows]}

    @app.get("/admin", response_class=HTMLResponse, include_in_schema=False)
    def admin_page() -> HTMLResponse:
        # Static shell only; every action it takes goes through the token-checked API above.
        return HTMLResponse(ADMIN_PAGE.read_text(encoding="utf-8"), headers={"Cache-Control": "no-store"})

    app.include_router(public)
    app.include_router(publish)
    app.include_router(admin)
    return app


def revision_status(revision: Revision) -> RevisionStatus:
    return RevisionStatus(
        revisionId=revision.id,
        revision=revision.number,
        status=revision.status,
        submittedAt=revision.submittedAt,
        reviewedAt=revision.reviewedAt,
        reviewNote=revision.reviewNote,
    )


app = create_app()
