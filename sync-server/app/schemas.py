from __future__ import annotations

from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator

Timestamp = Annotated[int, Field(ge=0)]
OptionalDuration = Annotated[int | None, Field(default=None, ge=0)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", from_attributes=True)


def canonical_uuid(value: str) -> str:
    return str(UUID(value))


class SessionPayload(StrictModel):
    sessionId: str
    courseName: str = Field(min_length=1)
    name: str = Field(min_length=1)
    startedAt: Timestamp
    endedAt: Timestamp | None = None
    createdAt: Timestamp
    updatedAt: Timestamp
    deleted: bool = False

    @field_validator("sessionId")
    @classmethod
    def validate_session_id(cls, value: str) -> str:
        return canonical_uuid(value)


class SegmentPayload(StrictModel):
    segmentId: str
    sessionId: str
    startTime: Timestamp
    endTime: Timestamp
    audioDurationMs: Timestamp
    recognitionDurationMs: OptionalDuration = None
    text: str
    correctedText: str | None = None
    correctedAt: Timestamp | None = None
    sourceSegmentId: str | None = None
    sequenceNumber: Timestamp | None = None
    asrJobId: str | None = None
    queueDurationMs: OptionalDuration = None
    uploadDurationMs: OptionalDuration = None
    responseWaitDurationMs: OptionalDuration = None
    totalAsrDurationMs: OptionalDuration = None
    serverModel: str | None = None
    createdAt: Timestamp
    updatedAt: Timestamp
    deleted: bool = False

    @field_validator("segmentId", "sessionId")
    @classmethod
    def validate_ids(cls, value: str) -> str:
        return canonical_uuid(value)


class SyncRequest(StrictModel):
    deviceId: str
    lastSyncAt: Timestamp = 0
    sessions: list[SessionPayload] = Field(default_factory=list)
    segments: list[SegmentPayload] = Field(default_factory=list)

    @field_validator("deviceId")
    @classmethod
    def validate_device_id(cls, value: str) -> str:
        return canonical_uuid(value)


class SyncAck(StrictModel):
    id: str
    updatedAt: Timestamp
    matches: bool


class SyncResponse(StrictModel):
    serverTime: Timestamp
    sessions: list[SessionPayload]
    segments: list[SegmentPayload]
    sessionAcks: list[SyncAck]
    segmentAcks: list[SyncAck]
