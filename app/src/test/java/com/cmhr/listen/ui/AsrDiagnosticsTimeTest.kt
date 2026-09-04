package com.cmhr.listen.ui

import com.cmhr.listen.data.stt.AsrClockBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun elapsedRealtimeDurationNeverFallsBackToWallClock() {
        assertEquals(
            500L,
            calculateElapsedMs(
                startElapsedRealtimeMs = 10_000L,
                fallbackStartWallTimeMs = 1_780_000_000_000L,
                clockBasis = AsrClockBasis.ELAPSED_REALTIME.name,
                elapsedRealtime = { 10_500L },
                wallTime = { 1_780_000_000_500L }
            )
        )
        assertNull(
            calculateElapsedMs(
                startElapsedRealtimeMs = null,
                fallbackStartWallTimeMs = 1_780_000_000_000L,
                clockBasis = AsrClockBasis.ELAPSED_REALTIME.name,
                elapsedRealtime = { 10_500L },
                wallTime = { 1_780_000_000_500L }
            )
        )
    }

    @Test
    fun legacyClockUsesOnlyPairedWallClockTimestamps() {
        assertEquals(
            750L,
            calculateElapsedMs(
                startElapsedRealtimeMs = 81_188_900L,
                fallbackStartWallTimeMs = 1_780_000_000_000L,
                clockBasis = AsrClockBasis.LEGACY_WALL_FALLBACK.name,
                elapsedRealtime = { 162_377_800L },
                wallTime = { 1_780_000_000_750L }
            )
        )
        assertNull(
            calculateElapsedMs(
                startElapsedRealtimeMs = 0L,
                fallbackStartWallTimeMs = 0L,
                clockBasis = AsrClockBasis.LEGACY_WALL_FALLBACK.name,
                elapsedRealtime = { 81_188_900L },
                wallTime = { 1_780_000_000_750L }
            )
        )
    }
}
