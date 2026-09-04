package com.cmhr.listen.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.SettingsUiState
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.VadDiagnosticsUiState
import com.cmhr.listen.data.stt.AsrDiagnosticStateCounts
import com.cmhr.listen.data.stt.AsrClockBasis
import com.cmhr.listen.data.stt.AsrLifecycleState
import com.cmhr.listen.data.stt.AsrRuntimeSummary
import com.cmhr.listen.data.stt.AsrSegmentDiagnosticEntity
import com.cmhr.listen.ui.theme.ListenTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UiInteractionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun floatingComposerOverlaysFullHeightContent() {
        composeRule.setContent {
            ListenTheme {
                FloatingComposerLayout(
                    modifier = Modifier.width(320.dp).height(480.dp),
                    content = {
                        Box(Modifier.fillMaxSize().testTag("floating-test-content"))
                    },
                    composer = { modifier ->
                        Box(
                            modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .testTag("floating-test-composer")
                        )
                    }
                )
            }
        }

        val layoutBounds = composeRule.onNodeWithTag("floating-composer-layout").fetchSemanticsNode().boundsInRoot
        val contentBounds = composeRule.onNodeWithTag("floating-test-content").fetchSemanticsNode().boundsInRoot
        val composerBounds = composeRule.onNodeWithTag("floating-test-composer").fetchSemanticsNode().boundsInRoot
        assertEquals(layoutBounds.bottom, contentBounds.bottom, 0.5f)
        assertEquals(layoutBounds.bottom, composerBounds.bottom, 0.5f)
        assertTrue(composerBounds.top > contentBounds.top)
    }

    @Test
    fun extendedFabShowsActionNameAndHandlesClick() {
        var clicked = false
        composeRule.setContent { ListenTheme { AppFab("新建课程", Icons.Outlined.Add) { clicked = true } } }

        composeRule.onNodeWithText("新建课程", useUnmergedTree = true).assertTextContains("新建课程")
        composeRule.onNodeWithTag("global-fab").performClick()
        assertTrue(clicked)
    }

    @Test
    fun listeningStopUsesGlobalRedFab() {
        composeRule.setContent {
            ListenTheme { ListeningStopFab(SystemClock.elapsedRealtime(), click = {}) }
        }
        composeRule.onNodeWithTag("global-stop-listening").assertExists()
        composeRule.onNodeWithContentDescription("停止监听").assertExists()
    }

    @Test
    fun compactListeningStatusShowsCaptureWithoutDiagnostics() {
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
        composeRule.onNodeWithText("检测到语音：是", substring = true).assertExists()
    }

    @Test
    fun currentRecordDiagnosticsShowLifecycleAndHealthRefreshIsManual() {
        val now = System.currentTimeMillis()
        val diagnostic = diagnostic(
            id = "12345678-test",
            recordId = 1,
            capturedAt = now - 4_000,
            state = AsrLifecycleState.QUEUED_LOCAL
        )
        var healthRefreshes = 0
        composeRule.setContent {
            ListenTheme {
                AsrDiagnosticsScreen(
                    state = ListeningUiState(pendingQueueCount = 1),
                    vadState = VadDiagnosticsUiState(vadProbability = 0.42f),
                    currentRecordId = 1,
                    currentRecordName = "第一课",
                    diagnostics = listOf(diagnostic),
                    totalCount = 1,
                    events = { flowOf(emptyList()) },
                    refreshHealth = { healthRefreshes += 1 },
                    confirmRetryUnknown = {},
                    openHistory = {},
                    activeDiagnostics = listOf(diagnostic),
                    recentCounts = AsrDiagnosticStateCounts(0, 0, 0),
                    runtimeSummary = AsrRuntimeSummary(
                        activeCount = 4,
                        recognizingCount = 3,
                        queuedLocalCount = 1,
                        submittingCount = 1,
                        serverInFlightCount = 2,
                        pollingCount = 1,
                        submissionUnknownCount = 0,
                        completedCount = 8,
                        failedCount = 1,
                        globalInFlightCount = 3
                    )
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, healthRefreshes) }
        composeRule.onNodeWithText("客户端队列：1 / 持久化").assertExists()
        composeRule.onNodeWithText("待提交：1").assertExists()
        composeRule.onNodeWithText("提交中：1").assertExists()
        composeRule.onNodeWithText("服务端在途：2").assertExists()
        composeRule.onNodeWithText("全局并发槽：3 / 3").assertExists()
        composeRule.onNodeWithText("正在轮询：1").assertExists()
        composeRule.onNodeWithTag("asr-capture-vad").assertExists()
        composeRule.onNodeWithText("全部记录").assertDoesNotExist()
        composeRule.onNodeWithTag("asr-diagnostics-list")
            .performScrollToNode(hasTestTag("asr-health-refresh"))
        composeRule.onNodeWithTag("asr-health-refresh").performClick()
        composeRule.runOnIdle { assertEquals(1, healthRefreshes) }
        composeRule.onNodeWithTag("asr-diagnostics-list")
            .performScrollToNode(hasText("#12345678 · 客户端排队"))
        composeRule.onNodeWithText("#12345678 · 客户端排队").assertExists()
    }

    @Test
    fun processingWithoutValidServerAnchorShowsEstimatingInsteadOfDeviceUptime() {
        val now = System.currentTimeMillis()
        val diagnostic = diagnostic(
            id = "processing-anchor-test",
            recordId = 7,
            capturedAt = now - 2_000,
            state = AsrLifecycleState.PROCESSING
        ).copy(
            jobId = "job-test",
            clockBasis = AsrClockBasis.ELAPSED_REALTIME.name,
            submitCompletedElapsedMs = null,
            submitCompletedAt = now - 1_000
        )
        composeRule.setContent {
            ListenTheme {
                AsrDiagnosticsScreen(
                    state = ListeningUiState(),
                    vadState = VadDiagnosticsUiState(),
                    currentRecordId = 7,
                    currentRecordName = "计时测试",
                    diagnostics = listOf(diagnostic),
                    totalCount = 1,
                    events = { flowOf(emptyList()) },
                    refreshHealth = {},
                    confirmRetryUnknown = {},
                    openHistory = {},
                    activeDiagnostics = listOf(diagnostic),
                    recentCounts = AsrDiagnosticStateCounts(0, 0, 0)
                )
            }
        }

        composeRule.onNodeWithTag("asr-diagnostics-list").performScrollToIndex(3)
        composeRule.onNodeWithText("服务端等待：—（估算中）").assertExists()
        composeRule.onNodeWithText("81188.9s", substring = true).assertDoesNotExist()
    }

    @Test
    fun diagnosticsPreviewShowsFifteenThenOpensCurrentRecordHistory() {
        val now = System.currentTimeMillis()
        val preview = (16 downTo 2).map { index ->
            diagnostic(
                id = "preview-$index",
                recordId = 8,
                capturedAt = now + index,
                state = AsrLifecycleState.COMPLETED
            )
        }
        var openedRecordId: Long? = null
        composeRule.setContent {
            ListenTheme {
                AsrDiagnosticsScreen(
                    state = ListeningUiState(),
                    vadState = VadDiagnosticsUiState(),
                    currentRecordId = 8,
                    currentRecordName = "课堂 A",
                    diagnostics = preview,
                    totalCount = 16,
                    events = { flowOf(emptyList()) },
                    refreshHealth = {},
                    confirmRetryUnknown = {},
                    openHistory = { openedRecordId = it },
                    activeDiagnostics = emptyList(),
                    recentCounts = AsrDiagnosticStateCounts(15, 0, 0)
                )
            }
        }

        composeRule.onNodeWithTag("asr-diagnostic-preview-16").assertExists()
        composeRule.onNodeWithTag("asr-diagnostic-preview-1").assertDoesNotExist()
        // record, capture/VAD, summary, fifteen diagnostics, then the more action
        composeRule.onNodeWithTag("asr-diagnostics-list").performScrollToIndex(18)
        composeRule.onNodeWithTag("asr-more-button").assertExists().performClick()
        composeRule.runOnIdle { assertEquals(8L, openedRecordId) }
    }

    @Test
    fun diagnosticsHistoryContainsTheSixteenthEntry() {
        val now = System.currentTimeMillis()
        val all = (16 downTo 1).map { index ->
            diagnostic(
                id = "history-$index",
                recordId = 8,
                capturedAt = now + index,
                state = AsrLifecycleState.COMPLETED
            )
        }
        composeRule.setContent {
            ListenTheme {
                AsrDiagnosticsHistoryScreen(
                    recordName = "课堂 A",
                    diagnostics = all,
                    events = { flowOf(emptyList()) },
                    confirmRetryUnknown = {}
                )
            }
        }

        composeRule.onNodeWithTag("asr-diagnostics-history-list").performScrollToIndex(16)
        composeRule.onNodeWithTag("asr-diagnostic-history-1").assertExists()
    }

    @Test
    fun smoothElapsedTextRefreshesAtTenthsOfASecond() {
        var elapsedRealtime by mutableLongStateOf(10_000L)
        composeRule.setContent {
            ListenTheme {
                SmoothElapsedText(
                    label = "已等待：",
                    startElapsedRealtimeMs = 10_000L,
                    fallbackStartWallTimeMs = null,
                    active = true,
                    modifier = Modifier.testTag("smooth-elapsed-test"),
                    elapsedRealtime = { elapsedRealtime },
                    wallTime = { 0L }
                )
            }
        }

        composeRule.onNodeWithText("已等待：0.0s").assertExists()
        composeRule.runOnIdle { elapsedRealtime = 10_137L }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("已等待：0.1s").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("已等待：0.1s").assertExists()
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
                    back = {}, organizeNotes = {}, exportTxt = {},
                    openResults = {}, select = {}, editAsrPrompt = {}
                )
            }
        }

        composeRule.onNodeWithText("记录详情").assertTextContains("记录详情")
        composeRule.onNodeWithContentDescription("更多操作").performClick()
        listOf("整理成笔记", "导出 TXT", "AI 结果", "选择", "ASR 提示词").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
        composeRule.onNodeWithText("总结").assertDoesNotExist()
    }

    @Test
    fun markdownRendererAcceptsHeadingTableTaskListAndStrikethrough() {
        composeRule.setContent {
            ListenTheme {
                MarkdownText("## 二级标题\n\n| 项目 | 内容 |\n| --- | --- |\n| 课程 | 数学 |\n\n- [x] 已完成\n\n~~删除线~~")
            }
        }

        composeRule.onNodeWithTag("markdown-content").assertExists()
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

private fun diagnostic(
    id: String,
    recordId: Long,
    capturedAt: Long,
    state: AsrLifecycleState
) = AsrSegmentDiagnosticEntity(
    segmentId = id,
    recordId = recordId,
    state = state.name,
    audioStartTime = capturedAt,
    audioEndTime = capturedAt + 1_000,
    audioDurationMs = 1_000,
    captureStartedAt = capturedAt,
    captureFinishedAt = capturedAt + 1_000,
    queuedLocalAt = capturedAt + 1_000,
    finishedAt = if (state in setOf(
            AsrLifecycleState.COMPLETED,
            AsrLifecycleState.FAILED,
            AsrLifecycleState.DROPPED
        )
    ) capturedAt + 2_000 else null
)
