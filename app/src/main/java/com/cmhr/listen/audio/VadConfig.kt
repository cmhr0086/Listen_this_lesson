package com.cmhr.listen.audio

data class VadConfig(
    val threshold: Float = 0.35f,
    val startConfirmMs: Long = 100,
    val endSilenceMs: Long = 1_100,
    val preRollMs: Long = 2_000,
    val postRollMs: Long = 500,
    val minSegmentMs: Long = 1_000,
    val softLimitMs: Long = 15_000,
    val hardLimitMs: Long = 20_000,
    val overlapMs: Long = 1_000
) {
    fun validated(): VadConfig {
        val hardLimit = hardLimitMs.coerceIn(10_000, 60_000)
        return copy(
            threshold = threshold.coerceIn(0.10f, 0.90f),
            startConfirmMs = startConfirmMs.coerceIn(50, 500),
            endSilenceMs = endSilenceMs.coerceIn(300, 2_000),
            preRollMs = preRollMs.coerceIn(0, 3_000),
            postRollMs = postRollMs.coerceIn(0, 1_500),
            minSegmentMs = minSegmentMs.coerceIn(200, 3_000),
            softLimitMs = softLimitMs.coerceIn(5_000, hardLimit - 1),
            hardLimitMs = hardLimit,
            overlapMs = overlapMs.coerceIn(0, minOf(3_000, hardLimit - 1))
        )
    }

    companion object {
        val Default = VadConfig()
        val RemoteClassroom = VadConfig(
            threshold = 0.35f,
            startConfirmMs = 100,
            endSilenceMs = 1_100,
            preRollMs = 2_000,
            postRollMs = 500
        )
        val CloseSpeech = VadConfig(
            threshold = 0.50f,
            startConfirmMs = 150,
            endSilenceMs = 700,
            preRollMs = 800,
            postRollMs = 300
        )
        val Noisy = VadConfig(
            threshold = 0.60f,
            startConfirmMs = 200,
            endSilenceMs = 900,
            preRollMs = 1_200,
            postRollMs = 400
        )
    }
}

enum class VadPreset(val id: String, val displayName: String) {
    DEFAULT("default", "默认"),
    REMOTE_CLASSROOM("remote_classroom", "远距离课堂"),
    CLOSE_SPEECH("close_speech", "近距离讲话"),
    NOISY("noisy", "高噪声环境");

    val config: VadConfig
        get() = when (this) {
            DEFAULT -> VadConfig.Default
            REMOTE_CLASSROOM -> VadConfig.RemoteClassroom
            CLOSE_SPEECH -> VadConfig.CloseSpeech
            NOISY -> VadConfig.Noisy
        }

    companion object {
        fun fromId(id: String): VadPreset? = entries.firstOrNull { it.id == id }
    }
}
