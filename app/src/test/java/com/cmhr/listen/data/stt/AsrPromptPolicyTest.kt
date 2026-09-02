package com.cmhr.listen.data.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrPromptPolicyTest {
    private val good = SegmentQuality(3_000, 1_800, 0.72f, 0.6f, 12f)

    @Test fun `off never sends and always sends nonempty prompt`() {
        assertFalse(AsrPromptPolicy.decide(AsrPromptMode.OFF, null, "术语", good, AsrPromptAutoConfig()).included)
        assertTrue(AsrPromptPolicy.decide(AsrPromptMode.ALWAYS, null, "术语", good, AsrPromptAutoConfig()).included)
        assertFalse(AsrPromptPolicy.decide(AsrPromptMode.ALWAYS, null, "  ", good, AsrPromptAutoConfig()).included)
    }

    @Test fun `course override takes priority over global mode`() {
        val decision = AsrPromptPolicy.decide(AsrPromptMode.OFF, AsrPromptMode.ALWAYS.name, "术语", good, AsrPromptAutoConfig())
        assertEquals(AsrPromptMode.ALWAYS, decision.effectiveMode)
        assertTrue(decision.included)
    }

    @Test fun `auto enforces every available quality gate`() {
        assertTrue(AsrPromptPolicy.decide(AsrPromptMode.AUTO, null, "术语", good, AsrPromptAutoConfig()).included)
        listOf(
            good.copy(audioDurationMs = 1_999),
            good.copy(voicedDurationMs = 999),
            good.copy(meanSpeechProbability = 0.54f),
            good.copy(speechFrameRatio = 0.24f),
            good.copy(estimatedSnrDb = 5.9f)
        ).forEach { quality ->
            assertFalse(AsrPromptPolicy.decide(AsrPromptMode.AUTO, null, "术语", quality, AsrPromptAutoConfig()).included)
        }
    }

    @Test fun `auto does not reject when snr cannot be estimated`() {
        assertTrue(AsrPromptPolicy.decide(AsrPromptMode.AUTO, null, "术语", good.copy(estimatedSnrDb = null), AsrPromptAutoConfig()).included)
    }
}
