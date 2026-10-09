package com.cmhr.listen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cmhr.listen.AiPromptKind
import com.cmhr.listen.CloudSyncRunState
import com.cmhr.listen.ServiceCheck
import com.cmhr.listen.SettingsUiState
import com.cmhr.listen.SettingsViewModel
import com.cmhr.listen.SttViewModel
import com.cmhr.listen.audio.VadConfig
import com.cmhr.listen.audio.VadPreset
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.settings.AiGenerationSettings
import com.cmhr.listen.data.settings.AiPromptSettings
import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AiReasoningEffort
import com.cmhr.listen.data.settings.AiThinkingMode
import com.cmhr.listen.data.stt.AsrPromptAutoConfig
import com.cmhr.listen.data.stt.AsrPromptMode
import com.cmhr.listen.ui.theme.DarkModePreference
import com.cmhr.listen.ui.theme.ThemePalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Settings sub-pages. One rule for all of them: switches, choices and sliders take effect as soon
 * as they change; text values are edited in a bottom sheet and saved (then tested) on confirm.
 * Only the long prompt editor keeps an explicit 保存.
 */

// ---------- Shared pieces ----------

internal enum class CardTone { OK, PROBLEM, NEUTRAL, BUSY }

@Composable
private fun okColors(): Pair<Color, Color> =
    if (isDarkSurface()) Color(0xFF1E3A28) to Color(0xFF9BD5AE) else Color(0xFFE2F0E6) to Color(0xFF2F7448)

/** The top card of a service page: what state it is in, when that was last checked, and one action. */
@Composable
internal fun StatusCard(
    tone: CardTone,
    icon: ImageVector,
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) = GroupCard(modifier.fillMaxWidth().testTag("status-card")) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val (bg, fg) = when (tone) {
                CardTone.OK -> okColors()
                CardTone.PROBLEM -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                CardTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
                CardTone.BUSY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) {
                if (tone == CardTone.BUSY) CircularProgressIndicator(Modifier.size(24.dp), color = fg, strokeWidth = 2.5.dp)
                else Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("status-title"))
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("status-detail"))
                }
            }
            action?.invoke()
        }
        content?.invoke(this)
    }
}

@Composable
private fun SettingsPage(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    content = content
)

@Composable
private fun Note(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier.padding(horizontal = 4.dp))

/** A setting shown as its current value; tapping edits it. */
@Composable
private fun ValueRow(title: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) = ListRow(
    title = title,
    subtitle = subtitle,
    trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            value?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)
                )
            }
            ChevronIcon()
        }
    },
    onClick = onClick,
    modifier = modifier
)


private const val SECRET_DOTS = "••••••"

@Composable
private fun DangerRow(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) = ListGroup(modifier.padding(top = 12.dp)) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, dismiss: () -> Unit) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = { onConfirm(); dismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
    dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
)

/** A radio row with a one-line explanation of what the choice does. */
@Composable
private fun RadioRow(title: String, description: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                badge?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Bottom sheet for one text value. Secrets start empty and are never shown back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextEditSheet(
    title: String,
    description: String,
    label: String,
    initial: String,
    secret: Boolean,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    dismiss: () -> Unit,
    placeholder: String? = null,
    hint: String? = null,
    keyboardType: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var value by rememberSaveable { mutableStateOf(initial) }
    var reveal by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState, modifier = Modifier.testTag("edit-sheet")) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                placeholder = placeholder?.let { { Text(it) } },
                supportingText = hint?.let { { Text(it) } },
                singleLine = true,
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                trailingIcon = if (secret) {
                    {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (reveal) "隐藏" else "显示")
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("edit-sheet-field")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                Button(
                    onClick = { onConfirm(value.trim()); dismiss() },
                    enabled = value.isNotBlank() && value.trim() != initial.trim(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("edit-sheet-confirm")
                ) { Text(confirmLabel) }
            }
        }
    }
}

/** 「今天 11:42」, 「刚刚」, 「10月8日 09:30」. */
internal fun checkTime(at: Long, now: Long = System.currentTimeMillis()): String {
    if (now - at in 0 until 60_000) return "刚刚"
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = day.get(Calendar.YEAR) == today.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(at))
    return if (sameDay) "今天 $time" else SimpleDateFormat("M月d日 ", Locale.CHINA).format(Date(at)) + time
}

@Composable
private fun TestButton(testing: Boolean, label: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, enabled = !testing, modifier = Modifier.testTag("status-test")) { Text(if (testing) "测试中" else label) }

private enum class EditTarget { URL, KEY }

// ---------- 语音识别 ----------

@Composable
fun SttServiceSettingsScreen(state: SettingsUiState, model: SettingsViewModel, openAsrPrompt: () -> Unit = {}) {
    var editing by rememberSaveable { mutableStateOf<EditTarget?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.checkSttIfUnknown() }
    val server = state.server
    val check = state.sttCheck
    SettingsPage {
        item("status") {
            when {
                server.baseUrl.isBlank() -> StatusCard(CardTone.NEUTRAL, Icons.Outlined.Settings, "还没设置", "填好服务器地址和 API Key 后就能实时识别。")
                check is ServiceCheck.Testing -> StatusCard(CardTone.BUSY, Icons.Outlined.Sync, "正在测试…", "连接服务器并检查 API Key")
                check is ServiceCheck.Passed -> StatusCard(
                    CardTone.OK, Icons.Outlined.Check, "已连接，可以识别",
                    "${checkTime(check.at)} 测试 · API Key 有效 · ${check.latencyMs} ms",
                    action = { TestButton(false, "重新测试", model::checkStt) }
                )
                check is ServiceCheck.Failed -> StatusCard(
                    CardTone.PROBLEM, Icons.Outlined.ErrorOutline, "连接失败",
                    "${checkTime(check.at)} · ${check.message}",
                    action = { TestButton(false, "重试", model::checkStt) }
                )
                else -> StatusCard(CardTone.NEUTRAL, Icons.Outlined.Settings, "已配置", "还没测试", action = { TestButton(false, "测试", model::checkStt) })
            }
        }
        item("server-heading") { SectionHeader("服务器", Modifier.padding(top = 12.dp)) }
        item("server") {
            ListGroup {
                ValueRow("地址", null, { editing = EditTarget.URL }, Modifier.testTag("stt-url"), subtitle = server.baseUrl.ifBlank { "未填写" })
                ListDivider(16.dp)
                ValueRow(
                    "API Key", if (server.hasApiKey) SECRET_DOTS else "未填写", { editing = EditTarget.KEY }, Modifier.testTag("stt-key"),
                    subtitle = if (server.hasApiKey) "已加密保存在本机" else null
                )
            }
        }
        item("server-note") { Note("改完地址或 Key 会立即保存，并自动测试一次。") }
        item("related-heading") { SectionHeader("相关", Modifier.padding(top = 12.dp)) }
        item("related") {
            ListGroup { ValueRow("专业词提示", state.globalAsrPromptMode.displayName, openAsrPrompt) }
        }
        if (server.hasApiKey) item("clear") { DangerRow("清除 API Key", null, { confirmClear = true }, Modifier.testTag("stt-clear-key")) }
    }
    when (editing) {
        EditTarget.URL -> TextEditSheet(
            title = "服务器地址", description = "识别服务的地址，以 http:// 或 https:// 开头。", label = "地址",
            initial = server.baseUrl, secret = false, confirmLabel = "保存并测试", placeholder = "https://asr.example.com",
            onConfirm = model::saveSttUrl, dismiss = { editing = null }
        )
        EditTarget.KEY -> TextEditSheet(
            title = "API Key", description = "粘贴识别服务给你的 Key。只保存在本机，不会同步到其他设备。", label = "新的 API Key",
            initial = "", secret = true, confirmLabel = "保存并测试",
            hint = "保存后会马上用新 Key 测试一次。",
            onConfirm = model::saveSttKey, dismiss = { editing = null }
        )
        null -> Unit
    }
    if (confirmClear) ConfirmDialog("清除 API Key？", "清除后无法识别，直到重新填写。", "清除", model::clearApiKey) { confirmClear = false }
}

// ---------- AI 服务 ----------

@Composable
fun AiServiceSettingsScreen(state: SettingsUiState, model: SettingsViewModel, openModels: () -> Unit = {}) {
    var editing by rememberSaveable { mutableStateOf<EditTarget?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.checkAiIfUnknown() }
    val ai = state.ai
    val check = state.aiCheck
    SettingsPage {
        item("status") {
            when {
                !ai.hasApiKey -> StatusCard(CardTone.NEUTRAL, Icons.Outlined.Settings, "还没设置", "AI 用来整理笔记、修正文字和回答问题；不设置也能正常录音识别。")
                check is ServiceCheck.Testing -> StatusCard(CardTone.BUSY, Icons.Outlined.Sync, "正在测试…", null)
                check is ServiceCheck.Passed -> StatusCard(
                    CardTone.OK, Icons.Outlined.Check, if (ai.model.isBlank()) "已连接，请选模型" else "可用",
                    "${checkTime(check.at)} 测试通过 · ${check.latencyMs} ms",
                    action = { TestButton(false, "测试", model::checkAi) }
                )
                check is ServiceCheck.Failed -> StatusCard(
                    CardTone.PROBLEM, Icons.Outlined.ErrorOutline, "不可用", "${checkTime(check.at)} · ${check.message}",
                    action = { TestButton(false, "重试", model::checkAi) }
                )
                else -> StatusCard(CardTone.NEUTRAL, Icons.Outlined.Settings, "已配置", "还没测试", action = { TestButton(false, "测试", model::checkAi) })
            }
        }
        item("provider-heading") { SectionHeader("服务商", Modifier.padding(top = 12.dp)) }
        item("provider") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderCard("DeepSeek", "地址自动填好", ai.provider == AiProvider.DEEPSEEK, { model.chooseAiProvider(AiProvider.DEEPSEEK) }, Modifier.weight(1f).testTag("provider-deepseek"))
                ProviderCard("其他服务", "兼容 OpenAI 接口，需填地址", ai.provider == AiProvider.OPENAI_COMPATIBLE, { model.chooseAiProvider(AiProvider.OPENAI_COMPATIBLE) }, Modifier.weight(1f).testTag("provider-openai"))
            }
        }
        item("config-heading") { SectionHeader("配置", Modifier.padding(top = 12.dp)) }
        item("config") {
            ListGroup {
                if (ai.provider == AiProvider.OPENAI_COMPATIBLE) {
                    ValueRow("地址", null, { editing = EditTarget.URL }, Modifier.testTag("ai-url"), subtitle = ai.baseUrl.ifBlank { "未填写" })
                    ListDivider(16.dp)
                }
                ValueRow("模型", ai.model.ifBlank { "未选择" }, openModels, Modifier.testTag("ai-model"))
                ListDivider(16.dp)
                ValueRow("API Key", if (ai.hasApiKey) SECRET_DOTS else "未填写", { editing = EditTarget.KEY }, Modifier.testTag("ai-key"))
            }
        }
        item("config-note") {
            Note(
                if (ai.provider == AiProvider.DEEPSEEK) "使用 DeepSeek 官方地址 api.deepseek.com。改动会立即保存并自动测试。"
                else "地址必须使用 HTTPS。改动会立即保存并自动测试。"
            )
        }
        item("use-heading") { SectionHeader("用在哪里", Modifier.padding(top = 12.dp)) }
        item("use") {
            ListGroup {
                ListRow(
                    "下课后自动整理笔记", subtitle = "归档或识别完成后生成整节课笔记",
                    trailing = { Switch(state.autoNotes, model::setAutoNotes) },
                    onClick = { model.setAutoNotes(!state.autoNotes) }
                )
            }
        }
        if (ai.hasApiKey) item("clear") { DangerRow("清除 API Key", null, { confirmClear = true }) }
    }
    when (editing) {
        EditTarget.URL -> TextEditSheet(
            title = "服务地址", description = "兼容 OpenAI 接口的地址，通常以 /v1 结尾。", label = "地址",
            initial = ai.baseUrl, secret = false, confirmLabel = "保存并测试", placeholder = "https://api.example.com/v1",
            onConfirm = model::saveAiBaseUrl, dismiss = { editing = null }
        )
        EditTarget.KEY -> TextEditSheet(
            title = "API Key", description = "只保存在本机，不会同步到其他设备。", label = "新的 API Key",
            initial = "", secret = true, confirmLabel = "保存并测试",
            onConfirm = model::saveAiKey, dismiss = { editing = null }
        )
        null -> Unit
    }
    if (confirmClear) ConfirmDialog("清除 API Key？", "清除后 AI 整理和问答都不能用，直到重新填写。", "清除", model::clearAiApiKey) { confirmClear = false }
}

@Composable
private fun ProviderCard(title: String, description: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.heightIn(min = 76.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else groupColor(),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else groupBorder()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val LEGACY_DEEPSEEK_MODELS = setOf("deepseek-chat", "deepseek-reasoner")

@Composable
fun AiModelsScreen(state: SettingsUiState, model: SettingsViewModel, done: () -> Unit) {
    var manual by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.availableAiModels.isEmpty() && state.ai.hasApiKey) model.fetchAiModels() }
    val provider = if (state.ai.provider == AiProvider.DEEPSEEK) "DeepSeek" else "服务"
    val options = (state.availableAiModels + listOfNotNull(state.ai.model.takeIf { it.isNotBlank() })).distinct()
    SettingsPage {
        item("caption") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        !state.ai.hasApiKey -> "先填 API Key 才能获取模型列表。"
                        state.isLoadingAiModels -> "正在从 $provider 获取模型…"
                        state.aiModelsError != null -> "获取失败：${state.aiModelsError}"
                        else -> "从 $provider 获取到 ${state.availableAiModels.size} 个模型"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.aiModelsError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                if (state.ai.hasApiKey) IconButton(onClick = model::fetchAiModels, enabled = !state.isLoadingAiModels) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重新获取模型列表")
                }
            }
        }
        if (options.isNotEmpty()) item("models") {
            ListGroup(Modifier.testTag("ai-model-list")) {
                options.forEachIndexed { index, name ->
                    if (index > 0) ListDivider(56.dp)
                    RadioRow(
                        title = name,
                        description = if (state.ai.provider == AiProvider.DEEPSEEK && name in LEGACY_DEEPSEEK_MODELS) "旧名称，按兼容模式保留" else null,
                        selected = name == state.ai.model,
                        onClick = { if (name != state.ai.model) model.saveAiModel(name); done() },
                        badge = if (name == state.ai.model) "当前" else null
                    )
                }
            }
        }
        item("manual-heading") { SectionHeader("列表里没有？", Modifier.padding(top = 12.dp)) }
        item("manual") {
            ListGroup { ListRow("手动输入模型名称", leading = { Icon(Icons.Outlined.Edit, contentDescription = null) }, onClick = { manual = true }) }
        }
        item("note") { Note("选中即生效，并会自动测试一次。") }
    }
    if (manual) TextEditSheet(
        title = "模型名称", description = "按服务商文档填写，例如 $DEFAULT_MODEL_EXAMPLE。", label = "模型名称",
        initial = state.ai.model, secret = false, confirmLabel = "使用", keyboardType = KeyboardType.Ascii,
        onConfirm = { model.saveAiModel(it); done() }, dismiss = { manual = false }
    )
}

private const val DEFAULT_MODEL_EXAMPLE = com.cmhr.listen.DEEPSEEK_DEFAULT_MODEL

// ---------- 云同步 ----------

@Composable
fun CloudSyncSettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    var editing by rememberSaveable { mutableStateOf<EditTarget?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    val sync = state.cloudSync
    val configured = sync.baseUrl.isNotBlank() && sync.hasApiToken
    val run = state.cloudSyncState
    SettingsPage {
        item("status") {
            val syncButton: @Composable () -> Unit = {
                Button(
                    onClick = model::syncNow,
                    enabled = configured && run !is CloudSyncRunState.Syncing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync-now")
                ) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("立即同步", Modifier.padding(start = 8.dp))
                }
            }
            when {
                !configured -> StatusCard(
                    CardTone.NEUTRAL, Icons.Outlined.CloudOff, "还没设置",
                    "填好同步服务器地址和 Token 后，可以在多台设备之间同步课堂。"
                )
                run is CloudSyncRunState.Syncing -> StatusCard(
                    CardTone.BUSY, Icons.Outlined.Sync, "正在同步…",
                    state.cloudSyncProgress?.let { "已上传 ${it.completedSessions} / ${it.totalSessions} 节课" } ?: "正在准备本机改动"
                )
                run is CloudSyncRunState.Error -> StatusCard(CardTone.PROBLEM, Icons.Outlined.ErrorOutline, "同步失败", run.message) { syncButton() }
                run is CloudSyncRunState.Success -> StatusCard(
                    CardTone.OK, Icons.Outlined.CloudDone, "已同步", if (sync.lastSyncAt > 0) checkTime(sync.lastSyncAt) else null
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncTile("上传", "${run.summary.uploadedSessions} 节课 · ${run.summary.uploadedSegments} 段", Modifier.weight(1f))
                        SyncTile("收到", "${run.summary.receivedSessions} 节课 · ${run.summary.receivedSegments} 段", Modifier.weight(1f))
                    }
                    syncButton()
                }
                sync.lastSyncAt > 0 -> StatusCard(CardTone.OK, Icons.Outlined.CloudDone, "已同步", "上次 ${checkTime(sync.lastSyncAt)}") { syncButton() }
                else -> StatusCard(CardTone.NEUTRAL, Icons.Outlined.CloudOff, "已配置", "还没同步过") { syncButton() }
            }
        }
        item("server-heading") { SectionHeader("服务器", Modifier.padding(top = 12.dp)) }
        item("server") {
            ListGroup {
                ValueRow("地址", null, { editing = EditTarget.URL }, Modifier.testTag("sync-url"), subtitle = sync.baseUrl.ifBlank { "未填写" })
                ListDivider(16.dp)
                ValueRow("Token", if (sync.hasApiToken) SECRET_DOTS else "未填写", { editing = EditTarget.KEY }, Modifier.testTag("sync-token"))
            }
        }
        item("note") { Note("同步课程、课堂、文字、主题和重点标记。录音文件只留在本机。") }
        if (sync.hasApiToken) item("stop") {
            DangerRow("停用云同步", "清除本机保存的 Token；服务器上的数据不受影响", { confirmStop = true })
        }
    }
    when (editing) {
        EditTarget.URL -> TextEditSheet(
            title = "同步服务器地址", description = "必须使用 HTTPS。", label = "地址",
            initial = sync.baseUrl, secret = false, confirmLabel = "保存", placeholder = "https://sync.example.com",
            onConfirm = model::saveCloudSyncUrl, dismiss = { editing = null }
        )
        EditTarget.KEY -> TextEditSheet(
            title = "Token", description = "同步服务器的访问 Token，只保存在本机。", label = "新的 Token",
            initial = "", secret = true, confirmLabel = "保存",
            onConfirm = model::saveCloudSyncToken, dismiss = { editing = null }
        )
        null -> Unit
    }
    if (confirmStop) ConfirmDialog("停用云同步？", "会清除本机的 Token，服务器上的数据不受影响。", "停用", model::clearCloudSyncApiToken) { confirmStop = false }
}

@Composable
private fun SyncTile(label: String, value: String, modifier: Modifier = Modifier) = Column(
    modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp)
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.titleSmall)
}

// ---------- 专业词提示 ----------

internal fun asrTermCount(prompt: String) = prompt.split(Regex("[，,、；;\\s]+")).count { it.isNotBlank() }

private fun AsrPromptMode.explanation() = when (this) {
    AsrPromptMode.OFF -> "从不附带术语"
    AsrPromptMode.AUTO -> "只在声音清楚、片段够长时附带，避免术语串进噪声里"
    AsrPromptMode.ALWAYS -> "每段都附带；教室嘈杂时可能识别出没说过的术语"
}

@Composable
fun AsrPromptPolicySettingsScreen(
    state: SettingsUiState,
    model: SettingsViewModel,
    courses: List<CourseEntity> = emptyList(),
    saveCourse: (id: Long, prompt: String, mode: String?) -> Unit = { _, _, _ -> },
    openThresholds: () -> Unit = {}
) {
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    SettingsPage {
        item("intro") {
            Text(
                "把每门课填写的术语一起交给识别服务，人名、公式名这类词会认得更准。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        item("mode-heading") { SectionHeader("全局方式", Modifier.padding(top = 4.dp)) }
        item("modes") {
            ListGroup(Modifier.testTag("global-asr-prompt-modes")) {
                AsrPromptMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) ListDivider(56.dp)
                    RadioRow(
                        mode.displayName, mode.explanation(), state.globalAsrPromptMode == mode,
                        { model.saveGlobalAsrPromptMode(mode) },
                        Modifier.testTag("global-asr-prompt-${mode.name.lowercase()}"),
                        badge = if (mode == AsrPromptMode.AUTO) "推荐" else null
                    )
                }
            }
        }
        item("courses-heading") { SectionHeader("各门课", Modifier.padding(top = 12.dp)) }
        if (courses.isEmpty()) item("courses-empty") { Note("还没有课程。新建课程后，可以在这里填写它的专业词。") }
        else item("courses") {
            ListGroup {
                courses.forEachIndexed { index, course ->
                    if (index > 0) ListDivider(60.dp)
                    val terms = asrTermCount(course.asrPrompt)
                    val mode = course.asrPromptModeOverride?.let { runCatching { AsrPromptMode.valueOf(it).displayName }.getOrNull() }
                    ListRow(
                        title = course.name,
                        subtitle = if (terms == 0) "还没填术语" else "$terms 个术语 · ${mode ?: "跟随全局"}",
                        leading = { CourseBadge(course.id, course.name, 32.dp) },
                        onClick = { editingId = course.id },
                        modifier = Modifier.testTag("asr-course-${course.id}")
                    )
                }
            }
        }
        if (state.developerMode) item("thresholds") {
            ListGroup(Modifier.padding(top = 12.dp)) {
                ListRow("自动模式门槛", subtitle = "开发者 · 时长、VAD 概率、信噪比", onClick = openThresholds, modifier = Modifier.testTag("asr-thresholds"))
            }
        }
    }
    courses.firstOrNull { it.id == editingId }?.let { course ->
        var prompt by remember(course.id) { mutableStateOf(course.asrPrompt) }
        var mode by remember(course.id) { mutableStateOf(course.asrPromptModeOverride) }
        AsrPromptDialog(
            prompt = prompt, update = { prompt = it }, modeOverride = mode, updateMode = { mode = it },
            save = { saveCourse(course.id, prompt, mode); editingId = null },
            dismiss = { editingId = null }
        )
    }
}

@Composable
fun AsrPromptThresholdsScreen(state: SettingsUiState, model: SettingsViewModel) {
    val config = state.asrPromptAutoConfig
    val defaults = AsrPromptAutoConfig()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    fun save(value: AsrPromptAutoConfig) = model.saveAsrPromptAutoConfig(value)
    SettingsPage {
        item("note") { Note("自动模式只给满足全部门槛的片段附带术语。改了即生效。") }
        item("params") {
            ListGroup {
                NumberParam("min-audio", "最短音频", "片段总长度", config.minAudioDurationMs.toFloat(), defaults.minAudioDurationMs.toFloat(), 500f..10_000f, 100f, ::formatMs, expanded, { expanded = it }) { save(config.copy(minAudioDurationMs = it.roundToLong())) }
                ListDivider(16.dp)
                NumberParam("min-voiced", "最短有效语音", "VAD 判为语音的累计时长", config.minVoicedDurationMs.toFloat(), defaults.minVoicedDurationMs.toFloat(), 200f..5_000f, 100f, ::formatMs, expanded, { expanded = it }) { save(config.copy(minVoicedDurationMs = it.roundToLong())) }
                ListDivider(16.dp)
                NumberParam("mean-vad", "平均语音概率", "语音帧的平均 VAD 概率", config.minMeanSpeechProbability, defaults.minMeanSpeechProbability, 0.1f..0.9f, 0.05f, ::formatFraction, expanded, { expanded = it }) { save(config.copy(minMeanSpeechProbability = it)) }
                ListDivider(16.dp)
                NumberParam("speech-ratio", "语音帧占比", "语音帧占全部帧的比例", config.minSpeechFrameRatio, defaults.minSpeechFrameRatio, 0.05f..0.9f, 0.05f, ::formatFraction, expanded, { expanded = it }) { save(config.copy(minSpeechFrameRatio = it)) }
                ListDivider(16.dp)
                NumberParam("snr", "最低信噪比", "可估算时使用；噪声样本不足时跳过", config.minSnrDb, defaults.minSnrDb, 0f..30f, 1f, { "${it.roundToInt()} dB" }, expanded, { expanded = it }) { save(config.copy(minSnrDb = it)) }
            }
        }
        item("restore") { TextButton(onClick = model::restoreDefaultAsrPromptAutoConfig) { Text("恢复默认") } }
    }
}

private fun Float.roundToLong(): Long = kotlin.math.round(this).toLong()
internal fun formatMs(value: Float): String = if (value >= 10_000f && value % 1000f == 0f) "${(value / 1000f).roundToInt()} s" else "${value.roundToInt()} ms"
internal fun formatFraction(value: Float): String = String.format(Locale.US, "%.2f", value)

/**
 * One numeric parameter as a compact 「名称 · 数值」 row; tapping opens a slider with −/+ steps.
 * The slider saves when released, the steps save at once.
 */
@Composable
private fun NumberParam(
    key: String,
    label: String,
    description: String,
    value: Float,
    reference: Float?,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    expandedKey: String?,
    setExpanded: (String?) -> Unit,
    referenceLabel: String = "默认",
    change: (Float) -> Unit
) {
    val expanded = expandedKey == key
    val modified = reference != null && abs(reference - value) > step / 10f
    fun snap(v: Float) = (((v - range.start) / step).roundToInt() * step + range.start).coerceIn(range.start, range.endInclusive)
    Column(
        Modifier.fillMaxWidth()
            .then(if (expanded) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)) else Modifier)
            .testTag("param-$key")
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { setExpanded(if (expanded) null else key) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (expanded) FontWeight.Medium else null, modifier = Modifier.weight(1f))
            if (modified) Text("已改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            Text(
                format(value), style = MaterialTheme.typography.bodyLarge,
                color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("param-$key-value")
            )
        }
        if (expanded) {
            var dragging by remember(value) { mutableFloatStateOf(value) }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedIconButton(onClick = { change(snap(value - step)) }, enabled = value > range.start, modifier = Modifier.testTag("param-$key-minus")) {
                        Icon(Icons.Outlined.Remove, contentDescription = "减少")
                    }
                    Slider(
                        value = dragging,
                        onValueChange = { dragging = snap(it) },
                        onValueChangeFinished = { if (dragging != value) change(dragging) },
                        valueRange = range,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedIconButton(onClick = { change(snap(value + step)) }, enabled = value < range.endInclusive, modifier = Modifier.testTag("param-$key-plus")) {
                        Icon(Icons.Outlined.Add, contentDescription = "增加")
                    }
                }
                Text(
                    (reference?.let { "$referenceLabel ${format(it)} · " } ?: "") + "范围 ${format(range.start)}–${format(range.endInclusive)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- 外观 ----------

@Composable
fun AppearanceSettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    val palettes = ThemePalette.entries.filter { it != ThemePalette.DYNAMIC || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S }
    SettingsPage {
        item("preview") {
            GroupCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CourseBadge(1, "高等数学", 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text("高等数学 · 第 8 节", style = MaterialTheme.typography.titleMedium)
                            Text("预览 · 笔记已生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Text("实时转写", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                        }
                        Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Text("标记重点", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        item("palette-heading") { SectionHeader("主题色", Modifier.padding(top = 12.dp)) }
        item("palettes") {
            ListGroup {
                palettes.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                        row.forEach { palette ->
                            val selected = state.appearance.palette == palette
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                    .selectable(selected, role = Role.RadioButton) { model.setThemePalette(palette) }
                                    .padding(vertical = 8.dp)
                                    .testTag("palette-${palette.name.lowercase()}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    Modifier.size(48.dp)
                                        .then(if (selected) Modifier.border(2.dp, palette.swatch, CircleShape).padding(4.dp) else Modifier.padding(4.dp))
                                        .clip(CircleShape).background(palette.swatch),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                                Text(
                                    if (palette == ThemePalette.DYNAMIC) "跟随壁纸" else palette.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item("palette-note") { Note("待识别、出错这类状态色和课程颜色不随主题变化。") }
        item("dark-heading") { SectionHeader("深色模式", Modifier.padding(top = 12.dp)) }
        item("dark") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DarkModePreference.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.appearance.darkMode == mode,
                        onClick = { model.setDarkMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, DarkModePreference.entries.size),
                        modifier = Modifier.testTag("dark-mode-${mode.name.lowercase()}")
                    ) { Text(mode.label) }
                }
            }
        }
    }
}

// ---------- VAD 预设与参数（开发者） ----------

private fun VadConfig.differences(other: VadConfig): Int = listOf(
    threshold != other.threshold, startConfirmMs != other.startConfirmMs, endSilenceMs != other.endSilenceMs,
    preRollMs != other.preRollMs, postRollMs != other.postRollMs, minSegmentMs != other.minSegmentMs,
    softLimitMs != other.softLimitMs, hardLimitMs != other.hardLimitMs, overlapMs != other.overlapMs
).count { it }

/** The preset a custom config was most likely tuned from: the one it differs from least. */
internal fun nearestVadPreset(config: VadConfig): VadPreset =
    VadPreset.entries.minBy { config.differences(it.config.validated()) }

@Composable
fun VadSettingsScreen(config: VadConfig, selectedPreset: VadPreset?, model: SttViewModel) {
    val base = selectedPreset ?: nearestVadPreset(config)
    val ref = base.config.validated()
    val changed = config.differences(ref)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    fun update(value: VadConfig) = model.updateVadConfig(value)
    SettingsPage {
        item("presets") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VadPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = base == preset,
                        onClick = { model.applyVadPreset(preset) },
                        label = { Text(preset.displayName) },
                        modifier = Modifier.testTag("vad-preset-${preset.id}")
                    )
                }
            }
        }
        item("summary") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (changed == 0) "使用「${base.displayName}」预设 · 从下一段开始生效"
                    else "已在「${base.displayName}」上微调 $changed 项 · 从下一段开始生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (changed == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("vad-summary")
                )
                if (changed > 0) TextButton(onClick = { model.applyVadPreset(base) }, modifier = Modifier.testTag("vad-restore")) { Text("恢复预设") }
            }
        }
        item("detect-heading") { SectionHeader("判定") }
        item("detect") {
            ListGroup {
                NumberParam("threshold", "语音阈值", "判定为语音的概率门槛；教室噪声大就调高", config.threshold, ref.threshold, 0.1f..0.9f, 0.05f, ::formatFraction, expanded, { expanded = it }, "预设值") { update(config.copy(threshold = it)) }
                ListDivider(16.dp)
                NumberParam("start", "开始确认", "连续疑似语音达到此时长才开始一段", config.startConfirmMs.toFloat(), ref.startConfirmMs.toFloat(), 50f..500f, 50f, ::formatMs, expanded, { expanded = it }, "预设值") { update(config.copy(startConfirmMs = it.roundToLong())) }
                ListDivider(16.dp)
                NumberParam("silence", "结束静音", "连续静音这么久才收段；老师停顿多就调大", config.endSilenceMs.toFloat(), ref.endSilenceMs.toFloat(), 300f..2_000f, 50f, ::formatMs, expanded, { expanded = it }, "预设值") { update(config.copy(endSilenceMs = it.roundToLong())) }
            }
        }
        item("keep-heading") { SectionHeader("保留", Modifier.padding(top = 8.dp)) }
        item("keep") {
            ListGroup {
                NumberParam("preroll", "前置保留", "保留语音开始前的声音，避免吞掉第一个字", config.preRollMs.toFloat(), ref.preRollMs.toFloat(), 0f..3_000f, 100f, ::formatMs, expanded, { expanded = it }, "预设值") { update(config.copy(preRollMs = it.roundToLong())) }
                ListDivider(16.dp)
                NumberParam("postroll", "后置保留", "保留语音结束后的尾音", config.postRollMs.toFloat(), ref.postRollMs.toFloat(), 0f..1_500f, 100f, ::formatMs, expanded, { expanded = it }, "预设值") { update(config.copy(postRollMs = it.roundToLong())) }
                ListDivider(16.dp)
                NumberParam("minseg", "最短片段", "更短的片段直接丢弃", config.minSegmentMs.toFloat(), ref.minSegmentMs.toFloat(), 200f..3_000f, 100f, ::formatMs, expanded, { expanded = it }, "预设值") { update(config.copy(minSegmentMs = it.roundToLong())) }
            }
        }
        item("split-heading") { SectionHeader("切分", Modifier.padding(top = 8.dp)) }
        item("split") {
            ListGroup {
                NumberParam("soft", "软上限 · 等停顿", "到达后在下一个自然停顿处切分；须小于硬上限", config.softLimitMs.toFloat(), ref.softLimitMs.toFloat(), 5_000f..30_000f, 500f, ::formatMs, expanded, { expanded = it }, "预设值") { update(config.copy(softLimitMs = it.roundToLong())) }
                ListDivider(16.dp)
                NumberParam("hard", "硬上限 · 强制切", "到达后强制切分", config.hardLimitMs.toFloat(), ref.hardLimitMs.toFloat(), 10_000f..60_000f, 1_000f, ::formatMs, expanded, { expanded = it }, "预设值") { update(config.copy(hardLimitMs = it.roundToLong())) }
                ListDivider(16.dp)
                NumberParam("overlap", "强制切分重叠", "强制切分时与下一段重叠，避免切断词语", config.overlapMs.toFloat(), ref.overlapMs.toFloat(), 0f..3_000f, 100f, ::formatMs, expanded, { expanded = it }, "预设值") { update(config.copy(overlapMs = it.roundToLong())) }
            }
        }
    }
}

// ---------- AI 提示词（开发者） ----------

@Composable
fun AiPromptsSettingsScreen(state: SettingsUiState, model: SettingsViewModel, open: (AiPromptKind) -> Unit = {}) {
    var confirmRestore by remember { mutableStateOf(false) }
    val groups = listOf(
        "课堂" to listOf(AiPromptKind.NOTES, AiPromptKind.CORRECT, AiPromptKind.QUICK, AiPromptKind.CLASS_CHAT, AiPromptKind.IMAGE),
        "其他" to listOf(AiPromptKind.GENERAL)
    )
    SettingsPage {
        item("note") { Note("只影响之后的请求。已生成的笔记和进行中的对话继续用当时的提示词。") }
        groups.forEach { (title, kinds) ->
            item("heading-$title") { SectionHeader(title, Modifier.padding(top = 8.dp)) }
            item("group-$title") {
                ListGroup {
                    kinds.forEachIndexed { index, kind ->
                        if (index > 0) ListDivider(16.dp)
                        PromptRow(kind, state.aiPrompts) { open(kind) }
                    }
                }
            }
        }
        if (AiPromptKind.entries.any { !it.isDefault(state.aiPrompts) }) item("restore") {
            TextButton(onClick = { confirmRestore = true }) { Text("全部恢复默认") }
        }
    }
    if (confirmRestore) ConfirmDialog("全部恢复默认？", "所有改过的提示词都会换回默认内容。", "恢复", model::restoreDefaultAiPrompts) { confirmRestore = false }
}

@Composable
private fun PromptRow(kind: AiPromptKind, prompts: AiPromptSettings, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).testTag("prompt-${kind.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind.label, style = MaterialTheme.typography.titleMedium)
                if (!kind.isDefault(prompts)) StatusPill("已修改", StatusTone.READY)
            }
            Text(
                kind.read(prompts).replace(Regex("\\s+"), " "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        ChevronIcon()
    }
}

@Composable
fun AiPromptEditScreen(kind: AiPromptKind, state: SettingsUiState, model: SettingsViewModel, done: () -> Unit) {
    val saved = kind.read(state.aiPrompts)
    var text by rememberSaveable(kind) { mutableStateOf(saved) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = text.trim() != saved.trim()
    BackHandler(enabled = dirty) { confirmLeave = true }
    Column(Modifier.fillMaxSize().imePadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Note(kind.usage)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            isError = text.isBlank(),
            supportingText = if (text.isBlank()) { { Text("提示词不能为空") } } else null,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("prompt-editor"),
            shape = RoundedCornerShape(18.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${text.codePointCount(0, text.length)} 字" + if (dirty) " · 有未保存的修改" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            val default = kind.read(AiPromptSettings())
            if (text.trim() != default.trim()) TextButton(onClick = { text = default }) { Text("恢复默认") }
            Button(
                onClick = { model.saveAiPrompt(kind, text); done() },
                enabled = dirty && text.isNotBlank(),
                modifier = Modifier.testTag("prompt-save")
            ) { Text("保存") }
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("放弃修改？") },
        text = { Text("「${kind.label}」的修改还没保存。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; done() }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } }
    )
}

// ---------- AI 生成参数（开发者） ----------

private fun AiReasoningEffort.label() = when (this) {
    AiReasoningEffort.LOW -> "低"
    AiReasoningEffort.HIGH -> "高"
    AiReasoningEffort.MAX -> "最高"
}

@Composable
fun AiGenerationSettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    val value = state.aiGeneration
    val defaults = AiGenerationSettings()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    fun save(next: AiGenerationSettings) = model.saveAiGeneration(next)
    val deepSeek = state.ai.provider == AiProvider.DEEPSEEK
    val thinking = deepSeek && value.deepSeekThinkingMode == AiThinkingMode.ENABLED
    SettingsPage {
        item("note") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note("改了即生效，只影响之后的请求。", Modifier.weight(1f))
                if (value != defaults) TextButton(onClick = model::restoreDefaultAiGeneration) { Text("恢复默认") }
            }
        }
        item("output-heading") { SectionHeader("输出") }
        item("output") {
            ListGroup {
                ListRow(
                    "最大输出长度", subtitle = "太短时笔记会被截断",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedIconButton(onClick = { save(value.copy(maxTokens = (value.maxTokens - 512).coerceAtLeast(512))) }, enabled = value.maxTokens > 512) {
                                Icon(Icons.Outlined.Remove, contentDescription = "减少")
                            }
                            Text("${value.maxTokens}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(56.dp).testTag("max-tokens"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            OutlinedIconButton(onClick = { save(value.copy(maxTokens = (value.maxTokens + 512).coerceAtMost(32_768))) }, enabled = value.maxTokens < 32_768) {
                                Icon(Icons.Outlined.Add, contentDescription = "增加")
                            }
                        }
                    }
                )
            }
        }
        item("temp-heading") { SectionHeader("温度 · 越低越稳定，越高越发散", Modifier.padding(top = 8.dp)) }
        item("temp") {
            ListGroup {
                NumberParam("fixed-temp", "笔记、纠错、快速回答", "固定任务；保持低温能减少编造", value.fixedTemperature, defaults.fixedTemperature, 0f..1.5f, 0.1f, { String.format(Locale.US, "%.1f", it) }, expanded, { expanded = it }) { save(value.copy(fixedTemperature = it)) }
                ListDivider(16.dp)
                NumberParam("chat-temp", "课堂问答和追问", "对话任务", value.chatTemperature, defaults.chatTemperature, 0f..1.5f, 0.1f, { String.format(Locale.US, "%.1f", it) }, expanded, { expanded = it }) { save(value.copy(chatTemperature = it)) }
            }
        }
        if (thinking) item("temp-note") { Note("开启深度思考时不发送温度。") }
        item("reason-heading") { SectionHeader(if (deepSeek) "推理 · 当前为 DeepSeek" else "推理", Modifier.padding(top = 8.dp)) }
        if (!deepSeek) item("reason-na") { Note("深度思考和推理强度只对 DeepSeek 生效；当前服务商不使用这些设置。") }
        else item("reason") {
            GroupCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("深度思考", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("deepseek-thinking-modes")) {
                        AiThinkingMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = value.deepSeekThinkingMode == mode,
                                onClick = { save(value.copy(deepSeekThinkingMode = mode)) },
                                shape = SegmentedButtonDefaults.itemShape(index, AiThinkingMode.entries.size),
                                modifier = Modifier.testTag("deepseek-thinking-${mode.name.lowercase()}")
                            ) { Text(mode.displayName) }
                        }
                    }
                    Text(
                        if (thinking) "推理强度" else "推理强度 · 开启深度思考后可选",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (thinking) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("ai-reasoning-efforts")) {
                        AiReasoningEffort.entries.forEachIndexed { index, effort ->
                            SegmentedButton(
                                selected = value.reasoningEffort == effort,
                                onClick = { save(value.copy(reasoningEffort = effort)) },
                                enabled = thinking,
                                shape = SegmentedButtonDefaults.itemShape(index, AiReasoningEffort.entries.size),
                                modifier = Modifier.testTag("ai-reasoning-${effort.name.lowercase()}")
                            ) { Text(effort.label()) }
                        }
                    }
                }
            }
        }
    }
}
