package com.cmhr.listen.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    private const val HEADER_SIZE = 44
    private const val PCM_FORMAT = 1.toShort()

    fun encodePcm16Mono(pcm: ByteArray, sampleRateHz: Int = PcmRecorder.SAMPLE_RATE_HZ): ByteArray {
        require(sampleRateHz > 0) { "采样率必须大于 0。" }
        val channelCount = PcmRecorder.CHANNEL_COUNT
        val bitsPerSample = PcmRecorder.BITS_PER_SAMPLE
        val byteRate = sampleRateHz * channelCount * bitsPerSample / 8
        val blockAlign = (channelCount * bitsPerSample / 8).toShort()

        return ByteBuffer.allocate(HEADER_SIZE + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".encodeToByteArray())
                putInt(36 + pcm.size)
                put("WAVE".encodeToByteArray())
                put("fmt ".encodeToByteArray())
                putInt(16)
                putShort(PCM_FORMAT)
                putShort(channelCount.toShort())
                putInt(sampleRateHz)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(bitsPerSample.toShort())
                put("data".encodeToByteArray())
                putInt(pcm.size)
                put(pcm)
            }
            .array()
    }
}
