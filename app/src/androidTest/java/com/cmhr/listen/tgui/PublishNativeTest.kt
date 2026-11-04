package com.cmhr.listen.tgui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.text.Spanned
import android.text.StaticLayout
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.hamcrest.Matchers.allOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.cmhr.listen.DemoData
import com.cmhr.listen.data.ai.AiAttachmentStore
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.publish.PublishCourseState
import com.cmhr.listen.data.publish.PublishApiClient
import com.cmhr.listen.data.publish.termOf
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.SetupState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Components.ForegroundColorSpanThemable
import java.io.File
import java.util.concurrent.TimeUnit

/** Real native views, DemoData only. Requests and document picker results stay local. */
@RunWith(AndroidJUnit4::class)
class PublishNativeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val database by lazy { ListenDatabase.get(context) }
    private val settings by lazy { AppSettingsRepository(context) }
    private var scenario: ActivityScenario<ListenHostActivity>? = null
    private var server: MockWebServer? = null
    private var courseId = 0L

    @Before fun prepare() = runBlocking {
        check(context.packageName == "com.cmhr.listen.debug")
        // This suite never reads credential plaintext or permits a real bound-account request.
        settings.unbindDevice()
        settings.clearPublishToken()
        settings.setSetupState(SetupState.DONE)
        DemoData.seedIfEmpty(context)
        courseId = database.courseDao().courses().first().first { it.name == "示例 · 材料力学" }.id
        settings.updatePublishCourse(courseId) { PublishCourseState() }
        Intents.init()
    }

    @After fun finish() {
        scenario?.close()
        Intents.release()
        server?.shutdown()
        runBlocking {
            settings.clearPublishToken()
            settings.updatePublishCourse(courseId) { PublishCourseState() }
            val repository = CourseRepository(database, AiAttachmentStore(context))
            database.courseDao().courses().first().filter { it.name == IMPORT_COURSE }.forEach { repository.deleteCourse(it.id) }
        }
    }

    @Test fun searchFiltersNotesHighlightsMatchesAndOpensClassroom() {
        launch("search")
        found(By.desc("搜索文字和笔记"))
        onView(allOf(withContentDescription("搜索文字和笔记"), isDisplayed(), isAssignableFrom(EditText::class.java))).perform(replaceText("静矩"), closeSoftKeyboard())
        found(By.descStartsWith("范围，")).click()
        found(By.text("笔记")).click()
        assertFalse(device.hasObject(By.text("只看重点")))
        val note = found(By.descStartsWith("笔记. "))
        assertTrue(note.contentDescription.contains("静矩"))
        var highlighted = false
        scenario!!.onActivity { activity ->
            val field = DialogCell::class.java.getDeclaredField("messageLayout").apply { isAccessible = true }
            highlighted = descendants(activity.window.decorView).filterIsInstance<DialogCell>().any { cell ->
                val layout = field.get(cell) as? StaticLayout
                val text = layout?.text as? Spanned
                text?.getSpans(0, text.length, ForegroundColorSpanThemable::class.java)?.isNotEmpty() == true
            }
        }
        assertTrue("Search result must render a highlighted match", highlighted)
        note.click()
        eventually {
            var route = ""
            scenario!!.onActivity { route = (it.lastFragment as? ListenListFragment)?.route.orEmpty() }
            route.startsWith("record/")
        }
        device.pressBack()
        found(By.desc("范围，笔记"))
        onView(allOf(withContentDescription("搜索文字和笔记"), isDisplayed(), isAssignableFrom(EditText::class.java))).perform(replaceText("不存在的演示关键词"), closeSoftKeyboard())
        found(By.desc("没有找到结果"))
    }

    @Test fun publicationHubShowsCachedStatusAndCourseEditsRemainLocal() = runBlocking {
        settings.updatePublishCourse(courseId) {
            it.copy(remoteCourses = mapOf(TERM to "mock-course"), remoteStatus = mapOf(TERM to "approved"))
        }
        launch("publish")
        val row = found(By.descStartsWith("示例 · 材料力学. "))
        assertTrue(row.contentDescription.contains("已公开"))
        row.click()
        found(By.desc("尚未绑定设备"))
        scrolled(By.descStartsWith("学校，")).click()
        found(By.clazz(EditText::class.java))
        onView(allOf(isAssignableFrom(EditText::class.java), isDisplayed())).inRoot(isDialog())
            .perform(replaceText("演示学校"), closeSoftKeyboard())
        found(By.text("保存")).click()
        found(By.desc("学校，演示学校"))
        assertEquals("演示学校", settings.settings.first().publish.schoolName)
    }

    @Test fun courseMaterialsUploadViewAndDeleteUseOnlyMockServer() = runBlocking {
        settings.savePublishToken("instrumentation-placeholder")
        settings.updatePublishCourse(courseId) { it.copy(remoteCourses = mapOf(TERM to "mock-course")) }
        val mock = startServer()
        val baseUrl = mock.url("/").toString()
        val source = localDocument("演示笔记.md", "# 演示笔记\n测试内容".toByteArray())
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(source))
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        launch("home")
        eventually {
            var ready = false
            scenario!!.onActivity { ready = it.settings.uiState.value.publish.hasToken }
            ready
        }
        val materialService = MaterialService(baseUrl, requireHttps = false)
        scenario!!.onActivity { it.presentFragment(MaterialsFragment(courseId, service = materialService)) }
        mock.enqueue(MockResponse().setBody("{\"materials\":[]}"))
        found(By.desc("刷新资料")).click()
        found(By.desc("暂无资料"))
        assertEquals("/api/admin/v1/courses/mock-course/materials", request(mock).path)
        mock.enqueue(MockResponse().setBody(MATERIAL))
        mock.enqueue(MockResponse().setBody("{\"materials\":[$MATERIAL]}"))
        found(By.desc("上传笔记")).click()
        val uploaded = found(By.descStartsWith("演示笔记. "))
        val upload = request(mock)
        assertEquals("POST", upload.method)
        assertEquals("note", upload.requestUrl!!.queryParameter("category"))
        assertEquals("演示笔记.md", upload.requestUrl!!.queryParameter("fileName"))
        assertEquals("Bearer instrumentation-placeholder", upload.getHeader("Authorization"))
        assertTrue(upload.body.readUtf8().contains("演示笔记"))
        request(mock) // list after upload
        mock.enqueue(MockResponse().setBody("# 演示笔记\n测试内容"))
        uploaded.click()
        assertEquals("/api/admin/v1/materials/mock-material/file", request(mock).path)
        found(By.desc("刷新资料"))
        Intents.intended(hasAction(Intent.ACTION_CHOOSER))
        val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
        @Suppress("DEPRECATION")
        val viewer = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, viewer.action)
        assertEquals("content", viewer.data!!.scheme)
        assertTrue(viewer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(viewer.toUri(0).contains("instrumentation-placeholder"))
        found(By.descStartsWith("演示笔记. ")).longClick()
        found(By.text("删除")).click()
        found(By.text("删除资料？"))
        mock.enqueue(MockResponse().setBody("{\"deleted\":true}"))
        mock.enqueue(MockResponse().setBody("{\"materials\":[]}"))
        found(By.text("删除").enabled(true)).click()
        found(By.desc("暂无资料"))
        assertEquals("DELETE", request(mock).method)
        request(mock)
        assertEquals(6, mock.requestCount)
    }

    @Test fun timetableSelectsPdfShowsSlotsAndCreatesSelectedCourse() {
        val mock = startServer()
        val baseUrl = mock.url("/").toString()
        val source = localDocument("演示课表.pdf", "%PDF-1.7\nDemoData".toByteArray())
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(source))
        launch("home")
        val timetableService = TimetableService(baseUrl, requireHttps = false)
        scenario!!.onActivity { it.presentFragment(TimetableFragment(timetableService)) }
        mock.enqueue(MockResponse().setBody("""{"courses":[{"name":"$IMPORT_COURSE","teacher":"演示教师","slots":[{"day":2,"from":3,"to":4,"weekFrom":1,"weekTo":16,"parity":"odd","room":"演示教室"}]}],"maxWeek":16,"maxPeriod":4,"skipped":[]}"""))
        found(By.desc("选择 PDF")).click()
        found(By.desc(IMPORT_COURSE))
        found(By.desc("课表详情，演示教师")).click()
        found(By.text("周二 3–4 节 · 1–16 周单周 · 演示教室"))
        found(By.text("关闭")).click()
        found(By.desc("创建所选课程，1 门")).click()
        found(By.desc("导入结果，已创建 1 门课程，已有 0 门"))
        runBlocking { assertEquals(1, database.courseDao().courses().first().count { it.name == IMPORT_COURSE }) }
        val request = request(mock)
        assertEquals("/api/public/v1/tools/timetable-pdf", request.path)
        assertTrue(request.body.readUtf8().startsWith("%PDF-"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun classMaterialsFilterOtherLessonsAndUploadToTheCorrectLesson() = runBlocking {
        val record = database.recordDao().summariesForCourse(courseId).first().first().session
        settings.savePublishToken("instrumentation-placeholder")
        settings.updatePublishCourse(courseId) { it.copy(remoteCourses = mapOf(termOf(record.startedAt) to "mock-course")) }
        val mock = startServer()
        val baseUrl = mock.url("/").toString()
        val scoped = MATERIAL.dropLast(1) + ",\"lessonId\":\"mock-lesson\",\"lessonTitle\":\"演示课堂\"}"
        val unrelated = MATERIAL.replace("mock-material", "course-material").replace("演示笔记", "整门课程资料")
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(localDocument("演示资料.txt", "演示资料".toByteArray())))
        launch("home")
        eventually {
            var ready = false
            scenario!!.onActivity { ready = it.settings.uiState.value.publish.hasToken }
            ready
        }
        val materialService = MaterialService(baseUrl, requireHttps = false)
        val publishClient = PublishApiClient(requireHttps = false)
        scenario!!.onActivity {
            it.presentFragment(MaterialsFragment(courseId, record.id,
                materialService, publishClient, baseUrl))
        }
        mock.enqueue(MockResponse().setBody("""{"lessons":[{"lessonId":"mock-lesson","sessionId":"${record.sessionId}","status":"active","revision":1,"updatedAt":1}]}"""))
        mock.enqueue(MockResponse().setBody("{\"materials\":[$scoped,$unrelated]}"))
        found(By.desc("刷新资料")).click()
        found(By.descStartsWith("演示笔记. "))
        assertFalse(device.hasObject(By.descStartsWith("整门课程资料. ")))
        assertEquals("/api/publish/v1/courses/mock-course/lessons", request(mock).path)
        request(mock)
        mock.enqueue(MockResponse().setBody(scoped))
        mock.enqueue(MockResponse().setBody("{\"materials\":[$scoped]}"))
        found(By.desc("上传资料")).click()
        found(By.descStartsWith("演示笔记. "))
        val upload = request(mock)
        assertEquals("material", upload.requestUrl!!.queryParameter("category"))
        assertEquals("mock-lesson", upload.requestUrl!!.queryParameter("lessonId"))
        request(mock)
        assertEquals(4, mock.requestCount)
    }

    @Test fun unavailableTimetableEndpointShowsAnHonestFailure() {
        val mock = startServer()
        val baseUrl = mock.url("/").toString()
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(localDocument("未提供服务.pdf", "%PDF-1.7\nDemoData".toByteArray())))
        launch("home")
        val timetableService = TimetableService(baseUrl, requireHttps = false)
        scenario!!.onActivity { it.presentFragment(TimetableFragment(timetableService)) }
        mock.enqueue(MockResponse().setResponseCode(404))
        found(By.desc("选择 PDF")).click()
        found(By.desc("导入失败，当前服务尚未提供课表导入"))
        assertFalse(device.hasObject(By.descStartsWith("创建所选课程，")))
        assertEquals("/api/public/v1/tools/timetable-pdf", request(mock).path)
    }

    private fun launch(route: String) {
        scenario = ActivityScenario.launch(Intent(context, ListenHostActivity::class.java).putExtra("listen.route", route))
        device.waitForIdle()
    }

    private fun startServer(): MockWebServer = MockWebServer().apply { start(); server = this }

    private fun request(mock: MockWebServer) = mock.takeRequest(10, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected local MockWebServer request")

    private fun localDocument(name: String, content: ByteArray): android.net.Uri {
        val directory = File(context.cacheDir, "materials").apply { mkdirs() }
        val file = File(directory, name).apply { writeBytes(content) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun ActivityResult(uri: android.net.Uri) = android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,
        Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

    private fun found(selector: BySelector): UiObject2 = device.wait(Until.findObject(selector), 10_000)
        ?: throw AssertionError("Missing native view: $selector")

    private fun scrolled(selector: BySelector): UiObject2 {
        repeat(5) {
            device.findObject(selector)?.let { return it }
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f)
            device.waitForIdle()
        }
        return found(selector)
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Native state did not settle", condition())
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    companion object {
        private const val TERM = "2026-2027 第一学期"
        private const val IMPORT_COURSE = "示例 · 课表导入测试"
        private const val MATERIAL = """{"materialId":"mock-material","courseId":"mock-course","title":"演示笔记","fileName":"演示笔记.md","ext":"md","size":30,"category":"note","viewer":"none"}"""
    }
}
