package com.cmhr.listen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cmhr.listen.ConnectionTestState
import com.cmhr.listen.SettingsUiState
import com.cmhr.listen.SettingsViewModel
import com.cmhr.listen.SttViewModel
import com.cmhr.listen.audio.VadConfig
import com.cmhr.listen.audio.VadPreset
import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AiPromptSettings
import com.cmhr.listen.data.settings.AiGenerationSettings
import com.cmhr.listen.data.settings.AiThinkingMode
import com.cmhr.listen.data.settings.AiReasoningEffort
import com.cmhr.listen.data.stt.AsrPromptMode
import com.cmhr.listen.data.stt.AsrPromptAutoConfig
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    model: SettingsViewModel,
    onSttService: () -> Unit,
    onAiService: () -> Unit,
    onVadParameters: () -> Unit,
    onVadPresets: () -> Unit,
    onAiPrompts: () -> Unit,
    onAsrPromptPolicy: () -> Unit,
    onAiGeneration: () -> Unit
) = SettingsOverview(
    state = state,
    setDeveloperMode = model::setDeveloperMode,
    onSttService = onSttService,
    onAiService = onAiService,
    onVadParameters = onVadParameters,
    onVadPresets = onVadPresets,
    onAiPrompts = onAiPrompts,
    onAsrPromptPolicy = onAsrPromptPolicy,
    onAiGeneration = onAiGeneration
)

@Composable
internal fun SettingsOverview(
    state: SettingsUiState,
    setDeveloperMode: (Boolean) -> Unit,
    onSttService: () -> Unit,
    onAiService: () -> Unit,
    onVadParameters: () -> Unit,
    onVadPresets: () -> Unit,
    onAiPrompts: () -> Unit,
    onAsrPromptPolicy: () -> Unit,
    onAiGeneration: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item("stt-link") {
            SettingsLink(
                "STT 服务器",
                "${state.server.baseUrl.ifBlank { "未设置地址" }} · ${if (state.server.hasApiKey) "Key 已配置" else "Key 未配置"}",
                onSttService
            )
        }
        item("ai-link") {
            SettingsLink(
                "AI 配置",
                "${providerName(state.ai.provider)} · ${state.ai.model.ifBlank { "未设置模型" }} · ${if (state.ai.hasApiKey) "Key 已配置" else "Key 未配置"}",
                onAiService
            )
        }
        item("asr-prompt-policy-link") {
            SettingsLink("ASR 提示词模式", "全局模式：${state.globalAsrPromptMode.displayName}", onAsrPromptPolicy)
        }

        item("developer-switch") {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("开发者模式", style = MaterialTheme.typography.titleMedium)
                        Text("开启后显示 VAD 设置，并在记录页显示实时诊断。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(state.developerMode, setDeveloperMode)
                }
            }
        }
        if (state.developerMode) {
            item("vad-parameters-link") { SettingsLink("VAD 参数", "调整阈值、静音、前后保留和时长限制。", onVadParameters) }
            item("vad-presets-link") { SettingsLink("VAD 预设", "选择默认、远距离、近距离或高噪声配置。", onVadPresets) }
            item("ai-prompts-link") { SettingsLink("AI 提示词", "编辑总结、笔记、纠错、回答、对话和图片场景提示词。", onAiPrompts) }
            item("ai-generation-link") { SettingsLink("AI 生成参数", "调整输出长度、温度、思考模式与推理强度。", onAiGeneration) }
        }
    }
}

@Composable
fun SttServiceSettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    var serverUrl by remember(state.server.baseUrl) { mutableStateOf(state.server.baseUrl) }
    var serverKey by remember { mutableStateOf("") }
    var showServerKey by remember { mutableStateOf(false) }
    val testing = state.connectionTestState is ConnectionTestState.Testing

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item("stt-settings") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        serverUrl,
                        { serverUrl = it },
                        label = { Text("服务器地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        serverKey,
                        { serverKey = it },
                        label = { Text(if (state.server.hasApiKey) "API Key（留空则保留）" else "API Key") },
                        visualTransformation = if (showServerKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showServerKey = !showServerKey }) {
                                Icon(
                                    if (showServerKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showServerKey) "隐藏 API Key" else "显示 API Key"
                                )
                            }
                        },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.saveServer(serverUrl, serverKey); serverKey = "" }) {
                            Text("保存")
                        }
                        OutlinedButton(onClick = { model.testConnection(serverUrl) }, enabled = !testing) {
                            Text(if (testing) "正在测试" else "测试连接")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiServiceSettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    var provider by remember(state.ai.provider) { mutableStateOf(state.ai.provider) }
    var baseUrl by remember(state.ai.baseUrl) { mutableStateOf(state.ai.baseUrl) }
    var aiModel by remember(state.ai.model) { mutableStateOf(state.ai.model) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val testing = state.aiConnectionTestState is ConnectionTestState.Testing

    androidx.compose.runtime.LaunchedEffect(state.availableAiModels, state.isLoadingAiModels) {
        if (!state.isLoadingAiModels && state.availableAiModels.isNotEmpty()) modelMenuExpanded = true
    }

    fun chooseProvider(value: AiProvider) {
        provider = value
        if (value == AiProvider.DEEPSEEK) {
            baseUrl = "https://api.deepseek.com"
            aiModel = "deepseek-v4-flash"
        } else if (baseUrl == "https://api.deepseek.com") {
            baseUrl = "https://api.openai.com/v1"
            aiModel = ""
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item("provider") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("接口类型", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = provider == AiProvider.OPENAI_COMPATIBLE,
                            onClick = { chooseProvider(AiProvider.OPENAI_COMPATIBLE) },
                            label = { Text("OpenAI 通用接口") }
                        )
                        FilterChip(
                            selected = provider == AiProvider.DEEPSEEK,
                            onClick = { chooseProvider(AiProvider.DEEPSEEK) },
                            label = { Text("DeepSeek") }
                        )
                    }
                    Text("AI 服务地址必须使用 HTTPS。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item("ai-form") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Box {
                        OutlinedTextField(
                            aiModel,
                            { aiModel = it },
                            label = { Text("模型名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        modelMenuExpanded = false
                                        model.fetchAiModels(baseUrl, apiKey)
                                    },
                                    enabled = !state.isLoadingAiModels
                                ) { Icon(Icons.Outlined.ArrowDropDown, contentDescription = "获取或选择模型") }
                            }
                        )
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            state.availableAiModels.forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(value) },
                                    onClick = { aiModel = value; modelMenuExpanded = false }
                                )
                            }
                        }
                    }
                    if (provider == AiProvider.DEEPSEEK && aiModel in setOf("deepseek-chat", "deepseek-reasoner")) {
                        Text("该模型名称按兼容模式保留；建议使用右侧下拉按钮获取当前可用模型。", color = MaterialTheme.colorScheme.tertiary)
                    }
                    OutlinedTextField(
                        apiKey,
                        { apiKey = it },
                        label = { Text(if (state.ai.hasApiKey) "API Key（留空则保留）" else "API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showKey) "隐藏 API Key" else "显示 API Key"
                                )
                            }
                        },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.saveAiService(provider, baseUrl, aiModel, apiKey); apiKey = "" }) { Text("保存") }
                        OutlinedButton(onClick = { model.testAiConnection(baseUrl, apiKey) }, enabled = !testing) {
                            Text(if (testing) "正在测试" else "测试连接")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiPromptsSettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    var value by remember(state.aiPrompts) { mutableStateOf(state.aiPrompts) }
    val valid = listOf(value.summary, value.organizeNotes, value.correctAsr, value.quickAnswer, value.customConversation, value.imageContext)
        .all { it.isNotBlank() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("prompt-help") {
            Text("新设置只影响后续 AI 请求；已经生成的结果与现有对话继续使用创建时冻结的提示词。", style = MaterialTheme.typography.bodyMedium)
        }
        item("prompt-summary") { PromptField("总结", value.summary) { value = value.copy(summary = it) } }
        item("prompt-notes") { PromptField("整理成笔记", value.organizeNotes) { value = value.copy(organizeNotes = it) } }
        item("prompt-correct") { PromptField("修正明显 ASR 错误", value.correctAsr) { value = value.copy(correctAsr = it) } }
        item("prompt-quick") { PromptField("快速回答", value.quickAnswer) { value = value.copy(quickAnswer = it) } }
        item("prompt-chat") { PromptField("自定义对话", value.customConversation) { value = value.copy(customConversation = it) } }
        item("prompt-image") { PromptField("图片补充", value.imageContext) { value = value.copy(imageContext = it) } }
        item("prompt-actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.saveAiPrompts(value) }, enabled = valid) { Text("保存") }
                OutlinedButton(onClick = model::restoreDefaultAiPrompts) { Text("恢复默认值") }
            }
        }
        if (!valid) item("prompt-error") { Text("提示词不能为空。", color = MaterialTheme.colorScheme.error) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsrPromptPolicySettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    var config by remember(state.asrPromptAutoConfig) { mutableStateOf(state.asrPromptAutoConfig) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("asr-mode") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("全局提示词模式", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AsrPromptMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.globalAsrPromptMode == mode,
                                onClick = { model.saveGlobalAsrPromptMode(mode) },
                                label = { Text(mode.displayName) }
                            )
                        }
                    }
                    Text("课程可以覆盖此设置；自动模式只给正常、较长且清晰的片段附带 Prompt。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.developerMode) item("auto-thresholds") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("自动模式质量门槛", style = MaterialTheme.typography.titleMedium)
                    MsParameter("最短音频", "片段总长度", config.minAudioDurationMs, 500..10000, 100) { config = config.copy(minAudioDurationMs = it) }
                    MsParameter("最短有效语音", "VAD 判为语音的累计时长", config.minVoicedDurationMs, 200..5000, 100) { config = config.copy(minVoicedDurationMs = it) }
                    FloatParameter("平均 VAD", "语音帧平均概率", config.minMeanSpeechProbability, 0.1f..0.9f, 0.05f) { config = config.copy(minMeanSpeechProbability = it) }
                    FloatParameter("语音帧占比", "语音帧占全部帧的比例", config.minSpeechFrameRatio, 0.05f..0.9f, 0.05f) { config = config.copy(minSpeechFrameRatio = it) }
                    FloatParameter("最低 SNR（dB）", "可估算时使用；噪声样本不足时跳过", config.minSnrDb, 0f..30f, 1f) { config = config.copy(minSnrDb = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.saveAsrPromptAutoConfig(config) }) { Text("保存门槛") }
                        OutlinedButton(onClick = model::restoreDefaultAsrPromptAutoConfig) { Text("恢复默认值") }
                    }
                }
            }
        }
    }
}

@Composable
fun AiGenerationSettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    var value by remember(state.aiGeneration) { mutableStateOf(state.aiGeneration) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("generation-settings") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("仅影响后续 AI 请求。诊断不会记录密钥或思维链。", style = MaterialTheme.typography.bodySmall)
                    IntParameter("最大输出 Tokens", value.maxTokens, 512..32768, 512) { value = value.copy(maxTokens = it) }
                    FloatParameter("固定任务温度", "总结、笔记和纠错", value.fixedTemperature, 0f..1.5f, 0.1f) { value = value.copy(fixedTemperature = it) }
                    FloatParameter("对话温度", "课堂问答和追问", value.chatTemperature, 0f..1.5f, 0.1f) { value = value.copy(chatTemperature = it) }
                    Text("DeepSeek 思考", style = MaterialTheme.typography.titleSmall)
                    AiThinkingMode.entries.forEach { mode ->
                        FilterChip(selected = value.deepSeekThinkingMode == mode, onClick = { value = value.copy(deepSeekThinkingMode = mode) }, label = { Text(mode.displayName) })
                    }
                    Text("推理强度", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiReasoningEffort.entries.forEach { effort ->
                            FilterChip(selected = value.reasoningEffort == effort, onClick = { value = value.copy(reasoningEffort = effort) }, label = { Text(effort.name.lowercase()) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.saveAiGeneration(value) }) { Text("保存") }
                        OutlinedButton(onClick = model::restoreDefaultAiGeneration) { Text("恢复默认值") }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntParameter(label: String, value: Int, range: IntRange, step: Int, change: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Slider(value.toFloat(), { change((it / step).roundToInt() * step) }, valueRange = range.first.toFloat()..range.last.toFloat())
        OutlinedTextField(
            value = text,
            onValueChange = { candidate ->
                text = candidate.filter(Char::isDigit)
                text.toIntOrNull()?.takeIf { it in range }?.let(change)
            },
            label = { Text("当前数值") },
            suffix = { Text("tokens") },
            isError = text.toIntOrNull()?.let { it !in range } ?: true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PromptField(label: String, value: String, update: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = update,
    label = { Text(label) },
    supportingText = { Text("${value.codePointCount(0, value.length)} 个字符") },
    minLines = 4,
    maxLines = 10,
    isError = value.isBlank(),
    modifier = Modifier.fillMaxWidth()
)

@Composable
fun VadParametersScreen(config: VadConfig, model: SttViewModel) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp)
) {
    item("vad-parameters") { VadParametersCard(config, model::updateVadConfig, model::restoreDefaultVadConfig) }
}

@Composable
fun VadPresetsScreen(selectedPreset: VadPreset?, model: SttViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("current-preset") { Text("当前预设：${selectedPreset?.displayName ?: "自定义"}", style = MaterialTheme.typography.titleMedium) }
        items(VadPreset.entries, key = { "preset-${it.id}" }) { preset ->
            val config = preset.config
            Card(Modifier.fillMaxWidth().clickable { model.applyVadPreset(preset) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(preset.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("阈值 ${formatFloat(config.threshold)} · 开始 ${config.startConfirmMs} ms · 静音 ${config.endSilenceMs} ms")
                    if (selectedPreset == preset) Text("当前使用", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun VadParametersCard(config: VadConfig, change: (VadConfig) -> Unit, restore: () -> Unit) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("参数会保存到本机；正在切分的片段从下一段使用新边界参数。", style = MaterialTheme.typography.bodySmall)
        FloatParameter("VAD 阈值", "判定语音的概率门槛", config.threshold, 0.1f..0.9f, 0.05f) { change(config.copy(threshold = it)) }
        MsParameter("语音开始确认", "连续疑似语音达到此时长才开始", config.startConfirmMs, 50..500, 50) { change(config.copy(startConfirmMs = it)) }
        MsParameter("结束静音阈值", "连续静音达到此时长后收段", config.endSilenceMs, 300..2000, 50) { change(config.copy(endSilenceMs = it)) }
        MsParameter("Pre-roll 前置保留", "保留语音前的环境声", config.preRollMs, 0..3000, 100) { change(config.copy(preRollMs = it)) }
        MsParameter("Post-roll 后置保留", "保留语音结束后的尾音", config.postRollMs, 0..1500, 100) { change(config.copy(postRollMs = it)) }
        MsParameter("最短语音片段", "更短的片段将丢弃", config.minSegmentMs, 200..3000, 100) { change(config.copy(minSegmentMs = it)) }
        MsParameter("Soft limit", "到达后优先等待自然停顿", config.softLimitMs, 5000..30000, 500, { it < config.hardLimitMs }) { change(config.copy(softLimitMs = it)) }
        MsParameter("Hard limit", "到达后强制切分", config.hardLimitMs, 10000..60000, 1000, { it > config.softLimitMs && config.overlapMs < it }) { change(config.copy(hardLimitMs = it)) }
        MsParameter("Hard limit overlap", "强制切分时与下一段重叠", config.overlapMs, 0..3000, 100, { it < config.hardLimitMs }) { change(config.copy(overlapMs = it)) }
        OutlinedButton(onClick = restore) { Text("恢复默认值") }
    }
}

@Composable
private fun FloatParameter(label: String, description: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, change: (Float) -> Unit) {
    var input by remember(value) { mutableStateOf(formatFloat(value)) }
    val valid = input.toFloatOrNull()?.let { it in range } == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(input, { text -> input = text; text.toFloatOrNull()?.takeIf { it in range }?.let(change) }, label = { Text("当前值") }, isError = input.isNotBlank() && !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Slider(value, { change(((it / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)) }, valueRange = range, steps = ((range.endInclusive - range.start) / step).roundToInt() - 1)
    }
}

@Composable
private fun MsParameter(label: String, description: String, value: Long, range: IntRange, step: Int, validExtra: (Long) -> Boolean = { true }, change: (Long) -> Unit) {
    var input by remember(value) { mutableStateOf(value.toString()) }
    val parsed = input.toLongOrNull()
    val valid = parsed != null && parsed in range && validExtra(parsed)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(input, { text -> input = text; text.toLongOrNull()?.takeIf { it in range && validExtra(it) }?.let(change) }, label = { Text("毫秒") }, isError = input.isNotBlank() && !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Slider(value.toFloat(), { change(((it / step).roundToInt() * step).toLong()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first) / step - 1)
        if (input.isNotBlank() && !valid) Text("请输入允许范围内且满足关联限制的数值。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)
@Composable private fun SettingsLink(title: String, description: String, click: () -> Unit) = Card(Modifier.fillMaxWidth().clickable(onClick = click)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodyMedium) } }
private fun providerName(provider: AiProvider) = if (provider == AiProvider.DEEPSEEK) "DeepSeek" else "OpenAI 通用接口"
