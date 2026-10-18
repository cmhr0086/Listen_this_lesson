from __future__ import annotations

from sqlalchemy import Boolean, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# Kept apart from the personal sync models on purpose: this database is the only thing the
# public reader can reach, so private sync rows cannot leak into a public query by mistake.


class Base(DeclarativeBase):
    pass


class Uploader(Base):
    __tablename__ = "uploaders"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    displayName: Mapped[str] = mapped_column(Text, nullable=False)
    tokenHash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    tokenHint: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(Text, nullable=False, default="active")
    createdAt: Mapped[int] = mapped_column(Integer, nullable=False)


class Course(Base):
    __tablename__ = "courses"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    schoolName: Mapped[str] = mapped_column(Text, nullable=False)
    term: Mapped[str] = mapped_column(Text, nullable=False)
    courseName: Mapped[str] = mapped_column(Text, nullable=False)
    teacherName: Mapped[str] = mapped_column(Text, nullable=False)
    normKey: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    createdAt: Mapped[int] = mapped_column(Integer, nullable=False)
    # Review happens per course: once approved, its lessons go live as they are uploaded.
    # pending | approved | rejected | taken_down
    status: Mapped[str] = mapped_column(Text, nullable=False, default="pending", server_default="pending")
    requestedBy: Mapped[int | None] = mapped_column(Integer)
    requestedAt: Mapped[int | None] = mapped_column(Integer)
    reviewedAt: Mapped[int | None] = mapped_column(Integer)
    reviewNote: Mapped[str | None] = mapped_column(Text)


class Lesson(Base):
    __tablename__ = "lessons"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    uploaderId: Mapped[int] = mapped_column(ForeignKey("uploaders.id"), nullable=False)
    sessionId: Mapped[str] = mapped_column(Text, nullable=False)
    courseId: Mapped[str] = mapped_column(ForeignKey("courses.id"), nullable=False, index=True)
    # Plain column rather than a foreign key: revisions point back at lessons, and the cycle
    # buys nothing here because only service code writes it.
    publishedRevisionId: Mapped[int | None] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(Text, nullable=False, default="active")
    withdrawRequestedAt: Mapped[int | None] = mapped_column(Integer)
    withdrawReason: Mapped[str | None] = mapped_column(Text)
    createdAt: Mapped[int] = mapped_column(Integer, nullable=False)
    updatedAt: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (UniqueConstraint("uploaderId", "sessionId"),)


class Revision(Base):
    __tablename__ = "revisions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    lessonId: Mapped[str] = mapped_column(ForeignKey("lessons.id"), nullable=False)
    number: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(Text, nullable=False)
    clientSubmissionId: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    contentHash: Mapped[str] = mapped_column(String(64), nullable=False)
    courseId: Mapped[str] = mapped_column(ForeignKey("courses.id"), nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    topic: Mapped[str | None] = mapped_column(Text)
    startedAt: Mapped[int] = mapped_column(Integer, nullable=False)
    endedAt: Mapped[int | None] = mapped_column(Integer)
    submittedAt: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    reviewedAt: Mapped[int | None] = mapped_column(Integer)
    reviewNote: Mapped[str | None] = mapped_column(Text)

    __table_args__ = (Index("ix_revisions_lesson_status", "lessonId", "status"),)


class RevisionSegment(Base):
    __tablename__ = "revision_segments"

    revisionId: Mapped[int] = mapped_column(ForeignKey("revisions.id"), primary_key=True)
    seq: Mapped[int] = mapped_column(Integer, primary_key=True)
    startTime: Mapped[int] = mapped_column(Integer, nullable=False)
    endTime: Mapped[int] = mapped_column(Integer, nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    correctedText: Mapped[str | None] = mapped_column(Text)
    marked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)


class RevisionNote(Base):
    __tablename__ = "revision_notes"

    revisionId: Mapped[int] = mapped_column(ForeignKey("revisions.id"), primary_key=True)
    position: Mapped[int] = mapped_column(Integer, primary_key=True)
    noteId: Mapped[str] = mapped_column(Text, nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    format: Mapped[str] = mapped_column(Text, nullable=False)


class AuditLog(Base):
    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    at: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    actor: Mapped[str] = mapped_column(Text, nullable=False)
    action: Mapped[str] = mapped_column(Text, nullable=False)
    target: Mapped[str] = mapped_column(Text, nullable=False)
    detail: Mapped[str | None] = mapped_column(Text)


class Reader(Base):
    """A mini program user, known only by openid. Transcripts need an admin-approved reader."""

    __tablename__ = "readers"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    openid: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    # none | pending | approved | revoked
    transcriptAccess: Mapped[str] = mapped_column(Text, nullable=False, default="none")
    requestNote: Mapped[str | None] = mapped_column(Text)
    requestedAt: Mapped[int | None] = mapped_column(Integer)
    reviewedAt: Mapped[int | None] = mapped_column(Integer)
    createdAt: Mapped[int] = mapped_column(Integer, nullable=False)
    lastSeenAt: Mapped[int] = mapped_column(Integer, nullable=False)


class ReaderSession(Base):
    __tablename__ = "reader_sessions"

    tokenHash: Mapped[str] = mapped_column(String(64), primary_key=True)
    readerId: Mapped[int] = mapped_column(ForeignKey("readers.id"), nullable=False, index=True)
    createdAt: Mapped[int] = mapped_column(Integer, nullable=False)
    expiresAt: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
