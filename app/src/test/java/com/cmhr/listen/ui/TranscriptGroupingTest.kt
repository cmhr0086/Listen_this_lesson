package com.cmhr.listen.ui

import com.cmhr.listen.data.course.TranscriptEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptGroupingTest {
    private var nextId = 1L
    private fun seg(start: Long, end: Long) = TranscriptEntity(
        id = nextId++, recordId = 1, sessionId = "s", startTime = start, endTime = end,
        audioDurationMs = end - start, recognitionDurationMs = null, text = "t$start"
    )

    @Test fun continuousSpeechStaysInOneParagraph() {
        val groups = groupTranscript(listOf(seg(0, 10_000), seg(11_000, 20_000), seg(21_000, 30_000)))
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().segments.size)
        assertEquals(0L, groups.single().startTime)
        assertEquals(30_000L, groups.single().endTime)
    }

    @Test fun aLongPauseStartsANewParagraph() {
        val groups = groupTranscript(listOf(seg(0, 10_000), seg(10_500, 20_000), seg(60_000, 70_000)))
        assertEquals(listOf(2, 1), groups.map { it.segments.size })
        assertEquals(60_000L, groups[1].startTime)
    }

    @Test fun paragraphsAreCappedAtThreeMinutes() {
        // 20 s segments back to back for 8 minutes.
        val segments = (0 until 24).map { seg(it * 20_000L, it * 20_000L + 19_000) }
        val groups = groupTranscript(segments)
        assertEquals(segments, groups.flatMap { it.segments })
        assertEquals(true, groups.all { it.endTime - it.startTime <= PARAGRAPH_MAX_SPAN_MS })
        assertEquals(3, groups.size)
    }

    @Test fun linePositionsJoinIntoOneCard() {
        assertEquals(LinePosition.ONLY, linePosition(0, 1))
        assertEquals(listOf(LinePosition.FIRST, LinePosition.MIDDLE, LinePosition.LAST), (0 until 3).map { linePosition(it, 3) })
    }

    @Test fun noSegmentsMeansNoParagraphs() {
        assertEquals(emptyList<TranscriptGroup>(), groupTranscript(emptyList()))
    }
}
