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
    /** "published" (this copy is now the public one) or "unchanged" (identical to it already). */
    val status: String,
    val courseId: String,
    /** Readers see the lesson only while its course is "approved". */
    val courseStatus: String,
    val courseReviewNote: String? = null
)

/** Review is per course: pending, approved, rejected or taken_down. */
@Serializable
data class PublishCourseStatus(
    val courseId: String,
    val schoolName: String,
    val term: String,
    val courseName: String,
    val teacherName: String,
    val status: String,
    val reviewNote: String? = null,
    val lessonCount: Int = 0
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

/** One of this uploader's lessons in a public course, as the server holds it. */
@Serializable
data class PublishCourseLesson(
    val lessonId: String,
    val sessionId: String,
    /** active or taken_down. */
    val status: String,
    val revision: Int,
    val updatedAt: Long,
    val withdrawRequested: Boolean = false
)

@Serializable
internal data class PublishCourseLessons(val lessons: List<PublishCourseLesson>)

@Serializable
data class PublishUploader(val displayName: String, val status: String)

@Serializable
internal data class PublishWithdrawRequest(val reason: String)
