package com.cmhr.listen.ui

import com.cmhr.listen.data.course.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
/** "第 8 节 · 函数的极限", falling back to the record's own name when it has no number yet. */
internal fun classTitle(courseName: String?, classNumber: Int, topic: String?, fallback: String): String {
    val number = classNumber.takeIf { it > 0 }?.let { "第 $it 节" }
    return listOfNotNull(courseName, number).joinToString(" · ").ifEmpty { fallback } +
        (topic?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
}

/** "大学物理" → "物", "大学英语" → "英": skip prefixes many course names share so badges differ. */
internal fun courseInitial(name: String): String {
    val trimmed = name.trim()
    val prefix = listOf("大学", "中国", "基础", "现代", "高级").firstOrNull { trimmed.startsWith(it) && trimmed.length > it.length }
    return (if (prefix != null) trimmed.removePrefix(prefix) else trimmed).take(1).ifEmpty { "课" }
}

/** 今天 / 本周 / 更早, so a long history still opens on this week's classes. */
internal fun recentGroupLabel(startedAt: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val weekStart = today.with(java.time.DayOfWeek.MONDAY)
    return when {
        day == today -> "今天"
        !day.isBefore(weekStart) && day.isBefore(today) -> "本周"
        else -> "更早"
    }
}

/** "函数的极限 · 周二 10:05–11:40 · 笔记 · 2 处重点"; today's classes leave out the day. */
internal fun sessionSubtitle(summary: SessionSummary, includeDay: Boolean, includeTopic: Boolean = false, now: Long = System.currentTimeMillis()): String {
    val session = summary.session
    val time = SimpleDateFormat("HH:mm", Locale.CHINA)
    val day = when {
        !includeDay -> ""
        recentGroupLabel(session.startedAt, now) == "今天" -> ""
        recentGroupLabel(session.startedAt, now) == "本周" -> SimpleDateFormat("EEE ", Locale.CHINA).format(Date(session.startedAt))
        else -> SimpleDateFormat("M月d日 ", Locale.CHINA).format(Date(session.startedAt))
    }
    val span = "$day${time.format(Date(session.startedAt))}" + (session.endedAt?.let { "–${time.format(Date(it))}" } ?: "")
    return listOfNotNull(
        session.topic?.takeIf { includeTopic && it.isNotBlank() },
        span,
        when {
            summary.noteCount > 0 -> "笔记"
            summary.segmentCount > 0 -> "${summary.segmentCount} 段文字"
            else -> null
        },
        summary.markCount.takeIf { it > 0 }?.let { "$it 处重点" }
    ).joinToString(" · ")
}

internal fun formatSpanMinutes(durationMs: Long): String? {
    val minutes = (durationMs / 60_000).toInt()
    return when {
        minutes <= 0 -> null
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}

/** "10月8日 周四 10:20–12:00": weekday matters more than seconds for weekly classes. */
internal fun formatSessionWhen(startedAt: Long, endedAt: Long?): String {
    val start = "${formatDayWithWeekday(startedAt)} ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(startedAt))}"
    val sameDay = endedAt != null && SimpleDateFormat("yyyyMMdd", Locale.CHINA).let { it.format(Date(endedAt)) == it.format(Date(startedAt)) }
    return if (sameDay) "$start–${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(endedAt!!))}" else start
}

internal fun formatDayWithWeekday(epochMs: Long): String = SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date(epochMs))

/** 本周 / 上周 / 「10月」: weekly classes read best grouped by week, older ones by month. */
internal fun weekGroupLabel(startedAt: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
    val weekStart = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(java.time.DayOfWeek.MONDAY)
    return when {
        !day.isBefore(weekStart) -> "本周"
        !day.isBefore(weekStart.minusWeeks(1)) -> "上周"
        else -> "${day.monthValue}月"
    }
}

/** A window of [text] around the first match, so long notes still show why they matched. */
internal fun snippetAround(text: String, query: String, radius: Int = 40): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    val at = if (query.isBlank()) -1 else flat.indexOf(query, ignoreCase = true)
    if (at < 0) return flat.take(radius * 2)
    val start = (at - radius).coerceAtLeast(0)
    val end = (at + query.length + radius).coerceAtMost(flat.length)
    return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
}
