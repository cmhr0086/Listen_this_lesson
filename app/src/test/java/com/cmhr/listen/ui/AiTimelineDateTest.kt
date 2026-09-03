package com.cmhr.listen.ui

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AiTimelineDateTest {
    private val today = LocalDate.of(2026, 9, 2)

    @Test
    fun `timeline groups recent items by relative day and older items by date`() {
        assertEquals("今天", dayGroupLabel(epoch(today), today))
        assertEquals("昨天", dayGroupLabel(epoch(today.minusDays(1)), today))
        assertEquals("2天前", dayGroupLabel(epoch(today.minusDays(2)), today))
        assertEquals("6天前", dayGroupLabel(epoch(today.minusDays(6)), today))
        assertEquals("2026-08-26", dayGroupLabel(epoch(today.minusDays(7)), today))
    }

    private fun epoch(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
