package com.cmhr.listen

import com.cmhr.listen.audio.VadConfig
import com.cmhr.listen.data.stt.PromptDecision
import com.cmhr.listen.data.stt.SegmentQuality

/**
 * High-frequency capture diagnostics kept separate from the app-wide listening state.
 * The recorder publishes this state at a UI-friendly cadence; audio processing itself is
 * intentionally not throttled.
 */
data class VadDiagnosticsUiState(
    val vadProbability: Float = 0f,
    val effectiveVadConfig: VadConfig = VadConfig.Default,
    val isSpeechDetected: Boolean = false,
    val silenceDurationMs: Long = 0,
    val segmentStartReason: String? = null,
    val segmentEndReason: String? = null,
    val discardedShortSegments: Int = 0,
    val audioReadErrors: Int = 0,
    val lastSegmentQuality: SegmentQuality? = null,
    val lastPromptDecision: PromptDecision? = null,
    val capturingSegmentId: String? = null,
    val capturingStartedAt: Long? = null,
    val capturingStartedAtElapsedRealtimeMs: Long? = null
)
