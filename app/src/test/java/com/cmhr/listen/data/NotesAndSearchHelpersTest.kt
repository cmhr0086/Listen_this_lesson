package com.cmhr.listen.data

import com.cmhr.listen.AiViewModel
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.data.course.escapeLike
import com.cmhr.listen.data.settings.decodeCourseColors
import com.cmhr.listen.data.settings.encodeCourseColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesAndSearchHelpersTest {
    @Test fun topicLineIsTakenOffGeneratedNotes() {
        assertEquals("函数的极限" to "## 一、定义", AiViewModel.splitTopic("主题：函数的极限\n\n## 一、定义"))
        assertEquals("函数的极限" to "正文", AiViewModel.splitTopic("**主题：「函数的极限」**\n正文"))
        assertEquals("函数的极限" to "正文", AiViewModel.splitTopic("# 主题: 函数的极限\n正文"))
    }

    @Test fun notesWithoutATopicLineAreLeftAlone() {
        val (topic, notes) = AiViewModel.splitTopic("## 一、定义\n内容")
        assertNull(topic)
        assertEquals("## 一、定义\n内容", notes)
    }

    @Test fun markedLinesAreFlaggedForTheModel() {
        val marked = TranscriptEntity(id = 1, recordId = 1, sessionId = "s", startTime = 0, endTime = 1, audioDurationMs = 1, recognitionDurationMs = null, text = "要考", marked = true)
        val snapshot = AiViewModel.buildSourceSnapshot(listOf(marked))
        assertTrue(snapshot.contains(AiViewModel.MARKED_PREFIX))
        val message = AiViewModel.actionUserMessage(com.cmhr.listen.data.ai.AiActionType.ORGANIZE_NOTES, listOf(marked), snapshot, askTopic = true)
        assertTrue(message.startsWith("请先在第一行单独写「主题：」"))
        assertTrue(message.contains("学生上课时标记的重点"))
    }

    @Test fun likeWildcardsAreSearchedLiterally() {
        assertEquals("50\\%", escapeLike("50%"))
        assertEquals("a\\_b", escapeLike("a_b"))
        assertEquals("c:\\\\d", escapeLike("c:\\d"))
    }

    @Test fun courseColorPreferenceRoundTripsAndIgnoresJunk() {
        val colors = mapOf(3L to 1, 12L to 7)
        assertEquals(colors, decodeCourseColors(encodeCourseColors(colors)))
        assertEquals(mapOf(3L to 1), decodeCourseColors("3:1,x:2,4,:"))
        assertTrue(decodeCourseColors(null).isEmpty())
    }
}
