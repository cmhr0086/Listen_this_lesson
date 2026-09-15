package com.cmhr.listen.data.course

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordNameGeneratorTest {
    @Test
    fun septemberThirdUsesZeroPaddedLocalDate() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val timestamp = localTimestamp(zone, 2026, Calendar.SEPTEMBER, 3)
        assertEquals("课程名-09-03", RecordNameGenerator.defaultName("课程名", timestamp, zone))
    }

    @Test
    fun decemberThirtyFirstUsesZeroPaddedLocalDate() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val timestamp = localTimestamp(zone, 2026, Calendar.DECEMBER, 31)
        assertEquals("课程名-12-31", RecordNameGenerator.defaultName("课程名", timestamp, zone))
    }

    @Test
    fun dateIsResolvedInDeviceTimeZone() {
        val timestamp = 1_788_454_800_000L
        assertEquals("课程名-09-04", RecordNameGenerator.defaultName("课程名", timestamp, TimeZone.getTimeZone("Asia/Shanghai")))
        assertEquals("课程名-09-03", RecordNameGenerator.defaultName("课程名", timestamp, TimeZone.getTimeZone("America/Los_Angeles")))
    }

    private fun localTimestamp(zone: TimeZone, year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, 12, 0, 0)
        }.timeInMillis
}
