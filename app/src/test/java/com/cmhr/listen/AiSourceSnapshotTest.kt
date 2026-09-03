package com.cmhr.listen

import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.data.ai.AiActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AiSourceSnapshotTest {
    @Test
    fun `snapshot preserves chronological order and original text`() {
        val later = segment(2, 2_000, "第二段原文")
        val earlier = segment(1, 1_000, "第一段原文")

        val snapshot = AiViewModel.buildSourceSnapshot(listOf(later, earlier))

        assertTrue(snapshot.indexOf("第一段原文") < snapshot.indexOf("第二段原文"))
        assertTrue(snapshot.contains("第一段原文"))
        assertTrue(snapshot.contains("第二段原文"))
    }

    @Test
    fun `snapshot uses applied correction while retaining raw text`() {
        val value = segment(1, 1_000, "创新创业").copy(correctedText = "创新创业教育")
        assertTrue(AiViewModel.buildSourceSnapshot(listOf(value)).contains("创新创业教育"))
        assertEquals("创新创业", value.text)
    }

    @Test
    fun `correction source carries stable ids`() {
        val source = AiViewModel.buildCorrectionSource(listOf(segment(42, 1_000, "原文")))
        assertTrue(source.contains("id=\"42\""))
        assertTrue(source.contains("原文"))
    }

    @Test
    fun `source limit counts Unicode code points without truncation`() {
        assertTrue(AiViewModel.isSourceWithinLimit("问".repeat(20_000)))
        assertTrue(AiViewModel.isSourceWithinLimit("😀".repeat(20_000)))
        assertFalse(AiViewModel.isSourceWithinLimit("😀".repeat(20_001)))
    }

    @Test
    fun `quick answer prompt answers explicit teacher questions without inventing`() {
        val prompt = AiViewModel.promptFor(AiActionType.QUICK_ANSWER)

        assertTrue(prompt.contains("识别老师明确提出的问题"))
        assertTrue(prompt.contains("逐条给出简洁答案"))
        assertTrue(prompt.contains("不得虚构"))
    }

    @Test
    fun `legacy extract questions action remains displayable`() {
        assertEquals("提取老师的问题", AiActionType.valueOf("EXTRACT_QUESTIONS").displayName)
    }

    @Test
    fun `general conversation title is whitespace normalized and Unicode safe`() {
        assertEquals("请 帮我回答", AiViewModel.conversationTitle("  请\n帮我回答  "))
        assertEquals(24, AiViewModel.conversationTitle("😀".repeat(30)).codePointCount(0, AiViewModel.conversationTitle("😀".repeat(30)).length))
        assertEquals("新对话", AiViewModel.conversationTitle("   "))
    }

    private fun segment(id: Long, start: Long, text: String) = TranscriptEntity(
        id = id,
        recordId = 1,
        startTime = start,
        endTime = start + 500,
        audioDurationMs = 500,
        recognitionDurationMs = 100,
        text = text
    )
}
