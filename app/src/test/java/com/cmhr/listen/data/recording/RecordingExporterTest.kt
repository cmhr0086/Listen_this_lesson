package com.cmhr.listen.data.recording

import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.course.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RecordingExporterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun summary(name: String, number: Int) = SessionSummary(
        session = SessionEntity(courseId = 1, name = name, startedAt = LocalDateTime.of(2026, 10, 19, 8, 30).atZone(zone).toInstant().toEpochMilli()),
        courseName = "材料力学",
        segmentCount = 0,
        classNumber = number
    )

    @Test
    fun namesSortInTeachingOrder() {
        assertEquals("第03节 截面的几何性质 2026-10-19.wav", RecordingExporter.exportFileName(summary("截面的几何性质", 3), 1, 1, zone))
    }

    @Test
    fun classesRecordedInPartsGetPartNumbers() {
        assertEquals("第12节 复习 2026-10-19 录音2.wav", RecordingExporter.exportFileName(summary("复习", 12), 2, 3, zone))
    }

    @Test
    fun unsafeCharactersAreReplaced() {
        assertEquals("第01节 A B C 2026-10-19.wav", RecordingExporter.exportFileName(summary("A/B:C", 1), 1, 1, zone))
    }
}
