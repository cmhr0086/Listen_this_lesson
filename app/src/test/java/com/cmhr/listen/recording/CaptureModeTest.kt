package com.cmhr.listen.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModeTest {
    @Test fun recordOnlyNeverRoutesToAsr() {
        assertFalse(CaptureMode.RECORD_ONLY.usesAsr)
        assertTrue(CaptureMode.REALTIME_ASR.usesAsr)
    }
}
