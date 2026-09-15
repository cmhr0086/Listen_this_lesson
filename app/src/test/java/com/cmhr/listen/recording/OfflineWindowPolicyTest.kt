package com.cmhr.listen.recording

import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.VadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineWindowPolicyTest {
    @Test fun fiveMinuteWindowsHaveStableFrameBoundariesWithoutOffsetPaging() {
        val total = 11L * 60L * PcmRecorder.SAMPLE_RATE_HZ
        val first = OfflineWindowPolicy.coreEnd(0, total)
        val second = OfflineWindowPolicy.coreEnd(first, total)
        assertEquals(5L * 60L * PcmRecorder.SAMPLE_RATE_HZ, first)
        assertEquals(10L * 60L * PcmRecorder.SAMPLE_RATE_HZ, second)
        assertEquals(total, OfflineWindowPolicy.coreEnd(second, total))
    }

    @Test fun overlapIsAssignedOnceBySpeechOwnershipFrame() {
        val boundary = OfflineWindowPolicy.CORE_WINDOW_FRAMES
        assertTrue(OfflineWindowPolicy.owns(boundary - 1, 0, boundary))
        assertFalse(OfflineWindowPolicy.owns(boundary, 0, boundary))
        assertTrue(OfflineWindowPolicy.owns(boundary, boundary, boundary * 2))
    }

    @Test fun analysisRangeAddsVadContextButClampsToRecording() {
        val range = OfflineWindowPolicy.analysisRange(0, OfflineWindowPolicy.CORE_WINDOW_FRAMES, OfflineWindowPolicy.CORE_WINDOW_FRAMES + PcmRecorder.SAMPLE_RATE_HZ, VadConfig.Default)
        assertEquals(0, range.first)
        assertEquals(OfflineWindowPolicy.CORE_WINDOW_FRAMES + PcmRecorder.SAMPLE_RATE_HZ - 1, range.last)
        assertEquals(1_001_000L, OfflineWindowPolicy.timestamp(1_000_000L, PcmRecorder.SAMPLE_RATE_HZ.toLong()))
    }
}
