package com.cmhr.listen.data.stt

enum class AsrPromptMode(val displayName: String) {
    OFF("关闭"),
    AUTO("自动"),
    ALWAYS("始终使用")
}

data class AsrPromptAutoConfig(
    val minAudioDurationMs: Long = 2_000,
    val minVoicedDurationMs: Long = 1_000,
    val minMeanSpeechProbability: Float = 0.55f,
    val minSpeechFrameRatio: Float = 0.25f,
    val minSnrDb: Float = 6f
) {
    fun validated() = copy(
        minAudioDurationMs = minAudioDurationMs.coerceIn(500, 15_000),
        minVoicedDurationMs = minVoicedDurationMs.coerceIn(200, minAudioDurationMs.coerceAtLeast(200)),
        minMeanSpeechProbability = minMeanSpeechProbability.coerceIn(0.1f, 0.95f),
        minSpeechFrameRatio = minSpeechFrameRatio.coerceIn(0.05f, 0.95f),
        minSnrDb = minSnrDb.coerceIn(0f, 30f)
    )
}

data class SegmentQuality(
    val audioDurationMs: Long,
    val voicedDurationMs: Long,
    val meanSpeechProbability: Float,
    val speechFrameRatio: Float,
    val estimatedSnrDb: Float?
)

data class PromptDecision(
    val effectiveMode: AsrPromptMode,
    val prompt: String?,
    val reason: String
) {
    val included: Boolean get() = prompt != null
}

object AsrPromptPolicy {
    fun decide(
        globalMode: AsrPromptMode,
        courseOverride: String?,
        coursePrompt: String,
        quality: SegmentQuality,
        config: AsrPromptAutoConfig
    ): PromptDecision {
        val effectiveMode = courseOverride
            ?.let { runCatching { AsrPromptMode.valueOf(it) }.getOrNull() }
            ?: globalMode
        val prompt = coursePrompt.trim()
        if (prompt.isEmpty()) return PromptDecision(effectiveMode, null, "课程 ASR 提示词为空")
        return when (effectiveMode) {
            AsrPromptMode.OFF -> PromptDecision(effectiveMode, null, "提示词模式已关闭")
            AsrPromptMode.ALWAYS -> PromptDecision(effectiveMode, prompt, "始终使用提示词")
            AsrPromptMode.AUTO -> autoDecision(prompt, quality, config.validated())
        }
    }

    private fun autoDecision(
        prompt: String,
        quality: SegmentQuality,
        config: AsrPromptAutoConfig
    ): PromptDecision {
        val rejection = when {
            quality.audioDurationMs < config.minAudioDurationMs ->
                "音频过短（${quality.audioDurationMs} < ${config.minAudioDurationMs} ms）"
            quality.voicedDurationMs < config.minVoicedDurationMs ->
                "有效语音过短（${quality.voicedDurationMs} < ${config.minVoicedDurationMs} ms）"
            quality.meanSpeechProbability < config.minMeanSpeechProbability ->
                "平均 VAD 概率偏低"
            quality.speechFrameRatio < config.minSpeechFrameRatio ->
                "语音帧占比偏低"
            quality.estimatedSnrDb != null && quality.estimatedSnrDb < config.minSnrDb ->
                "估算信噪比偏低"
            else -> null
        }
        return if (rejection == null) {
            PromptDecision(AsrPromptMode.AUTO, prompt, "片段质量通过自动门槛")
        } else {
            PromptDecision(AsrPromptMode.AUTO, null, rejection)
        }
    }
}
