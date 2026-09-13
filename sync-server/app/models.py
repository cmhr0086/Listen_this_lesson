from __future__ import annotations

from sqlalchemy import Boolean, ForeignKey, Index, Integer, String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class SessionRecord(Base):
    __tablename__ = "sessions"

    sessionId: Mapped[str] = mapped_column(Text, primary_key=True)
    courseName: Mapped[str] = mapped_column(Text, nullable=False)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    startedAt: Mapped[int] = mapped_column(Integer, nullable=False)
    endedAt: Mapped[int | None] = mapped_column(Integer)
    createdAt: Mapped[int] = mapped_column(Integer, nullable=False)
    updatedAt: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    deleted: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    serverChangedAt: Mapped[int] = mapped_column(Integer, nullable=False, index=True)


class SegmentRecord(Base):
    __tablename__ = "segments"

    segmentId: Mapped[str] = mapped_column(Text, primary_key=True)
    sessionId: Mapped[str] = mapped_column(
        Text,
        ForeignKey("sessions.sessionId", ondelete="RESTRICT"),
        nullable=False,
    )
    startTime: Mapped[int] = mapped_column(Integer, nullable=False)
    endTime: Mapped[int] = mapped_column(Integer, nullable=False)
    audioDurationMs: Mapped[int] = mapped_column(Integer, nullable=False)
    recognitionDurationMs: Mapped[int | None] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    correctedText: Mapped[str | None] = mapped_column(Text)
    correctedAt: Mapped[int | None] = mapped_column(Integer)
    sourceSegmentId: Mapped[str | None] = mapped_column(Text)
    sequenceNumber: Mapped[int | None] = mapped_column(Integer)
    asrJobId: Mapped[str | None] = mapped_column(Text)
    queueDurationMs: Mapped[int | None] = mapped_column(Integer)
    uploadDurationMs: Mapped[int | None] = mapped_column(Integer)
    responseWaitDurationMs: Mapped[int | None] = mapped_column(Integer)
    totalAsrDurationMs: Mapped[int | None] = mapped_column(Integer)
    serverModel: Mapped[str | None] = mapped_column(Text)
    createdAt: Mapped[int] = mapped_column(Integer, nullable=False)
    updatedAt: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    deleted: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    serverChangedAt: Mapped[int] = mapped_column(Integer, nullable=False, index=True)

    __table_args__ = (Index("ix_segments_sessionId", "sessionId"),)


class SyncClock(Base):
    __tablename__ = "sync_clock"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    lastServerTime: Mapped[int] = mapped_column(Integer, nullable=False)
