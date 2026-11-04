package com.cmhr.listen.ui

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.SetupState
import com.cmhr.listen.tgui.ListenHostActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native replacement of the original Compose attachment/IME regression test. */
class AiComposerImeTest {
    @Test fun attachmentMenuDoesNotFocusInputAndCanBeReopened() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.cmhr.listen.debug")
        runBlocking { AppSettingsRepository(context).setSetupState(SetupState.DONE) }
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch<ListenHostActivity>(Intent(context, ListenHostActivity::class.java).putExtra("listen.route", "ai/new")).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("添加附件")), 10_000))
            scenario.onActivity { activity ->
                val input = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                input.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.window.decorView.apply { isFocusableInTouchMode = true; requestFocus() }
            }
            onView(withTagValue(`is`("ai-composer"))).check(matches(not(hasFocus())))
            onView(withTagValue(`is`("ai-message-list"))).check(matches(isDisplayed()))
            device.findObject(By.desc("添加附件")).click()
            assertTrue(device.wait(Until.hasObject(By.text("图片")), 5_000))
            assertTrue(device.hasObject(By.text("文本文件")))
            assertTrue(device.hasObject(By.text("拍照")))
            scenario.onActivity { activity ->
                val field = activity.window.decorView.findViewWithTag<android.view.View>("ai-composer")
                org.junit.Assert.assertFalse(field.hasFocus())
            }
            // Native attachment menus are modal: dismiss, then reopen via the same button.
            device.findObject(By.text("取消")).click()
            assertTrue(device.wait(Until.gone(By.text("文本文件")), 5_000))
            device.findObject(By.desc("添加附件")).click()
            assertTrue(device.wait(Until.hasObject(By.text("图片")), 5_000))
            assertTrue(device.hasObject(By.text("文本文件")))
        }
    }
}
