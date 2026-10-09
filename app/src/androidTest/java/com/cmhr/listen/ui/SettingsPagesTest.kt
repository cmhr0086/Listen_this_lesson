package com.cmhr.listen.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.cmhr.listen.AiPromptKind
import com.cmhr.listen.ServiceCheck
import com.cmhr.listen.SettingsUiState
import com.cmhr.listen.SettingsViewModel
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.settings.AiGenerationSettings
import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AiServiceSettings
import com.cmhr.listen.data.settings.AiThinkingMode
import com.cmhr.listen.data.settings.ServerSettings
import com.cmhr.listen.ui.theme.ListenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The 1.3.1 settings pages: status cards, sheet editing, and the unsaved-prompt guard. */
class SettingsPagesTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun viewModel() = SettingsViewModel(composeRule.activity.application)

    @Test
    fun editSheetOnlyConfirmsAChangedValueAndTrimsIt() {
        var saved: String? = null
        composeRule.setContent {
            ListenTheme {
                var open by remember { mutableStateOf(true) }
                if (open) TextEditSheet(
                    title = "服务器地址", description = "说明", label = "地址", initial = "https://a.example",
                    secret = false, confirmLabel = "保存并测试", onConfirm = { saved = it }, dismiss = { open = false }
                )
            }
        }
        composeRule.onNodeWithTag("edit-sheet-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("edit-sheet-field").performTextReplacement("  https://b.example  ")
        composeRule.onNodeWithTag("edit-sheet-confirm").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals("https://b.example", saved)
        composeRule.onNodeWithTag("edit-sheet").assertDoesNotExist()
    }

    @Test
    fun failedSpeechCheckStaysOnThePage() {
        val state = SettingsUiState(
            server = ServerSettings(baseUrl = "https://asr.example.com", hasApiKey = true),
            sttCheck = ServiceCheck.Failed(System.currentTimeMillis(), "API Key 无效（HTTP 403）。")
        )
        val model = viewModel()
        composeRule.setContent { ListenTheme { SttServiceSettingsScreen(state, model) } }

        composeRule.onNodeWithTag("status-title").assertTextEquals("连接失败")
        composeRule.onNodeWithText("刚刚 · API Key 无效（HTTP 403）。").assertExists()
        composeRule.onNodeWithText("https://asr.example.com").assertExists()
        composeRule.onNodeWithTag("stt-clear-key").assertExists()
    }

    @Test
    fun promptEditorAsksBeforeDroppingEdits() {
        var done = false
        val model = viewModel()
        composeRule.setContent { ListenTheme { AiPromptEditScreen(AiPromptKind.NOTES, SettingsUiState(), model, done = { done = true }) } }

        composeRule.onNodeWithTag("prompt-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("prompt-editor").performTextInput("最后列出作业。")
        composeRule.onNodeWithTag("prompt-save").assertIsEnabled()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("放弃修改？").assertExists()
        assertFalse(done)
        composeRule.onNodeWithText("放弃").performClick()
        composeRule.waitForIdle()
        assertTrue(done)
    }

    @Test
    fun reasoningOptionsFollowTheProvider() {
        var state by mutableStateOf(SettingsUiState(ai = AiServiceSettings(provider = AiProvider.OPENAI_COMPATIBLE)))
        val model = viewModel()
        composeRule.setContent { ListenTheme { AiGenerationSettingsScreen(state, model) } }

        composeRule.onNodeWithText("深度思考和推理强度只对 DeepSeek 生效；当前服务商不使用这些设置。").assertExists()
        composeRule.onNodeWithTag("deepseek-thinking-modes").assertDoesNotExist()

        state = SettingsUiState(
            ai = AiServiceSettings(provider = AiProvider.DEEPSEEK),
            aiGeneration = AiGenerationSettings(deepSeekThinkingMode = AiThinkingMode.DISABLED)
        )
        composeRule.onNodeWithTag("ai-reasoning-high").assertIsNotEnabled()
        state = state.copy(aiGeneration = AiGenerationSettings(deepSeekThinkingMode = AiThinkingMode.ENABLED))
        composeRule.onNodeWithTag("ai-reasoning-high").assertIsEnabled()
    }

    @Test
    fun asrPromptPageListsEachCourseAndEditsIt() {
        var saved: Triple<Long, String, String?>? = null
        val courses = listOf(
            CourseEntity(id = 1, name = "高等数学", createdAt = 1, asrPrompt = "极限，导数、ε-δ"),
            CourseEntity(id = 2, name = "大学物理", createdAt = 1, asrPromptModeOverride = "ALWAYS")
        )
        val model = viewModel()
        composeRule.setContent {
            ListenTheme {
                AsrPromptPolicySettingsScreen(SettingsUiState(), model, courses, saveCourse = { id, prompt, mode -> saved = Triple(id, prompt, mode) })
            }
        }

        composeRule.onNodeWithText("3 个术语 · 跟随全局").assertExists()
        composeRule.onNodeWithText("还没填术语").assertExists()
        assertNull(saved)
        composeRule.onNodeWithTag("asr-course-1").performClick()
        composeRule.onNodeWithText("保存").performClick()
        composeRule.waitForIdle()
        assertEquals(Triple(1L, "极限，导数、ε-δ", null), saved)
    }
}
