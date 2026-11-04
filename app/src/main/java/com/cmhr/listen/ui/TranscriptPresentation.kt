package com.cmhr.listen.ui

import com.cmhr.listen.data.course.TranscriptEntity

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
/** Consecutive transcript segments shown as one paragraph under a single time label. */
internal data class TranscriptGroup(val startTime: Long, val endTime: Long, val segments: List<TranscriptEntity>)

/**
 * Splits chronologically ordered segments into paragraphs: a new paragraph starts after a pause
 * longer than [pauseMs] or once a paragraph spans [maxSpanMs]. Display only; segments, their
 * order and selection identity are untouched.
 */
internal fun groupTranscript(
    ordered: List<TranscriptEntity>,
    pauseMs: Long = PARAGRAPH_PAUSE_MS,
    maxSpanMs: Long = PARAGRAPH_MAX_SPAN_MS
): List<TranscriptGroup> {
    val groups = mutableListOf<TranscriptGroup>()
    var current = mutableListOf<TranscriptEntity>()
    fun flush() {
        if (current.isNotEmpty()) groups += TranscriptGroup(current.first().startTime, current.maxOf { it.endTime }, current)
        current = mutableListOf()
    }
    for (segment in ordered) {
        val previous = current.lastOrNull()
        val breaks = previous != null && (
            segment.startTime - previous.endTime > pauseMs ||
                segment.endTime - current.first().startTime > maxSpanMs
            )
        if (breaks) flush()
        current += segment
    }
    flush()
    return groups
}

internal const val PARAGRAPH_PAUSE_MS = 30_000L
internal const val PARAGRAPH_MAX_SPAN_MS = 3 * 60_000L

internal enum class LinePosition { ONLY, FIRST, MIDDLE, LAST }

internal fun linePosition(index: Int, size: Int): LinePosition = when {
    size == 1 -> LinePosition.ONLY
    index == 0 -> LinePosition.FIRST
    index == size - 1 -> LinePosition.LAST
    else -> LinePosition.MIDDLE
}

internal enum class GroupSelection { NONE, PARTIAL, ALL }

internal fun groupSelection(group: TranscriptGroup, selected: Set<Long>): GroupSelection {
    val count = group.segments.count { it.id in selected }
    return when (count) {
        0 -> GroupSelection.NONE
        group.segments.size -> GroupSelection.ALL
        else -> GroupSelection.PARTIAL
    }
}

/** How much of the class a new question carries along. */
internal enum class AskScope(val label: String) { RECENT("最近 10 分钟"), WHOLE("整节课"), NONE("不带原文") }

internal const val RECENT_SCOPE_MS = 10 * 60_000L

/** Segment ids a question in [scope] is grounded on; the newest are kept if a class is long. */
internal fun scopeSegmentIds(scope: AskScope, segments: List<TranscriptEntity>): Set<Long> = when (scope) {
    AskScope.NONE -> emptySet()
    AskScope.WHOLE -> segments.mapTo(linkedSetOf()) { it.id }
    AskScope.RECENT -> {
        val newest = segments.maxOfOrNull { it.endTime } ?: 0L
        segments.filter { it.endTime >= newest - RECENT_SCOPE_MS }.mapTo(linkedSetOf()) { it.id }
    }
}
