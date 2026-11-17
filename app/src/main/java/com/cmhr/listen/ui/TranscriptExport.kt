package com.cmhr.listen.ui

import com.cmhr.listen.AiViewModel
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SessionSummary
import com.cmhr.listen.data.course.TranscriptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What to export as 原文: every class of a course in one file, or one class. */
sealed interface TranscriptExportTarget {
    data class Course(val courseId: Long) : TranscriptExportTarget
    data class Class(val recordId: Long) : TranscriptExportTarget
}

/** A TXT ready for the system "save as" picker. [content] is null when there is no 原文 yet. */
data class TranscriptExport(val fileName: String, val content: String?)

internal suspend fun buildTranscriptExport(database: ListenDatabase, target: TranscriptExportTarget): TranscriptExport =
    withContext(Dispatchers.IO) {
        when (target) {
            is TranscriptExportTarget.Class -> {
                val summary = database.recordDao().summary(target.recordId).first()
                    ?: return@withContext TranscriptExport("原文.txt", null)
                val course = database.courseDao().course(summary.session.courseId).first()
                val segments = database.transcriptDao().segments(summary.session.id).first()
                TranscriptExport(
                    safeFileName(summary.session.name, "原文") + ".txt",
                    if (segments.isEmpty() || course == null) null else buildTxt(course, summary.session, segments)
                )
            }
            is TranscriptExportTarget.Course -> {
                val course = database.courseDao().course(target.courseId).first()
                    ?: return@withContext TranscriptExport("原文.txt", null)
                val classes = database.recordDao().summariesForCourse(course.id).first()
                    .sortedWith(compareBy({ it.session.startedAt }, { it.session.id }))
                    .map { it to database.transcriptDao().segments(it.session.id).first() }
                TranscriptExport(safeFileName("${course.name} 原文", "原文") + ".txt", buildCourseTxt(course, classes))
            }
        }
    }

/** Every class of a course, oldest first; classes without 原文 are left out. Null when none has any. */
internal fun buildCourseTxt(course: CourseEntity, classes: List<Pair<SessionSummary, List<TranscriptEntity>>>): String? {
    val withText = classes.filter { (_, segments) -> segments.isNotEmpty() }
    if (withText.isEmpty()) return null
    return buildString {
        appendLine("课程：${course.name}")
        appendLine("共 ${withText.size} 节")
        withText.forEach { (summary, segments) ->
            val record = summary.session
            appendLine()
            appendLine("==== ${classTitle(null, summary.classNumber, record.topic, record.name)} ====")
            if (summary.classNumber > 0) appendLine("记录：${record.name}")
            appendLine("开始时间：${formatDateTime(record.startedAt)}")
            appendLine("结束时间：${record.endedAt?.let(::formatDateTime) ?: "进行中"}")
            appendLine()
            AiViewModel.orderTranscriptSegments(segments).forEach {
                appendLine(formatDateTime(it.startTime))
                appendLine(it.effectiveText)
                appendLine()
            }
        }
    }
}

private fun safeFileName(name: String, fallback: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { fallback }
