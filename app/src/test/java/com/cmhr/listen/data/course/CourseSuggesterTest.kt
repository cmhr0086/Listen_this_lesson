package com.cmhr.listen.data.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CourseSuggesterTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-10-07 is a Wednesday. */
    private fun at(day: Int, hour: Int, minute: Int, month: Int = Calendar.OCTOBER) =
        Calendar.getInstance(zone).apply { clear(); set(2026, month, day, hour, minute) }.timeInMillis

    private fun course(id: Long, lastStartedAt: Long?, createdAt: Long = 0) =
        CourseSummary(CourseEntity(id = id, name = "c$id", createdAt = createdAt), recordCount = 1, lastStartedAt = lastStartedAt)

    @Test fun picksTheCourseUsuallyAttendedOnThisWeekdayAndTime() {
        val history = listOf(
            CourseStart(1, at(30, 10, 22, Calendar.SEPTEMBER)), // Wed
            CourseStart(1, at(23, 10, 25, Calendar.SEPTEMBER)), // Wed
            CourseStart(2, at(6, 15, 45)),                      // Tue afternoon, more recent
        )
        val courses = listOf(course(1, at(30, 10, 22, Calendar.SEPTEMBER)), course(2, at(6, 15, 45)))

        val suggestion = CourseSuggester.suggest(at(7, 10, 15), history, courses, zone)

        assertEquals(CourseSuggestion(1, CourseSuggestion.Reason.USUAL_TIME), suggestion)
    }

    @Test fun outsideTheTimeWindowFallsBackToTheMostRecentCourse() {
        val history = listOf(CourseStart(1, at(30, 10, 20, Calendar.SEPTEMBER)), CourseStart(2, at(6, 15, 45)))
        val courses = listOf(course(1, at(30, 10, 20, Calendar.SEPTEMBER)), course(2, at(6, 15, 45)))

        // Wednesday 13:00 is more than 75 minutes from the usual 10:20 slot.
        val suggestion = CourseSuggester.suggest(at(7, 13, 0), history, courses, zone)

        assertEquals(CourseSuggestion(2, CourseSuggestion.Reason.MOST_RECENT), suggestion)
    }

    @Test fun historyOlderThanTheLookbackIsIgnored() {
        val old = at(1, 10, 20, Calendar.JULY) // a Wednesday, but far outside 8 weeks
        val suggestion = CourseSuggester.suggest(at(7, 10, 20), listOf(CourseStart(1, old)), listOf(course(1, old), course(2, at(6, 9, 0))), zone)
        assertEquals(CourseSuggestion.Reason.MOST_RECENT, suggestion?.reason)
        assertEquals(2L, suggestion?.courseId)
    }

    @Test fun deletedCoursesInHistoryAreNotSuggested() {
        val history = listOf(CourseStart(9, at(30, 10, 20, Calendar.SEPTEMBER)))
        val suggestion = CourseSuggester.suggest(at(7, 10, 20), history, listOf(course(1, null, createdAt = 5)), zone)
        assertEquals(CourseSuggestion(1, CourseSuggestion.Reason.MOST_RECENT), suggestion)
    }

    @Test fun noCoursesMeansNoSuggestion() {
        assertNull(CourseSuggester.suggest(at(7, 10, 20), emptyList(), emptyList(), zone))
    }

    @Test fun aClassEarlierTodayIsNotTreatedAsTheUsualTime() {
        val earlierToday = at(7, 9, 50)
        val suggestion = CourseSuggester.suggest(at(7, 10, 15), listOf(CourseStart(1, earlierToday)), listOf(course(1, earlierToday)), zone)
        assertEquals(CourseSuggestion(1, CourseSuggestion.Reason.MOST_RECENT), suggestion)
    }
}
