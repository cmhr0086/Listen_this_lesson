package com.cmhr.listen.ui

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingState
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.recording.OfflineRecognitionState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Why capture cannot start on this record right now, or null when it can. */
internal fun captureBlockedReason(listening: ListeningUiState, recordId: Long, processing: OfflineRecognitionState): String? = when {
    listening.isListening && listening.activeRecordId != recordId ->
        "「${listening.currentRecordName ?: "另一节课"}」正在录制，停止后才能在这里开始。"
    processing.isProcessing -> "正在识别录音，暂停或完成后才能开始录制。"
    else -> null
}

/**
 * The single place to start, watch and stop capture for a record. Replaces the old FAB +
 * mode dialog + status card, so the user sees the choice and its consequences in one spot.
 */
@Composable
internal fun CapturePanel(
    recordId: Long,
    listening: ListeningUiState,
    processing: OfflineRecognitionState,
    start: (CaptureMode) -> Unit,
    stop: () -> Unit
) {
    val activeHere = listening.isListening && listening.activeRecordId == recordId
    if (activeHere) ActiveCapturePanel(listening, stop)
    else IdleCapturePanel(captureBlockedReason(listening, recordId, processing), listening.error, start)
}

@Composable
private fun IdleCapturePanel(blockedReason: String?, error: String?, start: (CaptureMode) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("capture-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptureModeButton(
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("start-realtime"),
                icon = Icons.Outlined.Mic,
                title = "实时转写",
                subtitle = "边录边出文字\n需连接识别服务",
                enabled = blockedReason == null,
                emphasized = true
            ) { start(CaptureMode.REALTIME_ASR) }
            CaptureModeButton(
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("start-record-only"),
                icon = Icons.Outlined.FiberManualRecord,
                title = "仅录音",
                subtitle = "先把课录下来\n稍后再识别",
                enabled = blockedReason == null,
                emphasized = false
            ) { start(CaptureMode.RECORD_ONLY) }
        }
        blockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun CaptureModeButton(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    emphasized: Boolean,
    click: () -> Unit
) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val faded = modifier.alpha(if (enabled) 1f else 0.45f)
    if (emphasized) Card(
        onClick = click,
        enabled = enabled,
        modifier = faded,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) { content() }
    else OutlinedCard(onClick = click, enabled = enabled, modifier = faded) { content() }
}

@Composable
private fun ActiveCapturePanel(listening: ListeningUiState, stop: () -> Unit) {
    val recordOnly = listening.captureMode == CaptureMode.RECORD_ONLY
    Card(
        Modifier.fillMaxWidth().testTag("capture-panel"),
        colors = CardDefaults.cardColors(
            containerColor = if (recordOnly) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(if (recordOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(if (recordOnly) "仅录音中" else "实时转写中", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ElapsedClock(listening.listeningStartedAtElapsedRealtimeMs)
            }
            if (recordOnly) {
                Text("已安全保存 ${formatClockDuration(listening.recordOnlySavedMs)}", style = MaterialTheme.typography.bodyMedium)
                Text("App 被关闭或意外退出时，已保存的录音不会丢失。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val status = when {
                    listening.isSpeechDetected -> "正在收音"
                    listening.isRecognizing -> "正在识别"
                    else -> "等待语音"
                }
                Text(
                    if (listening.pendingQueueCount > 0) "$status · ${listening.pendingQueueCount} 段排队识别" else status,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            listening.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = stop,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("stop-capture"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
            ) {
                Icon(Icons.Outlined.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (recordOnly) "停止录音" else "停止转写")
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "capture-dot")
    val alpha by transition.animateFloat(1f, 0.25f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "capture-dot-alpha")
    Box(Modifier.size(12.dp).alpha(alpha).background(color, CircleShape))
}

@Composable
private fun ElapsedClock(startedAt: Long?) {
    var elapsedMs by remember(startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            delay(1_000)
        }
    }
    Text(
        formatClockDuration(elapsedMs, alwaysHours = true),
        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Medium
    )
}

internal data class RecordingStatus(val label: String, val tone: StatusTone, val note: String? = null, val showProgress: Boolean = false)
internal enum class StatusTone { ACTIVE, READY, DONE, PROBLEM }

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

@Composable
internal fun RecordingItem(
    recording: RecordingEntity,
    number: Int,
    processing: OfflineRecognitionState,
    recognitionAllowed: Boolean,
    startRecognition: (String) -> Unit,
    pauseRecognition: () -> Unit,
    delete: (String) -> Unit
) {
    val processingHere = processing.activeRecordingId == recording.recordingId
    val processed = if (processingHere) processing.processedFrames else recording.processedFrames
    val progress = (processed.toFloat() / recording.totalFrames.coerceAtLeast(1)).coerceIn(0f, 1f)
    val status = recordingStatus(recording, processingHere, (progress * 100).toInt())
    val state = recording.recordingState
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    OutlinedCard(Modifier.fillMaxWidth().testTag("recording-${recording.recordingId}")) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("录音 $number", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatClockTime(recording.startedAt)} 开始 · 时长 ${formatClockDuration(recording.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(status)
                if (state.canDelete && !processingHere) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "录音操作") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("删除录音") }, onClick = { menuExpanded = false; confirmDelete = true })
                        }
                    }
                } else Spacer(Modifier.width(12.dp))
            }
            if (status.showProgress) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            status.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.tone == StatusTone.PROBLEM) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            processing.error?.takeIf { processingHere }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            when {
                processingHere -> OutlinedButton(onClick = pauseRecognition, modifier = Modifier.testTag("pause-recognition")) { Text("暂停识别") }
                state.canStartRecognition -> FilledTonalButton(
                    onClick = { startRecognition(recording.recordingId) },
                    enabled = recognitionAllowed,
                    modifier = Modifier.testTag("start-recognition")
                ) {
                    Text(
                        when (state) {
                            RecordingState.PAUSED -> "继续识别"
                            RecordingState.FAILED -> "重试识别"
                            else -> "开始识别"
                        }
                    )
                }
                else -> Unit
            }
        }
    }
    if (confirmDelete) TimedDeleteDialog(
        title = "删除录音",
        message = "将删除录音 $number 的音频文件（${formatClockDuration(recording.durationMs)}）。已经识别出的文字会保留。",
        confirm = { delete(recording.recordingId); confirmDelete = false },
        dismiss = { confirmDelete = false }
    )
}

@Composable
private fun StatusChip(status: RecordingStatus) {
    val (container, content) = when (status.tone) {
        StatusTone.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.READY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.DONE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.PROBLEM -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(status.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

internal fun formatClockDuration(milliseconds: Long, alwaysHours: Boolean = false): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (alwaysHours || seconds >= 3600) String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

private fun formatClockTime(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(epochMs))

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

