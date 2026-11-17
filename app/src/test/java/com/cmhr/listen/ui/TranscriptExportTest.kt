package com.cmhr.listen.ui

import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.SegmentEntity
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.course.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExportTest {
    private val course = CourseEntity(id = 1, name = "材料力学", createdAt = 0)

    private fun lesson(id: Long, number: Int, startedAt: Long, topic: String? = null) =
        SessionSummary(SessionEntity(id = id, courseId = 1, name = "材力-$id", startedAt = startedAt, endedAt = startedAt + 1000, topic = topic), "材料力学", 0, number)

    private fun segment(recordId: Long, seq: Long, text: String, corrected: String? = null) =
        SegmentEntity(recordId = recordId, startTime = seq * 100, endTime = seq * 100 + 50, audioDurationMs = 50, recognitionDurationMs = null, text = text, correctedText = corrected, sequenceNumber = seq, sessionId = "s$recordId")

    @Test
    fun courseTxtHasEveryClassWithTextInOrder() {
        val txt = buildCourseTxt(
            course,
            listOf(
                lesson(1, 1, 1_000, topic = "截面的几何性质") to listOf(segment(1, 2, "第二句"), segment(1, 1, "第一句", corrected = "第一句改")),
                lesson(2, 2, 2_000) to emptyList(),
                lesson(3, 3, 3_000) to listOf(segment(3, 1, "第三节"))
            )
        )!!
        assertTrue(txt.startsWith("课程：材料力学\n共 2 节\n"))
        assertTrue(txt.contains("==== 第 1 节 · 截面的几何性质 ===="))
        assertTrue(txt.contains("==== 第 3 节 ===="))
        assertFalse(txt.contains("第 2 节"))
        assertTrue(txt.indexOf("第一句改") < txt.indexOf("第二句"))
        assertTrue(txt.indexOf("第二句") < txt.indexOf("第三节"))
        assertFalse(txt.contains("第一句\n"))
    }

    @Test
    fun courseWithoutTextExportsNothing() {
        assertNull(buildCourseTxt(course, listOf(lesson(1, 1, 1_000) to emptyList())))
        assertNull(buildCourseTxt(course, emptyList()))
    }

    @Test
    fun noSegmentsMeansNoFile() {
        assertEquals(null, buildCourseTxt(course, listOf(lesson(2, 2, 0) to emptyList())))
    }
}
