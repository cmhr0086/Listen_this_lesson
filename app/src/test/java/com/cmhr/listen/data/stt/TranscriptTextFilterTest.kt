package com.cmhr.listen.data.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTextFilterTest {
    @Test
    fun `single and repeated filler results are discarded`() {
        listOf("嗯。", "啊啊", "呃……", "哦？", "唉～", "  欸！  ").forEach { text ->
            assertTrue(text, TranscriptTextFilter.isFillerOnly(text))
        }
    }

    @Test
    fun `different fillers and meaningful text are retained`() {
        listOf("嗯，好的", "啊这个问题", "哦我明白了", "嗯啊", "哎，同学们看这里").forEach { text ->
            assertFalse(text, TranscriptTextFilter.isFillerOnly(text))
        }
    }

    @Test
    fun `blank or punctuation only text is not classified as filler`() {
        listOf("", "   ", "……？！").forEach { text ->
            assertFalse(text, TranscriptTextFilter.isFillerOnly(text))
        }
    }
}
