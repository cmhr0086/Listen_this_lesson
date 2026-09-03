package com.cmhr.listen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrDiagnosticsTimeTest {
    @Test
    fun smoothDurationUsesTenthsOfASecond() {
        assertEquals("0.0s", formatSmoothMs(0))
        assertEquals("1.2s", formatSmoothMs(1_234))
        assertEquals("77.6s", formatSmoothMs(77_600))
    }

    @Test
    fun nextTickAlignsToNextOneHundredMillisecondBoundary() {
        assertEquals(100, nextSmoothTickDelay(null))
        assertEquals(100, nextSmoothTickDelay(0))
        assertEquals(99, nextSmoothTickDelay(1))
        assertEquals(50, nextSmoothTickDelay(1_250))
        assertEquals(100, nextSmoothTickDelay(1_300))
        assertTrue(nextSmoothTickDelay(1_299) >= 16)
    }
}
