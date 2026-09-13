package com.cmhr.listen.data.sync

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApiClientTest {
    @Test
    fun `posts unified sync payload and parses response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"serverTime":2000,"sessions":[],"segments":[],"sessionAcks":[],"segmentAcks":[]}"""
        ))
        server.start()
        try {
            val response = SyncApiClient(OkHttpClient()).sync(
                server.url("/").toString(),
                API_TOKEN,
                SyncRequest(DEVICE_ID, 1_000, listOf(session()), emptyList())
            )

            assertEquals(2_000L, response.serverTime)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("/api/v1/sync", request.path)
            assertEquals("Bearer $API_TOKEN", request.getHeader("Authorization"))
            assertTrue(body.contains("\"deviceId\":\"$DEVICE_ID\""))
            assertTrue(body.contains("\"sessionId\":\"$SESSION_ID\""))
            assertFalse(body.contains("syncStatus"))
            assertFalse(body.contains("courseId"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http failure is reported and has no successful response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        try {
            val error = runCatching {
                SyncApiClient(OkHttpClient()).sync(
                    server.url("/").toString(),
                    API_TOKEN,
                    SyncRequest(DEVICE_ID, 0, emptyList(), emptyList())
                )
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("503"))
        } finally {
            server.shutdown()
        }
    }

    private fun session() = SyncSessionPayload(
        sessionId = SESSION_ID,
        courseName = "测试课程",
        name = "测试课堂",
        startedAt = 900,
        createdAt = 900,
        updatedAt = 1_000,
        deleted = false
    )

    private companion object {
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000001"
        const val SESSION_ID = "00000000-0000-4000-8000-000000000002"
        const val API_TOKEN = "test-sync-token"
    }
}
