package com.cmhr.listen.tgui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.provider.MediaStore
import android.text.Spanned
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiConversationEntity
import com.cmhr.listen.data.ai.AiMessageEntity
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiServiceClient
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SegmentEntity
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.SetupState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.CoreMatchers.`is`
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.telegram.tgui.recyclerview.LinearLayoutManager
import org.telegram.tgui.recyclerview.RecyclerView
import org.telegram.ui.Components.RecyclerListView
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** End-to-end UI + unchanged ViewModel/repository/client tests. The only server is on loopback. */
class AiNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private lateinit var database: ListenDatabase
    private lateinit var server: MockWebServer
    private lateinit var settings: AppSettingsRepository
    private var activity: ListenHostActivity? = null
    private val createdConversations = mutableListOf<Long>()
    private val createdCourses = mutableListOf<Long>()

    @Before fun prepare() {
        check(context.packageName == "com.cmhr.listen.debug")
        database = ListenDatabase.get(context)
        server = MockWebServer().also { it.start() }
        settings = AppSettingsRepository(context)
        runBlocking {
            settings.setSetupState(SetupState.DONE)
            settings.setAutoNotes(false)
            // Fabricated test credential. No existing key is read, printed, or sent off-device.
            settings.saveAiService(AiProvider.OPENAI_COMPATIBLE, "https://ai-test.invalid/v1", "native-test", "local-fixture-only")
        }
    }

    @After fun cleanup() {
        try {
            activity?.let { current ->
                onActivity { it.finish() }
                waitUntil { current.isDestroyed }
            }
        } finally {
            activity = null
            try {
                runBlocking {
                    createdConversations.forEach { database.aiDao().deleteConversation(it) }
                    createdCourses.forEach { database.courseDao().softDelete(it) }
                    settings.clearAiApiKey()
                }
            } finally { server.shutdown() }
        }
    }

    private fun launch(route: String) {
        activity = instrumentation.startActivitySync(Intent(context, ListenHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("listen.route", route)) as ListenHostActivity
        onActivity { activity ->
            val localOnlyClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "ai-test.invalid") { "Only the local AI fixture is allowed" }
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
            // Test-only seam avoids changing production APIs, HTTPS checks, data, or request logic.
            AiViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }
                .set(activity.ai, AiServiceClient(httpClient = localOnlyClient))
        }
        val readyDescription = when {
            route.startsWith("ai/notes/new/") -> "生成笔记"
            route == "ai" -> "选择 AI 内容"
            else -> "更多操作"
        }
        assertTrue(device.wait(Until.hasObject(By.desc(readyDescription)), 10_000))
    }

    private fun seedConversation(): Long = runBlocking {
        val now = System.currentTimeMillis()
        val id = database.aiDao().insertConversation(AiConversationEntity(recordId = null, title = "示例 · 本地 AI 验收", sourceTextSnapshot = "", createdAt = now, updatedAt = now))
        createdConversations += id
        repeat(24) { index ->
            database.aiDao().insertMessage(AiMessageEntity(conversationId = id, role = if (index % 2 == 0) "user" else "assistant", content = "示例历史第 ${index + 1} 条。" + "用于验证阅读历史时保留滚动位置。".repeat(5), createdAt = now + index))
        }
        id
    }

    private fun seedRecord(): Long = runBlocking {
        val now = System.currentTimeMillis()
        val courseId = database.courseDao().insert(CourseEntity(name = "示例 · 本地笔记验收", createdAt = now))
        createdCourses += courseId
        val session = SessionEntity(courseId = courseId, name = "示例课堂", startedAt = now, endedAt = now + 60_000)
        val id = database.recordDao().insert(session)
        database.transcriptDao().insert(SegmentEntity(recordId = id, startTime = now, endTime = now + 30_000, audioDurationMs = 30_000, recognitionDurationMs = 100, text = "示例课堂：矩形截面的面积为宽乘高，惯性矩为宽乘高的三次方除以十二。", sessionId = session.sessionId))
        id
    }

    private fun streamResponse(answer: String, slow: Boolean = false): MockResponse {
        val data = answer.chunked(45).joinToString("") { chunk ->
            "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", chunk)))).toString() + "\n\n"
        } + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(data).apply {
            if (slow) throttleBody(180, 200, TimeUnit.MILLISECONDS)
        }
    }

    @Test fun streamingKeepsHistoryPositionAndCopiesFullMarkdownAfterReopen() {
        val id = seedConversation()
        val answer = "# 本地模拟回答\n\n| 项目 | 结果 |\n| --- | --- |\n| 表格验收 | 完整保留 |\n\n" +
            (1..95).joinToString("\n") { "- 第 $it 项：示例说明，验证流式回答与较长内容没有丢失。" } +
            "\n\n```python\nprint(\"代码验收\")\n```\n\nEND_NATIVE_MARKDOWN"
        server.enqueue(streamResponse(answer, slow = true))
        launch("ai-conversation/$id")
        onView(withTagValue(`is`("ai-composer"))).perform(replaceText("请整理本地示例"))
        assertTrue(device.wait(Until.hasObject(By.desc("发送")), 5_000))
        device.findObject(By.desc("发送")).click()
        onActivity { host -> host.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(host.window.decorView.windowToken, 0) }
        val pending = runBlocking { withTimeout(15_000) { database.aiDao().messages(id).first { rows -> rows.lastOrNull()?.let { it.role == "assistant" && it.status == AiRequestStatus.PENDING.name && it.content.length > 80 } == true }.last() } }
        val fullRefreshes = AtomicInteger()
        val streamedRebinds = AtomicInteger()
        val changedPositions = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        onActivity { activity ->
            activity.window.decorView.findViewWithTag<RecyclerListView>("ai-message-list").adapter!!.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { fullRefreshes.incrementAndGet() }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { streamedRebinds.incrementAndGet(); repeat(itemCount) { changedPositions += positionStart + it } }
            })
        }
        val viewport = Rect()
        onActivity { host -> host.window.decorView.findViewWithTag<RecyclerListView>("ai-message-list").getGlobalVisibleRect(viewport) }
        repeat(3) { device.swipe(viewport.centerX(), viewport.top + viewport.height() / 3, viewport.centerX(), viewport.top + viewport.height() * 3 / 4, 90) }
        waitUntil {
            var idle = false
            onActivity { host -> idle = host.window.decorView.findViewWithTag<RecyclerListView>("ai-message-list").scrollState == RecyclerView.SCROLL_STATE_IDLE }
            idle
        }
        var anchor = -1
        var top = 0
        onActivity { activity ->
            val list = activity.window.decorView.findViewWithTag<RecyclerListView>("ai-message-list")
            val manager = list.layoutManager as LinearLayoutManager
            assertTrue("Fixture must have older off-screen messages", list.canScrollVertically(1))
            anchor = manager.findFirstVisibleItemPosition()
            top = manager.findViewByPosition(anchor)!!.top
        }
        val beforeLength = runBlocking { database.aiDao().messagesOnce(id).last().content.length }
        val rebindsBefore = streamedRebinds.get()
        runBlocking { withTimeout(15_000) { database.aiDao().messages(id).first { it.last().content.length > beforeLength + 100 } } }
        waitUntil { streamedRebinds.get() > rebindsBefore }
        onActivity { activity ->
            val list = activity.window.decorView.findViewWithTag<RecyclerListView>("ai-message-list")
            val manager = list.layoutManager as LinearLayoutManager
            assertEquals("Streaming must retain the visible history anchor", anchor, manager.findFirstVisibleItemPosition())
            assertTrue("Streaming must not shift the reading offset", kotlin.math.abs(top - manager.findViewByPosition(anchor)!!.top) <= 2)
        }
        val completed = runBlocking { withTimeout(30_000) { database.aiDao().messages(id).first { it.lastOrNull()?.status == AiRequestStatus.SUCCESS.name && it.lastOrNull()?.id == pending.id }.last() } }
        assertEquals(answer, completed.content)
        assertEquals("No complete adapter refresh while streaming", 0, fullRefreshes.get())
        assertTrue("At least one streamed UI update must be observed", changedPositions.isNotEmpty())
        assertTrue("Only the in-progress answer may be rebound", changedPositions.all { it == 25 })
        val request = server.takeRequest(3, TimeUnit.SECONDS) ?: error("Mock request missing")
        val requestJson = JSONObject(request.body.readUtf8())
        assertTrue(requestJson.getBoolean("stream"))
        assertEquals("native-test", requestJson.getString("model"))

        device.findObject(By.desc("回到最新")).click()
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("回答：# 本地模拟回答")), 5_000))
        found(By.descStartsWith("回答：# 本地模拟回答")).longClick()
        assertTrue(device.wait(Until.hasObject(By.text("复制")), 5_000))
        device.findObject(By.text("复制")).click()
        onActivity { activity -> assertEquals(answer, activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
        // Popup dismissal and Android's clipboard overlay may temporarily hide the app's nodes.
        found(By.descStartsWith("回答：# 本地模拟回答")).longClick()
        assertTrue(device.wait(Until.hasObject(By.text("查看全文")), 5_000))
        device.findObject(By.text("查看全文")).click()
        waitForTag("ai-markdown-full")
        onActivity { activity ->
            val view = activity.window.decorView.findViewWithTag<TextView>("ai-markdown-full")
            val spanned = view.text as Spanned
            assertTrue("Markwon must render actual table spans", spanned.getSpans(0, spanned.length, Any::class.java).any { it.javaClass.name.contains("TableRowSpan") })
            assertTrue("Final code/body content must remain in the full viewer", view.text.toString().contains("END_NATIVE_MARKDOWN"))
        }
        device.findObject(By.desc("切换原文")).click()
        onActivity { activity -> assertEquals(answer, activity.window.decorView.findViewWithTag<TextView>("ai-markdown-full").text.toString()) }
        device.pressBack()
        awaitChatReady("ai-conversation/$id")
        device.pressBack()
        awaitNavigationIdle()
        onActivity { it.open("ai-conversation/$id") }
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("回答：# 本地模拟回答")), 5_000))
        assertEquals(answer, runBlocking { database.aiDao().messagesOnce(id).last().content })
    }

    @Test fun noteGenerationSuccessFailureAndRetryUseOriginalSource() {
        val recordId = seedRecord()
        server.enqueue(streamResponse("# 示例笔记\n\n| 概念 | 公式 |\n| --- | --- |\n| 面积 | 宽乘高 |\n\n```text\n这是代码块\n```\nNOTE_SUCCESS"))
        launch("ai/notes/new/$recordId")
        device.findObject(By.desc("复习提纲")).click()
        device.findObject(By.desc("生成笔记")).click()
        val success = runBlocking { withTimeout(15_000) { database.aiDao().results(recordId).first { it.firstOrNull()?.status == AiRequestStatus.SUCCESS.name }.first() } }
        assertEquals(AiActionType.ORGANIZE_NOTES.name, success.actionType)
        assertTrue(success.output!!.contains("NOTE_SUCCESS"))
        assertTrue(success.requestPrompt.contains("复习提纲"))
        assertTrue(success.sourceTextSnapshot.contains("矩形截面"))
        // Room may finish before the generation->result transition finishes. A second open
        // during that animation is rejected by the real native navigation controller.
        awaitChatReady("record/$recordId/ai-result/${success.id}")
        server.enqueue(MockResponse().setResponseCode(500).setBody("local fixture failure"))
        onActivity { it.open("ai/notes/new/$recordId") }
        awaitNavigationIdle()
        assertTrue(device.wait(Until.hasObject(By.desc("生成笔记")), 5_000))
        device.findObject(By.desc("生成笔记")).click()
        val failure = runBlocking { withTimeout(15_000) { database.aiDao().results(recordId).first { rows -> rows.firstOrNull()?.let { it.id != success.id && it.status == AiRequestStatus.ERROR.name } == true }.first() } }
        assertTrue(device.wait(Until.hasObject(By.descContains("生成失败")), 5_000))
        assertFalse(failure.errorMessage.isNullOrBlank())
        server.enqueue(streamResponse("# 重试笔记\n\nNOTE_RETRY_SUCCESS"))
        device.findObject(By.desc("更多操作")).click()
        assertTrue(device.wait(Until.hasObject(By.text("重新生成")), 5_000))
        device.findObject(By.text("重新生成")).click()
        assertTrue(device.wait(Until.hasObject(By.text("确定")), 5_000))
        device.findObject(By.text("确定")).click()
        val retried = runBlocking { withTimeout(15_000) { database.aiDao().result(failure.id).first { it?.status == AiRequestStatus.SUCCESS.name }!! } }
        assertEquals(failure.sourceTextSnapshot, retried.sourceTextSnapshot)
        assertTrue(retried.output!!.contains("NOTE_RETRY_SUCCESS"))
        assertEquals(3, server.requestCount)
    }

    @Test fun cameraAttachmentForwardsProviderUriAndCancelCleansTemporaryFile() {
        launch("ai/new")
        val captured = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != MediaStore.ACTION_IMAGE_CAPTURE) return null
                captured.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            device.findObject(By.desc("添加附件")).click()
            assertTrue(device.wait(Until.hasObject(By.text("拍照")), 5_000))
            device.findObject(By.text("拍照")).click()
            waitUntil { captured.get() != null }
            val intent = captured.get()
            @Suppress("DEPRECATION") val output = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
            assertEquals("content", output.scheme)
            assertEquals("com.cmhr.listen.debug.fileprovider", output.authority)
            assertEquals(output, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            waitUntil { runCatching { context.contentResolver.openInputStream(output)?.close() }.isFailure }
            assertEquals(0, server.requestCount)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun timelineLongPressDragSelectsAndUnselectsRange() {
        val ids = runBlocking {
            val time = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
            listOf("甲", "乙", "丙").mapIndexed { index, label ->
                database.aiDao().insertConversation(AiConversationEntity(recordId = null, title = "示例拖选$label", sourceTextSnapshot = "", createdAt = time + index, updatedAt = time + index))
            }
        }
        createdConversations += ids
        launch("ai")
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("示例拖选甲")), 5_000))
        val bottom = device.findObject(By.descStartsWith("示例拖选甲")).visibleCenter
        device.findObject(By.descStartsWith("示例拖选丙")).drag(bottom)
        assertTrue(device.wait(Until.hasObject(By.desc("已选择 3 项")), 5_000))
        onActivity { activity -> assertEquals(ids.toSet(), activity.ai.uiState.value.selectedContentKeys.map { it.id }.toSet()) }
        device.findObject(By.descStartsWith("示例拖选丙")).drag(bottom)
        assertTrue(device.wait(Until.hasObject(By.desc("已选择 0 项")), 5_000))
        onActivity { activity -> assertTrue(activity.ai.uiState.value.selectedContentKeys.isEmpty()) }
        assertEquals(0, server.requestCount)
    }

    private fun waitForTag(tag: String) = waitUntil {
        var found = false
        onActivity { found = it.window.decorView.findViewWithTag<View>(tag) != null }
        found
    }
    private fun found(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 10_000) ?: throw AssertionError("Missing native AI view: $selector")

    private fun awaitNavigationIdle() = waitUntil {
        var idle = false
        onActivity { idle = !it.actionBarLayout.isTransitionAnimationInProgress }
        idle
    }

    private fun awaitChatReady(route: String) = waitUntil {
        var ready = false
        onActivity { ready = (it.lastFragment as? AiChatFragment)?.savedRoute == route && !it.actionBarLayout.isTransitionAnimationInProgress && !it.ai.uiState.value.isBusy }
        ready
    }
    /** Unlike global Espresso/ActivityScenario idle waits, bounded UI inspection tolerates streaming frames. */
    private fun onActivity(block: (ListenHostActivity) -> Unit) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        Handler(Looper.getMainLooper()).post {
            try { block(requireNotNull(activity)) }
            catch (error: Throwable) { failure.set(error) }
            finally { completed.countDown() }
        }
        assertTrue("Main-thread inspection timed out", completed.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
    }
    private fun waitUntil(timeout: Long = 8_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out waiting for native UI condition")
            SystemClock.sleep(50)
        }
    }
}
