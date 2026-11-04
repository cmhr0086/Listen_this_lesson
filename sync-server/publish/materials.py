from __future__ import annotations

import hashlib
import os
from pathlib import Path
from uuid import uuid4

from sqlalchemy import func, select
from sqlalchemy.orm import Session, object_session

from .models import Course, CourseMaterial, Lesson, Revision
from .service import ServiceError, audit, now_ms, require

# Course materials are uploaded by the admin only. Readers get the list and the file of an
# approved course; nothing else about them is public.

MAX_MATERIAL_BYTES = 20 * 1024 * 1024
MAX_TITLE_CHARS = 100

# Formats the mini program can open in place: wx.openDocument handles these documents...
DOCUMENT_TYPES = {
    "doc": "application/msword",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls": "application/vnd.ms-excel",
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt": "application/vnd.ms-powerpoint",
    "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "pdf": "application/pdf",
}
# ...and wx.previewImage these.
IMAGE_TYPES = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png"}
# Accepted but not viewable in the mini program; readers can send them to a chat instead.
OTHER_TYPES = {
    "txt": "text/plain; charset=utf-8",
    "md": "text/markdown; charset=utf-8",
    "zip": "application/zip",
    "rar": "application/vnd.rar",
    "7z": "application/x-7z-compressed",
}
CONTENT_TYPES = {**DOCUMENT_TYPES, **IMAGE_TYPES, **OTHER_TYPES}


def directory() -> Path:
    path = Path(os.getenv("PUBLISH_MATERIALS_DIR", "/data/materials"))
    path.mkdir(parents=True, exist_ok=True)
    return path


def file_path(material: CourseMaterial) -> Path:
    return directory() / f"{material.id}.{material.ext}"


def viewer(ext: str) -> str:
    """How a reader opens it: document, image, or none (download to a chat only)."""
    return "document" if ext in DOCUMENT_TYPES else "image" if ext in IMAGE_TYPES else "none"


def split_name(file_name: str) -> tuple[str, str]:
    name = Path(file_name.replace("\\", "/")).name.strip()
    stem, dot, ext = name.rpartition(".")
    if not dot or not stem.strip():
        raise ServiceError(415, "文件没有扩展名，无法识别格式")
    ext = ext.lower()
    if ext not in CONTENT_TYPES:
        allowed = "、".join(sorted({e.upper() for e in CONTENT_TYPES if e != "jpeg"}))
        raise ServiceError(415, f"不支持 .{ext} 文件，可以上传 {allowed}")
    return clean_title(stem), ext


def clean_title(title: str) -> str:
    title = " ".join(title.split())
    if not title:
        raise ServiceError(422, "资料名称不能为空")
    return title[:MAX_TITLE_CHARS]


def _lesson_title(material: CourseMaterial) -> str | None:
    db = object_session(material)
    lesson = db.get(Lesson, material.lessonId) if material.lessonId and db else None
    revision = db.get(Revision, lesson.publishedRevisionId) if lesson and lesson.publishedRevisionId else None
    return revision.title if revision else None


def material_dict(material: CourseMaterial) -> dict:
    return {
        "category": material.category,
        "lessonId": material.lessonId,
        "lessonTitle": _lesson_title(material),
        "materialId": material.id,
        "courseId": material.courseId,
        "title": material.title,
        "ext": material.ext,
        "fileName": f"{material.title}.{material.ext}",
        "size": material.size,
        "viewer": viewer(material.ext),
        "position": material.position,
        "createdAt": material.createdAt,
        "updatedAt": material.updatedAt,
    }


def course_materials(db: Session, course_id: str) -> list[CourseMaterial]:
    return list(db.scalars(
        select(CourseMaterial).where(CourseMaterial.courseId == course_id)
        .order_by(CourseMaterial.position, CourseMaterial.createdAt)
    ))


def public_course(db: Session, course_id: str) -> Course | None:
    course = db.get(Course, course_id)
    return course if course is not None and course.status == "approved" else None


def public_material(db: Session, material_id: str) -> CourseMaterial | None:
    material = db.get(CourseMaterial, material_id)
    if material is None or public_course(db, material.courseId) is None:
        return None
    return material


def next_position(db: Session, course_id: str) -> int:
    return (db.scalar(select(func.max(CourseMaterial.position)).where(CourseMaterial.courseId == course_id)) or 0) + 1


def upload(
    db: Session, course_id: str, file_name: str, body: bytes, title: str | None = None,
    category: str = "material", lesson_id: str | None = None,
) -> CourseMaterial:
    course = require(db.get(Course, course_id), "课程不存在")
    if lesson_id:
        lesson = db.get(Lesson, lesson_id)
        if lesson is None or lesson.courseId != course.id:
            raise ServiceError(404, "这门课没有这节课堂")
    stem, ext = split_name(file_name)
    if not body:
        raise ServiceError(400, "文件是空的")
    if len(body) > MAX_MATERIAL_BYTES:
        raise ServiceError(413, f"文件超过 {MAX_MATERIAL_BYTES // 1024 // 1024} MB")
    at = now_ms()
    material = CourseMaterial(
        id=uuid4().hex,
        courseId=course.id,
        category=category,
        lessonId=lesson_id or None,
        title=clean_title(title) if title else stem,
        ext=ext,
        size=len(body),
        sha256=hashlib.sha256(body).hexdigest(),
        position=next_position(db, course.id),
        createdAt=at,
        updatedAt=at,
    )
    path = file_path(material)
    # Written beside the target and renamed, so a reader never downloads half a file.
    partial = path.with_suffix(path.suffix + ".part")
    partial.write_bytes(body)
    partial.replace(path)
    db.add(material)
    audit(db, "admin", "material.uploaded", f"course:{course.id}", f"{material.title}.{ext}，{len(body)} 字节")
    try:
        db.commit()
    except Exception:
        path.unlink(missing_ok=True)
        raise
    return material


def update(db: Session, material_id: str, title: str | None, course_id: str | None) -> CourseMaterial:
    material = require(db.get(CourseMaterial, material_id), "资料不存在")
    if title is not None:
        material.title = clean_title(title)
    if course_id is not None and course_id != material.courseId:
        course = require(db.get(Course, course_id), "课程不存在")
        audit(db, "admin", "material.moved", f"material:{material.id}", f"course:{material.courseId} → course:{course.id}")
        material.courseId = course.id
        # A lesson belongs to one course, so the file now belongs to the new course as a whole.
        material.lessonId = None
        material.position = next_position(db, course.id)
    material.updatedAt = now_ms()
    db.commit()
    return material


def reorder(db: Session, course_id: str, material_ids: list[str]) -> list[CourseMaterial]:
    require(db.get(Course, course_id), "课程不存在")
    current = course_materials(db, course_id)
    if sorted(m.id for m in current) != sorted(material_ids):
        raise ServiceError(409, "资料列表已变化，请刷新后再排序")
    by_id = {m.id: m for m in current}
    for position, material_id in enumerate(material_ids, start=1):
        by_id[material_id].position = position
    db.commit()
    return course_materials(db, course_id)


def remove(db: Session, material_id: str) -> None:
    material = require(db.get(CourseMaterial, material_id), "资料不存在")
    path = file_path(material)
    audit(db, "admin", "material.deleted", f"course:{material.courseId}", f"{material.title}.{material.ext}")
    db.delete(material)
    db.commit()
    path.unlink(missing_ok=True)


def remove_for_course(db: Session, course_id: str) -> list[Path]:
    """Called while a course is being deleted. Returns the files to remove once that commits."""
    paths = []
    for material in course_materials(db, course_id):
        paths.append(file_path(material))
        db.delete(material)
    return paths
