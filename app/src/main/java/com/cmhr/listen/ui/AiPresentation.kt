package com.cmhr.listen.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
internal fun dayGroupLabel(timestamp: Long, today: LocalDate = LocalDate.now()): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (val days = ChronoUnit.DAYS.between(date, today)) {
        0L -> "今天"
        1L -> "昨天"
        in 2L..6L -> "${days}天前"
        else -> date.toString()
    }
}

/** First meaningful line of an AI reply for list previews: Markdown markers and blank lines dropped. */
internal fun aiPreviewLine(text: String): String? = text.lineSequence()
    .map { it.trim().trimStart('#', '>', '-', '*', '|', ' ').replace("**", "").replace("`", "").trim() }
    .filter { it.isNotEmpty() && !it.all { ch -> ch == '-' || ch == '|' || ch == ':' || ch == ' ' } }
    .take(2)
    .joinToString(" ")
    .takeIf { it.isNotBlank() }
