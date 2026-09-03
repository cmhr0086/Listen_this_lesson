package com.cmhr.listen.data.ai

import com.cmhr.listen.data.course.TranscriptEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CorrectionSegment(
    val segmentId: Long,
    val correctedText: String,
    val changes: List<String> = emptyList()
)

@Serializable
data class CorrectionPayload(val segments: List<CorrectionSegment>)

object CorrectionPayloadCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String, source: List<TranscriptEntity>): CorrectionPayload {
        val normalized = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val payload = json.decodeFromString(CorrectionPayload.serializer(), normalized)
        val expected = source.map { it.id }.toSet()
        val actual = payload.segments.map { it.segmentId }
        require(actual.size == actual.distinct().size) { "AI 纠错结果包含重复的片段 ID。" }
        require(actual.toSet() == expected) { "AI 纠错结果未完整对应本次选择的片段。" }
        require(payload.segments.all { it.correctedText.isNotBlank() }) { "AI 纠错结果包含空文本。" }
        return payload
    }

    fun encode(value: CorrectionPayload): String = json.encodeToString(CorrectionPayload.serializer(), value)

    fun toMarkdown(value: CorrectionPayload, source: List<TranscriptEntity>): String {
        val originals = source.associateBy { it.id }
        return buildString {
            value.segments.forEachIndexed { index, corrected ->
                val original = requireNotNull(originals[corrected.segmentId])
                if (index > 0) appendLine().appendLine("---").appendLine()
                appendLine("### 片段 #${corrected.segmentId}")
                appendLine()
                appendLine("**纠正后文本**")
                appendLine()
                appendLine(corrected.correctedText)
                appendLine()
                if (corrected.correctedText == original.effectiveText && corrected.changes.isEmpty()) {
                    appendLine("未发现可确认的修改。")
                } else {
                    appendLine("**修改说明**")
                    appendLine()
                    if (corrected.changes.isEmpty()) appendLine("- 已校正明显的转写错误。")
                    else corrected.changes.forEach { appendLine("- $it") }
                }
            }
        }.trim()
    }
}
