from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.schemas import canonical_uuid

Timestamp = Annotated[int, Field(ge=0)]

MAX_SEGMENTS = 5_000
MAX_SEGMENT_CHARS = 10_000
MAX_NOTES = 50
MAX_NOTE_CHARS = 200_000


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


def _required_text(value: str) -> str:
    value = value.strip()
    if not value:
        raise ValueError("must not be blank")
    return value


def _optional_text(value: str | None) -> str | None:
    if value is None:
        return None
    value = value.strip()
    return value or None


class CourseInput(StrictModel):
    schoolName: str = Field(max_length=100)
    term: str = Field(max_length=50)
    courseName: str = Field(max_length=100)
    teacherName: str = Field(max_length=50)

    _required = field_validator("schoolName", "term", "courseName", "teacherName")(_required_text)


class SessionInput(StrictModel):
    sessionId: str
    name: str = Field(max_length=200)
    topic: str | None = Field(default=None, max_length=500)
    startedAt: Timestamp
    endedAt: Timestamp | None = None

    _id = field_validator("sessionId")(canonical_uuid)
    _name = field_validator("name")(_required_text)
    _topic = field_validator("topic")(_optional_text)


class SegmentInput(StrictModel):
    startTime: Timestamp
    endTime: Timestamp
    text: str = Field(max_length=MAX_SEGMENT_CHARS)
    correctedText: str | None = Field(default=None, max_length=MAX_SEGMENT_CHARS)
    marked: bool = False

    @model_validator(mode="after")
    def check_times(self) -> SegmentInput:
        if self.endTime < self.startTime:
            raise ValueError("endTime must not be before startTime")
        return self


class NoteInput(StrictModel):
    noteId: str = Field(min_length=1, max_length=200)
    title: str = Field(max_length=200)
    content: str = Field(max_length=MAX_NOTE_CHARS)
    format: Literal["plain", "markdown"] = "markdown"

    _title = field_validator("title")(_required_text)
    _content = field_validator("content")(_required_text)


class SubmissionRequest(StrictModel):
    clientSubmissionId: str
    course: CourseInput
    session: SessionInput
    segments: list[SegmentInput] = Field(default_factory=list, max_length=MAX_SEGMENTS)
    notes: list[NoteInput] = Field(default_factory=list, max_length=MAX_NOTES)

    _id = field_validator("clientSubmissionId")(canonical_uuid)

    @model_validator(mode="after")
    def check_content(self) -> SubmissionRequest:
        if not self.segments and not self.notes:
            raise ValueError("a submission needs transcript segments or notes")
        if len({note.noteId for note in self.notes}) != len(self.notes):
            raise ValueError("noteId values must be unique")
        return self


class SubmissionResult(BaseModel):
    lessonId: str
    revisionId: int | None
    revision: int | None
    # published: this version is now the lesson's public one; unchanged: identical to it already.
    status: Literal["published", "unchanged"]
    courseId: str
    # Readers see the lesson only while its course is approved.
    courseStatus: str
    courseReviewNote: str | None = None


class CourseStatus(BaseModel):
    courseId: str
    schoolName: str
    term: str
    courseName: str
    teacherName: str
    status: str
    reviewNote: str | None
    lessonCount: int


class RevisionStatus(BaseModel):
    revisionId: int
    revision: int
    status: str
    submittedAt: int
    reviewedAt: int | None
    reviewNote: str | None


class LessonSubmissions(BaseModel):
    lessonId: str | None
    lessonStatus: str | None
    published: bool
    withdrawRequested: bool
    revisions: list[RevisionStatus]


class WithdrawRequest(StrictModel):
    reason: str = Field(max_length=500)

    _reason = field_validator("reason")(_required_text)


class ReviewDecision(StrictModel):
    note: str | None = Field(default=None, max_length=1000)

    _note = field_validator("note")(_optional_text)


class UploaderCreate(StrictModel):
    displayName: str = Field(max_length=50)

    _name = field_validator("displayName")(_required_text)


class WechatLogin(StrictModel):
    code: str = Field(min_length=1, max_length=128)


class ReaderAccessRequest(StrictModel):
    # Lets the admin tell which openid is whose ("我是小林").
    note: str | None = Field(default=None, max_length=50)

    _note = field_validator("note")(_optional_text)


class TimetableUpload(StrictModel):
    timetable: dict
    updatedAt: Timestamp


class ReaderProfile(StrictModel):
    # None leaves a field as it is; "" clears it.
    nickname: str | None = Field(default=None, max_length=20)
    avatar: str | None = Field(default=None, max_length=96 * 1024)

    @field_validator("nickname")
    @classmethod
    def _strip(cls, value: str | None) -> str | None:
        return value.strip() if value is not None else None
