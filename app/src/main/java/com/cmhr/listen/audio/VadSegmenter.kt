package com.cmhr.listen.audio

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

class VadSegmenter(
    assetManager: AssetManager,
    private val configProvider: () -> VadConfig
) : AutoCloseable {
    private val history = PcmHistoryBuffer(HISTORY_SAMPLES)
    private val vad = Vad(
        assetManager,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_ASSET,
                threshold = VadConfig.Default.threshold,
                minSilenceDuration = 0.8f,
                minSpeechDuration = 1.0f,
                windowSize = PcmRecorder.CHUNK_SAMPLES,
                maxSpeechDuration = 14.4f
            ),
            sampleRate = PcmRecorder.SAMPLE_RATE_HZ,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )
    )

    private var candidateSpeechSamples = 0
    private var candidateSpeechStartSample = 0L
    private var activeSegmentStartSample: Long? = null
    private var activeConfig: VadConfig? = null
    private var silenceSamples = 0
    private var softLimitLogged = false
    private var lastStartReason: String? = null
    private var lastEndReason: String? = null

    fun acceptPcm(pcm: ByteArray): VadResult {
        history.append(pcm)
        val probability = vad.compute(pcm.toFloatSamples())
        val currentConfig = configProvider().validated()
        val isSpeech = probability >= currentConfig.threshold
        val chunkSamples = pcm.size / PcmRecorder.BYTES_PER_SAMPLE
        val chunkStart = history.endSample - chunkSamples
        val completedSegments = mutableListOf<CapturedPcmSegment>()
        val discardedShortDurationsMs = mutableListOf<Long>()

        val activeStart = activeSegmentStartSample
        if (activeStart == null) {
            if (isSpeech) {
                if (candidateSpeechSamples == 0) candidateSpeechStartSample = chunkStart
                candidateSpeechSamples += chunkSamples
                if (candidateSpeechSamples >= currentConfig.startConfirmSamples) {
                    activeConfig = currentConfig
                    activeSegmentStartSample =
                        (candidateSpeechStartSample - currentConfig.preRollSamples).coerceAtLeast(0)
                    candidateSpeechSamples = 0
                    silenceSamples = 0
                    lastStartReason = "连续语音达到 ${currentConfig.startConfirmMs} ms"
                    Log.d(TAG, "speech start: $lastStartReason, sample=$activeSegmentStartSample")
                }
            } else {
                candidateSpeechSamples = 0
            }
        } else {
            val segmentConfig = activeConfig ?: currentConfig
            if (isSpeech) silenceSamples = 0 else silenceSamples += chunkSamples

            val softEnd = activeStart + segmentConfig.softLimitSamples
            val maximumEnd = activeStart + segmentConfig.hardLimitSamples
            when {
                history.endSample >= maximumEnd -> {
                    emitSegment(
                        activeStart,
                        maximumEnd,
                        "达到最大长度",
                        hitMaxDuration = true,
                        config = segmentConfig,
                        completedSegments,
                        discardedShortDurationsMs
                    )
                    activeSegmentStartSample = (maximumEnd - segmentConfig.overlapSamples)
                        .coerceAtLeast(0)
                    activeConfig = currentConfig
                    silenceSamples = 0
                    softLimitLogged = false
                    lastStartReason = "达到最大长度后重叠续段"
                }

                silenceSamples >= segmentConfig.endSilenceSamples -> {
                    val speechEnd = history.endSample - silenceSamples
                    val end = minOf(speechEnd + segmentConfig.postRollSamples, history.endSample)
                    emitSegment(
                        activeStart,
                        end,
                        "自然静音结束",
                        hitMaxDuration = false,
                        config = segmentConfig,
                        completedSegments,
                        discardedShortDurationsMs
                    )
                    resetActiveSegment()
                }

                history.endSample >= softEnd && !softLimitLogged -> {
                    softLimitLogged = true
                    Log.d(TAG, "soft limit reached; waiting for natural silence")
                }
            }
        }

        return VadResult(
            probability = probability,
            effectiveConfig = activeConfig ?: currentConfig,
            isSpeechDetected = activeSegmentStartSample != null,
            silenceDurationMs = silenceSamples * 1_000L / PcmRecorder.SAMPLE_RATE_HZ,
            segmentStartReason = lastStartReason,
            segmentEndReason = lastEndReason,
            completedSegments = completedSegments,
            discardedShortDurationsMs = discardedShortDurationsMs
        )
    }

    private fun emitSegment(
        startSample: Long,
        endSample: Long,
        reason: String,
        hitMaxDuration: Boolean,
        config: VadConfig,
        completedSegments: MutableList<CapturedPcmSegment>,
        discardedShortDurationsMs: MutableList<Long>
    ) {
        val slice = history.copyPaddedSegmentWithRange(
            startSample = startSample,
            speechSamples = (endSample - startSample).toInt(),
            paddingSamples = 0,
            maxSegmentSamples = config.hardLimitSamples
        ) ?: return
        val durationMs = slice.pcm.size.toLong() * 1_000 /
            (PcmRecorder.SAMPLE_RATE_HZ * PcmRecorder.BYTES_PER_SAMPLE)
        lastEndReason = reason
        Log.d(TAG, "speech end: reason=$reason duration=${durationMs}ms")
        if (slice.pcm.size >= config.minSegmentSamples * PcmRecorder.BYTES_PER_SAMPLE) {
            Log.d(TAG, "segment emitted duration=${durationMs}ms reason=$reason")
            completedSegments += CapturedPcmSegment(slice, hitMaxDuration, reason)
        } else {
            Log.d(TAG, "segment discarded: below min duration (${durationMs}ms)")
            discardedShortDurationsMs += durationMs
        }
    }

    private fun resetActiveSegment() {
        activeSegmentStartSample = null
        activeConfig = null
        candidateSpeechSamples = 0
        silenceSamples = 0
        softLimitLogged = false
    }

    override fun close() = vad.release()

    private fun ByteArray.toFloatSamples(): FloatArray = FloatArray(size / 2) { index ->
        val low = this[index * 2].toInt() and 0xff
        val high = this[index * 2 + 1].toInt()
        ((high shl 8) or low) / 32768f
    }

    companion object {
        private const val MODEL_ASSET = "silero_vad.int8.onnx"
        private const val HISTORY_SAMPLES = PcmRecorder.SAMPLE_RATE_HZ * 20
        private const val TAG = "ListenVad"
    }
}

private fun VadConfig.msToSamples(value: Long): Long = value * PcmRecorder.SAMPLE_RATE_HZ / 1_000
private val VadConfig.startConfirmSamples: Int get() = msToSamples(startConfirmMs).toInt()
private val VadConfig.endSilenceSamples: Int get() = msToSamples(endSilenceMs).toInt()
private val VadConfig.preRollSamples: Long get() = msToSamples(preRollMs)
private val VadConfig.postRollSamples: Long get() = msToSamples(postRollMs)
private val VadConfig.softLimitSamples: Long get() = msToSamples(softLimitMs)
private val VadConfig.hardLimitSamples: Int get() = msToSamples(hardLimitMs).toInt()
private val VadConfig.overlapSamples: Long get() = msToSamples(overlapMs)
private val VadConfig.minSegmentSamples: Long get() = msToSamples(minSegmentMs)

data class VadResult(
    val probability: Float,
    val effectiveConfig: VadConfig,
    val isSpeechDetected: Boolean,
    val silenceDurationMs: Long,
    val segmentStartReason: String?,
    val segmentEndReason: String?,
    val completedSegments: List<CapturedPcmSegment>,
    val discardedShortDurationsMs: List<Long>
)

data class CapturedPcmSegment(
    val pcmSlice: PcmSegmentSlice,
    val hitMaxDuration: Boolean,
    val endReason: String
)
