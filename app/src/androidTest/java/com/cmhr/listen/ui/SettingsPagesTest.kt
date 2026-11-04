package com.cmhr.listen.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.cmhr.listen.AiPromptKind
import com.cmhr.listen.data.settings.AiGenerationSettings
import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AiThinkingMode
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.SetupState
import com.cmhr.listen.data.settings.SttProvider
import com.cmhr.listen.tgui.ListenHostActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Native replacements for every retained assertion from the old Compose SettingsPagesTest. */
class SettingsPagesTest : NativeSettingsFixture() {
    @Test fun editSheetOnlyConfirmsAChangedValueAndTrimsIt() {
        launch("settings/stt")
        click("服务地址")
        assertFalse(text("保存").isEnabled)
        val replacement = server.url("/changed").toString().trimEnd('/')
        replace("  $replacement  ")
        assertTrue(text("保存").isEnabled)
        click("保存")
        await { runBlocking { repository.settings.first().server.baseUrl == replacement } }
        assertEquals(replacement, runBlocking { repository.settings.first().server.baseUrl })
        assertFalse(device.hasObject(By.clazz(EditText::class.java)))
    }

    @Test fun failedSpeechCheckStaysOnThePage() {
        launch("settings/stt")
        scenario!!.onActivity { it.settings.checkStt() }
        text("连接失败")
        assertTrue(device.wait(Until.hasObject(By.textContains("API Key 无效（HTTP 403）")), 10_000))
        assertTrue(device.hasObject(By.text(server.url("/").toString().trimEnd('/'))))
        scrollTo("清除访问密钥")
        assertTrue(text("清除访问密钥").isEnabled)
        scenario!!.onActivity { it.open("settings/stt") }
        text("连接失败")
        assertTrue(device.hasObject(By.textContains("API Key 无效（HTTP 403）")))
    }

    @Test fun promptEditorAsksBeforeDroppingEdits() {
        val original = runBlocking { AiPromptKind.NOTES.read(repository.settings.first().aiPrompts) }
        launch("settings/prompt/NOTES")
        click("编辑提示词")
        assertFalse(text("保存").isEnabled)
        replace(original + "\n最后列出作业。")
        assertTrue(text("保存").isEnabled)
        click("取消")
        text("放弃修改？")
        assertEquals(original, runBlocking { AiPromptKind.NOTES.read(repository.settings.first().aiPrompts) })
        click("继续编辑")
        assertTrue(device.hasObject(By.clazz(EditText::class.java)))
        assertEquals(original + "\n最后列出作业。", device.findObject(By.clazz(EditText::class.java)).text)
        click("取消")
        click("放弃")
        await { !device.hasObject(By.clazz(EditText::class.java)) }
        assertEquals(original, runBlocking { AiPromptKind.NOTES.read(repository.settings.first().aiPrompts) })
        text("编辑提示词")
    }

    @Test fun reasoningOptionsFollowTheProvider() {
        runBlocking { repository.saveAiEndpoint(AiProvider.OPENAI_COMPATIBLE, "https://ai-test.invalid/v1", "fixture-model") }
        launch("settings/generation")
        assertFalse(device.hasObject(By.text("深度思考")))
        assertFalse(device.hasObject(By.desc("高")))
        runBlocking {
            repository.saveAiEndpoint(AiProvider.DEEPSEEK, "https://ai-test.invalid/v1", "fixture-model")
            repository.saveAiGeneration(AiGenerationSettings(deepSeekThinkingMode = AiThinkingMode.DISABLED))
        }
        scrollTo("高")
        assertFalse(row("高").isEnabled)
        scrollTo("开启", upwards = true)
        click("开启")
        await { runBlocking { repository.settings.first().aiGeneration.deepSeekThinkingMode == AiThinkingMode.ENABLED } }
        scrollTo("高")
        assertTrue(row("高").isEnabled)
    }
}

/** All fixtures live only in the debug package and call an in-process loopback server. */
abstract class NativeSettingsFixture {
    protected lateinit var repository: AppSettingsRepository
    protected lateinit var context: Context
    protected lateinit var server: MockWebServer
    protected val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    protected var scenario: ActivityScenario<ListenHostActivity>? = null
    private var previousIdleTimeout = 10_000L

    @Before fun prepareNativeSettings() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.cmhr.listen.debug")
        previousIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 100L
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().endsWith("/health") -> MockResponse().setBody("{\"status\":\"ok\",\"model\":\"fixture\",\"queued_jobs\":0,\"processing_jobs\":0,\"max_queue_depth\":3}")
                    request.path.orEmpty().contains("/jobs/") -> MockResponse().setResponseCode(403)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        repository = AppSettingsRepository(context)
        runBlocking {
            repository.unbindDevice()
            repository.setSetupState(SetupState.DONE)
            repository.setDeveloperMode(true)
            repository.setAutoNotes(false)
            repository.saveServer(server.url("/").toString(), "synthetic-test-credential")
            repository.saveSttProvider(SttProvider.SELF_HOSTED)
            repository.clearAiApiKey()
            repository.clearCloudSyncApiToken()
            repository.clearPublishToken()
            repository.saveAiEndpoint(AiProvider.OPENAI_COMPATIBLE, "https://ai-test.invalid/v1", "fixture-model")
            repository.restoreDefaultAiGeneration()
            repository.restoreDefaultAiPrompts()
        }
    }

    @After fun finishNativeSettings() {
        scenario?.close()
        if (::repository.isInitialized) runBlocking {
            repository.clearApiKey()
            repository.clearAiApiKey()
            repository.setSetupState(SetupState.DONE)
            repository.restoreDefaultAiGeneration()
            repository.restoreDefaultAiPrompts()
        }
        if (::server.isInitialized) server.shutdown()
        Configurator.getInstance().waitForIdleTimeout = previousIdleTimeout
    }

    protected fun launch(route: String) {
        scenario?.close()
        scenario = ActivityScenario.launch(Intent(context, ListenHostActivity::class.java).putExtra("listen.route", route))
        device.waitForIdle()
        await { device.currentPackageName == context.packageName }
        val expectedAddress = runBlocking { repository.settings.first().server.baseUrl }
        var loaded = false
        await { scenario!!.onActivity { loaded = it.settings.uiState.value.server.baseUrl == expectedAddress }; loaded }
    }
    protected fun text(value: String): UiObject2 {
        var result: UiObject2? = null
        await { result = device.findObject(By.text(value)) ?: describedRow(value); result != null }
        return result!!
    }
    protected fun row(value: String): UiObject2 {
        var result: UiObject2? = null
        await { result = describedRow(value); result != null }
        return result!!
    }
    // Text settings cells expose our explicit description; DialogCell builds its own
    // accessibility description as "title. message. time" from the rendered data.
    protected fun describedRow(value: String): UiObject2? = device.findObject(By.desc(value))
        ?: device.findObject(By.descStartsWith("$value，"))
        ?: device.findObject(By.descStartsWith("$value. "))
    protected fun click(value: String) { text(value).click(); device.waitForIdle() }
    protected fun replace(value: String) {
        assertTrue("编辑对话框必须显示真实输入框", device.wait(Until.hasObject(By.clazz(EditText::class.java)), 5_000))
        onView(allOf(isAssignableFrom(EditText::class.java), isDisplayed())).inRoot(isDialog())
            .perform(replaceText(value), closeSoftKeyboard())
        device.waitForIdle()
    }
    protected fun scrollTo(value: String, upwards: Boolean = false) {
        repeat(18) {
            if (device.hasObject(By.text(value)) || describedRow(value) != null) return
            val x = device.displayWidth / 2
            val top = (device.displayHeight * 0.25).toInt()
            val bottom = (device.displayHeight * 0.78).toInt()
            device.swipe(x, if (upwards) top else bottom, x, if (upwards) bottom else top, 20)
            device.waitForIdle()
        }
        throw AssertionError("滚动后找不到：$value")
    }
    protected fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        assertTrue("等待原生界面状态超时", condition())
    }
}
