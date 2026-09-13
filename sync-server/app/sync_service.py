from __future__ import annotations

import time
from collections.abc import Iterable

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from .models import SegmentRecord, SessionRecord, SyncClock
from .schemas import SegmentPayload, SessionPayload, SyncAck, SyncRequest, SyncResponse

SESSION_FIELDS = tuple(SessionPayload.model_fields)
SEGMENT_FIELDS = tuple(SegmentPayload.model_fields)


def _record_values(record, fields: Iterable[str]) -> dict:
    return {field: getattr(record, field) for field in fields}


def _payload_matches(record, payload, fields: Iterable[str]) -> bool:
    return _record_values(record, fields) == payload.model_dump()


def _copy_payload(record, payload, fields: Iterable[str]) -> None:
    for field in fields:
        setattr(record, field, getattr(payload, field))


def _allocate_server_time(db: Session) -> int:
    clock = db.get(SyncClock, 1)
    now = int(time.time() * 1000)
    if clock is None:
        server_time = now
        db.add(SyncClock(id=1, lastServerTime=server_time))
    else:
        server_time = max(now, clock.lastServerTime + 1)
        clock.lastServerTime = server_time
    return server_time


def _merge_sessions(db: Session, incoming: list[SessionPayload], changed_at: int) -> list[SyncAck]:
    for payload in incoming:
        existing = db.get(SessionRecord, payload.sessionId)
        if existing is None:
            values = payload.model_dump()
            db.add(SessionRecord(**values, serverChangedAt=changed_at))
        elif payload.updatedAt > existing.updatedAt:
            _copy_payload(existing, payload, SESSION_FIELDS)
            existing.serverChangedAt = changed_at
    db.flush()
    return [
        SyncAck(
            id=payload.sessionId,
            updatedAt=(record := db.get(SessionRecord, payload.sessionId)).updatedAt,
            matches=_payload_matches(record, payload, SESSION_FIELDS),
        )
        for payload in incoming
    ]


def _merge_segments(db: Session, incoming: list[SegmentPayload], changed_at: int) -> list[SyncAck]:
    for payload in incoming:
        if db.get(SessionRecord, payload.sessionId) is None:
            raise ValueError(f"Segment {payload.segmentId} references unknown Session {payload.sessionId}.")
        existing = db.get(SegmentRecord, payload.segmentId)
        if existing is None:
            values = payload.model_dump()
            db.add(SegmentRecord(**values, serverChangedAt=changed_at))
        elif payload.updatedAt > existing.updatedAt:
            _copy_payload(existing, payload, SEGMENT_FIELDS)
            existing.serverChangedAt = changed_at
    db.flush()
    return [
        SyncAck(
            id=payload.segmentId,
            updatedAt=(record := db.get(SegmentRecord, payload.segmentId)).updatedAt,
            matches=_payload_matches(record, payload, SEGMENT_FIELDS),
        )
        for payload in incoming
    ]


def synchronize(db: Session, request: SyncRequest) -> SyncResponse:
    # The engine begins this transaction with BEGIN IMMEDIATE. The cutoff is
    # persisted in the same transaction, and every accepted row gets exactly
    # this server-side change timestamp.
    with db.begin():
        server_time = _allocate_server_time(db)
        session_acks = _merge_sessions(db, request.sessions, server_time)
        segment_acks = _merge_segments(db, request.segments, server_time)

        requested_session_ids = [item.sessionId for item in request.sessions]
        requested_segment_ids = [item.segmentId for item in request.segments]
        session_filter = SessionRecord.serverChangedAt > request.lastSyncAt
        segment_filter = SegmentRecord.serverChangedAt > request.lastSyncAt
        if requested_session_ids:
            session_filter = or_(session_filter, SessionRecord.sessionId.in_(requested_session_ids))
        if requested_segment_ids:
            segment_filter = or_(segment_filter, SegmentRecord.segmentId.in_(requested_segment_ids))

        session_rows = db.scalars(
            select(SessionRecord)
            .where(session_filter, SessionRecord.serverChangedAt <= server_time)
            .order_by(SessionRecord.serverChangedAt, SessionRecord.sessionId)
        ).all()
        segment_rows = db.scalars(
            select(SegmentRecord)
            .where(segment_filter, SegmentRecord.serverChangedAt <= server_time)
            .order_by(SegmentRecord.serverChangedAt, SegmentRecord.segmentId)
        ).all()

        response = SyncResponse(
            serverTime=server_time,
            sessions=[SessionPayload.model_validate(row) for row in session_rows],
            segments=[SegmentPayload.model_validate(row) for row in segment_rows],
            sessionAcks=session_acks,
            segmentAcks=segment_acks,
        )
    return response
