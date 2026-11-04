package com.cmhr.listen.ui

import android.widget.EditText
import androidx.test.uiautomator.By
import com.cmhr.listen.AiPromptKind
import com.cmhr.listen.audio.VadConfig
import com.cmhr.listen.data.settings.SetupState
import com.cmhr.listen.data.stt.AsrPromptMode
import com.cmhr.listen.ui.theme.DarkModePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Additional settings parity checks exercise the real native fragments and persisted settings. */
class SettingsNativeTest : NativeSettingsFixture() {
    @Test fun appearanceOnlyExposesThreeModesAndKeepsStoredPalette() {
        val palette = runBlocking { repository.settings.first().appearance.palette }
        launch("settings/appearance")
        listOf("跟随系统", "日间", "夜间").forEach { text(it) }
        assertFalse(device.hasObject(By.text("主题色")))
        assertFalse(device.hasObject(By.text("跟随壁纸")))
        click("夜间")
        await { runBlocking { repository.settings.first().appearance.darkMode == DarkModePreference.DARK } }
        assertEquals(palette, runBlocking { repository.settings.first().appearance.palette })
        click("日间")
        await { runBlocking { repository.settings.first().appearance.darkMode == DarkModePreference.LIGHT } }
        click("跟随系统")
        await { runBlocking { repository.settings.first().appearance.darkMode == DarkModePreference.SYSTEM } }
        assertEquals(palette, runBlocking { repository.settings.first().appearance.palette })
    }

    @Test fun removedProfessionalPromptEntryDoesNotResetStoredValue() {
        val original = runBlocking { repository.settings.first().globalAsrPromptMode }
        try {
            runBlocking { repository.saveGlobalAsrPromptMode(AsrPromptMode.ALWAYS) }
            launch("settings")
            assertFalse(device.hasObject(By.text("专业词提示")))
            scrollTo("AI 生成参数")
            assertFalse(device.hasObject(By.text("专业词提示")))
            launch("settings/stt")
            assertFalse(device.hasObject(By.text("专业词提示")))
            assertEquals(AsrPromptMode.ALWAYS, runBlocking { repository.settings.first().globalAsrPromptMode })
        } finally {
            runBlocking { repository.saveGlobalAsrPromptMode(original) }
        }
    }

    @Test fun promptSavePersistsAndSingleRestoreReturnsToDefault() {
        val kind = AiPromptKind.GENERAL
        launch("settings/prompt/${kind.name}")
        click("编辑提示词")
        replace("  测试提示词：按课堂内容回答。  ")
        click("保存")
        await { runBlocking { kind.read(repository.settings.first().aiPrompts) == "测试提示词：按课堂内容回答。" } }
        launch("settings/prompt/${kind.name}")
        click("查看全文")
        text("测试提示词：按课堂内容回答。")
        click("关闭")
        click("恢复默认")
        click("确定")
        await { runBlocking { kind.isDefault(repository.settings.first().aiPrompts) } }
    }

    @Test fun allTenPromptKindsRemainReachable() {
        AiPromptKind.entries.forEach { kind ->
            launch("settings/prompt/${kind.name}")
            text(kind.label)
            text("编辑提示词")
            text("查看全文")
            text("恢复默认")
        }
        assertEquals(10, AiPromptKind.entries.size)
    }

    @Test fun generationLimitsRejectInvalidValuesAndPersistValidChange() {
        launch("settings/generation")
        click("最大输出长度")
        replace("40000")
        assertFalse(text("保存").isEnabled)
        assertEquals(8192, runBlocking { repository.settings.first().aiGeneration.maxTokens })
        replace("4096")
        assertTrue(text("保存").isEnabled)
        click("保存")
        await { runBlocking { repository.settings.first().aiGeneration.maxTokens == 4096 } }
        click("课堂问答和追问")
        replace("0.9")
        click("保存")
        await { runBlocking { repository.settings.first().aiGeneration.chatTemperature == 0.9f } }
        scrollTo("恢复默认参数")
        click("恢复默认参数")
        click("确定")
        await { runBlocking { repository.settings.first().aiGeneration.maxTokens == 8192 } }
    }

    @Test fun vadPresetsAndNumericValidationKeepAllParametersAvailable() {
        launch("settings/vad")
        click("近距离讲话")
        var config: VadConfig? = null
        await { scenario!!.onActivity { config = it.stt.uiState.value.configuredVadConfig }; config == VadConfig.CloseSpeech.validated() }
        scrollTo("语音阈值")
        click("语音阈值")
        replace("1.2")
        assertFalse(text("保存").isEnabled)
        replace("0.45")
        click("保存")
        await { scenario!!.onActivity { config = it.stt.uiState.value.configuredVadConfig }; config?.threshold == 0.45f }
        listOf("开始确认（毫秒）", "结束静音（毫秒）", "前置保留（毫秒）", "后置保留（毫秒）", "最短片段（毫秒）", "柔性上限（毫秒）", "硬性上限（毫秒）", "重叠（毫秒）").forEach { scrollTo(it); text(it) }
        scenario!!.onActivity { it.stt.restoreDefaultVadConfig() }
        await { scenario!!.onActivity { config = it.stt.uiState.value.configuredVadConfig }; config == VadConfig.Default.validated() }
    }

    @Test fun setupCanSkipServicesAndFinishWithoutNetworkRequests() {
        runBlocking {
            repository.clearApiKey()
            repository.clearAiApiKey()
            repository.setSetupState(SetupState.IN_PROGRESS)
        }
        val requests = server.requestCount
        launch("setup")
        click("手动配置")
        scrollTo("跳过")
        click("跳过")
        text("其他服务")
        scrollTo("跳过")
        click("跳过")
        text("准备好了")
        click("上一步")
        scrollTo("跳过")
        click("跳过")
        click("开始使用")
        await { runBlocking { repository.settings.first().setupState == SetupState.DONE } }
        assertFalse(device.hasObject(By.text("准备好了")))
        assertEquals(requests, server.requestCount)
    }

    @Test fun browsingServicePagesDoesNotCallRemoteServicesOrRevealSecrets() {
        val requests = server.requestCount
        listOf("settings/stt" to "自建识别服务", "settings/ai" to "其他服务", "settings/models" to "获取模型列表",
            "settings/sync" to "立即同步", "settings/publish" to "学校", "settings/device" to "用微信绑定"
        ).forEach { (route, label) ->
            launch(route)
            text(label)
            assertFalse(device.hasObject(By.text("synthetic-test-credential")))
            assertFalse(device.hasObject(By.clazz(EditText::class.java)))
        }
        assertEquals(requests, server.requestCount)
    }
}
