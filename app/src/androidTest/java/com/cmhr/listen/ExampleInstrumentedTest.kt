package com.cmhr.listen

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
        assertTrue("设备验证只允许调试构建", BuildConfig.DEBUG)
        assertEquals("com.cmhr.listen.debug", appContext.packageName)
        assertNotEquals("不得对用户正式应用运行设备测试", "com.cmhr.listen", appContext.packageName)
    }
}
