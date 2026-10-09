package com.cmhr.listen.ui

import com.cmhr.listen.AiPromptKind
import com.cmhr.listen.audio.VadConfig
import com.cmhr.listen.audio.VadPreset
import com.cmhr.listen.data.settings.AiPromptSettings
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPagesHelpersTest {
    private fun at(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun checkTimeReadsLikeAClock() {
        val now = at(9, 11, 45)
        assertEquals("刚刚", checkTime(now - 30_000, now))
        assertEquals("今天 11:42", checkTime(at(9, 11, 42), now))
        assertEquals("10月8日 09:30", checkTime(at(8, 9, 30), now))
    }

    @Test
    fun durationsSwitchToSecondsForWholeLongValues() {
        assertEquals("700 ms", formatMs(700f))
        assertEquals("15 s", formatMs(15_000f))
        assertEquals("15500 ms", formatMs(15_500f))
        assertEquals("0.35", formatFraction(0.35f))
    }

    @Test
    fun customVadConfigIsTracedBackToItsPreset() {
        val tuned = VadPreset.NOISY.config.validated().copy(endSilenceMs = 1_300)
        assertEquals(VadPreset.NOISY, nearestVadPreset(tuned))
        assertEquals(VadPreset.CLOSE_SPEECH, nearestVadPreset(VadPreset.CLOSE_SPEECH.config.validated()))
        // Ties resolve to the first preset, so an untouched default stays 默认.
        assertEquals(VadPreset.DEFAULT, nearestVadPreset(VadConfig.Default.validated()))
    }

    @Test
    fun termCountSplitsOnChineseAndAsciiSeparators() {
        assertEquals(0, asrTermCount("  "))
        assertEquals(4, asrTermCount("极限，导数、ε-δ; 洛必达"))
    }

    @Test
    fun promptKindsRoundTripAndDetectEdits() {
        val defaults = AiPromptSettings()
        AiPromptKind.entries.forEach { kind ->
            assertTrue(kind.isDefault(defaults))
            val edited = kind.write(defaults, "新的要求")
            assertEquals("新的要求", kind.read(edited))
            assertFalse(kind.isDefault(edited))
            // Editing one prompt leaves the others alone.
            AiPromptKind.entries.filter { it != kind }.forEach { other -> assertTrue(other.isDefault(edited)) }
        }
    }
}
