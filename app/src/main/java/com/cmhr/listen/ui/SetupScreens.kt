package com.cmhr.listen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmhr.listen.BindingState
import com.cmhr.listen.ServiceCheck
import com.cmhr.listen.SettingsUiState
import com.cmhr.listen.SettingsViewModel
import com.cmhr.listen.data.settings.AiProvider

private enum class SetupStep { BIND, STT, AI, DONE }

/** Where to pick up: each step's work is saved as it happens, so only what is missing is asked. */
private fun firstStep(state: SettingsUiState): SetupStep = when {
    !state.device.bound -> SetupStep.BIND
    !state.server.hasApiKey -> SetupStep.STT
    else -> SetupStep.AI
}

/** First-run setup. A phone whose account already has settings never gets past the first step. */
@Composable
fun SetupFlow(model: SettingsViewModel) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val binding by model.binding.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(firstStep(state)) }
    var pickingModel by rememberSaveable { mutableStateOf(false) }
    var confirmFailed by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(binding) {
        val done = binding as? BindingState.Done ?: return@LaunchedEffect
        if (done.hasStt) model.finishSetup() else step = SetupStep.STT
    }
    LaunchedEffect(state.device.bound) {
        // Settings can load just after the first frame; binding moves the step on by itself.
        if (state.device.bound && step == SetupStep.BIND && binding == BindingState.Idle) step = firstStep(state)
    }
    BackHandler(step != SetupStep.BIND || pickingModel) {
        when {
            pickingModel -> pickingModel = false
            step == SetupStep.DONE -> step = SetupStep.AI
            step == SetupStep.AI -> step = SetupStep.STT
            !state.device.bound -> step = SetupStep.BIND
        }
    }

    val next = {
        step = when (step) {
            SetupStep.BIND -> SetupStep.STT
            SetupStep.STT -> SetupStep.AI
            else -> SetupStep.DONE
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().testTag("setup")) {
            Text(
                when (step) {
                    SetupStep.BIND -> "欢迎使用听这节课"
                    SetupStep.STT -> "语音识别"
                    SetupStep.AI -> "AI 服务（可选）"
                    SetupStep.DONE -> "准备好了"
                },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)
            )
            Box(Modifier.weight(1f)) {
                when {
                    pickingModel -> AiModelsScreen(state, model, done = { pickingModel = false })
                    step == SetupStep.BIND -> SettingsPage {
                        item("panel") { BindingPanel(binding, model) }
                    }
                    step == SetupStep.STT -> SttServiceSettingsScreen(state, model, inSetup = true)
                    step == SetupStep.AI -> {
                        LaunchedEffect(Unit) { if (!state.ai.hasApiKey) model.chooseAiProvider(AiProvider.DEEPSEEK) }
                        AiServiceSettingsScreen(state, model, openModels = { pickingModel = true }, inSetup = true)
                    }
                    else -> SettingsPage { item("summary") { SetupSummary(state) } }
                }
            }
            if (!pickingModel) Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (step) {
                    SetupStep.BIND -> OutlinedButton(
                        onClick = { model.cancelBinding(); step = SetupStep.STT },
                        modifier = Modifier.fillMaxWidth().testTag("setup-manual")
                    ) { Text("手动配置") }
                    SetupStep.DONE -> Button(
                        onClick = { model.finishSetup() }, modifier = Modifier.fillMaxWidth().testTag("setup-finish")
                    ) { Text("开始使用") }
                    SetupStep.STT -> StepButtons(state.server.hasApiKey, state.sttCheck, next) { confirmFailed = it }
                    SetupStep.AI -> StepButtons(state.ai.hasApiKey, state.aiCheck, next) { confirmFailed = it }
                }
            }
        }
    }
    confirmFailed?.let { reason ->
        // The speech service may run on a computer that is simply off right now; that must not trap anyone here.
        AlertDialog(
            onDismissRequest = { confirmFailed = null },
            title = { Text("连接测试没通过") },
            text = { Text(reason) },
            confirmButton = { TextButton(onClick = { confirmFailed = null; next() }) { Text("仍然保存") } },
            dismissButton = { TextButton(onClick = { confirmFailed = null }) { Text("留在这里") } }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StepButtons(
    configured: Boolean,
    check: ServiceCheck,
    next: () -> Unit,
    failed: (String) -> Unit
) {
    OutlinedButton(onClick = next, modifier = Modifier.weight(1f).testTag("setup-skip")) { Text("跳过") }
    Button(
        onClick = { if (check is ServiceCheck.Failed) failed(check.message) else next() },
        enabled = configured && check !is ServiceCheck.Testing,
        modifier = Modifier.weight(1f).testTag("setup-next")
    ) { Text(if (check is ServiceCheck.Testing) "正在测试…" else "下一步") }
}

@Composable
private fun SetupSummary(state: SettingsUiState) {
    fun status(ready: Boolean, check: ServiceCheck, idle: String) = when {
        !ready -> idle
        check is ServiceCheck.Failed -> "已保存，连接失败"
        check is ServiceCheck.Passed -> "已连接"
        else -> "已配置"
    }
    val row: @Composable (String, String) -> Unit = { title, value ->
        ListRow(title, trailing = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) })
    }
    ListGroup {
        row("语音识别", status(state.server.hasApiKey, state.sttCheck, "未配置"))
        ListDivider(16.dp)
        row("AI 服务", status(state.ai.hasApiKey, state.aiCheck, "已跳过"))
        ListDivider(16.dp)
        row("云同步", if (state.cloudSync.hasApiToken) "已配置" else "未配置")
        ListDivider(16.dp)
        row("公开发布", if (state.publish.hasToken) "已配置" else "未配置")
    }
    Spacer(Modifier.height(8.dp))
    Note("之后都可以在设置里修改。")
}

/** The code to type into the mini program, and everything around waiting for it. */
@Composable
private fun BindingPanel(binding: BindingState, model: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (binding) {
            BindingState.Idle, is BindingState.Done -> {
                Note("用微信绑定后，语音识别、AI、云同步和公开发布的配置都存在云端，换手机时不用重新输入。")
                Button(onClick = model::startBinding, modifier = Modifier.fillMaxWidth().testTag("bind-start")) { Text("用微信绑定") }
            }
            BindingState.Requesting -> StatusCard(CardTone.BUSY, Icons.Outlined.PhoneAndroid, "正在获取绑定码…", null)
            is BindingState.Waiting -> {
                Text(
                    binding.code.chunked(3).joinToString(" "),
                    fontSize = 44.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("bind-code"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Note("打开微信小程序，进入「设置 › 我的设备 › 绑定新设备」，输入这个绑定码。5 分钟内有效。")
                TextButton(onClick = model::cancelBinding, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
            BindingState.Applying -> StatusCard(CardTone.BUSY, Icons.Outlined.Check, "已绑定，正在下载配置…", null)
            is BindingState.Failed -> {
                StatusCard(CardTone.PROBLEM, Icons.Outlined.PhoneAndroid, "没有绑定成功", binding.message)
                Button(onClick = model::startBinding, modifier = Modifier.fillMaxWidth().testTag("bind-retry")) { Text("重新获取绑定码") }
            }
        }
    }
}

@Composable
fun DeviceSettingsScreen(state: SettingsUiState, model: SettingsViewModel) {
    val binding by model.binding.collectAsStateWithLifecycle()
    var confirmUnbind by remember { mutableStateOf(false) }
    val device = state.device
    SettingsPage {
        if (device.bound) {
            item("status") {
                StatusCard(
                    CardTone.OK, Icons.Outlined.Check, "已绑定",
                    when {
                        device.dirty.isNotEmpty() -> "有改动等待同步"
                        device.lastSyncedAt > 0 -> "上次同步 ${checkTime(device.lastSyncedAt)}"
                        else -> "还没同步过"
                    },
                    action = { TestButton(false, "立即同步", model::syncDeviceConfig) }
                )
            }
            item("note") { Note("其他设备和这台设备的改动会自动同步。设备列表在小程序的「我的设备」里。") }
            item("unbind") { DangerRow("解除绑定", "本机已保存的配置不受影响", { confirmUnbind = true }) }
        } else {
            item("panel") { BindingPanel(binding, model) }
        }
    }
    if (confirmUnbind) ConfirmDialog("解除绑定？", "这台设备不再和云端同步配置。", "解除", model::unbindDevice) { confirmUnbind = false }
}
