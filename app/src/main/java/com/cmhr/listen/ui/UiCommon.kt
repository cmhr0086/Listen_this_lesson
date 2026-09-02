package com.cmhr.listen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NameDialog(
    title: String,
    value: String,
    update: (String) -> Unit,
    confirm: () -> Unit,
    dismiss: () -> Unit,
    allowBlank: Boolean = false
) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text(title) },
    text = {
        OutlinedTextField(
            value = value,
            onValueChange = update,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (allowBlank) "名称（留空自动生成）" else "名称") },
            singleLine = true
        )
    },
    confirmButton = { Button(onClick = confirm, enabled = allowBlank || value.isNotBlank()) { Text("确定") } },
    dismissButton = { OutlinedButton(onClick = dismiss) { Text("取消") } }
)

@Composable
fun ExpandHeader(title: String, expanded: Boolean, click: () -> Unit) =
    Card(Modifier.fillMaxWidth().clickable(onClick = click)) {
        Text("$title ${if (expanded) "▲" else "▼"}", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    }

@Composable
fun VadDebugCard(state: ListeningUiState) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("VAD 语音概率：${formatFloat(state.vadProbability)}")
        Text("当前实际 VAD 阈值：${formatFloat(state.effectiveVadConfig.threshold)}")
        Text("当前语音片段：${if (state.isSpeechDetected) "活动中" else "未活动"}")
        Text("当前连续静音时间：${state.silenceDurationMs} ms")
        Text("片段开始原因：${state.segmentStartReason ?: "无"}")
        Text("片段结束原因：${state.segmentEndReason ?: "无"}")
        Text("识别队列数量：${state.pendingQueueCount}")
        Text("已丢弃片段：${state.droppedSegments}")
        Text("已丢弃过短片段：${state.discardedShortSegments}")
        Text("AudioRecord 读取错误：${state.audioReadErrors}")
    }
}

@Composable
fun RuntimeTranscriptCard(segment: TranscriptSegment) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("#${segment.id}   ${formatTime(segment.audioStartTime)}", style = MaterialTheme.typography.titleMedium)
        Text("音频 ${formatDuration(segment.audioDurationMs)} · ASR ${segment.recognitionDurationMs?.let(::formatDuration) ?: "—"}")
        Text(segment.text ?: segment.error ?: "等待识别…", style = MaterialTheme.typography.bodyLarge)
    }
}

fun formatTime(value: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
fun formatDateTime(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
fun formatDuration(value: Long): String = String.format(Locale.US, "%.1fs", value / 1000.0)
fun formatFloat(value: Float): String = String.format(Locale.US, "%.2f", value)
