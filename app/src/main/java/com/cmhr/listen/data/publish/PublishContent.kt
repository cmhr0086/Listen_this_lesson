package com.cmhr.listen.data.publish

import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.course.RecordNameGenerator
import com.cmhr.listen.data.course.SegmentEntity
import com.cmhr.listen.data.course.SessionEntity
import java.util.Calendar
import java.util.UUID

/** School, term and teacher a local course is published under; remembered per course. */
data class PublishCourseInfo(
    val schoolName: String = "",
    val term: String = "",
    val courseName: String = "",
    val teacherName: String = ""
) {
    val complete: Boolean get() = listOf(schoolName, term, courseName, teacherName).all { it.isNotBlank() }
}

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

/** The newest whole-class notes are what most readers want; everything else is opt-in. */
internal fun defaultNoteSelection(candidates: List<PublishNoteCandidate>): Set<Long> =
    candidates.firstOrNull { it.title == NOTE_TITLES[AiActionType.ORGANIZE_NOTES.name] }?.let { setOf(it.resultId) } ?: emptySet()

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
    course: PublishCourseInfo,
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

/**
 * Terms near [now] in the "2026-2027 第一学期" form most Chinese universities use: the first term
 * runs from August to January, the second from February to July.
 */
internal fun termSuggestions(now: Long): List<String> {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    val (startYear, first) = when {
        month >= 8 -> year to true
        month == 1 -> year - 1 to true
        else -> year - 1 to false
    }
    fun term(start: Int, isFirst: Boolean) = "$start-${start + 1} ${if (isFirst) "第一学期" else "第二学期"}"
    val current = term(startYear, first)
    val previous = if (first) term(startYear - 1, false) else term(startYear, true)
    val next = if (first) term(startYear, false) else term(startYear + 1, true)
    return listOf(current, previous, next)
}
