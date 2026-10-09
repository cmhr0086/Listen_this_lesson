package com.cmhr.listen.ui

import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cmhr.listen.ServiceCheck
import com.cmhr.listen.SettingsUiState
import com.cmhr.listen.SettingsViewModel
import com.cmhr.listen.data.settings.AiProvider
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    model: SettingsViewModel,
    onSttService: () -> Unit,
    onAiService: () -> Unit,
    onVad: () -> Unit,
    onAiPrompts: () -> Unit,
    onAsrPromptPolicy: () -> Unit,
    onAiGeneration: () -> Unit,
    onCloudSync: () -> Unit = {},
    onAsrDiagnostics: () -> Unit = {},
    onAppearance: () -> Unit = {},
    vadSummary: String = ""
) = SettingsOverview(
    state = state,
    setDeveloperMode = model::setDeveloperMode,
    setAutoNotes = model::setAutoNotes,
    onSttService = onSttService,
    onAiService = onAiService,
    onVad = onVad,
    vadSummary = vadSummary,
    onAiPrompts = onAiPrompts,
    onAsrPromptPolicy = onAsrPromptPolicy,
    onAiGeneration = onAiGeneration,
    onCloudSync = onCloudSync,
    onAsrDiagnostics = onAsrDiagnostics,
    onAppearance = onAppearance
)

@Composable
internal fun SettingsOverview(
    state: SettingsUiState,
    setDeveloperMode: (Boolean) -> Unit,
    onSttService: () -> Unit,
    onAiService: () -> Unit,
    onVad: () -> Unit,
    onAiPrompts: () -> Unit,
    onAsrPromptPolicy: () -> Unit,
    onAiGeneration: () -> Unit,
    onCloudSync: () -> Unit = {},
    onAsrDiagnostics: () -> Unit = {},
    onAppearance: () -> Unit = {},
    setAutoNotes: (Boolean) -> Unit = {},
    vadSummary: String = ""
) {
    val sttReady = state.server.hasApiKey && state.server.baseUrl.isNotBlank()
    val aiReady = state.ai.hasApiKey && state.ai.model.isNotBlank()
    val syncReady = state.cloudSync.baseUrl.isNotBlank() && state.cloudSync.hasApiToken
    LazyColumn(
        Modifier.fillMaxSize().testTag("settings-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item("services-heading") { SectionHeader("服务") }
        item("services") {
            ListGroup {
                SettingsRow(
                    "语音识别", state.server.baseUrl.ifBlank { "未设置地址" }, Icons.Outlined.Mic, onSttService,
                    status = when {
                        !sttReady -> "未配置" to StatusTone.READY
                        state.sttCheck is ServiceCheck.Failed -> "连接失败" to StatusTone.PROBLEM
                        state.sttCheck is ServiceCheck.Passed -> "已连接" to StatusTone.DONE
                        else -> "已配置" to StatusTone.DONE
                    },
                    modifier = Modifier.testTag("settings-stt")
                )
                ListDivider(62.dp)
                SettingsRow(
                    "AI 服务", "${providerName(state.ai.provider)} · ${state.ai.model.ifBlank { "未设置模型" }}", Icons.Outlined.AutoAwesome, onAiService,
                    status = when {
                        !aiReady -> "可选" to StatusTone.READY
                        state.aiCheck is ServiceCheck.Failed -> "不可用" to StatusTone.PROBLEM
                        state.aiCheck is ServiceCheck.Passed -> "可用" to StatusTone.DONE
                        else -> "已配置" to StatusTone.DONE
                    },
                    modifier = Modifier.testTag("settings-ai")
                )
                ListDivider(62.dp)
                SettingsRow(
                    "云同步",
                    when {
                        !syncReady -> "在多台设备之间同步课堂文字"
                        state.cloudSync.lastSyncAt > 0 -> "上次同步 ${formatSyncTime(state.cloudSync.lastSyncAt)}"
                        else -> "已配置，尚未同步"
                    },
                    Icons.Outlined.Cloud, onCloudSync,
                    status = if (syncReady) null else "可选" to StatusTone.READY,
                    modifier = Modifier.testTag("settings-sync")
                )
            }
        }
        item("use-heading") { SectionHeader("使用", Modifier.padding(top = 8.dp)) }
        item("use") {
            ListGroup {
                SettingsRow("外观", "${state.appearance.palette.label} · ${state.appearance.darkMode.label}", Icons.Outlined.Palette, onAppearance)
                ListDivider(62.dp)
                SettingsRow("专业词提示", "全局：${state.globalAsrPromptMode.displayName}", Icons.Outlined.Translate, onAsrPromptPolicy)
                ListDivider(62.dp)
                SettingsSwitchRow(
                    "下课后自动整理笔记",
                    if (aiReady) "归档或识别完成后，用 AI 把整节课整理成笔记" else "需要先配置 AI 服务",
                    Icons.Outlined.Description,
                    checked = state.autoNotes,
                    onChange = setAutoNotes,
                    modifier = Modifier.testTag("settings-auto-notes")
                )
            }
        }
        item("dev-heading") { SectionHeader("开发者", Modifier.padding(top = 8.dp)) }
        item("dev") {
            ListGroup {
                SettingsSwitchRow(
                    "开发者模式", "显示 VAD、ASR 诊断和 AI 参数", Icons.Outlined.Code,
                    checked = state.developerMode, onChange = setDeveloperMode
                )
                if (state.developerMode) {
                    ListDivider(62.dp)
                    SettingsRow("ASR 诊断", "队列、异步任务和网络阶段", Icons.Outlined.MonitorHeart, onAsrDiagnostics)
                    ListDivider(62.dp)
                    SettingsRow("VAD 预设与参数", vadSummary.ifBlank { "阈值、静音、前后保留和切分" }, Icons.Outlined.Tune, onVad, modifier = Modifier.testTag("settings-vad"))
                    ListDivider(62.dp)
                    SettingsRow("AI 提示词", "笔记、纠错、回答、对话和图片场景", Icons.Outlined.Description, onAiPrompts)
                    ListDivider(62.dp)
                    SettingsRow("AI 生成参数", "输出长度、温度、思考模式与推理强度", Icons.Outlined.Tune, onAiGeneration)
                }
            }
        }
        item("version") {
            Text(
                "听这节课 ${com.cmhr.listen.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
        }
    }
}

@Composable
private fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun SettingsRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    click: () -> Unit,
    status: Pair<String, StatusTone>? = null,
    modifier: Modifier = Modifier
) = ListRow(
    title = title,
    subtitle = description,
    leading = { SettingsIcon(icon) },
    status = status?.let { (text, tone) -> { StatusPill(text, tone) } },
    onClick = click,
    modifier = modifier
)

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) = ListRow(
    title = title,
    subtitle = description,
    leading = { SettingsIcon(icon) },
    trailing = { Switch(checked, onChange) },
    onClick = { onChange(!checked) },
    modifier = modifier
)

internal fun formatSyncTime(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(timestampMs))

private fun providerName(provider: AiProvider) = if (provider == AiProvider.DEEPSEEK) "DeepSeek" else "其他服务"
