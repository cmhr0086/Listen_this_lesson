package com.cmhr.listen.audio

class PcmHistoryBuffer(private val capacitySamples: Int) {
    private val bytes = ByteArray(capacitySamples * PcmRecorder.BYTES_PER_SAMPLE)
    private var totalBytesWritten = 0L

    val endSample: Long get() = totalBytesWritten / PcmRecorder.BYTES_PER_SAMPLE
    private val startSample: Long get() = (endSample - capacitySamples).coerceAtLeast(0)

    fun append(pcm: ByteArray) {
        require(pcm.size % PcmRecorder.BYTES_PER_SAMPLE == 0) { "PCM 数据必须按采样边界对齐。" }
        pcm.forEach { value ->
            bytes[(totalBytesWritten % bytes.size).toInt()] = value
            totalBytesWritten++
        }
    }

    fun copyPaddedSegment(
        startSample: Long,
        speechSamples: Int,
        paddingSamples: Int,
        maxSegmentSamples: Int = Int.MAX_VALUE
    ): ByteArray? = copyPaddedSegmentWithRange(
        startSample,
        speechSamples,
        paddingSamples,
        maxSegmentSamples
    )?.pcm

    fun copyPaddedSegmentWithRange(
        startSample: Long,
        speechSamples: Int,
        paddingSamples: Int,
        maxSegmentSamples: Int = Int.MAX_VALUE
    ): PcmSegmentSlice? {
        val segmentStart = maxOf(startSample - paddingSamples, this.startSample)
        val segmentEnd = minOf(
            startSample + speechSamples + paddingSamples,
            endSample,
            segmentStart + maxSegmentSamples
        )
        if (segmentEnd <= segmentStart) return null

        val result = ByteArray(((segmentEnd - segmentStart) * PcmRecorder.BYTES_PER_SAMPLE).toInt())
        var sample = segmentStart
        var destination = 0
        while (sample < segmentEnd) {
            val source = ((sample * PcmRecorder.BYTES_PER_SAMPLE) % bytes.size).toInt()
            result[destination] = bytes[source]
            result[destination + 1] = bytes[(source + 1) % bytes.size]
            sample++
            destination += PcmRecorder.BYTES_PER_SAMPLE
        }
        return PcmSegmentSlice(segmentStart, segmentEnd, result)
    }
}

data class PcmSegmentSlice(
    val startSample: Long,
    val endSample: Long,
    val pcm: ByteArray
)
