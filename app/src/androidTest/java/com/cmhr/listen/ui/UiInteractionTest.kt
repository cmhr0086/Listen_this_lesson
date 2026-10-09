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
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.PausedClass
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseSuggestion
import com.cmhr.listen.data.course.CourseSummary
import com.cmhr.listen.recording.OfflineRecognitionState
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingState
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
    fun idleCapturePanelOffersBothModesAndStartsTheChosenOne() {
        var started: CaptureMode? = null
        composeRule.setContent {
            ListenTheme {
                CapturePanel(recordId = 1, listening = ListeningUiState(), processing = OfflineRecognitionState(), start = { started = it }, stop = {})
            }
        }
        composeRule.onNodeWithText("实时转写").assertExists()
        composeRule.onNodeWithTag("start-record-only").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(CaptureMode.RECORD_ONLY, started) }
    }

    @Test
    fun recordOnlyPanelShowsSavedLengthAndStops() {
        var stopped = false
        composeRule.setContent {
            ListenTheme {
                CapturePanel(
                    recordId = 1,
                    listening = ListeningUiState(
                        isListening = true,
                        activeRecordId = 1,
                        captureMode = CaptureMode.RECORD_ONLY,
                        recordOnlySavedMs = 12_000,
                        listeningStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime() - 13_000
                    ),
                    processing = OfflineRecognitionState(),
                    start = {},
                    stop = { stopped = true }
                )
            }
        }
        composeRule.onNodeWithText("仅录音中").assertExists()
        composeRule.onNodeWithText("已安全保存 00:12").assertExists()
        composeRule.onNodeWithText("检测到语音", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("stop-capture").performClick()
        composeRule.runOnIdle { assertTrue(stopped) }
    }

    @Test
    fun capturingPanelOffersPauseAndEnd() {
        var paused = false
        var ended = false
        composeRule.setContent {
            ListenTheme {
                CapturePanel(
                    recordId = 1,
                    listening = ListeningUiState(isListening = true, activeRecordId = 1, captureMode = CaptureMode.REALTIME_ASR, listeningStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()),
                    processing = OfflineRecognitionState(),
                    start = {},
                    stop = { ended = true },
                    pause = { paused = true }
                )
            }
        }
        composeRule.onNodeWithTag("pause-capture").performClick()
        composeRule.onNodeWithTag("stop-capture").performClick()
        composeRule.runOnIdle { assertTrue(paused); assertTrue(ended) }
    }

    @Test
    fun pausedClassResumesFromTheControlBar() {
        var resumed = false
        composeRule.setContent {
            ListenTheme {
                CaptureControlBar(
                    recordId = 1,
                    listening = ListeningUiState(pausedClass = PausedClass(1, CaptureMode.RECORD_ONLY, "散打", "散打-10-09", 754_000)),
                    processing = OfflineRecognitionState(),
                    start = {}, pause = {}, resume = { resumed = true }, end = {},
                    jumpToLatest = null
                )
            }
        }
        composeRule.onNodeWithText("已暂停 00:12:34").assertExists()
        composeRule.onNodeWithTag("bar-resume").performClick()
        composeRule.runOnIdle { assertTrue(resumed) }
        composeRule.onNodeWithTag("jump-to-latest").assertDoesNotExist()
    }

    @Test
    fun controlBarOffersContinueAndJumpToLatestForAFinishedClass() {
        var started: CaptureMode? = null
        var jumped = false
        composeRule.setContent {
            ListenTheme {
                CaptureControlBar(
                    recordId = 1,
                    listening = ListeningUiState(),
                    processing = OfflineRecognitionState(),
                    start = { started = it }, pause = {}, resume = {}, end = {},
                    jumpToLatest = { jumped = true }
                )
            }
        }
        composeRule.onNodeWithTag("bar-start-record-only").performClick()
        composeRule.onNodeWithContentDescription("回到最新").performClick()
        composeRule.runOnIdle { assertEquals(CaptureMode.RECORD_ONLY, started); assertTrue(jumped) }
    }

    @Test
    fun controlBarWhileCapturingShowsPauseAndEnd() {
        var paused = false
        composeRule.setContent {
            ListenTheme {
                CaptureControlBar(
                    recordId = 1,
                    listening = ListeningUiState(isListening = true, activeRecordId = 1, captureMode = CaptureMode.REALTIME_ASR, pendingQueueCount = 2, listeningStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()),
                    processing = OfflineRecognitionState(),
                    start = {}, pause = { paused = true }, resume = {}, end = {},
                    jumpToLatest = null
                )
            }
        }
        composeRule.onNodeWithText("实时转写 · 2 段识别中").assertExists()
        composeRule.onNodeWithTag("bar-pause").performClick()
        composeRule.runOnIdle { assertTrue(paused) }
    }

    @Test
    fun realtimePanelShowsSpeechAndQueue() {
        composeRule.setContent {
            ListenTheme {
                CapturePanel(
                    recordId = 1,
                    listening = ListeningUiState(
                        isListening = true,
                        isSpeechDetected = true,
                        pendingQueueCount = 3,
                        activeRecordId = 1,
                        captureMode = CaptureMode.REALTIME_ASR,
                        listeningStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime() - 2_000
                    ),
                    processing = OfflineRecognitionState(),
                    start = {},
                    stop = {}
                )
            }
        }
        composeRule.onNodeWithText("实时转写中").assertExists()
        composeRule.onNodeWithText("正在收音 · 3 段排队识别").assertExists()
    }

    @Test
    fun capturePanelIsBlockedWhileAnotherRecordCaptures() {
        composeRule.setContent {
            ListenTheme {
                CapturePanel(
                    recordId = 2,
                    listening = ListeningUiState(isListening = true, activeRecordId = 1, currentRecordName = "高数-10-08", captureMode = CaptureMode.RECORD_ONLY),
                    processing = OfflineRecognitionState(),
                    start = {},
                    stop = {}
                )
            }
        }
        composeRule.onNodeWithTag("start-realtime").assertIsNotEnabled()
        composeRule.onNodeWithTag("start-record-only").assertIsNotEnabled()
        composeRule.onNodeWithText("「高数-10-08」正在录制", substring = true).assertExists()
    }

    @Test
    fun homeStartsAClassWithTheSuggestedCourseWithoutNavigating() {
        var started: CaptureMode? = null
        var picked = false
        val course = CourseSummary(CourseEntity(id = 7, name = "毛概", createdAt = 1), recordCount = 3, lastStartedAt = 1_000)
        composeRule.setContent {
            ListenTheme {
                RecordHomeScreen(
                    recent = emptyList(),
                    startCourse = course,
                    suggestion = CourseSuggestion(7, CourseSuggestion.Reason.USUAL_TIME),
                    listening = ListeningUiState(),
                    processing = OfflineRecognitionState(),
                    pendingRecordingCounts = emptyMap(),
                    pickCourse = { picked = true },
                    start = { started = it },
                    stop = {},
                    openRecord = {},
                    openActiveRecord = {}
                )
            }
        }
        composeRule.onNodeWithTag("start-course-chip").assertTextContains("毛概")
        composeRule.onNodeWithText("按你平时的上课时间猜的", substring = true).assertExists()
        composeRule.onNodeWithTag("home-start-record-only").performClick()
        composeRule.onNodeWithTag("start-course-chip").performClick()
        composeRule.runOnIdle {
            assertEquals(CaptureMode.RECORD_ONLY, started)
            assertTrue(picked)
        }
    }

    @Test
    fun coursesTabShowsClassCountAndOpensTheCourse() {
        var opened: Long? = null
        val courses = listOf(
            CourseSummary(CourseEntity(id = 7, name = "毛概", createdAt = 1), recordCount = 7, lastStartedAt = 1_759_890_000_000),
            CourseSummary(CourseEntity(id = 8, name = "英语", createdAt = 2), recordCount = 0, lastStartedAt = null)
        )
        composeRule.setContent { ListenTheme { CoursesTabScreen(courses, openCourse = { opened = it }, courseMenu = {}) } }
        composeRule.onNodeWithText("7 节课", substring = true).assertExists()
        composeRule.onNodeWithText("还没有课堂").assertExists()
        composeRule.onNodeWithTag("course-7").performClick()
        composeRule.runOnIdle { assertEquals(7L, opened) }
    }

    @Test
    fun coursePickerConfirmsTheChosenCourse() {
        var confirmed: Long? = null
        val courses = listOf(1L to "英语", 2L to "毛概").map { (id, name) -> CourseSummary(CourseEntity(id = id, name = name, createdAt = id), 1, id) }
        composeRule.setContent {
            ListenTheme {
                CoursePickerDialog(
                    title = "这节课保存到哪门课？",
                    message = null,
                    courses = courses,
                    initialCourseId = 1,
                    confirmLabel = "保存",
                    confirm = { confirmed = it },
                    createNew = {},
                    dismiss = {}
                )
            }
        }
        composeRule.onNodeWithTag("pick-course-2").performClick()
        composeRule.onNodeWithTag("course-picker-confirm").performClick()
        composeRule.runOnIdle { assertEquals(2L, confirmed) }
    }

    @Test
    fun recordingsCollapseIntoOneSummaryWithRecognizeAll() {
        var all = false
        var expanded by mutableStateOf(false)
        val recordings = (0..3).map { i ->
            RecordingEntity(recordId = 1, sessionId = "s", localPath = "$i.wav", startedAt = i * 60_000L, durationMs = 10_000, totalFrames = 160_000, state = RecordingState.RECORDED.name)
        }
        composeRule.setContent {
            ListenTheme {
                RecordingsSummaryCard(
                    recordings = recordings,
                    processing = OfflineRecognitionState(),
                    recognizingAll = false,
                    recognitionAllowed = true,
                    expanded = expanded,
                    toggleExpanded = { expanded = !expanded },
                    recognizeAll = { all = true },
                    startRecognition = {}, pauseRecognition = {}, delete = {}
                )
            }
        }
        composeRule.onNodeWithText("录音文件 4 段 · 00:40").assertExists()
        composeRule.onNodeWithText("4 段待识别").assertExists()
        composeRule.onNodeWithTag("recording-${recordings[0].recordingId}").assertDoesNotExist()
        composeRule.onNodeWithTag("recognize-all").performClick()
        composeRule.onNodeWithContentDescription("展开录音文件").performClick()
        composeRule.onNodeWithTag("recording-${recordings[0].recordingId}").assertExists()
        composeRule.runOnIdle { assertTrue(all) }
    }

    @Test
    fun interruptedRecordingIsReadyToRecognizeWithNeutralNote() {
        var started: String? = null
        val recording = RecordingEntity(recordId = 1, sessionId = "s", localPath = "a.wav", startedAt = 0, durationMs = 5_000, totalFrames = 80_000, state = RecordingState.INTERRUPTED.name)
        composeRule.setContent {
            ListenTheme {
                RecordingItem(recording, 1, OfflineRecognitionState(), recognitionAllowed = true, startRecognition = { started = it }, pauseRecognition = {}, delete = {})
            }
        }
        composeRule.onNodeWithText("待识别").assertExists()
        composeRule.onNodeWithText("录音意外中断，已保留到 00:05。").assertExists()
        composeRule.onNodeWithTag("start-recognition").assertTextContains("开始识别").performClick()
        composeRule.runOnIdle { assertEquals(recording.recordingId, started) }
    }

    @Test
    fun pausedRecordingOffersResumeAndDelete() {
        val recording = RecordingEntity(recordId = 1, sessionId = "s", localPath = "a.wav", startedAt = 0, durationMs = 10_000, totalFrames = 160_000, processedFrames = 80_000, state = RecordingState.PAUSED.name)
        composeRule.setContent {
            ListenTheme {
                RecordingItem(recording, 2, OfflineRecognitionState(), recognitionAllowed = true, startRecognition = {}, pauseRecognition = {}, delete = {})
            }
        }
        composeRule.onNodeWithText("已暂停 50%").assertExists()
        composeRule.onNodeWithTag("start-recognition").assertTextContains("继续识别")
        composeRule.onNodeWithContentDescription("录音操作").performClick()
        composeRule.onNodeWithText("删除录音").assertExists()
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
        composeRule.onNodeWithText("本记录未完成 1").assertExists()
        composeRule.onNodeWithTag("asr-metric-queued").assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("asr-metric-submitting").assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("asr-metric-server").assertTextContains("2", substring = true)
        composeRule.onNodeWithTag("asr-metric-polling").assertTextContains("1", substring = true)
        composeRule.onNodeWithText("并发槽 3 / 3").assertExists()
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

        composeRule.onNodeWithTag("asr-diagnostics-list").performScrollToNode(hasTestTag("asr-diagnostic-processing-anchor-test"))
        composeRule.onNodeWithText("服务端等待 —（估算中）").assertExists()
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

        // LazyColumn only composes visible rows; scroll first so small screens pass too.
        composeRule.onNodeWithTag("asr-diagnostics-list").performScrollToNode(hasTestTag("asr-diagnostic-preview-16"))
        composeRule.onNodeWithTag("asr-diagnostic-preview-16").assertExists()
        composeRule.onNodeWithTag("asr-diagnostic-preview-1").assertDoesNotExist()
        composeRule.onNodeWithTag("asr-diagnostics-list").performScrollToNode(hasTestTag("asr-more-button"))
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
    fun longPressInDragSelectionListKeepsTheLineSelectedAfterRelease() {
        val segments = (1L..3L).map { id ->
            TranscriptEntity(id = id, recordId = 1, sessionId = "s", startTime = id * 1_000, endTime = id * 1_000 + 900, audioDurationMs = 900, recognitionDurationMs = null, text = "第 $id 句")
        }
        var selected by mutableStateOf(emptySet<Long>())
        composeRule.setContent {
            ListenTheme {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val controller = rememberDragSelectionController(listState, segments.map { it.id }, selected) { selected = it }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().dragSelectionViewport(controller), state = listState) {
                    items(segments.size) { index ->
                        val segment = segments[index]
                        TranscriptLine(
                            segment = segment,
                            position = linePosition(index, segments.size),
                            selected = segment.id in selected,
                            selectionMode = selected.isNotEmpty(),
                            dragSelectionEnabled = true,
                            modifier = Modifier.dragSelectableItem(segment.id, controller)
                        ) { selected = if (segment.id in selected) selected - segment.id else selected + segment.id }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("segment-2").performTouchInput { longClick() }

        composeRule.runOnIdle { assertEquals(setOf(2L), selected) }
        composeRule.onNodeWithTag("segment-2").assertIsSelected()
        // A plain tap in selection mode still toggles another line.
        composeRule.onNodeWithTag("segment-3").performClick()
        composeRule.runOnIdle { assertEquals(setOf(2L, 3L), selected) }
    }

    @Test
    fun selectionTopBarCopiesSelectedText() {
        var copied = false
        composeRule.setContent {
            ListenTheme { RecordSelectionTopBar(2, aiEnabled = true, close = {}, process = {}, delete = {}, copy = { copied = true }) }
        }
        composeRule.onNodeWithContentDescription("复制").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(copied) }
    }

    @Test
    fun longPressSelectsTranscriptWithoutChangingText() {
        val segment = TranscriptEntity(
            id = 7,
            recordId = 1,
            sessionId = "test-session",
            startTime = 1_000,
            endTime = 2_000,
            audioDurationMs = 1_000,
            recognitionDurationMs = 100,
            text = "永久保留的原始识别文本"
        )
        var selected by mutableStateOf(false)
        composeRule.setContent {
            ListenTheme {
                TranscriptLine(
                    segment = segment,
                    position = LinePosition.ONLY,
                    selected = selected,
                    selectionMode = selected,
                    dragSelectionEnabled = false
                ) { selected = !selected }
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
        listOf("整理成笔记", "导出 TXT", "AI 结果", "选择片段", "ASR 提示词").forEach {
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
    fun settingsOverviewIncludesManualCloudSyncEntry() {
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
        composeRule.onNodeWithText("云同步").assertExists()
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
