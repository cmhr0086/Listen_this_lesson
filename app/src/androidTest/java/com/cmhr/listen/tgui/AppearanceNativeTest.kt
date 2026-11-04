package com.cmhr.listen.tgui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.ui.NativeSettingsFixture
import com.cmhr.listen.ui.theme.DarkModePreference
import com.cmhr.listen.ui.theme.ThemePalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell

/** Tests the colors installed on existing native views, not just the preference write. */
@RunWith(AndroidJUnit4::class)
class AppearanceNativeTest : NativeSettingsFixture() {
    @Test fun dayAndNightUpdateActualBackgroundAndTextOnTheOpenPage() {
        val original = runBlocking { repository.settings.first().appearance }
        try {
            runBlocking { repository.setDarkMode(DarkModePreference.LIGHT) }
            launch("settings/appearance")
            val day = settledColors(false)
            click("夜间")
            val night = settledColors(true)
            assertSame("Switching appearance must update the open page", day.page, night.page)
            assertNotEquals("Page background must change", day.pageBackground, night.pageBackground)
            assertNotEquals("Native row background must change", day.rowBackground, night.rowBackground)
            assertNotEquals("Native option text must change", day.text, night.text)
            assertTrue(Color.luminance(day.rowBackground) > Color.luminance(night.rowBackground))
            assertTrue(Color.luminance(day.text) < Color.luminance(night.text))
            click("日间")
            val restored = settledColors(false)
            assertEquals(day.pageBackground, restored.pageBackground)
            assertEquals(day.rowBackground, restored.rowBackground)
            assertEquals(day.text, restored.text)
            assertEquals(original.palette, runBlocking { repository.settings.first().appearance.palette })
            assertEquals(0, server.requestCount)
        } finally {
            runBlocking { repository.setDarkMode(original.darkMode) }
        }
    }

    @Test fun followSystemAppliesTheCurrentConfigurationToVisibleColors() {
        val original = runBlocking { repository.settings.first().appearance }
        try {
            val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            runBlocking { repository.setDarkMode(if (systemDark) DarkModePreference.LIGHT else DarkModePreference.DARK) }
            launch("settings/appearance")
            val forced = settledColors(!systemDark)
            click("跟随系统")
            val following = settledColors(systemDark)
            assertNotEquals(forced.pageBackground, following.pageBackground)
            assertNotEquals(forced.rowBackground, following.rowBackground)
            assertNotEquals(forced.text, following.text)
            assertEquals(DarkModePreference.SYSTEM, runBlocking { repository.settings.first().appearance.darkMode })
            assertEquals(0, server.requestCount)
        } finally {
            runBlocking { repository.setDarkMode(original.darkMode) }
        }
    }

    @Test fun everyLegacyAppearanceValueStillRoundTripsThroughTheRepository() = runBlocking {
        val original = repository.settings.first().appearance
        val reader = AppSettingsRepository(context)
        try {
            ThemePalette.entries.forEach { palette ->
                repository.setThemePalette(palette)
                assertEquals(palette, reader.settings.first().appearance.palette)
            }
            DarkModePreference.entries.forEach { mode ->
                repository.setDarkMode(mode)
                assertEquals(mode, reader.settings.first().appearance.darkMode)
                assertEquals(ThemePalette.DYNAMIC, reader.settings.first().appearance.palette)
            }
            assertEquals(0, server.requestCount)
        } finally {
            repository.setThemePalette(original.palette)
            repository.setDarkMode(original.darkMode)
        }
    }

    private data class Colors(
        val page: View, val dark: Boolean, val pageBackground: Int, val rowBackground: Int,
        val text: Int, val expectedPage: Int, val expectedRow: Int, val expectedText: Int
    ) {
        fun matchesTheme() = pageBackground == expectedPage && rowBackground == expectedRow && text == expectedText
    }

    private fun settledColors(dark: Boolean): Colors {
        var current: Colors? = null
        var last: Colors? = null
        var stableSince = 0L
        await {
            scenario!!.onActivity { activity ->
                val page = activity.lastFragment.fragmentView ?: return@onActivity
                val cell = descendants(page).filterIsInstance<TextCheckCell>().firstOrNull {
                    it.isShown && descendants(it).filterIsInstance<TextView>().any { label -> label.text.toString() == "日间" }
                } ?: return@onActivity
                val label = descendants(cell).filterIsInstance<TextView>().first { it.text.toString() == "日间" }
                current = Colors(page, Theme.getCurrentTheme().isDark,
                    (page.background as ColorDrawable).color, (cell.background as ColorDrawable).color,
                    label.currentTextColor, Theme.getColor(Theme.key_windowBackgroundGray),
                    Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            }
            if (current != last) {
                last = current
                stableSince = SystemClock.uptimeMillis()
            }
            current?.let { it.dark == dark && it.matchesTheme() } == true &&
                SystemClock.uptimeMillis() - stableSince >= 200L
        }
        return requireNotNull(current)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
