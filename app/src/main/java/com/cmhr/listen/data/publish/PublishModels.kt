package com.cmhr.listen.data.publish

import kotlinx.serialization.Serializable

/**
 * Wire format of the publish service (`/api/publish/v1`). It is a separate system from personal
 * sync: a submission is a frozen copy of one class that an admin reviews before readers see it.
 */
@Serializable
data class PublishCourse(
    val schoolName: String,
    val term: String,
    val courseName: String,
    val teacherName: String
)

@Serializable
data class PublishSession(
    val sessionId: String,
    val name: String,
    val topic: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null
)

/** Only what readers see: no ASR job ids, timings, model names or device ids. */
@Serializable
data class PublishSegment(
    val startTime: Long,
    val endTime: Long,
    val text: String,
    val correctedText: String? = null,
    val marked: Boolean = false
)

@Serializable
data class PublishNote(
    val noteId: String,
    val title: String,
    val content: String,
    val format: String = "markdown"
)

@Serializable
data class PublishSubmission(
    val clientSubmissionId: String,
    val course: PublishCourse,
    val session: PublishSession,
    val segments: List<PublishSegment>,
    val notes: List<PublishNote>
)

@Serializable
data class PublishSubmissionResult(
    val lessonId: String,
    val revisionId: Int? = null,
    val revision: Int? = null,
    /** "pending" (queued for review) or "unchanged" (identical to what readers already see). */
    val status: String
)

@Serializable
data class PublishRevisionStatus(
    val revisionId: Int,
    val revision: Int,
    /** pending, approved, rejected or superseded. */
    val status: String,
    val submittedAt: Long,
    val reviewedAt: Long? = null,
    val reviewNote: String? = null
)

@Serializable
data class PublishLessonStatus(
    val lessonId: String? = null,
    /** active or taken_down; null until the first submission. */
    val lessonStatus: String? = null,
    val published: Boolean = false,
    val withdrawRequested: Boolean = false,
    val revisions: List<PublishRevisionStatus> = emptyList()
)

@Serializable
data class PublishUploader(val displayName: String, val status: String)

@Serializable
internal data class PublishWithdrawRequest(val reason: String)
