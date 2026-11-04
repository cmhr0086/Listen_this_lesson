package com.cmhr.listen.ui

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.PausedClass
import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.VadPreset
import com.cmhr.listen.audio.WavEncoder
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingRepository
import com.cmhr.listen.data.recording.RecordingState
import com.cmhr.listen.data.stt.AsrClockBasis
import com.cmhr.listen.data.stt.AsrLifecycleState
import com.cmhr.listen.data.stt.AsrQueueRuntime
import com.cmhr.listen.data.stt.AsrSegmentDiagnosticEntity
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.tgui.AiMarkdownFragment
import com.cmhr.listen.tgui.captureStatus
import com.cmhr.listen.tgui.duration
import io.noties.markwon.ext.tables.TableRowSpan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.telegram.ui.Components.ChatActivityEnterView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real native UI, Room fixtures and local-only services replace Compose component fixtures. */
class UiInteractionTest : NativeSettingsFixture() {
    private lateinit var database: ListenDatabase
    private lateinit var courses: CourseRepository
    private val createdCourses = mutableListOf<Long>()
    private val createdQueueFiles = mutableListOf<File>()
    private var courseId = 0L
    private var recordId = 0L
    private var className = ""
    private var courseName = ""

    @Before fun createClassFixture() {
        database = ListenDatabase.get(context)
        courses = CourseRepository(database)
        val suffix = SystemClock.uptimeMillis()
        courseName = "界面测试课程$suffix"
        className = "界面测试课堂$suffix"
        runBlocking {
            courseId = courses.createCourse(courseName).also(createdCourses::add)
            recordId = courses.createRecord(courseId, className)
            repository.selectRecord(courseId, recordId)
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun removeClassFixture() {
        scenario?.onActivity { it.stt.endClass(); it.recordings.stopRecognition() }
        if (scenario != null) await {
            var idle = false
            scenario!!.onActivity { idle = !it.stt.uiState.value.isListening && it.stt.uiState.value.pausedClass == null && it.recordings.uiState.value.processing.activeRecordingId == null }
            idle
        }
        if (::database.isInitialized) runBlocking {
            createdCourses.forEach { id ->
                database.recordDao().idsForCourse(id).forEach { RecordingRepository(context).deleteForSession(it) }
                courses.deleteCourse(id)
            }
        }
        createdQueueFiles.forEach { it.delete() }
    }

    @Test fun nativeComposerAndContentStayInsideTheViewport() {
        launch("ai/new")
        var checked = false
        await {
            scenario!!.onActivity { host ->
                val root = host.lastFragment.fragmentView ?: return@onActivity
                val input = descendants(root).filterIsInstance<ChatActivityEnterView>().firstOrNull() ?: return@onActivity
                if (input.height == 0) return@onActivity
                val xy = IntArray(2); input.getLocationOnScreen(xy)
                val rootXY = IntArray(2); root.getLocationOnScreen(rootXY)
                assertTrue(xy[1] >= rootXY[1])
                assertTrue(xy[1] + input.height <= rootXY[1] + root.height)
                assertTrue(input.width <= root.width)
                checked = true
            }
            checked
        }
        val editor = device.wait(Until.findObject(By.desc("输入消息")), 5000)!!
        editor.click()
        device.waitForIdle()
        assertTrue(device.hasObject(By.desc("输入消息")))
        device.pressBack()
    }

    @Test fun newCourseActionShowsItsNameAndCreatesTheCourse() {
        launch("courses")
        click("新建课程")
        replace("新建测试课程")
        click("保存")
        await { runBlocking { database.courseDao().idByName("新建测试课程") != null } }
        val id = runBlocking { database.courseDao().idByName("新建测试课程")!! }
        createdCourses += id
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("新建测试课程")), 5000))
    }

    @Test fun idleCapturePanelOffersBothModesAndStartsTheChosenOne() {
        launch("record/$recordId")
        click("开始录音")
        text("即时识别"); text("仅录音")
        click("仅录音")
        awaitListening(true)
        var mode: CaptureMode? = null
        scenario!!.onActivity { mode = it.stt.uiState.value.captureMode }
        assertEquals(CaptureMode.RECORD_ONLY, mode)
    }

    @Test fun recordOnlyPanelShowsSavedLengthAndStops() {
        beginRecording()
        await {
            var saved = 0L
            scenario!!.onActivity { saved = it.stt.uiState.value.recordOnlySavedMs }
            saved > 0L
        }
        assertTrue(device.hasObject(By.textContains("仅录音中")))
        assertTrue(device.hasObject(By.textContains("已安全保存")))
        assertFalse(device.hasObject(By.textContains("检测到语音")))
        endFromUi()
        val files = runBlocking { database.recordingDao().observeForSession(recordId).first() }
        assertTrue(files.any { it.durationMs > 0 && it.recordingState == RecordingState.RECORDED })
    }

    @Test fun capturingPanelOffersPauseAndEnd() {
        beginRecording()
        click("暂停")
        awaitPaused()
        text("继续"); text("结束课堂")
        endFromUi()
        assertNotNull(runBlocking { database.recordDao().recordNow(recordId)?.endedAt })
    }

    @Test fun pausedClassResumesFromTheControlBar() {
        beginRecording()
        click("暂停")
        awaitPaused()
        assertTrue(device.hasObject(By.textContains("已暂停")))
        click("继续")
        awaitListening(true)
        scenario!!.onActivity { assertEquals(recordId, it.stt.uiState.value.activeRecordId) }
        assertFalse(device.hasObject(By.text("回到最新")))
        val paused = ListeningUiState(pausedClass = PausedClass(recordId, CaptureMode.RECORD_ONLY, courseName, className, 754_000))
        assertTrue(captureStatus(paused).contains("12:34"))
    }

    @Test fun controlBarOffersContinueForAFinishedClass() {
        runBlocking { courses.finishRecord(recordId) }
        beginRecording()
        scenario!!.onActivity { assertEquals(recordId, it.stt.uiState.value.activeRecordId) }
        assertEquals(CaptureMode.RECORD_ONLY, currentListening().captureMode)
        assertFalse(device.hasObject(By.text("回到最新")))
    }

    @Test fun controlBarWhileCapturingShowsPauseAndEnd() {
        beginRecording()
        text("暂停"); text("结束课堂")
        val realtime = currentListening().copy(captureMode = CaptureMode.REALTIME_ASR, pendingQueueCount = 2)
        assertTrue(captureStatus(realtime).contains("即时识别"))
        assertTrue(captureStatus(realtime).contains("2 段"))
        click("暂停")
        awaitPaused()
    }

    @Test fun realtimePanelShowsSpeechAndQueue() {
        val realtime = ListeningUiState(isListening = true, isSpeechDetected = true, pendingQueueCount = 3,
            activeRecordId = recordId, captureMode = CaptureMode.REALTIME_ASR,
            listeningStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime() - 2000)
        // Same formatter is bound by HomeFragment/ClassroomFragment; deterministic speech fixture
        // avoids pretending emulator microphone silence is a real VAD-positive observation.
        val status = captureStatus(realtime)
        assertTrue(status.contains("即时识别"))
        assertTrue(status.contains("正在收音"))
        assertTrue(status.contains("3 段排队识别"))
    }

    @Test fun capturePanelIsBlockedWhileAnotherRecordCaptures() {
        beginRecording()
        val other = runBlocking { courses.createRecord(courseId, "另一节课堂") }
        scenario!!.onActivity { it.open("record/$other") }
        assertTrue(device.wait(Until.hasObject(By.textContains(className)), 5000))
        val start = describedRow("开始录音")
        assertTrue(start == null || !start.isEnabled)
        assertEquals(recordId, currentListening().activeRecordId)
        assertFalse(device.hasObject(By.text("暂停")))
    }

    @Test fun homeStartsAClassWithTheSuggestedCourseWithoutNavigating() {
        runBlocking {
            database.recordDao().insert(ClassRecordEntity(courseId = courseId, name = "上周课堂", startedAt = System.currentTimeMillis() - 7L * 24 * 3600_000))
        }
        launch("home")
        await { var matches = false; scenario!!.onActivity { matches = it.courses.uiState.value.suggestion?.courseId == courseId }; matches }
        assertTrue(device.hasObject(By.textContains(courseName)))
        text("更换课程")
        click("仅录音")
        awaitListening(true)
        val startedId = currentListening().activeRecordId!!
        assertEquals(courseId, runBlocking { database.recordDao().recordNow(startedId)!!.courseId })
        assertNotEquals(recordId, startedId)
    }

    @Test fun coursesTabShowsClassCountAndOpensTheCourse() {
        runBlocking { repeat(6) { courses.createRecord(courseId, "课堂${it + 2}") } }
        val emptyId = runBlocking { courses.createCourse("空课程").also(createdCourses::add) }
        launch("courses")
        val populated = device.wait(Until.findObject(By.descStartsWith(courseName)), 5000)!!
        assertTrue(populated.contentDescription.contains("7 节"))
        val empty = device.wait(Until.findObject(By.descStartsWith("空课程")), 5000)!!
        assertTrue(empty.contentDescription.contains("0 节") || empty.contentDescription.contains("还没有课堂"))
        populated.click()
        await { var selected: Long? = null; scenario!!.onActivity { selected = it.courses.uiState.value.selectedCourse?.id }; selected == courseId }
        assertNotEquals(courseId, emptyId)
    }

    @Test fun coursePickerConfirmsTheChosenCourse() {
        val target = runBlocking { courses.createCourse("迁移目标课程").also(createdCourses::add) }
        launch("record/$recordId")
        scrollTo("换课程")
        click("换课程")
        click("迁移目标课程")
        await { runBlocking { database.recordDao().recordNow(recordId)?.courseId == target } }
        assertEquals(target, runBlocking { database.recordDao().recordNow(recordId)!!.courseId })
    }

    @Test fun recordingsStayBehindOneSummaryAndOfferRecognizeAll() {
        val recordings = (0 until 4).map { addRecording(RecordingState.RECORDED, 10_000) }
        launch("record/$recordId")
        scrollTo("录音与补识别")
        assertTrue(row("录音与补识别").contentDescription.contains("4 段待识别"))
        assertFalse(device.hasObject(By.text("录音 1")))
        click("录音与补识别")
        text("录音文件 4 段 · 00:40")
        text("4 段待识别")
        text("录音 1")
        click("全部识别")
        await { runBlocking { database.recordingDao().recording(recordings.first().recordingId)?.state != RecordingState.RECORDED.name } }
        assertTrue(runBlocking { database.recordingDao().observeForSession(recordId).first().any { it.recordingState != RecordingState.RECORDED } })
    }

    @Test fun interruptedRecordingIsReadyToRecognizeWithNeutralNote() {
        val recording = addRecording(RecordingState.INTERRUPTED, 5000)
        launch("recordings/$recordId")
        assertTrue(device.wait(Until.hasObject(By.textContains("录音意外中断，已保留到 00:05。")), 5000))
        assertTrue(row("录音 1").contentDescription.contains("待识别"))
        click("录音 1")
        click("开始识别")
        await { runBlocking { database.recordingDao().recording(recording.recordingId)?.state != RecordingState.INTERRUPTED.name } }
    }

    @Test fun pausedRecordingOffersResumeAndDelete() {
        addRecording(RecordingState.PAUSED, 10_000, processedFrames = 80_000)
        launch("recordings/$recordId")
        assertTrue(device.wait(Until.hasObject(By.textContains("已暂停 50%")), 5000))
        click("录音 1")
        text("继续识别")
        text("删除录音")
    }

    @Test fun currentRecordDiagnosticsShowLifecycleAndHealthRefreshIsManual() {
        launch("home")
        val healthCalls = AtomicInteger()
        val pollStarted = CountDownLatch(2)
        val releasePoll = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/health") {
                    healthCalls.incrementAndGet()
                    return MockResponse().setBody("{\"status\":\"ok\",\"model\":\"fixture-model\",\"queued_jobs\":0,\"processing_jobs\":1,\"max_queue_depth\":3}")
                }
                pollStarted.countDown()
                releasePoll.await(90, TimeUnit.SECONDS)
                if (request.method == "POST") return MockResponse().setResponseCode(503)
                return MockResponse().setBody("{\"id\":\"fixture-job\",\"status\":\"processing\"}")
            }
        }
        try {
            val pending = diagnostic(1, AsrLifecycleState.QUEUED_LOCAL)
            val audio = File(File(context.noBackupFilesDir, "asr_queue").apply { mkdirs() }, "ui-$recordId-2.wav")
            createdQueueFiles += audio
            audio.writeBytes(WavEncoder.encodePcm16Mono(ByteArray(32_000), PcmRecorder.SAMPLE_RATE_HZ))
            val submitting = diagnostic(2, AsrLifecycleState.QUEUED_LOCAL).copy(wavRelativePath = audio.name, nextAttemptAt = System.currentTimeMillis())
            val processing = diagnostic(3, AsrLifecycleState.PROCESSING).copy(jobId = "fixture-job", nextAttemptAt = System.currentTimeMillis())
            val queuedServer = diagnostic(4, AsrLifecycleState.QUEUED_SERVER).copy(jobId = "fixture-job-2")
            runBlocking { listOf(pending, submitting, processing, queuedServer).forEach { database.asrDiagnosticsDao().insertSegment(it) } }
            AsrQueueRuntime.get(context).kick()
            assertTrue(pollStarted.await(10, TimeUnit.SECONDS))
            scenario!!.onActivity { it.open("settings/asr") }
            text("采集与语音检测")
            assertEquals(0, healthCalls.get())
            assertFalse(device.hasObject(By.text("全部记录")))
            assertRowValue("等待上传", "1")
            assertRowValue("正在提交", "1")
            assertRowValue("服务端处理中", "2")
            assertRowValue("正在轮询", "1")
            assertRowValue("全局并发", "3 / 3")
            scrollTo("刷新服务状态")
            click("刷新服务状态")
            await { healthCalls.get() == 1 }
            scrollTo("片段 1 · 本地排队")
            assertTrue(row("片段 1 · 本地排队").contentDescription.contains("客户端排队"))
        } finally { releasePoll.countDown() }
    }

    @Test fun processingWithoutValidServerAnchorShowsEstimatingInsteadOfDeviceUptime() {
        launch("home")
        val d = diagnostic(1, AsrLifecycleState.PROCESSING).copy(jobId = "anchor-job", submitCompletedAt = System.currentTimeMillis() - 1000, submitCompletedElapsedMs = null)
        runBlocking { database.asrDiagnosticsDao().insertSegment(d) }
        scenario!!.onActivity { it.open("settings/asr-segment/$recordId/${d.segmentId}") }
        assertRowValue("当前阶段", "服务端等待 —（估算中）")
        assertFalse(device.hasObject(By.textContains("81188.9")))
        assertRowValue("服务端等待（估算）", "—（估算中）")
    }

    @Test fun diagnosticsPreviewShowsFifteenThenOpensCurrentRecordHistory() {
        launch("home")
        runBlocking { (1..16).forEach { database.asrDiagnosticsDao().insertSegment(diagnostic(it, AsrLifecycleState.COMPLETED)) } }
        scenario!!.onActivity { it.open("settings/asr") }
        scrollTo("片段 16 · 已完成")
        text("片段 16 · 已完成")
        scrollTo("查看全部片段")
        assertFalse(device.hasObject(By.text("片段 1 · 已完成")))
        assertNull(describedRow("片段 1 · 已完成"))
        click("查看全部片段")
        text("诊断历史")
        scrollTo("片段 1 · 已完成")
        text("片段 1 · 已完成")
        scenario!!.onActivity { assertEquals("settings/asr-history/$recordId", (it.lastFragment as com.cmhr.listen.tgui.SettingsFragment).route) }
    }

    @Test fun diagnosticsHistoryContainsTheSixteenthEntry() {
        launch("home")
        runBlocking { (1..16).forEach { database.asrDiagnosticsDao().insertSegment(diagnostic(it, AsrLifecycleState.COMPLETED)) } }
        scenario!!.onActivity { it.open("settings/asr-history/$recordId") }
        text("片段 16 · 已完成")
        scrollTo("片段 1 · 已完成")
        click("片段 1 · 已完成")
        assertRowValue("片段", "ui-$recordId-1")
    }

    @Test fun smoothElapsedTextRefreshesAtTenthsOfASecond() {
        launch("home")
        val d = diagnostic(1, AsrLifecycleState.QUEUED_LOCAL).copy(captureStartedElapsedMs = SystemClock.elapsedRealtime(), queuedLocalElapsedMs = SystemClock.elapsedRealtime())
        runBlocking { database.asrDiagnosticsDao().insertSegment(d) }
        scenario!!.onActivity { it.open("settings/asr-segment/$recordId/${d.segmentId}") }
        text("总耗时")
        val before = row("总耗时").contentDescription
        val started = SystemClock.uptimeMillis()
        var changed = false
        while (!changed && SystemClock.uptimeMillis() - started < 900) {
            changed = row("总耗时").contentDescription != before
        }
        assertTrue("活动片段耗时应在一秒内刷新，不等待整秒", changed)
        assertTrue(row("总耗时").contentDescription.contains("秒"))
    }

    @Test fun lastVadChoiceRemainsReachableAndSelectable() {
        launch("settings/vad")
        scrollTo("高噪声环境")
        click("高噪声环境")
        await { var selected: VadPreset? = null; scenario!!.onActivity { selected = it.stt.uiState.value.selectedVadPreset }; selected == VadPreset.NOISY }
    }

    @Test fun longPressSelectionKeepsTheLineSelectedAfterRelease() {
        addTranscript()
        launch("transcript/$recordId")
        selectLine("第 2 句")
        text("已选 1 段")
        assertTrue(bubble("第 2 句").isSelected)
        bubble("第 3 句").click()
        text("已选 2 段")
        assertTrue(bubble("第 3 句").isSelected)
    }

    @Test fun selectionTopBarCountsAndSelectsAll() {
        addTranscript()
        launch("transcript/$recordId")
        selectLine("第 1 句")
        bubble("第 2 句").click()
        text("已选 2 段")
        click("全选")
        text("已选 3 段")
        (1..3).forEach { assertTrue(bubble("第 $it 句").isSelected) }
    }

    @Test fun selectionActionBarCopiesWithoutChangingMarkedData() {
        addTranscript()
        launch("transcript/$recordId")
        selectLine("第 1 句")
        bubble("第 2 句").click()
        text("已选 2 段")
        device.wait(Until.findObject(By.desc("复制所选原文")), 5000)!!.click()
        var copied = ""
        scenario!!.onActivity { host -> copied = host.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(host).toString() }
        assertEquals("第 1 句\n第 2 句", copied)
        assertFalse(device.hasObject(By.text("标为重点")))
        assertFalse(device.hasObject(By.text("取消重点")))
        val stored = runBlocking { courses.segments(recordId).first() }
        assertEquals(listOf("第 1 句", "第 2 句", "第 3 句"), stored.map { it.text })
        assertTrue(stored.none { it.marked })
    }

    @Test fun longPressSelectsTranscriptWithoutChangingText() {
        val original = "永久保留的原始识别文本"
        runBlocking { courses.saveSegment(recordId, System.currentTimeMillis(), System.currentTimeMillis() + 1000, 1000, 100, original) }
        launch("transcript/$recordId")
        selectLine(original)
        assertTrue(bubble(original).isSelected)
        assertEquals(original, runBlocking { courses.segments(recordId).first().single().text })
    }

    @Test fun paragraphHeaderSelectsAndDeselectsTheWholeParagraph() {
        addTranscript()
        launch("transcript/$recordId")
        selectLine("第 1 句")
        click("选择整段")
        text("已选 3 段")
        (1..3).forEach { assertTrue(bubble("第 $it 句").isSelected) }
        click("取消整段")
        text("已选 0 段")
        assertFalse(device.hasObject(By.text("已选 3 段")))
        (1..3).forEach { assertFalse(bubble("第 $it 句").isSelected) }
    }

    @Test fun emptySelectionCannotCopyOrAskAi() {
        addTranscript()
        launch("transcript/$recordId")
        selectLine("第 1 句")
        bubble("第 1 句").click()
        text("已选 0 段")
        await { !bubble("第 1 句").isSelected }
        listOf("复制所选原文", "问 AI").forEach { description ->
            val action = device.findObject(By.desc(description))
            assertTrue("空选择不应启用 $description", action == null || !action.isEnabled)
        }
    }

    @Test fun transcriptMenuContainsAllRetainedActions() {
        addTranscript()
        launch("transcript/$recordId")
        val more = device.findObject(By.desc("更多操作"))
        more?.click()
        text("导出 TXT")
        text("选择片段")
        assertFalse(device.hasObject(By.text("专业词提示")))
        assertFalse(device.hasObject(By.text("整理成笔记")))
        click("选择片段")
        val copy = device.findObject(By.desc("复制所选原文"))
        assertTrue(copy == null || !copy.isEnabled)
    }

    @Test fun markdownRendererAcceptsHeadingTableTaskListAndStrikethrough() {
        launch("home")
        val markdown = "## 二级标题\n\n| 项目 | 内容 |\n| --- | --- |\n| 课程 | 数学 |\n\n- [x] 已完成\n\n~~删除线~~"
        scenario!!.onActivity { it.presentFragment(AiMarkdownFragment("排版测试", markdown)) }
        text("排版测试")
        var rendered = ""
        var tableCells = emptyList<String>()
        await {
            var ready = false
            scenario!!.onActivity { host ->
                val text = descendants(host.lastFragment.fragmentView).filterIsInstance<TextView>().firstOrNull { it.tag == "ai-markdown-full" }
                if (text?.layout == null) return@onActivity
                rendered = text.text.toString()
                assertTrue(text.text is android.text.Spanned)
                assertTrue(text.layout.height > 0)
                val spanned = text.text as android.text.Spanned
                val tableRows = spanned.getSpans(0, spanned.length, TableRowSpan::class.java)
                assertEquals("表格必须实际渲染标题行和内容行", 2, tableRows.size)
                tableCells = tableRows.flatMap { row ->
                    listOf(0, row.cellWidth()).map { x ->
                        val cell = row.findLayoutForHorizontalOffset(x)
                        assertNotNull("表格单元格必须有排版结果", cell)
                        assertTrue(cell!!.height > 0)
                        cell.text.toString()
                    }
                }
                ready = true
            }
            ready
        }
        listOf("二级标题", "已完成", "删除线").forEach { assertTrue("渲染内容缺少 $it", rendered.contains(it)) }
        assertEquals(listOf("项目", "内容", "课程", "数学"), tableCells)
        assertFalse(rendered.contains("~~"))
    }

    @Test fun settingsOverviewIncludesManualCloudSyncEntry() {
        runBlocking { repository.setDeveloperMode(false) }
        launch("settings")
        listOf("语音识别", "AI 服务", "云同步").forEach { text(it) }
        scrollTo("下课后自动整理笔记")
        text("下课后自动整理笔记")
        assertFalse(device.hasObject(By.text("ASR 诊断")))
        scrollTo("开发者模式")
        click("开发者模式")
        scrollTo("ASR 诊断")
        text("ASR 诊断")
    }

    @Test fun persistentDeleteRequiresTwoSecondConfirmation() {
        addTranscript()
        launch("transcript/$recordId")
        bubble("第 1 句").longClick()
        click("删除")
        val initial = device.wait(Until.findObject(By.textStartsWith("删除（")), 1000)
        assertNotNull("必须显示不可立即执行的删除倒计时", initial)
        assertFalse(initial!!.isEnabled)
        assertEquals(3, runBlocking { courses.segments(recordId).first().size })
        val allowed = device.wait(Until.findObject(By.text("删除").enabled(true)), 5_000)
        assertNotNull("两秒保护结束后允许删除", allowed)
        allowed!!.click()
        await { runBlocking { courses.segments(recordId).first().size == 2 } }
        assertFalse(runBlocking { courses.segments(recordId).first().any { it.text == "第 1 句" } })
    }

    private fun addTranscript() = runBlocking {
        val start = System.currentTimeMillis()
        (1..3).forEach { courses.saveSegment(recordId, start + it * 1000, start + it * 1000 + 900, 900, null, "第 $it 句") }
    }
    private fun bubble(content: String): UiObject2 = device.wait(Until.findObject(By.desc(content)), 5000)
        ?: throw AssertionError("找不到原文气泡：$content")
    private fun selectLine(content: String) {
        bubble(content).longClick()
        click("多选")
        device.waitForIdle()
    }

    private fun assertRowValue(title: String, expected: String) {
        scrollTo(title)
        assertTrue("$title 应显示 $expected", row(title).contentDescription.contains(expected))
    }
    private fun diagnostic(index: Int, state: AsrLifecycleState): AsrSegmentDiagnosticEntity {
        val now = System.currentTimeMillis() - 5000 + index
        return AsrSegmentDiagnosticEntity(segmentId = "ui-$recordId-$index", recordId = recordId, sequenceNumber = index.toLong(), state = state.name,
            clockBasis = AsrClockBasis.ELAPSED_REALTIME.name, bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0),
            audioStartTime = now, audioEndTime = now + 1000, audioDurationMs = 1000,
            captureStartedAt = now, captureFinishedAt = now + 1000, queuedLocalAt = now + 1000,
            captureStartedElapsedMs = SystemClock.elapsedRealtime() - 5000,
            queuedLocalElapsedMs = SystemClock.elapsedRealtime() - 4000,
            finishedAt = if (state == AsrLifecycleState.COMPLETED) now + 2000 else null,
            finishedElapsedMs = if (state == AsrLifecycleState.COMPLETED) SystemClock.elapsedRealtime() - 3000 else null,
            nextAttemptAt = Long.MAX_VALUE)
    }
    private fun addRecording(state: RecordingState, ms: Long, processedFrames: Long = 0): RecordingEntity = runBlocking {
        val record = database.recordDao().recordNow(recordId)!!
        val value = RecordingEntity(recordId = recordId, sessionId = record.sessionId, localPath = "", startedAt = System.currentTimeMillis(),
            durationMs = ms, totalFrames = ms * 16, processedFrames = processedFrames, state = state.name)
        val folder = File(context.filesDir, RecordingRepository.DIRECTORY).apply { mkdirs() }
        val file = File(folder, RecordingRepository.audioFileName(value.recordingId))
        file.writeBytes(WavEncoder.encodePcm16Mono(ByteArray((ms * 32).toInt()), PcmRecorder.SAMPLE_RATE_HZ))
        val stored = value.copy(localPath = file.name)
        database.recordingDao().insert(stored)
        stored
    }

    private fun beginRecording() {
        launch("record/$recordId")
        click("开始录音")
        click("仅录音")
        awaitListening(true)
    }
    private fun currentListening(): ListeningUiState {
        var state: ListeningUiState? = null
        scenario!!.onActivity { state = it.stt.uiState.value }
        return state!!
    }
    private fun awaitListening(active: Boolean) = await { currentListening().isListening == active }
    private fun awaitPaused() = await { currentListening().pausedClass?.recordId == recordId }
    private fun endFromUi() {
        click("结束课堂")
        click("确定")
        await { val state = currentListening(); !state.isListening && state.pausedClass == null }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
