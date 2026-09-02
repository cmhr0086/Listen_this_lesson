package com.cmhr.listen.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmHistoryBufferTest {
    @Test
    fun `returns available pre and post padding around speech`() {
        val history = PcmHistoryBuffer(capacitySamples = 10)
        history.append(pcmSamples(0, 10))

        val segment = history.copyPaddedSegment(startSample = 5, speechSamples = 2, paddingSamples = 3)

        assertArrayEquals(pcmSamples(2, 8), segment)
    }

    @Test
    fun `drops overwritten samples while keeping requested padding`() {
        val history = PcmHistoryBuffer(capacitySamples = 10)
        history.append(pcmSamples(0, 10))
        history.append(pcmSamples(10, 5))

        val segment = history.copyPaddedSegment(startSample = 8, speechSamples = 2, paddingSamples = 3)

        assertArrayEquals(pcmSamples(5, 8), segment)
    }

    private fun pcmSamples(start: Int, count: Int): ByteArray = ByteArray(count * 2).also { bytes ->
        repeat(count) { index ->
            bytes[index * 2] = (start + index).toByte()
            bytes[index * 2 + 1] = 0
        }
    }
}
