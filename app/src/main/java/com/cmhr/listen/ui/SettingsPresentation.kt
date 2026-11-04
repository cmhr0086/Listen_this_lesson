package com.cmhr.listen.ui

import com.cmhr.listen.audio.VadConfig
import com.cmhr.listen.audio.VadPreset
import com.cmhr.listen.data.stt.AsrClockBasis
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
/** 「今天 11:42」, 「刚刚」, 「10月8日 09:30」. */
internal fun checkTime(at: Long, now: Long = System.currentTimeMillis()): String {
    if (now - at in 0 until 60_000) return "刚刚"
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = day.get(Calendar.YEAR) == today.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(at))
    return if (sameDay) "今天 $time" else SimpleDateFormat("M月d日 ", Locale.CHINA).format(Date(at)) + time
}

internal fun asrTermCount(prompt: String) = prompt.split(Regex("[，,、；;\\s]+")).count { it.isNotBlank() }

internal fun formatMs(value: Float): String = if (value >= 10_000f && value % 1000f == 0f) "${(value / 1000f).roundToInt()} s" else "${value.roundToInt()} ms"
internal fun formatFraction(value: Float): String = String.format(Locale.US, "%.2f", value)

internal fun VadConfig.differences(other: VadConfig): Int = listOf(
    threshold != other.threshold, startConfirmMs != other.startConfirmMs, endSilenceMs != other.endSilenceMs,
    preRollMs != other.preRollMs, postRollMs != other.postRollMs, minSegmentMs != other.minSegmentMs,
    softLimitMs != other.softLimitMs, hardLimitMs != other.hardLimitMs, overlapMs != other.overlapMs
).count { it }

/** The preset a custom config was most likely tuned from: the one it differs from least. */
internal fun nearestVadPreset(config: VadConfig): VadPreset =
    VadPreset.entries.minBy { config.differences(it.config.validated()) }

internal fun calculateElapsedMs(
    startElapsedRealtimeMs: Long?,
    fallbackStartWallTimeMs: Long?,
    clockBasis: String,
    elapsedRealtime: () -> Long,
    wallTime: () -> Long
): Long? {
    return when (clockBasis) {
        AsrClockBasis.ELAPSED_REALTIME.name -> {
            val start = startElapsedRealtimeMs?.takeIf { it > 0L } ?: return null
            val end = elapsedRealtime().takeIf { it >= start } ?: return null
            end - start
        }
        AsrClockBasis.LEGACY_WALL_FALLBACK.name -> {
            val start = fallbackStartWallTimeMs?.takeIf { it > 0L } ?: return null
            val end = wallTime().takeIf { it >= start } ?: return null
            end - start
        }
        else -> null
    }
}

internal fun nextSmoothTickDelay(elapsedMs: Long?): Long {
    if (elapsedMs == null) return SMOOTH_TIMER_TICK_MS
    val remainder = elapsedMs % SMOOTH_TIMER_TICK_MS
    return (SMOOTH_TIMER_TICK_MS - remainder).coerceIn(16L, SMOOTH_TIMER_TICK_MS)
}

internal fun formatSmoothMs(value: Long): String = String.format(Locale.US, "%.1fs", value / 1_000.0)

private const val SMOOTH_TIMER_TICK_MS = 100L
