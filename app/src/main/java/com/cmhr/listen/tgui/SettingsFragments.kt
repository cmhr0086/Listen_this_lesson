package com.cmhr.listen.tgui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.addTextChangedListener
import com.cmhr.listen.AiPromptKind
import com.cmhr.listen.BindingState
import com.cmhr.listen.BuildConfig
import com.cmhr.listen.CloudSyncRunState
import com.cmhr.listen.ServiceCheck
import com.cmhr.listen.SettingsUiState
import com.cmhr.listen.audio.VadPreset
import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AiPromptSettings
import com.cmhr.listen.data.settings.AiReasoningEffort
import com.cmhr.listen.data.settings.AiThinkingMode
import com.cmhr.listen.data.settings.SttProvider
import com.cmhr.listen.data.stt.ACTIVE_ASR_STATES
import com.cmhr.listen.data.stt.AsrClockBasis
import com.cmhr.listen.data.stt.AsrDiagnosticStateCounts
import com.cmhr.listen.data.stt.AsrLifecycleState
import com.cmhr.listen.data.stt.AsrNetworkEventEntity
import com.cmhr.listen.data.stt.AsrRuntimeSummary
import com.cmhr.listen.data.stt.AsrSegmentDiagnosticEntity
import com.cmhr.listen.ui.theme.DarkModePreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native settings routes. Flow collectors belong to the fragment, never createView(). */
open class SettingsFragment(override val route: String = "settings") : ListenListFragment(settingsTitle(route)) {
    protected val model get() = host.settings
    private var diagnostics = emptyList<AsrSegmentDiagnosticEntity>()
    private var events = emptyList<AsrNetworkEventEntity>()
    private var runtime: AsrRuntimeSummary? = null
    private var recentCounts = AsrDiagnosticStateCounts(0, 0, 0)
    private var diagnosticCount = 0
    private var diagnosticRecordId: Long? = null
    private var diagnosticJobs = emptyList<Job>()
    private var healthError: String? = null
    private var modelFilter = ""
    internal var activeInput: EditText? = null

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> = super.getThemeDescriptions().apply {
        add(ThemeDescription(activeInput, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_dialogTextBlack))
        add(ThemeDescription(activeInput, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_dialogTextGray))
    }

    override fun onReady() {
        super.onReady()
        scope.launch { model.uiState.collect { renderSettings() } }
        scope.launch { model.binding.collect { onBindingChanged(it); renderSettings() } }
        scope.launch { host.stt.uiState.collect { observeDiagnostics(); renderSettings() } }
        scope.launch { host.courses.uiState.collect { observeDiagnostics(); renderSettings() } }
        if (route.startsWith("settings/asr")) {
            scope.launch { host.stt.vadDiagnosticsState.collect { renderSettings() } }
            scope.launch { host.stt.isAsrHealthRefreshing.collect { renderSettings() } }
            scope.launch { host.stt.asrMessages.collect { healthError = it; renderSettings() } }
            scope.launch {
                while (isActive) { delay(100); if (diagnostics.any { it.state in ACTIVE_ASR_STATES } || host.stt.vadDiagnosticsState.value.capturingSegmentId != null) renderSettings() }
            }
        }
        renderSettings()
    }

    protected open fun onBindingChanged(binding: BindingState) = Unit
    protected open fun renderSettings() {
        val state = model.uiState.value
        submit(when {
            route == "settings" -> overviewRows(state)
            route == "settings/stt" -> sttRows(state)
            route == "settings/ai" -> aiRows(state)
            route == "settings/models" -> modelsRows(state)
            route == "settings/sync" -> syncRows(state)
            route == "settings/publish" -> publishRows(state)
            route == "settings/device" -> deviceRows(state)
            route == "settings/appearance" -> appearanceRows(state)
            route == "settings/vad" -> vadRows()
            route == "settings/prompts" -> promptsRows(state)
            route.startsWith("settings/prompt/") -> promptRows(state)
            route == "settings/generation" -> generationRows(state)
            route.startsWith("settings/asr-segment/") -> segmentRows()
            route.startsWith("settings/asr") -> diagnosticsRows()
            else -> overviewRows(state)
        })
    }

    private fun overviewRows(s: SettingsUiState) = buildList {
        add(ListenRow.header("服务"))
        add(link("设备绑定", "device", if (s.device.bound) "已绑定" else "未绑定"))
        add(link("语音识别", "stt", checkLabel(s.sttCheck, s.server.hasApiKey)))
        add(link("AI 服务", "ai", checkLabel(s.aiCheck, s.ai.hasApiKey)))
        add(link("云同步", "sync", if (s.cloudSync.hasApiToken) "已配置" else "未配置"))
        add(link("发布设置", "publish", if (s.publish.hasToken) "已配置" else "未配置"))
        add(ListenRow.shadow())
        add(ListenRow.header("使用"))
        add(link("外观", "appearance", appearanceName(s.appearance.darkMode)))
        add(ListenRow.check("下课后自动整理笔记", s.autoNotes) { model.setAutoNotes(!s.autoNotes) })
        add(ListenRow.shadow())
        add(ListenRow.header("开发者"))
        add(ListenRow.check("开发者模式", s.developerMode) { model.setDeveloperMode(!s.developerMode) })
        if (s.developerMode) {
            add(link("ASR 诊断", "asr"))
            add(link("VAD 预设与参数", "vad", host.stt.uiState.value.selectedVadPreset?.displayName ?: "自定义"))
            add(link("AI 提示词", "prompts"))
            add(link("AI 生成参数", "generation"))
        }
        add(ListenRow.shadow())
        add(value("Listen", BuildConfig.VERSION_NAME))
    }

    protected fun sttRows(s: SettingsUiState) = buildList {
        add(ListenRow.header("连接"))
        add(serviceRow(s.sttCheck, s.server.hasApiKey) { model.checkStt() })
        add(ListenRow.header("服务商"))
        add(ListenRow.check("自建识别服务", s.server.provider == SttProvider.SELF_HOSTED) { model.chooseSttProvider(SttProvider.SELF_HOSTED) })
        add(ListenRow.check("兼容接口", s.server.provider == SttProvider.OPENAI_COMPATIBLE) { model.chooseSttProvider(SttProvider.OPENAI_COMPATIBLE) })
        add(ListenRow.header("配置"))
        add(edit("服务地址", s.server.baseUrl) { model.saveSttUrl(it) })
        if (s.server.provider == SttProvider.OPENAI_COMPATIBLE) add(edit("识别模型", s.server.model) { model.saveSttModel(it) })
        add(secret("访问密钥", s.server.hasApiKey) { model.saveSttKey(it) })
        if (s.server.hasApiKey) add(ListenRow.action("清除访问密钥") { confirm("清除访问密钥？") { model.clearApiKey() } })
    }

    protected fun aiRows(s: SettingsUiState) = buildList {
        add(ListenRow.header("连接"))
        add(serviceRow(s.aiCheck, s.ai.hasApiKey) { model.checkAi() })
        add(ListenRow.header("服务商"))
        add(ListenRow.check("深度求索", s.ai.provider == AiProvider.DEEPSEEK) { model.chooseAiProvider(AiProvider.DEEPSEEK) })
        add(ListenRow.check("其他服务", s.ai.provider == AiProvider.OPENAI_COMPATIBLE) { model.chooseAiProvider(AiProvider.OPENAI_COMPATIBLE) })
        add(ListenRow.header("配置"))
        if (s.ai.provider == AiProvider.OPENAI_COMPATIBLE) add(edit("服务地址", s.ai.baseUrl) { model.saveAiBaseUrl(it) })
        add(link("模型", "models", s.ai.model.ifBlank { "未选择" }))
        add(secret("访问密钥", s.ai.hasApiKey) { model.saveAiKey(it) })
        add(ListenRow.check("下课后自动整理笔记", s.autoNotes) { model.setAutoNotes(!s.autoNotes) })
        if (s.ai.hasApiKey) add(ListenRow.action("清除访问密钥") { confirm("清除访问密钥？") { model.clearAiApiKey() } })
    }

    private fun modelsRows(s: SettingsUiState) = buildList {
        add(ListenRow.action(if (s.isLoadingAiModels) "正在获取模型…" else "获取模型列表") {
            if (!s.isLoadingAiModels) {
                if (s.ai.hasApiKey) model.fetchAiModels() else message("请先填写访问密钥")
            }
        })
        s.aiModelsError?.let { add(value("获取失败", it)) }
        add(ListenRow.action("搜索模型", modelFilter) { prompt("搜索模型", modelFilter) { modelFilter = it; renderSettings() } })
        add(ListenRow.action("手动输入模型名称") { editSettingsText("模型名称", s.ai.model) { model.saveAiModel(it); finishFragment() } })
        (s.availableAiModels + listOfNotNull(s.ai.model.takeIf { it.isNotBlank() })).distinct()
            .filter { it.contains(modelFilter, true) }.forEach { name ->
                add(ListenRow.check(name, name == s.ai.model) { model.saveAiModel(name); finishFragment() })
            }
    }

    private fun syncRows(s: SettingsUiState) = buildList {
        add(ListenRow.header("云同步"))
        add(value("状态", when (val run = s.cloudSyncState) {
            CloudSyncRunState.Idle -> if (s.cloudSync.hasApiToken) "已配置" else "未配置"
            CloudSyncRunState.Syncing -> "正在同步…"
            is CloudSyncRunState.Error -> "同步失败：${run.message}"
            is CloudSyncRunState.Success -> "已同步"
        }))
        s.cloudSyncProgress?.let { add(value("进度", "${it.completedSessions} / ${it.totalSessions} 节课")) }
        (s.cloudSyncState as? CloudSyncRunState.Success)?.summary?.let {
            add(value("上传", "${it.uploadedSessions} 节课 · ${it.uploadedSegments} 段"))
            add(value("收到", "${it.receivedSessions} 节课 · ${it.receivedSegments} 段"))
        }
        if (s.cloudSync.lastSyncAt > 0) add(value("上次同步", clock(s.cloudSync.lastSyncAt)))
        add(ListenRow.action("立即同步") {
            if (s.cloudSyncState !is CloudSyncRunState.Syncing) {
                if (s.cloudSync.hasApiToken && s.cloudSync.baseUrl.isNotBlank()) model.syncNow() else message("请先完成同步配置")
            }
        })
        add(ListenRow.header("服务器"))
        add(edit("同步服务器地址", s.cloudSync.baseUrl) { model.saveCloudSyncUrl(it) })
        add(secret("访问令牌", s.cloudSync.hasApiToken) { model.saveCloudSyncToken(it) })
        if (s.cloudSync.hasApiToken) add(ListenRow.action("停用云同步") {
            confirm("停用云同步？", "清除本机访问令牌") { model.clearCloudSyncApiToken() }
        })
    }

    private fun publishRows(s: SettingsUiState) = buildList {
        add(serviceRow(s.publishCheck, s.publish.hasToken) { model.checkPublish() })
        if (s.publish.viaDevice) add(value("授权方式", "绑定设备"))
        s.publishUploader?.let { add(value("上传者", it)) }
        add(edit("学校", s.publish.schoolName) { model.savePublishSchool(it) })
        if (!s.publish.viaDevice) {
            add(secret("上传令牌", s.publish.hasToken) { model.savePublishToken(it) })
            if (s.publish.hasToken) add(ListenRow.action("清除上传令牌") { confirm("清除上传令牌？") { model.clearPublishToken() } })
        }
        add(ListenRow.action("公开发布") { host.open("publish") })
        add(link("设备绑定", "device"))
    }

    protected fun deviceRows(s: SettingsUiState) = buildList {
        if (s.device.bound) {
            add(value("设备", "已绑定"))
            add(value("配置", if (s.device.dirty.isNotEmpty()) "等待同步" else "已同步"))
            if (s.device.lastSyncedAt > 0) add(value("上次同步", clock(s.device.lastSyncedAt)))
            add(ListenRow.action("立即同步配置") { model.syncDeviceConfig() })
            add(ListenRow.action("解除绑定") { confirm("解除绑定？", "本机配置继续保留") { model.unbindDevice() } })
        } else when (val binding = model.binding.value) {
            BindingState.Idle, is BindingState.Done -> add(ListenRow.action("用微信绑定") { model.startBinding() })
            BindingState.Requesting -> add(value("设备绑定", "正在获取绑定码…"))
            BindingState.Applying -> add(value("设备绑定", "正在下载配置…"))
            is BindingState.Waiting -> {
                add(value("绑定码", binding.code.chunked(3).joinToString(" ")))
                add(ListenRow.action("取消绑定") { model.cancelBinding() })
            }
            is BindingState.Failed -> {
                add(value("绑定失败", binding.message))
                add(ListenRow.action("重新获取绑定码") { model.startBinding() })
            }
        }
    }

    private fun appearanceRows(s: SettingsUiState) = DarkModePreference.entries.map { mode ->
        ListenRow.check(appearanceName(mode), mode == s.appearance.darkMode) {
            scope.launch { model.setDarkMode(mode).join(); host.applyAppearance() }
        }
    }

    private fun vadRows() = buildList {
        val state = host.stt.uiState.value
        val c = state.configuredVadConfig
        add(ListenRow.header("预设"))
        VadPreset.entries.forEach { preset -> add(ListenRow.check(preset.displayName, state.selectedVadPreset == preset) { host.stt.applyVadPreset(preset) }) }
        add(ListenRow.action("恢复默认参数") { confirm("恢复默认参数？") { host.stt.restoreDefaultVadConfig() } })
        add(ListenRow.header("判定"))
        add(number("语音阈值", c.threshold.toDouble(), 0.1, 0.9) { host.stt.updateVadConfig(c.copy(threshold = it.toFloat())) })
        add(number("开始确认（毫秒）", c.startConfirmMs.toDouble(), 50.0, 500.0) { host.stt.updateVadConfig(c.copy(startConfirmMs = it.toLong())) })
        add(number("结束静音（毫秒）", c.endSilenceMs.toDouble(), 300.0, 2000.0) { host.stt.updateVadConfig(c.copy(endSilenceMs = it.toLong())) })
        add(ListenRow.header("保留"))
        add(number("前置保留（毫秒）", c.preRollMs.toDouble(), 0.0, 3000.0) { host.stt.updateVadConfig(c.copy(preRollMs = it.toLong())) })
        add(number("后置保留（毫秒）", c.postRollMs.toDouble(), 0.0, 1500.0) { host.stt.updateVadConfig(c.copy(postRollMs = it.toLong())) })
        add(number("最短片段（毫秒）", c.minSegmentMs.toDouble(), 200.0, 3000.0) { host.stt.updateVadConfig(c.copy(minSegmentMs = it.toLong())) })
        add(ListenRow.header("切分"))
        add(number("柔性上限（毫秒）", c.softLimitMs.toDouble(), 5000.0, (c.hardLimitMs - 1).toDouble()) { host.stt.updateVadConfig(c.copy(softLimitMs = it.toLong())) })
        add(number("硬性上限（毫秒）", c.hardLimitMs.toDouble(), 10000.0, 60000.0) { host.stt.updateVadConfig(c.copy(hardLimitMs = it.toLong())) })
        add(number("重叠（毫秒）", c.overlapMs.toDouble(), 0.0, 3000.0) { host.stt.updateVadConfig(c.copy(overlapMs = it.toLong())) })
    }

    private fun promptsRows(s: SettingsUiState) = buildList {
        AiPromptKind.entries.forEach { kind -> add(ListenRow.action(kind.label, if (kind.isDefault(s.aiPrompts)) "默认" else "已修改") { host.open("settings/prompt/${kind.name}") }) }
        add(ListenRow.action("恢复全部默认提示词") { confirm("恢复全部默认提示词？") { model.restoreDefaultAiPrompts() } })
    }

    private fun promptRows(s: SettingsUiState) = buildList {
        val kind = runCatching { AiPromptKind.valueOf(route.substringAfterLast('/')) }.getOrDefault(AiPromptKind.NOTES)
        val text = kind.read(s.aiPrompts)
        add(ListenRow.action("编辑提示词", "${text.codePointCount(0, text.length)} 字") {
            editSettingsText(kind.label, text, multiline = true) { model.saveAiPrompt(kind, it) }
        })
        add(ListenRow.action("查看全文") { showSettingsText(kind.label, text) })
        add(ListenRow.action("恢复默认") { confirm("恢复默认提示词？") { model.saveAiPrompt(kind, kind.read(AiPromptSettings())) } })
    }

    private fun generationRows(s: SettingsUiState) = buildList {
        val g = s.aiGeneration
        add(ListenRow.header("输出"))
        add(number("最大输出长度", g.maxTokens.toDouble(), 512.0, 32768.0) { model.saveAiGeneration(g.copy(maxTokens = it.toInt())) })
        add(ListenRow.header("温度"))
        add(number("笔记、纠错、快速回答", g.fixedTemperature.toDouble(), 0.0, 1.5) { model.saveAiGeneration(g.copy(fixedTemperature = it.toFloat())) })
        add(number("课堂问答和追问", g.chatTemperature.toDouble(), 0.0, 1.5) { model.saveAiGeneration(g.copy(chatTemperature = it.toFloat())) })
        if (s.ai.provider == AiProvider.DEEPSEEK) {
            add(ListenRow.header("深度思考"))
            AiThinkingMode.entries.forEach { mode -> add(ListenRow.check(mode.displayName, mode == g.deepSeekThinkingMode) { model.saveAiGeneration(g.copy(deepSeekThinkingMode = mode)) }) }
            add(ListenRow.header("推理强度"))
            AiReasoningEffort.entries.forEachIndexed { index, effort ->
                add(ListenRow.check(listOf("低", "高", "最高")[index], effort == g.reasoningEffort) {
                    model.saveAiGeneration(g.copy(reasoningEffort = effort))
                }.let { if (g.deepSeekThinkingMode == AiThinkingMode.ENABLED) it else it.copy(onClick = null) })
            }
        }
        add(ListenRow.action("恢复默认参数") { confirm("恢复默认参数？") { model.restoreDefaultAiGeneration() } })
    }

    private fun observeDiagnostics() {
        if (!route.startsWith("settings/asr")) return
        val id = if (route.startsWith("settings/asr-history/")) route.substringAfterLast('/').toLongOrNull()
            else if (route.startsWith("settings/asr-segment/") && route.split('/').size >= 4) route.split('/')[2].toLongOrNull()
            else host.courses.uiState.value.selectedRecord?.id ?: host.stt.uiState.value.activeRecordId
        if (id == diagnosticRecordId) return
        diagnosticRecordId = id
        diagnosticJobs.forEach { it.cancel() }
        diagnostics = emptyList(); diagnosticCount = 0; runtime = null
        diagnosticJobs = if (id == null) emptyList() else listOf(
            scope.launch { host.stt.observeAsrDiagnostics(id).collect { diagnostics = it; renderSettings() } },
            scope.launch { host.stt.observeAsrDiagnosticCount(id).collect { diagnosticCount = it; renderSettings() } },
            scope.launch { host.stt.observeAsrRuntimeSummary(id).collect { runtime = it; renderSettings() } },
            scope.launch { host.stt.observeAsrStateCounts(id, System.currentTimeMillis() - 24L * 60 * 60_000).collect { recentCounts = it; renderSettings() } }
        ) + if (route.startsWith("settings/asr-segment/")) listOf(scope.launch {
            host.stt.observeAsrEvents(route.substringAfterLast('/')).collect { events = it; renderSettings() }
        }) else emptyList()
    }

    private fun diagnosticsRows() = buildList {
        val state = host.stt.uiState.value
        val vad = host.stt.vadDiagnosticsState.value
        add(ListenRow.header(host.courses.uiState.value.selectedRecord?.name ?: state.currentRecordName ?: "请选择课堂记录"))
        if (!route.startsWith("settings/asr-history/")) {
            add(ListenRow.header("采集与语音检测"))
            add(value("录音", if (state.isListening) "正在录制" else if (state.pausedClass != null) "已暂停" else "已停止"))
            add(value("语音概率", String.format(Locale.CHINA, "%.2f", vad.vadProbability)))
            add(value("检测", if (vad.isSpeechDetected) "正在讲话" else "静音"))
            add(value("静音时长", millis(vad.silenceDurationMs)))
            add(value("当前阈值", vad.effectiveVadConfig.threshold.toString()))
            vad.capturingSegmentId?.let { add(value("正在采集", it)) }
            vad.capturingStartedAtElapsedRealtimeMs?.let { add(value("采集时长", millis((SystemClock.elapsedRealtime() - it).coerceAtLeast(0)))) }
            vad.segmentStartReason?.let { add(value("开始原因", reasonName(it))) }
            vad.segmentEndReason?.let { add(value("结束原因", reasonName(it))) }
            add(value("丢弃短片段", vad.discardedShortSegments.toString()))
            add(value("音频读取错误", vad.audioReadErrors.toString()))
            add(ListenRow.header("识别队列"))
            runtime?.let {
                add(value("等待上传", it.queuedLocalCount.toString()))
                add(value("正在提交", it.submittingCount.toString()))
                add(value("服务端处理中", it.serverInFlightCount.toString()))
                add(value("正在轮询", it.pollingCount.toString()))
                add(value("全局并发", "${it.globalInFlightCount} / ${it.inFlightCapacity}"))
                add(value("本次运行", "完成 ${it.completedCount} · 失败 ${it.failedCount}"))
                if (it.isBackpressured) add(value("队列状态", "并发已满，等待提交"))
                add(value("待确认", it.submissionUnknownCount.toString()))
                if (it.submissionUnknownCount > 0) add(ListenRow.action("重试全部待确认片段") {
                    confirm("重新提交？", "可能产生重复任务，请先确认服务端状态") { host.stt.confirmRetryAllUnknown() }
                })
            } ?: add(value("等待识别", state.pendingQueueCount.toString()))
            add(value("本课堂最近24小时", "完成 ${recentCounts.completedCount} · 失败 ${recentCounts.failedCount} · 丢弃 ${recentCounts.droppedCount}"))
            add(value("过滤语气词", recentCounts.discardedFillerCount.toString()))
            add(ListenRow.header("服务状态"))
            add(ListenRow.action(if (host.stt.isAsrHealthRefreshing.value) "正在刷新…" else "刷新服务状态") {
                if (!host.stt.isAsrHealthRefreshing.value) { healthError = null; host.stt.refreshAsrHealth() }
            })
            healthError?.let { add(value("刷新结果", it)) }
            state.asrHealth?.let {
                add(value("模型", it.model)); add(value("精度", it.dtype ?: "未知"))
                add(value("服务器队列", "${it.queuedJobs} / ${it.maxQueueDepth}"))
                add(value("服务器处理", it.processingJobs.toString())); add(value("更新时间", clock(it.observedAt)))
            }
        }
        add(ListenRow.header("片段 · 共 $diagnosticCount 个"))
        val visible = if (route.startsWith("settings/asr-history/")) diagnostics else diagnostics.take(15)
        if (visible.isEmpty()) add(value("片段", "暂无诊断数据"))
        visible.forEach { d -> add(ListenRow.dialog(d.segmentId.hashCode().toLong(), "片段 ${d.sequenceNumber ?: d.segmentId.take(8)} · ${stateName(d.lifecycleState)}", "音频 ${millis(d.audioDurationMs)} · 总耗时 ${duration(d)}" + if (d.state in ACTIVE_ASR_STATES) " · ${stageDuration(d)}" else "", clock(d.audioStartTime), { host.open("settings/asr-segment/${d.recordId}/${d.segmentId}") })) }
        if (!route.startsWith("settings/asr-history/") && diagnosticCount > 15) add(ListenRow.action("查看全部片段", diagnosticCount.toString()) { host.open("settings/asr-history/$diagnosticRecordId") })
    }

    private fun segmentRows() = buildList {
        val d = diagnostics.firstOrNull { it.segmentId == route.substringAfterLast('/') }
        if (d == null) { add(value("片段", "正在读取…")); return@buildList }
        add(value("片段", d.segmentId)); add(value("状态", stateName(d.lifecycleState)))
        add(value("总耗时", duration(d))); add(value("音频时长", millis(d.audioDurationMs)))
        if (d.state in ACTIVE_ASR_STATES) add(value("当前阶段", stageDuration(d)))
        add(ListenRow.header("耗时"))
        listOf("客户端排队" to d.clientQueueDurationMs, "提交请求" to d.postDurationMs,
            "实际上传" to d.uploadDurationMs, "等待提交响应" to d.submitResponseWaitDurationMs,
            "服务器排队（估算）" to d.estimatedServerQueueDurationMs,
            "模型处理（估算）" to d.estimatedProcessingDurationMs, "结果返回" to d.resultResponseDurationMs
        ).forEach { (name, time) -> add(value(name, millis(time))) }
        add(value("服务端等待（估算）", serverDuration(d)))
        add(ListenRow.header("时间点"))
        listOf("采集开始" to d.captureStartedAt, "采集结束" to d.captureFinishedAt, "本地入队" to d.queuedLocalAt,
            "提交开始" to d.submitStartedAt, "提交结束" to d.submitCompletedAt, "首次服务端排队" to d.firstServerQueuedAt,
            "首次开始处理" to d.firstServerProcessingAt, "首次完成" to d.firstServerCompletedAt, "下次尝试" to d.nextAttemptAt
        ).forEach { (name, time) -> add(value(name, time?.let(::clock) ?: "—")) }
        add(ListenRow.header("请求"))
        add(value("任务编号", d.jobId ?: "尚未取得")); add(value("提交次数", d.submitAttempts.toString()))
        add(value("轮询次数", d.pollAttempts.toString())); add(value("响应状态码", d.lastHttpStatus?.toString() ?: "—"))
        add(value("服务端模型", d.serverModel ?: "—"))
        add(value("计时基准", if (d.clockBasis == AsrClockBasis.ELAPSED_REALTIME.name) "设备单调时钟" else "旧数据墙上时钟"))
        d.failureStage?.let { add(value("失败阶段", failureName(it))) }
        d.exceptionClass?.let { add(value("异常类型", it.substringAfterLast('.'))) }
        d.safeErrorMessage?.let { add(value("错误", it)) }
        add(ListenRow.header("网络事件"))
        events.forEach { event ->
            add(value("${requestName(event.requestKind)} ${event.attempt} · ${eventName(event.eventType)}", "${clock(event.timestampMs)} · ${millis(event.elapsedSinceCallStartMs)} · ${if (event.appInForeground) "前台" else "后台"}"))
            event.exceptionClass?.let { add(value("异常", it.substringAfterLast('.'))) }
        }
        if (events.isEmpty()) add(value("网络事件", "暂无记录"))
        if (d.lifecycleState == AsrLifecycleState.SUBMISSION_UNKNOWN) add(ListenRow.action(if (d.jobId == null) "重新提交" else "继续轮询已有任务") {
            confirm("确认重试？", "可能产生重复任务，请先确认服务端状态") { host.stt.confirmRetryUnknown(d.segmentId) }
        })
    }

    protected fun value(title: String, text: String) = ListenRow.action(title, text) { showSettingsText(title, text) }
    private fun link(title: String, path: String, summary: String = "") = ListenRow.action(title, summary) { host.open("settings/$path") }
    private fun edit(title: String, initial: String, save: (String) -> Unit) = ListenRow.action(title, initial.ifBlank { "未填写" }) { editSettingsText(title, initial, onSave = save) }
    private fun secret(title: String, configured: Boolean, save: (String) -> Unit) = ListenRow.action(title, if (configured) "已保存" else "未填写") { editSettingsText(title, secret = true, onSave = save) }
    private fun serviceRow(check: ServiceCheck, configured: Boolean, test: () -> Unit) = ListenRow.action(
        if (check is ServiceCheck.Failed) "连接失败" else checkLabel(check, configured),
        when (check) {
            is ServiceCheck.Failed -> "${clock(check.at)} · ${check.message}\n点击重试"
            is ServiceCheck.Passed -> "${clock(check.at)} · 重新测试"
            ServiceCheck.Testing -> "正在连接"
            ServiceCheck.Unknown -> "测试连接"
        }
    ) {
        if (check !is ServiceCheck.Testing) test()
    }
    private fun number(title: String, current: Double, min: Double, max: Double, save: (Double) -> Unit) = ListenRow.action(title, decimal(current)) {
        editSettingsText(title, decimal(current), numeric = true, validate = { text ->
            text.toDoubleOrNull()?.let { it.isFinite() && it in min..max } ?: false
        }, error = "范围 ${decimal(min)}–${decimal(max)}") { save(it.toDouble()) }
    }
}

/** The same persisted service forms are used inside the four-step first-run flow. */
class SetupFragment : SettingsFragment("setup") {
    private var step = 0
    private var initialized = false
    override fun onReady() {
        super.onReady()
        scope.launch { model.uiState.collect { s ->
            if (!initialized && (s.device.bound || s.server.hasApiKey)) {
                step = if (s.server.hasApiKey) 2 else 1
                initialized = true
            }
            renderSettings()
        } }
    }
    override fun onBindingChanged(binding: BindingState) {
        if (binding is BindingState.Done) {
            if (binding.hasStt) finishSetup() else moveToStep(1)
        }
    }
    override fun renderSettings() {
        val s = model.uiState.value
        submit(buildList {
            add(ListenRow.header(listOf("欢迎使用 Listen", "语音识别", "AI 服务", "准备好了")[step]))
            when (step) {
                0 -> {
                    addAll(deviceRows(s))
                    add(ListenRow.action("手动配置") { model.cancelBinding(); initialized = true; moveToStep(1) })
                }
                1, 2 -> {
                    addAll(if (step == 1) sttRows(s) else aiRows(s))
                    add(ListenRow.shadow())
                    add(ListenRow.action("跳过") { moveToStep(step + 1) })
                    val configured = if (step == 1) s.server.hasApiKey else s.ai.hasApiKey
                    val check = if (step == 1) s.sttCheck else s.aiCheck
                    add(ListenRow.action(if (check is ServiceCheck.Testing) "正在测试…" else "下一步") {
                        if (!configured) message("请先完成配置，或选择跳过")
                        else if (check is ServiceCheck.Failed) confirm("连接测试没通过", check.message) { moveToStep(step + 1) }
                        else if (check !is ServiceCheck.Testing) moveToStep(step + 1)
                    })
                }
                else -> {
                    add(value("语音识别", checkLabel(s.sttCheck, s.server.hasApiKey)))
                    add(value("AI 服务", checkLabel(s.aiCheck, s.ai.hasApiKey)))
                    add(value("云同步", if (s.cloudSync.hasApiToken) "已配置" else "未配置"))
                    add(value("公开发布", if (s.publish.hasToken) "已配置" else "未配置"))
                    add(ListenRow.action("开始使用") { finishSetup() })
                }
            }
            if (step > 0) add(ListenRow.action("上一步") { moveToStep(step - 1) })
        })
    }
    override fun onBackPressed(): Boolean {
        if (step > 0) { moveToStep(step - 1); return false }
        return super.onBackPressed()
    }
    private fun finishSetup() { scope.launch { model.finishSetup().join(); finishFragment() } }
    private fun moveToStep(next: Int) {
        step = next.coerceIn(0, 3)
        renderSettings()
        if (fragmentView != null) list.scrollToPosition(0)
    }
}

private fun settingsTitle(route: String): String = when {
    route.startsWith("settings/prompt/") -> runCatching { AiPromptKind.valueOf(route.substringAfterLast('/')).label }.getOrDefault("提示词")
    route.startsWith("settings/asr-segment/") -> "片段诊断"
    route.startsWith("settings/asr-history/") -> "诊断历史"
    else -> mapOf("settings" to "设置", "settings/stt" to "语音识别", "settings/ai" to "AI 服务", "settings/models" to "选择模型",
        "settings/sync" to "云同步", "settings/publish" to "发布设置", "settings/device" to "设备绑定", "settings/appearance" to "外观",
        "settings/vad" to "VAD 预设与参数", "settings/prompts" to "AI 提示词", "settings/generation" to "AI 生成参数", "settings/asr" to "ASR 诊断", "setup" to "配置向导")[route] ?: "设置"
}
private fun appearanceName(mode: DarkModePreference) = when (mode) { DarkModePreference.SYSTEM -> "跟随系统"; DarkModePreference.LIGHT -> "日间"; DarkModePreference.DARK -> "夜间" }
private fun checkLabel(check: ServiceCheck, configured: Boolean) = when (check) {
    ServiceCheck.Unknown -> if (configured) "已配置" else "未配置"
    ServiceCheck.Testing -> "正在测试…"
    is ServiceCheck.Passed -> "已连接 · ${check.latencyMs} 毫秒"
    is ServiceCheck.Failed -> "连接失败：${check.message}"
}
private fun clock(time: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(time))
private fun millis(time: Long?): String = time?.let { String.format(Locale.CHINA, "%.2f 秒", it / 1000.0) } ?: "—"
private fun decimal(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
private fun duration(d: AsrSegmentDiagnosticEntity): String {
    val finished = d.totalEndToEndDurationMs
    if (finished != null || d.state !in ACTIVE_ASR_STATES) return millis(finished)
    val start = if (d.clockBasis == AsrClockBasis.ELAPSED_REALTIME.name) d.captureStartedElapsedMs else d.captureStartedAt
    val now = if (d.clockBasis == AsrClockBasis.ELAPSED_REALTIME.name) SystemClock.elapsedRealtime() else System.currentTimeMillis()
    return millis(start?.takeIf { it > 0 && now >= it }?.let { now - it })
}
private fun elapsed(d: AsrSegmentDiagnosticEntity, monotonic: Long?, wall: Long?): Long? {
    val start = if (d.clockBasis == AsrClockBasis.ELAPSED_REALTIME.name) monotonic else wall
    val now = if (d.clockBasis == AsrClockBasis.ELAPSED_REALTIME.name) SystemClock.elapsedRealtime() else System.currentTimeMillis()
    return start?.takeIf { it > 0 && now >= it }?.let { now - it }
}
private fun serverDuration(d: AsrSegmentDiagnosticEntity): String {
    d.serverWaitDurationMs?.let { return millis(it) }
    return if (d.jobId != null && d.lifecycleState in setOf(AsrLifecycleState.QUEUED_SERVER, AsrLifecycleState.PROCESSING, AsrLifecycleState.RETRY_WAIT)) {
        elapsed(d, d.submitCompletedElapsedMs, d.submitCompletedAt)?.let(::millis) ?: "—（估算中）"
    } else "—（无法计算）"
}
private fun stageDuration(d: AsrSegmentDiagnosticEntity): String = when (d.lifecycleState) {
    AsrLifecycleState.QUEUED_LOCAL, AsrLifecycleState.CAPTURING -> "客户端排队 ${millis(elapsed(d, d.queuedLocalElapsedMs, d.queuedLocalAt))}"
    AsrLifecycleState.SUBMITTING -> "正在提交 ${millis(elapsed(d, d.submitStartedElapsedMs, d.submitStartedAt))}"
    AsrLifecycleState.QUEUED_SERVER, AsrLifecycleState.PROCESSING, AsrLifecycleState.RETRY_WAIT -> "服务端等待 ${serverDuration(d)}"
    else -> stateName(d.lifecycleState)
}
private fun stateName(state: AsrLifecycleState) = when (state) {
    AsrLifecycleState.CAPTURING -> "采集中"; AsrLifecycleState.QUEUED_LOCAL -> "本地排队"; AsrLifecycleState.SUBMITTING -> "正在提交"
    AsrLifecycleState.QUEUED_SERVER -> "服务端排队"; AsrLifecycleState.PROCESSING -> "正在识别"; AsrLifecycleState.RETRY_WAIT -> "等待重试"
    AsrLifecycleState.SUBMISSION_UNKNOWN -> "等待确认"; AsrLifecycleState.COMPLETED -> "已完成"; AsrLifecycleState.FAILED -> "失败"; AsrLifecycleState.DROPPED -> "已丢弃"
}
private fun requestName(raw: String) = mapOf("HEALTH" to "状态检测", "SUBMIT" to "提交", "POLL" to "轮询")[raw] ?: "请求"
private fun eventName(raw: String) = mapOf("CALL_START" to "请求开始", "CONNECT_END" to "连接完成", "REQUEST_BODY_START" to "开始上传", "REQUEST_BODY_END" to "上传完成", "RESPONSE_HEADERS_START" to "收到响应头", "RESPONSE_BODY_END" to "响应完成", "CALL_FAILED" to "请求失败")[raw] ?: "网络事件"
private fun failureName(raw: String) = mapOf("AUDIO_CAPTURE" to "音频采集", "LOCAL_PERSISTENCE" to "本地保存", "CLIENT_QUEUE" to "本地队列", "CONNECT" to "连接", "AUDIO_UPLOAD" to "音频上传", "SUBMIT_RESPONSE" to "提交响应", "SERVER_QUEUE" to "服务端队列", "MODEL_PROCESSING" to "模型处理", "JOB_POLLING" to "任务轮询", "RESULT_PARSE" to "结果解析")[raw] ?: "未知阶段"
private fun reasonName(raw: String) = mapOf("speech_confirmed" to "确认语音", "silence" to "静音", "soft_limit" to "柔性上限", "hard_limit" to "硬性上限", "stop" to "停止录制", "overlap" to "重叠切分")[raw] ?: raw

/** Secret values are never read back; only a new user-entered value reaches the existing save API. */
private fun SettingsFragment.editSettingsText(
    title: String, initial: String = "", secret: Boolean = false, multiline: Boolean = false,
    numeric: Boolean = false, validate: (String) -> Boolean = { it.isNotBlank() }, error: String = "不能为空", onSave: (String) -> Unit
) {
    val context = parentActivity ?: return
    val input = EditText(context).apply {
        setText(initial); hint = title; contentDescription = title
        inputType = when { secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        setTextColor(Theme.getColor(Theme.key_dialogTextBlack)); setHintTextColor(Theme.getColor(Theme.key_dialogTextGray))
        backgroundTintList = android.content.res.ColorStateList.valueOf(Theme.getColor(Theme.key_dialogTextBlue))
        textSize = 17f; minHeight = AndroidUtilities.dp(48f)
        if (multiline) { minLines = 8; maxLines = 14; gravity = Gravity.TOP; setHorizontallyScrolling(false) }
        else setSingleLine(true)
        setSelection(text.length)
        if (secret) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    activeInput = input
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(8f), AndroidUtilities.dp(24f), AndroidUtilities.dp(8f))
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    var saved = false
    val builder = AlertDialog.Builder(context).setTitle(title).setView(container)
        .setPositiveButton("保存", null).setNegativeButton("取消", null)
    fun guardUnsavedPrompt() {
        builder.overrideDismissListener { dismiss ->
            if (multiline && !saved && input.text.toString() != initial && !context.isFinishing) {
                AlertDialog.Builder(context).setTitle("放弃修改？")
                    .setPositiveButton("放弃") { _, _ -> dismiss.run() }
                    .setNegativeButton("继续编辑") { _, _ -> guardUnsavedPrompt() }
                    .create().also { it.setCancelable(false); it.show() }
            } else dismiss.run()
        }
    }
    guardUnsavedPrompt()
    val dialog = builder.create()
    showDialog(dialog)
    fun updateSaveEnabled() {
        val text = input.text.toString().trim()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = validate(text) && text != initial.trim()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).alpha = if (dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled) 1f else 0.5f
    }
    updateSaveEnabled()
    input.addTextChangedListener { updateSaveEnabled() }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = input.text.toString().trim()
        if (!validate(value)) { input.error = error; input.requestFocus() }
        else { saved = true; onSave(value); dialog.dismiss() }
    }
    input.requestFocus()
    AndroidUtilities.showKeyboard(input)
}

private fun SettingsFragment.showSettingsText(title: String, text: String) {
    val context = parentActivity ?: return
    showDialog(AlertDialog.Builder(context).setTitle(title).setMessage(text)
        .setPositiveButton("关闭", null).setNeutralButton("复制") { _, _ ->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, text))
        }.create())
}
