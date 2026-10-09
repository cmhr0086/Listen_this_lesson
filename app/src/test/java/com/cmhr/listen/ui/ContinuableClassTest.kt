package com.cmhr.listen.ui

import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.PausedClass
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.course.SessionSummary
import com.cmhr.listen.recording.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class ContinuableClassTest {
    private fun at(hour: Int, minute: Int, day: Int = 9) =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }.timeInMillis

    private fun summary(id: Long, start: Long, end: Long?) =
        SessionSummary(SessionEntity(id = id, courseId = 1, name = "散打-10-09", startedAt = start, endedAt = end), courseName = "散打", segmentCount = 10)

    @Test fun todaysClassEndedRecentlyCanBeContinued() {
        val recent = listOf(summary(1, at(10, 29), at(11, 53)))
        assertEquals(1L, continuableClass(recent, ListeningUiState(), now = at(12, 30))?.session?.id)
    }

    @Test fun tooLongAgoOrYesterdayIsNotOffered() {
        assertNull(continuableClass(listOf(summary(1, at(8, 0), at(8, 50))), ListeningUiState(), now = at(12, 30)))
        assertNull(continuableClass(listOf(summary(1, at(21, 0, day = 8), at(23, 50, day = 8))), ListeningUiState(), now = at(0, 30)))
    }

    @Test fun nothingIsOfferedWhileCapturingOrPaused() {
        val recent = listOf(summary(1, at(10, 29), at(11, 53)))
        assertNull(continuableClass(recent, ListeningUiState(isListening = true, activeRecordId = 2), now = at(12, 0)))
        val paused = ListeningUiState(pausedClass = PausedClass(1, CaptureMode.REALTIME_ASR, "散打", "散打-10-09", 60_000))
        assertNull(continuableClass(recent, paused, now = at(12, 0)))
    }

    @Test fun aClassStillOpenIsNotOffered() {
        assertNull(continuableClass(listOf(summary(1, at(10, 29), null)), ListeningUiState(), now = at(10, 40)))
    }
}

class TabOwnershipTest {
    @Test fun theLiveClassBelongsToRecordAndOtherClassesToCourses() {
        assertEquals(MainDestination.RECORD, mainDestinationForRoute("record/{recordId}", recordId = 5, liveRecordId = 5))
        assertEquals(MainDestination.COURSES, mainDestinationForRoute("record/{recordId}", recordId = 4, liveRecordId = 5))
        assertEquals(MainDestination.COURSES, mainDestinationForRoute("record/{recordId}", recordId = 4, liveRecordId = null))
        assertEquals(MainDestination.COURSES, mainDestinationForRoute("course/{courseId}"))
        assertEquals(MainDestination.RECORD, mainDestinationForRoute("record-home"))
        assertEquals(MainDestination.AI, mainDestinationForRoute("ai-conversation/{conversationId}"))
        assertEquals(MainDestination.SETTINGS, mainDestinationForRoute("settings/appearance"))
    }
}
