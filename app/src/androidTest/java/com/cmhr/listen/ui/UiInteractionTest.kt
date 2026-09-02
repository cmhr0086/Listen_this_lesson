package com.cmhr.listen.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.SettingsUiState
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.ui.theme.ListenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UiInteractionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun extendedFabShowsActionNameAndHandlesClick() {
        var clicked = false
        composeRule.setContent { ListenTheme { AppFab("新建课程", Icons.Outlined.Add) { clicked = true } } }

        composeRule.onNodeWithText("新建课程", useUnmergedTree = true).assertTextContains("新建课程")
        composeRule.onNodeWithTag("global-fab").performClick()
        assertTrue(clicked)
    }

    @Test
    fun listeningStopUsesFixedRecordActionBar() {
        composeRule.setContent {
            ListenTheme { RecordListeningStopBar(stop = {}) }
        }
        composeRule.onNodeWithTag("record-stop-listening").assertExists()
        composeRule.onNodeWithText("停止监听").assertTextContains("停止监听")
    }

    @Test
    fun compactListeningStatusShowsCaptureAndQueueState() {
        composeRule.setContent {
            ListenTheme {
                CompactListeningStatus(
                    listening = ListeningUiState(
                        isListening = true,
                        isSpeechDetected = true,
                        isRecognizing = true,
                        pendingQueueCount = 3,
                        listeningStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime() - 2_000
                    ),
                    active = true
                )
            }
        }
        composeRule.onNodeWithTag("compact-listening-status").assertExists()
        composeRule.onNodeWithText("正在收音").assertExists()
        composeRule.onNodeWithText("队列：3", substring = true).assertExists()
    }

    @Test
    fun horizontalChoiceSelectorScrollsAndSelectsLastOption() {
        val options = listOf("跟随全局", "关闭", "自动", "始终使用")
        var selected by mutableStateOf(options.first())
        composeRule.setContent {
            ListenTheme {
                Box(Modifier.width(220.dp)) {
                    HorizontalChoiceSelector(
                        options = options,
                        selected = selected,
                        onSelect = { selected = it },
                        label = { it },
                        testTag = "test-horizontal-choices",
                        optionTestTag = { "test-choice-$it" }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("test-horizontal-choices").performScrollToIndex(3)
        composeRule.onNodeWithTag("test-choice-始终使用").performClick().assertIsSelected()
        composeRule.runOnIdle { assertEquals("始终使用", selected) }
    }

    @Test
    fun courseAsrPromptDialogUsesScrollableHorizontalChoices() {
        var mode: String? by mutableStateOf(null)
        composeRule.setContent {
            ListenTheme {
                AsrPromptDialog(
                    prompt = "创新创业",
                    update = {},
                    modeOverride = mode,
                    updateMode = { mode = it },
                    save = {},
                    dismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("course-asr-prompt-modes").performScrollToIndex(3)
        composeRule.onNodeWithTag("course-asr-prompt-always").performClick().assertIsSelected()
        composeRule.runOnIdle { assertEquals("ALWAYS", mode) }
    }

    @Test
    fun longPressSelectsTranscriptWithoutChangingText() {
        val segment = TranscriptEntity(
            id = 7,
            recordId = 1,
            startTime = 1_000,
            endTime = 2_000,
            audioDurationMs = 1_000,
            recognitionDurationMs = 100,
            text = "永久保留的原始识别文本"
        )
        var selected by mutableStateOf(false)
        composeRule.setContent {
            ListenTheme {
                SelectableTranscriptCard(segment, selected, selectionMode = selected) { selected = !selected }
            }
        }

        composeRule.onNodeWithTag("segment-7").performTouchInput { longClick() }
        composeRule.onNodeWithTag("segment-7").assertIsSelected().assertTextContains("永久保留的原始识别文本")
    }

    @Test
    fun zeroSelectionTopBarDisablesAiProcessing() {
        composeRule.setContent {
            ListenTheme { RecordSelectionTopBar(0, aiEnabled = false, close = {}, process = {}, delete = {}) }
        }

        composeRule.onNodeWithText("已选择 0 条").assertTextContains("已选择 0 条")
        composeRule.onNodeWithText("AI 处理").assertIsNotEnabled()
    }

    @Test
    fun recordMenuContainsAllRequestedActions() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            ListenTheme {
                RecordNormalTopBar(
                    menuExpanded = expanded,
                    setMenuExpanded = { expanded = it },
                    back = {}, summary = {}, organizeNotes = {}, exportTxt = {},
                    openResults = {}, select = {}, editAsrPrompt = {}
                )
            }
        }

        composeRule.onNodeWithText("记录详情").assertTextContains("记录详情")
        composeRule.onNodeWithContentDescription("更多操作").performClick()
        listOf("总结", "整理成笔记", "导出 TXT", "AI 结果", "选择", "ASR 提示词").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun settingsOverviewUsesOnlySttAndAiEntryCards() {
        composeRule.setContent {
            ListenTheme {
                SettingsOverview(
                    state = SettingsUiState(),
                    setDeveloperMode = {},
                    onSttService = {}, onAiService = {}, onVadParameters = {}, onVadPresets = {}, onAiPrompts = {},
                    onAsrPromptPolicy = {}, onAiGeneration = {}
                )
            }
        }

        composeRule.onNodeWithText("STT 服务器").assertExists()
        composeRule.onNodeWithText("AI 配置").assertExists()
        composeRule.onNodeWithText("开发者功能").assertDoesNotExist()
        composeRule.onNodeWithText("语音识别服务").assertDoesNotExist()
        composeRule.onNodeWithText("AI 服务").assertDoesNotExist()
    }

    @Test
    fun persistentDeleteRequiresTwoSecondConfirmation() {
        var deleted = false
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ListenTheme {
                TimedDeleteDialog("删除测试", "该数据将永久删除。", confirm = { deleted = true }, dismiss = {})
            }
        }

        composeRule.onNodeWithText("删除（2s）").assertIsNotEnabled()
        composeRule.mainClock.advanceTimeBy(2_100)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认删除").assertIsEnabled().performClick()
        assertTrue(deleted)
    }
}
