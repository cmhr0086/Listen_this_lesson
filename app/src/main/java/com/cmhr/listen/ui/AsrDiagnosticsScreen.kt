package com.cmhr.listen.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
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
            Text(
                currentRecordName?.let { "当前记录：$it" } ?: "请先选择课堂记录",
                style = MaterialTheme.typography.titleMedium
            )
        }
        item("capture-vad") { CaptureAndVadCard(state, vadState) }
        item("summary") {
            RuntimeSummaryCard(
                activeDiagnostics = visibleActiveDiagnostics,
                totalCount = totalCount,
                recentCounts = recentCounts,
                runtimeSummary = runtimeSummary,
                health = health,
                healthRefreshing = healthRefreshing,
                healthError = healthError,
                refreshHealth = refreshHealth
            )
        }
        if (visibleDiagnostics.isEmpty() && vadState.capturingSegmentId == null) {
            item("empty") {
                Text(if (currentRecordId == null) "选择课堂记录后可查看诊断。" else "当前记录尚无 ASR 生命周期数据。")
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
                    Text("查看更多（剩余 ${totalCount - DIAGNOSTICS_PREVIEW_LIMIT} 条）")
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("record") {
            Text(
                recordName?.let { "课堂记录：$it" } ?: "ASR 诊断历史",
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (diagnostics.isEmpty()) item("empty") { Text("当前记录尚无 ASR 生命周期数据。") }
        items(diagnostics, key = { "diagnostic-history-${it.segmentId}" }) { diagnostic ->
            AsrDiagnosticCard(diagnostic, events, confirmRetryUnknown)
        }
    }
}

@Composable
private fun CaptureAndVadCard(state: ListeningUiState, vadState: VadDiagnosticsUiState) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("asr-capture-vad")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("采集与 VAD", style = MaterialTheme.typography.titleMedium)
            Text("采集状态：${if (state.isListening) "正在监听" else "未监听"}")
            Text("当前片段：${vadState.capturingSegmentId?.let(::shortSegmentId) ?: "无"}")
            if (vadState.capturingSegmentId != null) {
                SmoothElapsedText(
                    label = "已收音：",
                    startElapsedRealtimeMs = vadState.capturingStartedAtElapsedRealtimeMs,
                    fallbackStartWallTimeMs = vadState.capturingStartedAt,
                    active = true,
                    modifier = Modifier.testTag("asr-capturing-elapsed")
                )
            }
            Text("VAD 语音概率：${formatFloat(vadState.vadProbability)}")
            Text("当前实际阈值：${formatFloat(vadState.effectiveVadConfig.threshold)}")
            Text("检测到语音：${if (vadState.isSpeechDetected) "是" else "否"}")
            Text("连续静音：${vadState.silenceDurationMs} ms")
            ExpandHeader("更多采集信息", expanded) { expanded = !expanded }
            if (expanded) {
                Text("片段开始原因：${vadState.segmentStartReason ?: "无"}")
                Text("片段结束原因：${vadState.segmentEndReason ?: "无"}")
                Text("已丢弃过短片段：${vadState.discardedShortSegments}")
                Text("录音读取错误：${vadState.audioReadErrors}")
                vadState.lastPromptDecision?.let {
                    Text("Prompt 模式：${it.effectiveMode.displayName}")
                    Text("最近片段携带 Prompt：${if (it.included) "是" else "否"}")
                    Text("Prompt 决策：${it.reason}")
                }
            }
        }
    }
}

@Composable
private fun RuntimeSummaryCard(
    activeDiagnostics: List<AsrSegmentDiagnosticEntity>,
    totalCount: Int,
    recentCounts: AsrDiagnosticStateCounts,
    runtimeSummary: AsrRuntimeSummary?,
    health: AsrHealthSnapshot?,
    healthRefreshing: Boolean,
    healthError: String?,
    refreshHealth: () -> Unit
) {
    val processing = activeDiagnostics.firstOrNull {
        it.lifecycleState == AsrLifecycleState.SUBMITTING ||
            it.lifecycleState == AsrLifecycleState.QUEUED_SERVER ||
            it.lifecycleState == AsrLifecycleState.PROCESSING
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("运行概况", style = MaterialTheme.typography.titleMedium)
            Text("客户端队列：${activeDiagnostics.size} / 持久化")
            if (runtimeSummary != null) {
                Text("待提交：${runtimeSummary.queuedLocalCount}")
                Text("提交中：${runtimeSummary.submittingCount}")
                Text("服务端在途：${runtimeSummary.serverInFlightCount}")
                Text("全局并发槽：${runtimeSummary.globalInFlightCount} / ${runtimeSummary.inFlightCapacity}")
                Text("正在轮询：${runtimeSummary.pollingCount}")
                Text("待人工确认：${runtimeSummary.submissionUnknownCount}")
                Text("累计完成：${runtimeSummary.completedCount} · 失败 ${runtimeSummary.failedCount}")
                if (runtimeSummary.isBackpressured) {
                    Text(
                        "并发槽已满，新的片段正在本地持久队列等待。",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text("当前处理：${processing?.let { shortSegmentId(it.segmentId) + " · " + stateName(it.lifecycleState) } ?: "无"}")
            Text("诊断记录：$totalCount 条")
            Text("服务端模型：${health?.model ?: "尚未手动获取"}")
            Text("服务端队列：${health?.let { "queued ${it.queuedJobs} · processing ${it.processingJobs} · 上限 ${it.maxQueueDepth}" } ?: "—"}")
            Text("Health 手动快照：${health?.observedAt?.let(::formatDiagnosticTime) ?: "—"}")
            Text("最近 24 小时：成功 ${recentCounts.completedCount} · 失败 ${recentCounts.failedCount} · 丢弃 ${recentCounts.droppedCount}")
            Text("其中已丢弃语气词片段：${recentCounts.discardedFillerCount}")
            healthError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(
                onClick = refreshHealth,
                enabled = !healthRefreshing,
                modifier = Modifier.testTag("asr-health-refresh")
            ) { Text(if (healthRefreshing) "正在刷新" else "刷新服务状态") }
        }
    }
}

@Composable
private fun AsrDiagnosticCard(
    diagnostic: AsrSegmentDiagnosticEntity,
    events: (String) -> Flow<List<AsrNetworkEventEntity>>,
    confirmRetryUnknown: (String) -> Unit
) {
    var expanded by remember(diagnostic.segmentId) { mutableStateOf(false) }
    val state = diagnostic.lifecycleState
    Card(
        Modifier.fillMaxWidth()
            .testTag("asr-diagnostic-${diagnostic.segmentId}")
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "${shortSegmentId(diagnostic.segmentId)} · ${stateName(state)}",
                style = MaterialTheme.typography.titleMedium,
                color = when (state) {
                    AsrLifecycleState.FAILED, AsrLifecycleState.DROPPED,
                    AsrLifecycleState.SUBMISSION_UNKNOWN -> MaterialTheme.colorScheme.error
                    AsrLifecycleState.COMPLETED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Text("捕获：${formatDiagnosticTime(diagnostic.audioStartTime)} · 音频 ${formatMs(diagnostic.audioDurationMs)}")
            Text("生命周期：${diagnostic.state}", style = MaterialTheme.typography.bodySmall)
            ClientQueueDuration(diagnostic)
            ServerWaitDuration(diagnostic)
            EndToEndDuration(diagnostic)
            if (expanded) {
                Text("job_id：${diagnostic.jobId ?: "尚未取得"}")
                Text(
                    "计时基准：${if (diagnostic.clockBasis == AsrClockBasis.ELAPSED_REALTIME.name) "设备单调时钟" else "旧数据墙上时钟"}"
                )
                Text("本地入队：${formatDiagnosticTime(diagnostic.queuedLocalAt)}")
                Text("提交开始：${formatNullableTime(diagnostic.submitStartedAt)}")
                Text("提交结束：${formatNullableTime(diagnostic.submitCompletedAt)}")
                Text("首次观察 queued：${formatNullableTime(diagnostic.firstServerQueuedAt)}")
                Text("首次观察 processing：${formatNullableTime(diagnostic.firstServerProcessingAt)}")
                Text("首次观察 completed：${formatNullableTime(diagnostic.firstServerCompletedAt)}")
                Text("POST /transcribe：${formatNullableMs(diagnostic.postDurationMs)}")
                Text("实际上传：${formatNullableMs(diagnostic.uploadDurationMs)}")
                Text("等待提交响应：${formatNullableMs(diagnostic.submitResponseWaitDurationMs)}")
                Text("服务端排队：${formatNullableMs(diagnostic.estimatedServerQueueDurationMs)}（估算）")
                Text("模型处理：${formatNullableMs(diagnostic.estimatedProcessingDurationMs)}（估算）")
                Text("结果返回：${formatNullableMs(diagnostic.resultResponseDurationMs)}")
                Text("尝试次数：提交 ${diagnostic.submitAttempts} · 轮询 ${diagnostic.pollAttempts}")
                diagnostic.lastHttpStatus?.let { Text("最近 HTTP：$it") }
                diagnostic.serverModel?.let { Text("服务端模型：$it") }
                diagnostic.failureStage?.let { Text("失败阶段：${failureStageName(it)}") }
                diagnostic.exceptionClass?.let { Text("异常类型：$it") }
                diagnostic.safeErrorMessage?.let { Text("安全错误：$it", color = MaterialTheme.colorScheme.error) }
                NetworkEventTimeline(events(diagnostic.segmentId))
                if (state == AsrLifecycleState.SUBMISSION_UNKNOWN) {
                    Text("重新提交可能在服务端产生重复任务，请先确认服务端没有该任务。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { confirmRetryUnknown(diagnostic.segmentId) }) {
                        Text(if (diagnostic.jobId == null) "已确认，重新提交" else "继续轮询已有任务")
                    }
                }
            } else Text("点击展开完整时间轴", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ClientQueueDuration(diagnostic: AsrSegmentDiagnosticEntity) {
    val active = diagnostic.state in ACTIVE_ASR_STATES && diagnostic.submitStartedAt == null
    val finalValue = diagnostic.clientQueueDurationMs
    if (finalValue != null || !active) StaticDurationText("客户端排队：", finalValue)
    else SmoothElapsedText(
        "客户端排队：",
        diagnostic.queuedLocalElapsedMs,
        diagnostic.queuedLocalAt,
        true,
        clockBasis = diagnostic.clockBasis
    )
}

@Composable
private fun ServerWaitDuration(diagnostic: AsrSegmentDiagnosticEntity) {
    val active = diagnostic.jobId != null && diagnostic.lifecycleState in setOf(
        AsrLifecycleState.QUEUED_SERVER, AsrLifecycleState.PROCESSING, AsrLifecycleState.RETRY_WAIT
    )
    val finalValue = diagnostic.serverWaitDurationMs
    if (finalValue != null) StaticDurationText("服务端等待：", finalValue, "（估算）")
    else if (!active) Text("服务端等待：—（无法计算）", fontFamily = FontFamily.Monospace)
    else SmoothElapsedText(
        "服务端等待：", diagnostic.submitCompletedElapsedMs, diagnostic.submitCompletedAt, true,
        suffix = "（估算）",
        unavailableText = "—（估算中）",
        clockBasis = diagnostic.clockBasis
    )
}

@Composable
private fun EndToEndDuration(diagnostic: AsrSegmentDiagnosticEntity) {
    val finalValue = diagnostic.totalEndToEndDurationMs
    val label = when (diagnostic.lifecycleState) {
        AsrLifecycleState.COMPLETED -> "端到端："
        AsrLifecycleState.FAILED, AsrLifecycleState.DROPPED -> "任务历时："
        else -> "任务年龄："
    }
    if (finalValue != null || diagnostic.state !in ACTIVE_ASR_STATES) StaticDurationText(label, finalValue)
    else SmoothElapsedText(
        label,
        diagnostic.captureStartedElapsedMs,
        diagnostic.captureStartedAt,
        true,
        clockBasis = diagnostic.clockBasis
    )
}

@Composable
private fun StaticDurationText(label: String, value: Long?, suffix: String = "") {
    Text("$label${formatNullableMs(value)}$suffix", fontFamily = FontFamily.Monospace)
}

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
        fontFamily = FontFamily.Monospace
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
    Text("网络事件", style = MaterialTheme.typography.titleSmall)
    if (values.isEmpty()) Text("暂无网络事件。", style = MaterialTheme.typography.bodySmall)
    values.groupBy { it.requestKind to it.attempt }.forEach { (request, requestEvents) ->
        Text("${request.first}#${request.second}", style = MaterialTheme.typography.labelLarge)
        if (requestEvents.none { it.eventType == "CONNECT_END" } && requestEvents.any {
                it.eventType == "REQUEST_BODY_START" || it.eventType == "RESPONSE_HEADERS_START"
            }
        ) Text("connectEnd · 复用已有连接", style = MaterialTheme.typography.bodySmall)
        requestEvents.forEach { event ->
            Text(
                "${eventName(event.eventType)} · " +
                    "${event.elapsedSinceCallStartMs?.let { "+${it}ms" } ?: "—"} · " +
                    if (event.appInForeground) "前台" else "后台",
                style = MaterialTheme.typography.bodySmall
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
private fun formatNullableTime(value: Long?) = value?.let(::formatDiagnosticTime) ?: "—"
