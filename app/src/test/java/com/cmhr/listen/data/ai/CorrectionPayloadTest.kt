package com.cmhr.listen.data.ai

import com.cmhr.listen.data.course.TranscriptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionPayloadTest {
    private val source = listOf(
        segment(1, "油盐油锅"),
        segment(2, "受制飞")
    )

    @Test
    fun `decodes all selected segments and renders useful result`() {
        val raw = """{"segments":[{"segmentId":1,"correctedText":"油盐锅","changes":["油盐油锅 → 油盐锅"]},{"segmentId":2,"correctedText":"受制于","changes":["受制飞 → 受制于"]}]}"""
        val payload = CorrectionPayloadCodec.decode(raw, source)
        assertEquals(listOf(1L, 2L), payload.segments.map { it.segmentId })
        assertTrue(CorrectionPayloadCodec.toMarkdown(payload, source).contains("受制飞 → 受制于"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects missing segment`() {
        CorrectionPayloadCodec.decode(
            """{"segments":[{"segmentId":1,"correctedText":"油盐锅","changes":[]}]}""",
            source
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate segment ids`() {
        CorrectionPayloadCodec.decode(
            """{"segments":[{"segmentId":1,"correctedText":"甲","changes":[]},{"segmentId":1,"correctedText":"乙","changes":[]}]}""",
            listOf(source.first())
        )
    }

    private fun segment(id: Long, text: String) = TranscriptEntity(
        id = id,
        recordId = 7,
        startTime = id * 1_000,
        endTime = id * 1_000 + 500,
        audioDurationMs = 500,
        recognitionDurationMs = 100,
        text = text
    )
}
