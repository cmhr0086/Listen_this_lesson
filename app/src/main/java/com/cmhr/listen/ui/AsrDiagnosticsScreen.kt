package com.cmhr.listen.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.VadDiagnosticsUiState
import com.cmhr.listen.data.stt.ACTIVE_ASR_STATES
import com.cmhr.listen.data.stt.AsrClockBasis
import com.cmhr.listen.data.stt.AsrDiagnosticStateCounts
import com.cmhr.listen.data.stt.AsrFailureStage
import com.cmhr.listen.data.stt.AsrHealthSnapshot
import com.cmhr.listen.data.stt.AsrLifecycleState
import com.cmhr.listen.data.stt.AsrNetworkEventEntity
import com.cmhr.listen.data.stt.AsrRuntimeSummary
import com.cmhr.listen.data.stt.AsrSegmentDiagnosticEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DIAGNOSTICS_PREVIEW_LIMIT = 15
private const val SMOOTH_TIMER_TICK_MS = 100L

/** The record-scoped overview. [diagnostics] is supplied by the latest-15 Room query. */
@Composable
fun AsrDiagnosticsScreen(
    state: ListeningUiState,
    vadState: VadDiagnosticsUiState,
    currentRecordId: Long?,
    currentRecordName: String?,
    diagnostics: List<AsrSegmentDiagnosticEntity>,
    totalCount: Int,
    events: (String) -> Flow<List<AsrNetworkEventEntity>>,
    refreshHealth: () -> Unit,
    confirmRetryUnknown: (String) -> Unit,
    openHistory: (Long) -> Unit,
    activeDiagnostics: List<AsrSegmentDiagnosticEntity>,
    recentCounts: AsrDiagnosticStateCounts,
    runtimeSummary: AsrRuntimeSummary? = null,
    health: AsrHealthSnapshot? = state.asrHealth,
    healthRefreshing: Boolean = false,
    healthError: String? = null
) {
    val visibleDiagnostics = if (currentRecordId == null) {
        emptyList()
    } else {
        diagnostics.asSequence()
            .filter { it.recordId == currentRecordId }
            .take(DIAGNOSTICS_PREVIEW_LIMIT)
            .toList()
    }
    val visibleActiveDiagnostics = currentRecordId?.let { recordId ->
        activeDiagnostics.filter { it.recordId == recordId }
    }.orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("asr-diagnostics-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("record") {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(currentRecordName ?: "请先选择课堂记录", style = MaterialTheme.typography.titleLarge)
                if (currentRecordId != null) Text(
                    "本记录共 $totalCount 个识别片段",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item("capture-vad") { CaptureAndVadCard(state, vadState, recentCounts.discardedFillerCount) }
        item("summary") { QueueCard(visibleActiveDiagnostics, runtimeSummary, recentCounts) }
        item("server") { ServerCard(health, healthRefreshing, healthError, refreshHealth) }
        item("segments-heading") {
            DiagSectionTitle(if (totalCount > DIAGNOSTICS_PREVIEW_LIMIT) "最近 $DIAGNOSTICS_PREVIEW_LIMIT 个片段" else "片段")
        }
        if (visibleDiagnostics.isEmpty() && vadState.capturingSegmentId == null) {
            item("empty") {
                Text(
                    if (currentRecordId == null) "选择课堂记录后可查看诊断。" else "当前记录尚无 ASR 生命周期数据。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        items(visibleDiagnostics, key = { "diagnostic-${it.segmentId}" }) { diagnostic ->
            AsrDiagnosticCard(diagnostic, events, confirmRetryUnknown)
        }
        if (currentRecordId != null && totalCount > DIAGNOSTICS_PREVIEW_LIMIT) {
            item("more") {
                OutlinedButton(
                    onClick = { openHistory(currentRecordId) },
                    modifier = Modifier.fillMaxWidth().testTag("asr-more-button")
                ) {
                    Text("查看全部（还有 ${totalCount - DIAGNOSTICS_PREVIEW_LIMIT} 个）")
                }
            }
        }
    }
}

/** Full, record-scoped history used by the second-level diagnostics route. */
@Composable
fun AsrDiagnosticsHistoryScreen(
    recordName: String?,
    diagnostics: List<AsrSegmentDiagnosticEntity>,
    events: (String) -> Flow<List<AsrNetworkEventEntity>>,
    confirmRetryUnknown: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("asr-diagnostics-history-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item("record") {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text(recordName ?: "ASR 诊断历史", style = MaterialTheme.typography.titleLarge)
                Text("共 ${diagnostics.size} 个片段，最新的在前", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (diagnostics.isEmpty()) item("empty") { Text("当前记录尚无 ASR 生命周期数据。") }
        items(diagnostics, key = { "diagnostic-history-${it.segmentId}" }) { diagnostic ->
            AsrDiagnosticCard(diagnostic, events, confirmRetryUnknown)
        }
    }
}

@Composable
private fun DiagSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun DiagCard(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    GroupCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

/** A label on the left and a monospace value on the right; used for every key/value line. */
@Composable
private fun DiagRow(label: String, value: String, valueColor: Color = Color.Unspecified, indent: Boolean = false) {
    DiagRow(label, indent) { Text(value, style = DiagValueStyle, color = valueColor) }
}

@Composable
private fun DiagRow(label: String, indent: Boolean = false, value: @Composable () -> Unit) {
    // Sub-steps of the line above are indented so the breakdown reads as a tree.
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
private fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
private fun CaptureAndVadCard(state: ListeningUiState, vadState: VadDiagnosticsUiState, discardedFillers: Int) {
    var expanded by remember { mutableStateOf(false) }
    val listeningColor = if (state.isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    DiagCard(
        "采集与 VAD",
        Modifier.testTag("asr-capture-vad"),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(listeningColor)
                Text(if (state.isListening) "正在监听" else "未监听", style = MaterialTheme.typography.labelLarge, color = listeningColor)
            }
        }
    ) {
        VadMeter(vadState)
        DiagRow("当前片段") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(vadState.capturingSegmentId?.let(::shortSegmentId) ?: "无", style = DiagValueStyle)
                if (vadState.capturingSegmentId != null) SmoothElapsedText(
                    label = "",
                    startElapsedRealtimeMs = vadState.capturingStartedAtElapsedRealtimeMs,
                    fallbackStartWallTimeMs = vadState.capturingStartedAt,
                    active = true,
                    modifier = Modifier.testTag("asr-capturing-elapsed")
                )
            }
        }
        DiagRow("连续静音", "${vadState.silenceDurationMs} ms")
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text("更多采集信息", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            DiagRow("片段开始原因", vadState.segmentStartReason ?: "无")
            DiagRow("片段结束原因", vadState.segmentEndReason ?: "无")
            DiagRow("丢弃过短片段", "${vadState.discardedShortSegments}")
            DiagRow("丢弃语气词片段（24h）", "$discardedFillers")
            DiagRow("录音读取错误", "${vadState.audioReadErrors}", if (vadState.audioReadErrors > 0) MaterialTheme.colorScheme.error else Color.Unspecified)
            vadState.lastPromptDecision?.let {
                DiagRow("Prompt 模式", it.effectiveMode.displayName)
                DiagRow("最近片段携带 Prompt", if (it.included) "是" else "否")
                Text("Prompt 决策：${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Speech probability as a bar with the threshold marked, so "why did it not cut?" is visible. */
@Composable
private fun VadMeter(vadState: VadDiagnosticsUiState) {
    val probability = vadState.vadProbability.coerceIn(0f, 1f)
    val threshold = vadState.effectiveVadConfig.threshold.coerceIn(0f, 1f)
    val speech = vadState.isSpeechDetected
    val barColor = if (speech) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (speech) "检测到语音" else "静音",
                style = MaterialTheme.typography.titleSmall,
                color = if (speech) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "概率 ${formatFloat(vadState.vadProbability)} · 阈值 ${formatFloat(vadState.effectiveVadConfig.threshold)}",
                style = DiagValueStyle
            )
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp).testTag("asr-vad-meter")) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            if (probability > 0f) drawRoundRect(barColor, size = Size(size.width * probability, size.height), cornerRadius = radius)
            val x = size.width * threshold
            drawLine(marker, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun QueueCard(activeDiagnostics: List<AsrSegmentDiagnosticEntity>, runtimeSummary: AsrRuntimeSummary?, recentCounts: AsrDiagnosticStateCounts) {
    val processing = activeDiagnostics.firstOrNull {
        it.lifecycleState == AsrLifecycleState.SUBMITTING ||
            it.lifecycleState == AsrLifecycleState.QUEUED_SERVER ||
            it.lifecycleState == AsrLifecycleState.PROCESSING
    }
    DiagCard(
        "识别队列",
        trailing = {
            Text("本记录未完成 ${activeDiagnostics.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    ) {
        if (runtimeSummary == null) {
            Text("队列状态尚未就绪。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("待提交", runtimeSummary.queuedLocalCount, "queued", Modifier.weight(1f))
                MetricTile("提交中", runtimeSummary.submittingCount, "submitting", Modifier.weight(1f))
                MetricTile("服务端", runtimeSummary.serverInFlightCount, "server", Modifier.weight(1f))
                MetricTile("轮询", runtimeSummary.pollingCount, "polling", Modifier.weight(1f))
            }
            ConcurrencyBar(runtimeSummary.globalInFlightCount, runtimeSummary.inFlightCapacity)
            if (runtimeSummary.isBackpressured) {
                Text("并发槽已满，新的片段在本地持久队列等待。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (runtimeSummary.submissionUnknownCount > 0) {
                Text(
                    "${runtimeSummary.submissionUnknownCount} 个片段提交状态未知，在下方展开确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            DiagRow("本次运行", "完成 ${runtimeSummary.completedCount} · 失败 ${runtimeSummary.failedCount}")
        }
        DiagRow(
            "最近 24 小时（全部记录）",
            "成功 ${recentCounts.completedCount} · 失败 ${recentCounts.failedCount} · 丢弃 ${recentCounts.droppedCount}"
        )
        DiagRow("当前处理", processing?.let { shortSegmentId(it.segmentId) + " · " + stateName(it.lifecycleState) } ?: "无")
    }
}

@Composable
private fun MetricTile(label: String, value: Int, tag: String, modifier: Modifier) {
    val active = value > 0
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("asr-metric-$tag"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "$value",
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Global in-flight slots as discrete pips: full means new segments must wait locally. */
@Composable
private fun ConcurrencyBar(used: Int, capacity: Int) {
    val full = used >= capacity
    DiagRow("并发槽 $used / $capacity") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(capacity) { index ->
                Box(
                    Modifier.size(width = 18.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(
                        when {
                            index >= used -> MaterialTheme.colorScheme.outlineVariant
                            full -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun ServerCard(health: AsrHealthSnapshot?, refreshing: Boolean, error: String?, refresh: () -> Unit) {
    DiagCard(
        "服务端",
        trailing = {
            TextButton(
                onClick = refresh,
                enabled = !refreshing,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(32.dp).testTag("asr-health-refresh")
            ) {
                if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (refreshing) "正在刷新" else "刷新")
            }
        }
    ) {
        if (health == null) {
            Text("不会自动请求服务端；需要时点「刷新」获取一次快照。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DiagRow("模型", health.model + (health.dtype?.let { " · $it" } ?: ""))
            DiagRow("服务端队列", "排队 ${health.queuedJobs} · 处理 ${health.processingJobs} · 上限 ${health.maxQueueDepth}")
            DiagRow("快照时间", formatDiagnosticTime(health.observedAt))
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun stateColor(state: AsrLifecycleState): Color = when (state) {
    AsrLifecycleState.FAILED, AsrLifecycleState.DROPPED, AsrLifecycleState.SUBMISSION_UNKNOWN -> MaterialTheme.colorScheme.error
    AsrLifecycleState.COMPLETED -> MaterialTheme.colorScheme.primary
    AsrLifecycleState.RETRY_WAIT -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun AsrDiagnosticCard(
    diagnostic: AsrSegmentDiagnosticEntity,
    events: (String) -> Flow<List<AsrNetworkEventEntity>>,
    confirmRetryUnknown: (String) -> Unit
) {
    var expanded by remember(diagnostic.segmentId) { mutableStateOf(false) }
    val state = diagnostic.lifecycleState
    val color = stateColor(state)
    val active = diagnostic.state in ACTIVE_ASR_STATES
    GroupCard(
            Modifier.fillMaxWidth()
            .testTag("asr-diagnostic-${diagnostic.segmentId}")
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(color)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(shortSegmentId(diagnostic.segmentId)) }
                        append(" · ")
                        withStyle(SpanStyle(color = color)) { append(stateName(state)) }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                EndToEndDuration(diagnostic)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                "${formatClock(diagnostic.audioStartTime)} · 音频 ${formatMs(diagnostic.audioDurationMs)}" +
                    (if (diagnostic.submitAttempts > 1) " · 提交 ${diagnostic.submitAttempts} 次" else "") +
                    (diagnostic.lastHttpStatus?.takeIf { it >= 400 }?.let { " · HTTP $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (active) LiveStageLine(diagnostic) else StageBreakdown(diagnostic)
            diagnostic.safeErrorMessage?.takeIf { !expanded }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                DiagSubTitle("耗时")
                DiagRow("客户端排队") { ClientQueueDuration(diagnostic) }
                DiagRow("POST /transcribe", formatNullableMs(diagnostic.postDurationMs))
                DiagRow("实际上传", formatNullableMs(diagnostic.uploadDurationMs), indent = true)
                DiagRow("等待提交响应", formatNullableMs(diagnostic.submitResponseWaitDurationMs), indent = true)
                DiagRow("服务端等待（估算）") { ServerWaitDuration(diagnostic) }
                DiagRow("排队（估算）", formatNullableMs(diagnostic.estimatedServerQueueDurationMs), indent = true)
                DiagRow("模型处理（估算）", formatNullableMs(diagnostic.estimatedProcessingDurationMs), indent = true)
                DiagRow("结果返回", formatNullableMs(diagnostic.resultResponseDurationMs))
                DiagSubTitle("时间点 · ${formatDay(diagnostic.queuedLocalAt)}")
                DiagRow("本地入队", formatTimeOfDay(diagnostic.queuedLocalAt))
                DiagRow("提交开始", formatNullableTimeOfDay(diagnostic.submitStartedAt))
                DiagRow("提交结束", formatNullableTimeOfDay(diagnostic.submitCompletedAt))
                DiagRow("首次 queued", formatNullableTimeOfDay(diagnostic.firstServerQueuedAt))
                DiagRow("首次 processing", formatNullableTimeOfDay(diagnostic.firstServerProcessingAt))
                DiagRow("首次 completed", formatNullableTimeOfDay(diagnostic.firstServerCompletedAt))
                DiagSubTitle("请求")
                DiagRow("job_id", diagnostic.jobId?.take(18) ?: "尚未取得")
                DiagRow("尝试次数", "提交 ${diagnostic.submitAttempts} · 轮询 ${diagnostic.pollAttempts}")
                diagnostic.lastHttpStatus?.let { DiagRow("最近 HTTP", "$it", if (it >= 400) MaterialTheme.colorScheme.error else Color.Unspecified) }
                diagnostic.serverModel?.let { DiagRow("服务端模型", it) }
                DiagRow("计时基准", if (diagnostic.clockBasis == AsrClockBasis.ELAPSED_REALTIME.name) "设备单调时钟" else "旧数据墙上时钟")
                if (diagnostic.failureStage != null || diagnostic.exceptionClass != null || diagnostic.safeErrorMessage != null) {
                    DiagSubTitle("错误", MaterialTheme.colorScheme.error)
                    diagnostic.failureStage?.let { DiagRow("失败阶段", failureStageName(it), MaterialTheme.colorScheme.error) }
                    diagnostic.exceptionClass?.let { DiagRow("异常类型", it.substringAfterLast('.')) }
                    diagnostic.safeErrorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                NetworkEventTimeline(events(diagnostic.segmentId))
                if (state == AsrLifecycleState.SUBMISSION_UNKNOWN) {
                    Text("重新提交可能在服务端产生重复任务，请先确认服务端没有该任务。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { confirmRetryUnknown(diagnostic.segmentId) }) {
                        Text(if (diagnostic.jobId == null) "已确认，重新提交" else "继续轮询已有任务")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagSubTitle(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(top = 4.dp))
}

/** For a segment still in the pipeline: only the stage it is waiting in, ticking. */
@Composable
private fun LiveStageLine(diagnostic: AsrSegmentDiagnosticEntity) {
    when (diagnostic.lifecycleState) {
        AsrLifecycleState.QUEUED_LOCAL, AsrLifecycleState.CAPTURING -> SmoothElapsedText(
            "客户端排队 ", diagnostic.queuedLocalElapsedMs, diagnostic.queuedLocalAt, true,
            clockBasis = diagnostic.clockBasis
        )
        AsrLifecycleState.SUBMITTING -> SmoothElapsedText(
            "正在提交 ", diagnostic.submitStartedElapsedMs, diagnostic.submitStartedAt, true,
            clockBasis = diagnostic.clockBasis
        )
        AsrLifecycleState.QUEUED_SERVER, AsrLifecycleState.PROCESSING, AsrLifecycleState.RETRY_WAIT ->
            ServerWaitDuration(diagnostic, label = "服务端等待 ")
        else -> Unit
    }
}

/** Finished segment: where the end-to-end time went, as one stacked bar. */
@Composable
private fun StageBreakdown(diagnostic: AsrSegmentDiagnosticEntity) {
    val queue = diagnostic.clientQueueDurationMs ?: 0L
    val submit = diagnostic.postDurationMs ?: 0L
    val server = diagnostic.serverWaitDurationMs ?: 0L
    val total = queue + submit + server
    if (total <= 0L) return
    val colors = listOf(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary)
    val parts = listOf(queue, submit, server)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surface)) {
            parts.forEachIndexed { index, value ->
                if (value > 0L) Box(Modifier.weight(value.toFloat()).fillMaxHeight().background(colors[index]))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("排队" to queue, "提交" to submit, "服务端" to server).forEachIndexed { index, (label, value) ->
                if (value > 0L) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusDot(colors[index], 6.dp)
                    Text("$label ${formatShortMs(value)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ClientQueueDuration(diagnostic: AsrSegmentDiagnosticEntity) {
    val active = diagnostic.state in ACTIVE_ASR_STATES && diagnostic.submitStartedAt == null
    val finalValue = diagnostic.clientQueueDurationMs
    if (finalValue != null || !active) StaticDurationText("", finalValue)
    else SmoothElapsedText(
        "",
        diagnostic.queuedLocalElapsedMs,
        diagnostic.queuedLocalAt,
        true,
        clockBasis = diagnostic.clockBasis
    )
}

@Composable
private fun ServerWaitDuration(diagnostic: AsrSegmentDiagnosticEntity, label: String = "") {
    val active = diagnostic.jobId != null && diagnostic.lifecycleState in setOf(
        AsrLifecycleState.QUEUED_SERVER, AsrLifecycleState.PROCESSING, AsrLifecycleState.RETRY_WAIT
    )
    val finalValue = diagnostic.serverWaitDurationMs
    if (finalValue != null) StaticDurationText(label, finalValue)
    else if (!active) StaticText("$label—（无法计算）")
    else SmoothElapsedText(
        label, diagnostic.submitCompletedElapsedMs, diagnostic.submitCompletedAt, true,
        unavailableText = "—（估算中）",
        clockBasis = diagnostic.clockBasis
    )
}

/** Right-aligned in the row header: end-to-end when done, otherwise the task's age, ticking. */
@Composable
private fun EndToEndDuration(diagnostic: AsrSegmentDiagnosticEntity) {
    val finalValue = diagnostic.totalEndToEndDurationMs
    if (finalValue != null || diagnostic.state !in ACTIVE_ASR_STATES) StaticDurationText("", finalValue)
    else SmoothElapsedText(
        "",
        diagnostic.captureStartedElapsedMs,
        diagnostic.captureStartedAt,
        true,
        clockBasis = diagnostic.clockBasis
    )
}

@Composable
private fun StaticDurationText(label: String, value: Long?, suffix: String = "") {
    StaticText("$label${formatNullableMs(value)}$suffix")
}

@Composable
private fun StaticText(text: String) {
    Text(text, style = DiagValueStyle)
}

/** Tabular digits keep columns of times aligned without monospacing the Chinese around them. */
private val DiagValueStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")

/** Its local state invalidates this Text, not the complete diagnostics list. */
@Composable
internal fun SmoothElapsedText(
    label: String,
    startElapsedRealtimeMs: Long?,
    fallbackStartWallTimeMs: Long?,
    active: Boolean,
    modifier: Modifier = Modifier,
    suffix: String = "",
    unavailableText: String = "—",
    clockBasis: String = AsrClockBasis.ELAPSED_REALTIME.name,
    elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    wallTime: () -> Long = { System.currentTimeMillis() }
) {
    val elapsedMs by produceState(
        initialValue = calculateElapsedMs(
            startElapsedRealtimeMs,
            fallbackStartWallTimeMs,
            clockBasis,
            elapsedRealtime,
            wallTime
        ),
        startElapsedRealtimeMs, fallbackStartWallTimeMs, clockBasis, active
    ) {
        if (!active) return@produceState
        while (true) {
            val current = calculateElapsedMs(
                startElapsedRealtimeMs,
                fallbackStartWallTimeMs,
                clockBasis,
                elapsedRealtime,
                wallTime
            )
            value = current
            delay(nextSmoothTickDelay(current))
        }
    }
    Text(
        "$label${elapsedMs?.let { formatSmoothMs(it) + suffix } ?: unavailableText}",
        modifier = modifier,
        style = DiagValueStyle
    )
}

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

@Composable
private fun NetworkEventTimeline(flow: Flow<List<AsrNetworkEventEntity>>) {
    val values by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    DiagSubTitle("网络事件")
    if (values.isEmpty()) Text("暂无网络事件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    values.groupBy { it.requestKind to it.attempt }.forEach { (request, requestEvents) ->
        Text("${request.first} #${request.second}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        if (requestEvents.none { it.eventType == "CONNECT_END" } && requestEvents.any {
                it.eventType == "REQUEST_BODY_START" || it.eventType == "RESPONSE_HEADERS_START"
            }
        ) Text("复用已有连接（无 connectEnd）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        requestEvents.forEach { event ->
            DiagRow(
                "  " + eventName(event.eventType) + if (event.appInForeground) "" else " · 后台",
                event.elapsedSinceCallStartMs?.let { "+${it}ms" } ?: "—",
                if (event.eventType == "CALL_FAILED") MaterialTheme.colorScheme.error else Color.Unspecified
            )
        }
    }
}

private fun stateName(state: AsrLifecycleState) = when (state) {
    AsrLifecycleState.CAPTURING -> "正在捕获"
    AsrLifecycleState.QUEUED_LOCAL -> "客户端排队"
    AsrLifecycleState.SUBMITTING -> "正在提交"
    AsrLifecycleState.QUEUED_SERVER -> "服务端排队"
    AsrLifecycleState.PROCESSING -> "模型处理中"
    AsrLifecycleState.RETRY_WAIT -> "等待重试"
    AsrLifecycleState.SUBMISSION_UNKNOWN -> "提交状态未知"
    AsrLifecycleState.COMPLETED -> "已完成"
    AsrLifecycleState.FAILED -> "失败"
    AsrLifecycleState.DROPPED -> "已丢弃"
}

private fun failureStageName(raw: String): String = runCatching { AsrFailureStage.valueOf(raw) }.getOrNull()?.let {
    when (it) {
        AsrFailureStage.AUDIO_CAPTURE -> "音频捕获"
        AsrFailureStage.LOCAL_PERSISTENCE -> "本地持久化"
        AsrFailureStage.CLIENT_QUEUE -> "客户端排队"
        AsrFailureStage.CONNECT -> "连接服务器"
        AsrFailureStage.AUDIO_UPLOAD -> "上传音频"
        AsrFailureStage.SUBMIT_RESPONSE -> "等待提交响应"
        AsrFailureStage.SERVER_QUEUE -> "服务端排队"
        AsrFailureStage.MODEL_PROCESSING -> "模型处理"
        AsrFailureStage.JOB_POLLING -> "轮询状态"
        AsrFailureStage.RESULT_PARSE -> "解析结果"
    }
} ?: raw

private fun eventName(raw: String) = when (raw) {
    "CALL_START" -> "callStart"
    "CONNECT_END" -> "connectEnd"
    "REQUEST_BODY_START" -> "requestBodyStart"
    "REQUEST_BODY_END" -> "requestBodyEnd"
    "RESPONSE_HEADERS_START" -> "responseHeadersStart"
    "RESPONSE_BODY_END" -> "responseBodyEnd"
    "CALL_FAILED" -> "callFailed"
    else -> raw
}

private fun shortSegmentId(id: String) = "#${id.take(8)}"
private fun formatMs(value: Long) = String.format(Locale.US, "%.2fs", value / 1_000.0)
private fun formatNullableMs(value: Long?) = value?.let(::formatMs) ?: "—"
private fun formatDiagnosticTime(value: Long) = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(value))
private fun formatDay(value: Long) = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(value))
private fun formatTimeOfDay(value: Long) = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(value))
private fun formatNullableTimeOfDay(value: Long?) = value?.let(::formatTimeOfDay) ?: "—"
private fun formatClock(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
private fun formatShortMs(value: Long) = if (value < 1_000) "${value}ms" else String.format(Locale.US, "%.1fs", value / 1_000.0)
