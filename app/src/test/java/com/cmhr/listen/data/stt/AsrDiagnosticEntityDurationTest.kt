package com.cmhr.listen.data.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrDiagnosticEntityDurationTest {
    @Test
    fun elapsedRealtimeDiagnosticsNeverFallBackToWallClock() {
        val diagnostic = entity(
            clockBasis = AsrClockBasis.ELAPSED_REALTIME,
            submitCompletedElapsedMs = null,
            completedElapsedMs = 81_188_900L,
            submitCompletedAt = 1_780_000_000_000L,
            completedAt = 1_780_000_001_250L
        )

        assertNull(diagnostic.serverWaitDurationMs)
    }

    @Test
    fun legacyDiagnosticsUseOnlyPairedWallClockValues() {
        val diagnostic = entity(
            clockBasis = AsrClockBasis.LEGACY_WALL_FALLBACK,
            submitCompletedElapsedMs = 1L,
            completedElapsedMs = 81_188_900L,
            submitCompletedAt = 1_780_000_000_000L,
            completedAt = 1_780_000_001_250L
        )

        assertEquals(1_250L, diagnostic.serverWaitDurationMs)
    }

    @Test
    fun missingAndZeroTimestampsDoNotProduceHugeDurations() {
        val diagnostic = entity(
            clockBasis = AsrClockBasis.ELAPSED_REALTIME,
            submitCompletedElapsedMs = 0L,
            completedElapsedMs = 81_188_900L,
            submitCompletedAt = null,
            completedAt = null
        )

        assertNull(diagnostic.serverWaitDurationMs)
        assertNull(validDuration(0L, 81_188_900L))
        assertNull(validDuration(10L, 9L))
    }

    private fun entity(
        clockBasis: AsrClockBasis,
        submitCompletedElapsedMs: Long?,
        completedElapsedMs: Long?,
        submitCompletedAt: Long?,
        completedAt: Long?
    ) = AsrSegmentDiagnosticEntity(
        segmentId = "duration-test",
        recordId = 1,
        clockBasis = clockBasis.name,
        audioStartTime = 100,
        audioEndTime = 1_100,
        audioDurationMs = 1_000,
        captureStartedAt = 100,
        captureFinishedAt = 1_100,
        queuedLocalAt = 1_100,
        submitCompletedAt = submitCompletedAt,
        submitCompletedElapsedMs = submitCompletedElapsedMs,
        firstServerCompletedAt = completedAt,
        firstServerCompletedElapsedMs = completedElapsedMs
    )
}
