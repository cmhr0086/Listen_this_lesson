package com.cmhr.listen.data.publish

import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.course.RecordNameGenerator
import com.cmhr.listen.data.course.SegmentEntity
import com.cmhr.listen.data.course.SessionEntity
import java.security.MessageDigest
import java.util.Calendar
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Everything this device remembers about publishing one local course. School is global (set once
 * in settings) and the term follows each class's date, so a course only needs its teacher.
 */
data class PublishCourseState(
    /** Name readers see; blank means the local course name. */
    val publicName: String = "",
    val teacherName: String = "",
    /** Term → public course id, filled in by the first upload of a class in that term. */
    val remoteCourses: Map<String, String> = emptyMap(),
    /** Term → last known review status, so the course page can show it without a request. */
    val remoteStatus: Map<String, String> = emptyMap(),
    /** Local record id → fingerprint of what was last uploaded, to tell changed classes apart. */
    val synced: Map<Long, String> = emptyMap(),
    /** Classes the user chose to keep off the public reader. */
    val excluded: Set<Long> = emptySet(),
    val lastSyncAt: Long = 0
)

/** An AI result that can go out as a public note. Corrections are applied to the text instead. */
data class PublishNoteCandidate(val resultId: Long, val title: String, val createdAt: Long, val content: String)

private val NOTE_TITLES = mapOf(
    AiActionType.ORGANIZE_NOTES.name to "课堂笔记",
    AiActionType.SUMMARY.name to "总结",
    AiActionType.EXTRACT_QUESTIONS.name to "老师的问题",
    AiActionType.QUICK_ANSWER.name to "快速回答"
)

internal fun noteCandidates(results: List<AiResultEntity>): List<PublishNoteCandidate> = results
    .filter { it.status == AiRequestStatus.SUCCESS.name && it.actionType in NOTE_TITLES && !it.output.isNullOrBlank() }
    .sortedByDescending { it.finishedAt ?: it.createdAt }
    .map { PublishNoteCandidate(it.id, NOTE_TITLES.getValue(it.actionType), it.finishedAt ?: it.createdAt, it.output!!.trim()) }

/** The newest whole-class notes go out with a class; other AI results stay private. */
internal fun publicNotes(candidates: List<PublishNoteCandidate>): List<PublishNoteCandidate> =
    listOfNotNull(candidates.firstOrNull { it.title == NOTE_TITLES[AiActionType.ORGANIZE_NOTES.name] })

/**
 * Readers already see the course, so an auto-generated "高数-10-14" becomes "第 3 节"; a name the
 * user typed is kept as is.
 */
internal fun defaultPublishTitle(recordName: String, courseName: String?, startedAt: Long, classNumber: Int): String {
    val generated = courseName != null && recordName == RecordNameGenerator.defaultName(courseName, startedAt)
    return if (generated && classNumber > 0) "第 $classNumber 节" else recordName
}

internal fun buildSubmission(
    session: SessionEntity,
    title: String,
    course: PublishCourse,
    segments: List<SegmentEntity>,
    notes: List<PublishNoteCandidate>,
    submissionId: String = UUID.randomUUID().toString()
): PublishSubmission = PublishSubmission(
    clientSubmissionId = submissionId,
    course = PublishCourse(course.schoolName.trim(), course.term.trim(), course.courseName.trim(), course.teacherName.trim()),
    session = PublishSession(
        sessionId = session.sessionId,
        name = title.trim(),
        topic = session.topic?.trim()?.takeIf { it.isNotEmpty() },
        startedAt = session.startedAt,
        endedAt = session.endedAt
    ),
    segments = segments
        .filter { !it.deleted && it.effectiveText.isNotBlank() }
        .map { PublishSegment(it.startTime, maxOf(it.startTime, it.endTime), it.text, it.correctedText?.takeIf(String::isNotBlank), it.marked) },
    // sessionId is a UUID, so this stays unique across devices without a new table.
    notes = notes.map { PublishNote("${session.sessionId}:${it.resultId}", it.title, it.content) }
)

private val fingerprintJson = Json { encodeDefaults = true }

/** Same content → same fingerprint; the per-upload submission id is left out on purpose. */
internal fun fingerprint(submission: PublishSubmission): String {
    val bytes = fingerprintJson.encodeToString(PublishSubmission.serializer(), submission.copy(clientSubmissionId = "")).toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * The term a class belongs to, in the "2026-2027 第一学期" form most Chinese universities use: the
 * first term runs from August to January, the second from February to July.
 */
internal fun termOf(at: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = at }
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    return when {
        month >= 8 -> "$year-${year + 1} 第一学期"
        month == 1 -> "${year - 1}-$year 第一学期"
        else -> "${year - 1}-$year 第二学期"
    }
}
