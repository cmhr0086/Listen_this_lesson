package com.cmhr.listen.data

import com.cmhr.listen.data.sync.SyncSegmentPayload
import com.cmhr.listen.data.sync.SyncSessionPayload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A 1.2 sync server rejects unknown fields, so the 1.3 fields must stay out until they are set. */
class SyncPayloadCompatibilityTest {
    // Same settings as SyncApiClient's request encoder.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    private val segment = SyncSegmentPayload(
        segmentId = "11111111-1111-1111-1111-111111111111", sessionId = "22222222-2222-2222-2222-222222222222",
        startTime = 1, endTime = 2, audioDurationMs = 1, text = "t", createdAt = 1, updatedAt = 1, deleted = false
    )
    private val session = SyncSessionPayload(
        sessionId = "22222222-2222-2222-2222-222222222222", courseName = "高数", name = "课",
        startedAt = 1, createdAt = 1, updatedAt = 1, deleted = false
    )

    @Test fun unsetNewFieldsAreLeftOut() {
        assertFalse(json.encodeToString(SyncSegmentPayload.serializer(), segment).contains("marked"))
        assertFalse(json.encodeToString(SyncSessionPayload.serializer(), session).contains("topic"))
        // Older fields keep being sent even when null.
        assertTrue(json.encodeToString(SyncSegmentPayload.serializer(), segment).contains("\"correctedText\":null"))
    }

    @Test fun setNewFieldsAreSent() {
        assertTrue(json.encodeToString(SyncSegmentPayload.serializer(), segment.copy(marked = true)).contains("\"marked\":true"))
        assertTrue(json.encodeToString(SyncSessionPayload.serializer(), session.copy(topic = "极限")).contains("\"topic\":\"极限\""))
    }
}
