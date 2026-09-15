package com.cmhr.listen.recording

import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.VadConfig

internal object OfflineWindowPolicy {
    const val CORE_WINDOW_FRAMES = 5L * 60L * PcmRecorder.SAMPLE_RATE_HZ
    fun coreEnd(start: Long, total: Long): Long = minOf(start + CORE_WINDOW_FRAMES, total)
    fun analysisRange(coreStart: Long, coreEnd: Long, total: Long, config: VadConfig): LongRange {
        val before = (config.preRollMs + config.startConfirmMs) * PcmRecorder.SAMPLE_RATE_HZ / 1_000
        val after = (config.hardLimitMs + config.endSilenceMs + config.postRollMs) * PcmRecorder.SAMPLE_RATE_HZ / 1_000
        val start = (coreStart - before).coerceAtLeast(0)
        val endExclusive = (coreEnd + after).coerceAtMost(total)
        return start until endExclusive
    }
    fun owns(absoluteOwnershipFrame: Long, coreStart: Long, coreEnd: Long): Boolean =
        absoluteOwnershipFrame in coreStart until coreEnd
    fun timestamp(startedAt: Long, frame: Long): Long =
        startedAt + frame * 1_000 / PcmRecorder.SAMPLE_RATE_HZ
}
