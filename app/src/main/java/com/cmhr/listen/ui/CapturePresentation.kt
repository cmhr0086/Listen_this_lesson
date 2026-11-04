package com.cmhr.listen.ui

import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.recording.OfflineRecognitionState
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
/** Why capture cannot start on this record right now, or null when it can. */
internal fun captureBlockedReason(listening: ListeningUiState, recordId: Long, processing: OfflineRecognitionState): String? = when {
    listening.isListening && listening.activeRecordId != recordId ->
        "「${listening.currentRecordName ?: "另一节课"}」正在录制，结束后才能在这里开始。"
    processing.isProcessing -> "正在识别录音，暂停或完成后才能开始录制。"
    else -> null
}

internal data class RecordingStatus(val label: String, val tone: StatusTone, val note: String? = null, val showProgress: Boolean = false)
enum class StatusTone { ACTIVE, READY, DONE, PROBLEM }

internal fun recordingStatus(recording: RecordingEntity, processingHere: Boolean, progressPercent: Int): RecordingStatus =
    when (if (processingHere) RecordingState.PROCESSING else recording.recordingState) {
        RecordingState.RECORDING -> RecordingStatus("录音中", StatusTone.ACTIVE)
        RecordingState.RECORDED -> RecordingStatus("待识别", StatusTone.READY)
        RecordingState.INTERRUPTED -> RecordingStatus("待识别", StatusTone.READY, "录音意外中断，已保留到 ${formatClockDuration(recording.durationMs)}。")
        RecordingState.PROCESSING -> RecordingStatus("识别中 $progressPercent%", StatusTone.ACTIVE, showProgress = true)
        RecordingState.PAUSED -> RecordingStatus("已暂停 $progressPercent%", StatusTone.READY, "已识别的文字已保存，继续会从中断处接着识别。", showProgress = true)
        RecordingState.COMPLETED -> RecordingStatus("已识别", StatusTone.DONE)
        RecordingState.FAILED -> RecordingStatus("识别出错", StatusTone.PROBLEM, recording.errorMessage, showProgress = recording.processedFrames > 0)
    }

internal fun formatClockDuration(milliseconds: Long, alwaysHours: Boolean = false): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (alwaysHours || seconds >= 3600) String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

internal fun formatClockTime(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(epochMs))

/** "10月8日 21:36–21:52" for a same-day record, full dates otherwise; open records say 进行中. */
internal fun formatRecordSpan(startedAt: Long, endedAt: Long?): String {
    val day = SimpleDateFormat("M月d日", Locale.CHINA)
    val start = "${day.format(Date(startedAt))} ${formatClockTime(startedAt)}"
    return when {
        endedAt == null -> "$start 开始"
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).let { it.format(Date(endedAt)) == it.format(Date(startedAt)) } ->
            "$start–${formatClockTime(endedAt)}"
        else -> "$start – ${day.format(Date(endedAt))} ${formatClockTime(endedAt)}"
    }
}

/** Total recorded audio of a record, for the section heading. */
internal fun List<RecordingEntity>.totalDurationMs(): Long = sumOf { it.durationMs }
