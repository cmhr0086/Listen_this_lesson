package com.cmhr.listen.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {
    @Test
    fun `encodes PCM as standard mono 16-bit WAV`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = WavEncoder.encodePcm16Mono(pcm, sampleRateHz = 16_000)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(48, wav.size)
        assertArrayEquals("RIFF".encodeToByteArray(), wav.copyOfRange(0, 4))
        assertEquals(40, header.getInt(4))
        assertArrayEquals("WAVE".encodeToByteArray(), wav.copyOfRange(8, 12))
        assertArrayEquals("fmt ".encodeToByteArray(), wav.copyOfRange(12, 16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertArrayEquals("data".encodeToByteArray(), wav.copyOfRange(36, 40))
        assertEquals(4, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, 48))
    }
}
