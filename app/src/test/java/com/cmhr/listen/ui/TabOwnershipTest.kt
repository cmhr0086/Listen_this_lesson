package com.cmhr.listen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

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
