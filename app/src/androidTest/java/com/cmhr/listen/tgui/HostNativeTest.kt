package com.cmhr.listen.tgui

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cmhr.listen.ListeningForegroundService
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.recording.RecordingRepository
import com.cmhr.listen.data.recording.RecordingState
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.recording.ClassroomCaptureRuntime
import com.cmhr.listen.ui.NativeSettingsFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.telegram.ui.ActionBar.INavigationLayout
import java.io.File

/** Real microphone/service lifecycle on the debug emulator; no ASR or AI credentials. */
class HostNativeTest : NativeSettingsFixture() {
    private lateinit var database: ListenDatabase
    private lateinit var courses: CourseRepository
    private var courseId = 0L
    private var recordId = 0L
    private var courseName = ""

    @Before fun createHostFixture() {
        database = ListenDatabase.get(context)
        courses = CourseRepository(database)
        runBlocking {
            repository.clearApiKey()
            repository.clearAiApiKey()
            repository.setAutoNotes(false)
            courseName = "宿主测试课程${SystemClock.uptimeMillis()}"
            courseId = courses.createCourse(courseName)
            recordId = courses.createRecord(courseId, "宿主录音测试课堂")
            repository.selectRecord(courseId, recordId)
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun removeHostFixture() {
        try {
            scenario?.onActivity { it.stt.endClass() }
            if (::database.isInitialized && recordId > 0) {
                await {
                    !ClassroomCaptureRuntime.get(context).state.value.active &&
                        runBlocking { database.recordingDao().observeForSession(recordId).first().none { it.recordingState == RecordingState.RECORDING } }
                }
                runBlocking {
                    RecordingRepository(context).deleteForSession(recordId)
                    courses.deleteCourse(courseId)
                }
            }
        } finally { device.unfreezeRotation() }
    }

    @Test fun recordOnlySurvivesHomeAndPauseResumeThenSavesAudio() {
        launch("record/$recordId")
        click("开始录音")
        click("仅录音")
        await { ClassroomCaptureRuntime.get(context).state.value.let { it.active && it.recordId == recordId && it.mode == CaptureMode.RECORD_ONLY } }
        awaitForeground(true)
        val initialNotification = recordingNotification()!!
        assertTrue(initialNotification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue(initialNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(initialNotification.`when` > 0)
        assertEquals(listOf("结束"), initialNotification.actions.map { it.title.toString() })

        // Accumulate a real 15 seconds of AudioRecord data, rather than a synthetic WAV fixture.
        waitFor(30_000) { ClassroomCaptureRuntime.get(context).state.value.durationMs >= 15_000 }
        val beforeHome = ClassroomCaptureRuntime.get(context).state.value.durationMs
        device.pressHome()
        await { device.currentPackageName != context.packageName }
        waitFor(10_000) { ClassroomCaptureRuntime.get(context).state.value.durationMs >= beforeHome + 3_000 }
        awaitForeground(true)
        assertEquals(initialNotification.`when`, recordingNotification()!!.`when`)
        context.startActivity(Intent(context, ListenHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await { device.currentPackageName == context.packageName }
        text("暂停")
        assertRoute("record/$recordId")
        click("暂停")
        var pausedElapsed = 0L
        await {
            scenario!!.onActivity { pausedElapsed = it.stt.uiState.value.pausedClass?.takeIf { it.recordId == recordId }?.elapsedMs ?: 0 }
            pausedElapsed >= 18_000 && !ClassroomCaptureRuntime.get(context).state.value.active
        }
        awaitForeground(false)
        click("继续")
        await { ClassroomCaptureRuntime.get(context).state.value.active }
        awaitForeground(true)
        // Each resumed capture starts a new WAV; the class clock alone keeps the paused offset.
        waitFor(10_000) { ClassroomCaptureRuntime.get(context).state.value.durationMs >= 2_000 }
        scenario!!.onActivity { host ->
            val state = host.stt.uiState.value
            assertEquals(recordId, state.activeRecordId)
            assertTrue(SystemClock.elapsedRealtime() - requireNotNull(state.listeningStartedAtElapsedRealtimeMs) >= pausedElapsed)
        }
        click("结束课堂")
        click("确定")
        await { !ClassroomCaptureRuntime.get(context).state.value.active }
        awaitForeground(false)
        await { runBlocking { database.recordingDao().observeForSession(recordId).first().let { rows -> rows.size >= 2 && rows.all { it.recordingState == RecordingState.RECORDED } } } }
        val saved = runBlocking { database.recordingDao().observeForSession(recordId).first() }
        assertTrue(saved.sumOf { it.durationMs } >= 20_000)
        saved.forEach { row ->
            assertTrue(row.totalFrames > 0)
            val audio = File(File(context.filesDir, RecordingRepository.DIRECTORY), row.localPath)
            assertTrue("Each finalized recording must exist on disk", audio.isFile && audio.length() > 44)
        }
        assertNotNull(runBlocking { database.recordDao().recordNow(recordId)?.endedAt })
        assertTrue(runBlocking { database.aiDao().results(recordId).first().isEmpty() })
    }

    @Test fun notificationEntryDoesNotOverrideSavedRouteOnRecreate() {
        scenario = ActivityScenario.launch(Intent(context, ListenHostActivity::class.java)
            .putExtra(ListeningForegroundService.EXTRA_RECORD_ID, recordId))
        assertRoute("record/$recordId")
        assertTitle("宿主录音测试课堂")
        // Force native view reconstruction after data already arrived: no new Room emission
        // is required to bind the dynamic title, including after process/back-stack restoration.
        scenario!!.onActivity { it.actionBarLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST) }
        assertTitle("宿主录音测试课堂")
        scenario!!.onActivity { it.open("courses") }
        assertRoute("courses")
        scenario!!.onActivity { it.open("course/$courseId") }
        assertRoute("course/$courseId")
        assertTitle(courseName)
        scenario!!.onActivity { it.actionBarLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST) }
        assertTitle(courseName)
        scenario!!.recreate()
        assertRoute("course/$courseId")
        assertTitle(courseName)
        scenario!!.onActivity { it.open("record/$recordId") }
        assertRoute("record/$recordId")
        assertTitle("宿主录音测试课堂")
        scenario!!.recreate()
        assertRoute("record/$recordId")
        assertTitle("宿主录音测试课堂")
        device.pressBack()
        assertRoute("course/$courseId")
        assertTitle(courseName)
        // Temporary viewers are intentionally not serialized; no source text enters the Bundle.
        scenario!!.onActivity { it.presentFragment(AiMarkdownFragment("临时正文", "只属于本地测试的临时内容")) }
        await {
            var ready = false
            scenario!!.onActivity { ready = it.lastFragment is AiMarkdownFragment && !it.actionBarLayout.isTransitionAnimationInProgress }
            ready
        }
        scenario!!.recreate()
        assertRoute("course/$courseId")
        assertTitle(courseName)
        device.pressBack()
        assertRoute("courses")
        text("新建课程")
        device.pressBack()
        assertRoute("record/$recordId")
        assertTitle("宿主录音测试课堂")
        device.pressBack()
        assertRoute("home")
    }

    @Test fun rotationKeepsContentInsideSystemBarsAndLeftEdgeSwipeReturns() {
        launch("courses")
        scenario!!.onActivity { it.open("course/$courseId") }
        assertRoute("course/$courseId")
        try {
            device.setOrientationLeft()
            await {
                var landscape = false
                scenario!!.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            assertContentInsets()
            device.setOrientationNatural()
            await {
                var portrait = false
                scenario!!.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                portrait
            }
            assertContentInsets()
            device.swipe(2, device.displayHeight / 2, device.displayWidth * 4 / 5, device.displayHeight / 2, 35)
            assertRoute("courses")
        } finally { device.unfreezeRotation() }
    }

    private fun assertContentInsets() {
        await {
            var checked = false
            scenario!!.onActivity { host ->
                val root = host.lastFragment.fragmentView ?: return@onActivity
                if (root.width == 0 || root.height == 0 || root.isLayoutRequested) return@onActivity
                val decor = host.window.decorView
                val bars = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: return@onActivity
                val xy = IntArray(2)
                val windowXY = IntArray(2)
                root.getLocationOnScreen(xy)
                decor.getLocationOnScreen(windowXY)
                val bounds = Rect(xy[0], xy[1], xy[0] + root.width, xy[1] + root.height)
                assertTrue("Content must clear the status bar", bounds.top >= windowXY[1] + bars.top)
                assertTrue("Content must clear the left cutout/navigation inset", bounds.left >= windowXY[0] + bars.left)
                assertTrue("Content must clear the right cutout/navigation inset", bounds.right <= windowXY[0] + decor.width - bars.right)
                assertTrue("Content must clear the navigation bar", bounds.bottom <= windowXY[1] + decor.height - bars.bottom)
                checked = true
            }
            checked
        }
    }

    private fun assertRoute(expected: String) = await {
        var route: String? = null
        scenario!!.onActivity { if (!it.actionBarLayout.isTransitionAnimationInProgress) route = (it.lastFragment as? ListenListFragment)?.route }
        route == expected
    }

    private fun assertTitle(expected: String) = await {
        var actual: String? = null
        scenario!!.onActivity { actual = it.lastFragment.actionBar.title }
        actual == expected
    }

    private fun recordingNotification(): Notification? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.notification.channelId == "listening" }?.notification

    @Suppress("DEPRECATION")
    private fun awaitForeground(expected: Boolean) = await {
        val service = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == ListeningForegroundService::class.java.name }
        if (expected) service?.foreground == true && recordingNotification() != null
        else service?.foreground != true && recordingNotification() == null
    }

    private fun waitFor(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for real capture progress", predicate())
    }
}
