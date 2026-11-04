from __future__ import annotations

import hashlib
import json
import os
import secrets
import time
from collections.abc import Iterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.security.utils import get_authorization_scheme_param
from pydantic import ValidationError
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session
from starlette.middleware.gzip import GZipMiddleware

from app.compression import GzipRequestMiddleware
from app.database import create_sync_engine

from starlette.concurrency import run_in_threadpool

from . import devices, materials, queries, readers, service
from .timetable_pdf import TimetableError, parse_timetable
from .database import initialize_database
from .models import AuditLog, Course, Device, Lesson, Reader, Revision, RevisionNote, RevisionSegment, Uploader
from .schemas import (
    ConfigSync,
    CourseStatus,
    MaterialOrder,
    MaterialUpdate,
    PairingClaim,
    PairingRequest,
    ReaderAccessRequest,
    ReaderProfile,
    ReaderRemark,
    TimetableUpload,
    WechatLogin,
    LessonSubmissions,
    ReviewDecision,
    RevisionStatus,
    SubmissionRequest,
    SubmissionResult,
    UploaderCreate,
    WithdrawRequest,
)

MAX_SUBMISSION_BYTES = 8 * 1024 * 1024
MAX_TIMETABLE_PDF_BYTES = 2 * 1024 * 1024
TIMETABLE_PDF_PER_HOUR = 20
WECHAT_LOGINS_PER_HOUR = 30
PAIRINGS_PER_HOUR = 20
PAIRING_LOOKUPS_PER_HOUR = 10
ADMIN_PAGE = Path(__file__).with_name("admin.html")


def create_app(database_url: str | None = None) -> FastAPI:
    engine = create_sync_engine(database_url or os.getenv("PUBLISH_DATABASE_URL", "sqlite:////data/listen-publish.db"))
    admin_token = os.getenv("PUBLISH_ADMIN_TOKEN")
    wechat_appid = os.getenv("WECHAT_APPID")
    wechat_secret = os.getenv("WECHAT_SECRET")
    config_key = os.getenv("PUBLISH_CONFIG_KEY")
    config_cipher = devices.ConfigCipher(config_key) if config_key else None
    sync_url, sync_token = os.getenv("PUBLISH_SYNC_BASE_URL"), os.getenv("SYNC_API_TOKEN")
    sync_defaults = {"baseUrl": sync_url, "apiToken": sync_token} if sync_url and sync_token else None

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

    def optional_reader(
        authorization: Annotated[str | None, Header()] = None, db: Session = Depends(get_db)
    ) -> Reader | None:
        # Content endpoints treat a missing or expired reader token as anonymous: notes only.
        token = bearer(authorization)
        return readers.authenticate(db, token) if token else None

    def require_reader(reader: Reader | None = Depends(optional_reader)) -> Reader:
        if reader is None:
            raise unauthorized()
        return reader

    def require_device(authorization: Annotated[str | None, Header()] = None, db: Session = Depends(get_db)) -> Device:
        token = bearer(authorization)
        device = devices.authenticate(db, token) if token else None
        if device is None:
            raise unauthorized()
        return device

    def require_admin(authorization: Annotated[str | None, Header()] = None, db: Session = Depends(get_db)) -> None:
        # The /admin page sends the admin token; the mini program sends an admin reader's token.
        token = bearer(authorization)
        if admin_token and token and secrets.compare_digest(token, admin_token):
            return
        reader = readers.authenticate(db, token) if token else None
        if reader is None or not reader.isAdmin:
            raise unauthorized()
        db.info["actor"] = f"reader:{reader.id}"
        db.info["readerId"] = reader.id

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    # ---- public, anonymous, read only ---------------------------------------------------

    public = APIRouter(prefix="/api/public/v1")

    def cached(request: Request, payload, reader_aware: bool = False) -> Response:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        etag = 'W/"' + hashlib.sha256(body).hexdigest()[:32] + '"'
        headers = {"ETag": etag, "Cache-Control": "public, max-age=30"}
        if reader_aware:
            # The same URL answers differently for allowed readers; keep shared caches out of it.
            # no-cache: always revalidate, so a reader allowed a moment ago sees the transcript at
            # once; the ETag keeps unchanged answers to a 304.
            headers.update({"Cache-Control": "private, no-cache", "Vary": "Authorization"})
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
    def lesson(
        request: Request, lesson_id: str, reader: Reader | None = Depends(optional_reader), db: Session = Depends(get_db)
    ):
        detail = queries.lesson_detail(db, lesson_id, transcripts=readers.can_read_transcripts(reader))
        if detail is None:
            raise not_found("课堂不存在或已下架")
        return cached(request, detail, reader_aware=True)

    @public.get("/search")
    def search(
        request: Request,
        q: Annotated[str, Query(max_length=50)],
        courseId: str | None = None,
        cursor: str | None = None,
        limit: Annotated[int, Query(ge=1, le=50)] = 20,
        reader: Reader | None = Depends(optional_reader),
        db: Session = Depends(get_db),
    ):
        query = q.strip()
        if not query:
            raise HTTPException(400, "请输入关键词")
        try:
            hits = queries.search(db, query, courseId, cursor, limit, transcripts=readers.can_read_transcripts(reader))
            return cached(request, hits, reader_aware=True)
        except ValueError as error:
            raise HTTPException(400, str(error)) from error

    @public.get("/lessons/{lesson_id}/export.txt")
    def export(
        lesson_id: str,
        parts: str | None = None,
        reader: Reader | None = Depends(optional_reader),
        db: Session = Depends(get_db),
    ):
        allowed = readers.can_read_transcripts(reader)
        # Without parts: notes, plus the transcript for readers allowed to see it.
        wanted = {"notes", "transcript"} if allowed else {"notes"}
        if parts is not None:
            wanted = {part.strip() for part in parts.split(",") if part.strip()}
            if not wanted or not wanted <= {"transcript", "notes"}:
                raise HTTPException(400, "parts 只能是 transcript、notes 或两者")
            if "transcript" in wanted and not allowed:
                raise HTTPException(403, "原文只对获准的读者开放")
        try:
            result = queries.export_txt(db, lesson_id, wanted, refuse_empty_notes=parts is not None)
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
                "Cache-Control": "private, no-cache",
                "Vary": "Authorization",
            },
        )

    # ---- course materials ---------------------------------------------------------------

    def material_file(material) -> FileResponse:
        path = materials.file_path(material)
        if not path.is_file():
            raise not_found("资料文件不存在，请联系管理员重新上传")
        # Every upload gets a new id, so a file at a given URL never changes.
        return FileResponse(
            path,
            media_type=materials.CONTENT_TYPES[material.ext],
            filename=f"{material.title}.{material.ext}",
            headers={"Cache-Control": "public, max-age=86400"},
        )

    @public.get("/courses/{course_id}/materials")
    def course_materials(request: Request, course_id: str, db: Session = Depends(get_db)):
        course = materials.public_course(db, course_id)
        if course is None:
            raise not_found("课程不存在或未公开")
        rows = materials.course_materials(db, course.id)
        return cached(request, {"course": queries.course_dict(course), "materials": [materials.material_dict(m) for m in rows]})

    @public.get("/materials/{material_id}/file")
    def material_download(material_id: str, db: Session = Depends(get_db)):
        material = materials.public_material(db, material_id)
        if material is None:
            raise not_found("资料不存在或已删除")
        return material_file(material)

    # A stateless helper for the mini program's timetable: the PDF is parsed in memory and
    # dropped; nothing is stored or logged. It lives under /api/public so the existing proxy
    # route and the mini program's request domain cover it.
    pdf_calls: dict[str, list[float]] = {}

    @public.post("/tools/timetable-pdf")
    async def timetable_pdf(request: Request):
        client = request.headers.get("x-real-ip") or (request.client.host if request.client else "")
        now = time.monotonic()
        recent = [t for t in pdf_calls.get(client, []) if now - t < 3600]
        if len(recent) >= TIMETABLE_PDF_PER_HOUR:
            raise HTTPException(429, "导入太频繁，请稍后再试")
        pdf_calls[client] = recent + [now]
        if len(pdf_calls) > 10_000:
            pdf_calls.clear()
        body = await request.body()
        if not body:
            raise HTTPException(400, "没有收到文件")
        if len(body) > MAX_TIMETABLE_PDF_BYTES:
            raise HTTPException(413, "文件太大，课表 PDF 一般不超过 2 MB")
        try:
            return await run_in_threadpool(parse_timetable, body)
        except TimetableError as error:
            raise HTTPException(422, str(error)) from error

    # ---- readers (mini program sign-in) -----------------------------------------------

    login_calls: dict[str, list[float]] = {}

    @public.post("/auth/wechat")
    async def wechat_login(body: WechatLogin, request: Request, db: Session = Depends(get_db)):
        if not (wechat_appid and wechat_secret):
            raise HTTPException(503, "服务端未配置微信登录")
        client = request.headers.get("x-real-ip") or (request.client.host if request.client else "")
        now = time.monotonic()
        recent = [t for t in login_calls.get(client, []) if now - t < 3600]
        if len(recent) >= WECHAT_LOGINS_PER_HOUR:
            raise HTTPException(429, "登录太频繁，请稍后再试")
        login_calls[client] = recent + [now]
        if len(login_calls) > 10_000:
            login_calls.clear()
        openid = await run_in_threadpool(readers.code_to_openid, wechat_appid, wechat_secret, body.code)
        reader, token, expires = readers.sign_in(db, openid)
        return {"token": token, "expiresAt": expires, "reader": readers.reader_dict(reader)}

    @public.get("/me")
    def reader_me(reader: Reader = Depends(require_reader)):
        return readers.reader_dict(reader)

    @public.put("/me/profile")
    def reader_profile(body: ReaderProfile, reader: Reader = Depends(require_reader), db: Session = Depends(get_db)):
        return readers.reader_dict(readers.save_profile(db, reader, body.nickname, body.avatar))

    @public.post("/me/transcript-access")
    def reader_request_access(
        body: ReaderAccessRequest | None = None, reader: Reader = Depends(require_reader), db: Session = Depends(get_db)
    ):
        return readers.reader_dict(readers.request_access(db, reader, body.note if body else None))

    @public.get("/me/timetable")
    def reader_timetable(reader: Reader = Depends(require_reader), db: Session = Depends(get_db)):
        return JSONResponse(readers.timetable(db, reader), headers={"Cache-Control": "no-store"})

    @public.put("/me/timetable")
    def reader_save_timetable(body: TimetableUpload, reader: Reader = Depends(require_reader), db: Session = Depends(get_db)):
        try:
            return readers.save_timetable(db, reader, body.timetable, body.updatedAt)
        except readers.TimetableConflict as conflict:
            # Changed later on another phone: hand that copy back so this one can take it.
            return JSONResponse(status_code=409, content={"detail": "课表在另一台手机上更新过", **conflict.current})

    # ---- devices: bind a phone, then keep its service settings in the cloud ---------------

    pairing_calls: dict[str, list[float]] = {}

    def limit_pairing(key: str, per_hour: int) -> None:
        now = time.monotonic()
        recent = [t for t in pairing_calls.get(key, []) if now - t < 3600]
        if len(recent) >= per_hour:
            raise HTTPException(429, "尝试太频繁，请稍后再试")
        pairing_calls[key] = recent + [now]
        if len(pairing_calls) > 10_000:
            pairing_calls.clear()

    def require_config_cipher() -> devices.ConfigCipher:
        if config_cipher is None:
            raise HTTPException(503, "服务端未配置设备绑定")
        return config_cipher

    def require_binding_admin(reader: Reader = Depends(require_reader)) -> Reader:
        require_config_cipher()
        if not reader.isAdmin:
            raise HTTPException(403, "这个账号不能绑定设备")
        limit_pairing(f"reader:{reader.id}", PAIRING_LOOKUPS_PER_HOUR)
        return reader

    def no_store(payload) -> JSONResponse:
        return JSONResponse(payload, headers={"Cache-Control": "no-store"})

    @public.post("/devices/pairings")
    def device_pairing(body: PairingRequest, request: Request, db: Session = Depends(get_db)):
        require_config_cipher()
        client = request.headers.get("x-real-ip") or (request.client.host if request.client else "")
        limit_pairing(f"ip:{client}", PAIRINGS_PER_HOUR)
        return no_store(devices.create_pairing(db, body.name))

    @public.post("/devices/pairings/claim")
    def device_claim(body: PairingClaim, db: Session = Depends(get_db)):
        return no_store(devices.claim_pairing(db, body.claim))

    @public.post("/devices/config/sync")
    def device_config(body: ConfigSync, device: Device = Depends(require_device), db: Session = Depends(get_db)):
        return no_store(devices.sync_config(db, device, require_config_cipher(), body, sync_defaults))

    @public.delete("/devices/current")
    def device_unbind(device: Device = Depends(require_device), db: Session = Depends(get_db)):
        devices.remove(db, device, f"device:{device.id}")
        return {"removed": True}

    @public.get("/me/device-pairings/{code}")
    def reader_pairing(code: str, _reader: Reader = Depends(require_binding_admin), db: Session = Depends(get_db)):
        return devices.preview_pairing(db, code)

    @public.post("/me/device-pairings/{code}/approve")
    def reader_approve_pairing(code: str, reader: Reader = Depends(require_binding_admin), db: Session = Depends(get_db)):
        return devices.approve_pairing(db, reader, code)

    @public.get("/me/devices")
    def reader_devices(reader: Reader = Depends(require_reader), db: Session = Depends(get_db)):
        return {"devices": devices.list_devices(db, reader)}

    @public.delete("/me/devices/{device_id}")
    def reader_remove_device(device_id: str, reader: Reader = Depends(require_reader), db: Session = Depends(get_db)):
        device = db.get(Device, device_id)
        if device is None or device.readerId != reader.id:
            raise not_found("设备不存在")
        devices.remove(db, device, f"reader:{reader.id}")
        return {"removed": True}

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
            "pendingReaders": db.scalar(select(func.count()).select_from(Reader).where(Reader.transcriptAccess == "pending")),
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

    @admin.delete("/courses/{course_id}")
    def admin_course_delete(course_id: str, db: Session = Depends(get_db)):
        service.delete_course(db, course_id)
        return {"courseId": course_id, "deleted": True}

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

    @admin.post("/lessons/{lesson_id}/withdraw-dismiss")
    def admin_withdraw_dismiss(lesson_id: str, body: ReviewDecision, db: Session = Depends(get_db)):
        lesson = service.dismiss_withdraw(db, lesson_id, body.note)
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
        old = db.get(Uploader, uploader_id)
        old_hash = old.tokenHash if old else None
        uploader, token = service.rotate_uploader_token(db, uploader_id)
        if config_cipher is not None:
            devices.replace_publish_token(db, config_cipher, old_hash, token)
        return {**uploader_dict(uploader), "token": token}

    @admin.post("/uploaders/{uploader_id}/disable")
    def admin_disable(uploader_id: int, db: Session = Depends(get_db)):
        return uploader_dict(service.set_uploader_status(db, uploader_id, "disabled"))

    @admin.post("/uploaders/{uploader_id}/enable")
    def admin_enable(uploader_id: int, db: Session = Depends(get_db)):
        return uploader_dict(service.set_uploader_status(db, uploader_id, "active"))

    @admin.get("/readers")
    def admin_readers(status: str = "all", db: Session = Depends(get_db)):
        stmt = select(Reader)
        if status != "all":
            stmt = stmt.where(Reader.transcriptAccess == status)
        rows = db.scalars(stmt.order_by(Reader.requestedAt.desc().nulls_last(), Reader.lastSeenAt.desc()).limit(200))
        return {"readers": [readers.admin_reader_dict(reader) for reader in rows]}

    @admin.post("/readers/{reader_id}/remark")
    def admin_reader_remark(reader_id: int, body: ReaderRemark, db: Session = Depends(get_db)):
        reader = service.require(db.get(Reader, reader_id), "读者不存在")
        return readers.admin_reader_dict(readers.set_remark(db, reader, body.remark))

    @admin.post("/readers/{reader_id}/approve")
    def admin_reader_approve(reader_id: int, db: Session = Depends(get_db)):
        reader = service.require(db.get(Reader, reader_id), "读者不存在")
        return readers.admin_reader_dict(readers.decide(db, reader, approved=True))

    @admin.post("/readers/{reader_id}/make-admin")
    def admin_reader_make_admin(reader_id: int, db: Session = Depends(get_db)):
        reader = service.require(db.get(Reader, reader_id), "读者不存在")
        return readers.admin_reader_dict(readers.set_admin(db, reader, True))

    @admin.post("/readers/{reader_id}/remove-admin")
    def admin_reader_remove_admin(reader_id: int, db: Session = Depends(get_db)):
        reader = service.require(db.get(Reader, reader_id), "读者不存在")
        if db.info.get("readerId") == reader.id:
            raise HTTPException(409, "不能取消自己的管理员身份")
        return readers.admin_reader_dict(readers.set_admin(db, reader, False))

    @admin.post("/readers/{reader_id}/revoke")
    def admin_reader_revoke(reader_id: int, db: Session = Depends(get_db)):
        reader = service.require(db.get(Reader, reader_id), "读者不存在")
        return readers.admin_reader_dict(readers.decide(db, reader, approved=False))

    @admin.get("/audit")
    def admin_audit(
        before: Annotated[int | None, Query(ge=1)] = None,
        limit: Annotated[int, Query(ge=1, le=100)] = 100,
        db: Session = Depends(get_db),
    ):
        stmt = select(AuditLog).order_by(AuditLog.id.desc()).limit(limit)
        if before is not None:
            stmt = stmt.where(AuditLog.id < before)
        rows = list(db.scalars(stmt))

        def actor_name(actor: str) -> str:
            kind, _, key = actor.partition(":")
            row = db.get(Reader, int(key)) if kind == "reader" and key.isdigit() else None
            if row is not None:
                return row.remark or row.nickname or "未命名读者"
            uploader = db.get(Uploader, int(key)) if kind == "uploader" and key.isdigit() else None
            if uploader is not None:
                return uploader.displayName
            device = db.get(Device, key) if kind == "device" else None
            if device is not None:
                return device.name
            return "管理员" if actor == "admin" else "已移除的设备" if kind == "device" else actor
        return {"entries": [{
            "id": r.id, "at": r.at, "actor": r.actor, "actorName": actor_name(r.actor), "action": r.action,
            "target": r.target, "targetName": service.audit_target_name(db, r.target), "detail": r.detail,
        } for r in rows]}

    @admin.get("/courses/{course_id}/materials")
    def admin_materials(course_id: str, db: Session = Depends(get_db)):
        course = service.require(db.get(Course, course_id), "课程不存在")
        return {"course": queries.course_dict(course), "materials": [materials.material_dict(m) for m in materials.course_materials(db, course.id)]}

    @admin.post("/courses/{course_id}/materials")
    async def admin_upload_material(
        request: Request,
        course_id: str,
        fileName: Annotated[str, Query(max_length=255)],
        title: Annotated[str | None, Query(max_length=200)] = None,
        db: Session = Depends(get_db),
    ):
        # The raw file is the body, as with the timetable PDF, so the mini program can send it
        # with wx.request and needs no separate upload domain.
        declared = request.headers.get("content-length")
        if declared and declared.isdigit() and int(declared) > materials.MAX_MATERIAL_BYTES:
            raise HTTPException(413, f"文件超过 {materials.MAX_MATERIAL_BYTES // 1024 // 1024} MB")
        body = await request.body()
        return materials.material_dict(materials.upload(db, course_id, fileName, body, title))

    @admin.patch("/materials/{material_id}")
    def admin_update_material(material_id: str, body: MaterialUpdate, db: Session = Depends(get_db)):
        return materials.material_dict(materials.update(db, material_id, body.title, body.courseId))

    @admin.put("/courses/{course_id}/materials/order")
    def admin_order_materials(course_id: str, body: MaterialOrder, db: Session = Depends(get_db)):
        return {"materials": [materials.material_dict(m) for m in materials.reorder(db, course_id, body.materialIds)]}

    @admin.delete("/materials/{material_id}")
    def admin_delete_material(material_id: str, db: Session = Depends(get_db)):
        materials.remove(db, material_id)
        return {"materialId": material_id, "deleted": True}

    @admin.get("/materials/{material_id}/file")
    def admin_material_download(material_id: str, db: Session = Depends(get_db)):
        return material_file(service.require(db.get(materials.CourseMaterial, material_id), "资料不存在"))

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
