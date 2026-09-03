package com.cmhr.listen.data.stt

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttApiClientTest {
    @Test
    fun `parses legacy transcription JSON for stored test compatibility`() {
        val response = SttApiClient.parseResponse("""{"text":"测试文本","id":"unused"}""")
        assertEquals("测试文本", response.text)
    }

    @Test
    fun `submits asynchronous job using only audio language and context`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"job_id":"job-1","status":"queued"}"""))
        server.start()
        try {
            val outcome = client(server).submit("segment-1", 1, byteArrayOf(1, 2, 3), context = "线性代数 特征向量")

            assertTrue(outcome is SttCallOutcome.Success)
            assertEquals("job-1", (outcome as SttCallOutcome.Success).value.jobId)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("/transcribe", request.path)
            assertTrue(body.contains("name=\"audio\""))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("Chinese"))
            assertTrue(body.contains("name=\"context\""))
            assertTrue(body.contains("线性代数 特征向量"))
            assertFalse(body.contains("name=\"model\""))
            assertFalse(body.contains("name=\"prompt\""))
            assertTrue(outcome.trace.any { it.eventType == AsrNetworkEventType.CALL_START })
            assertTrue(outcome.trace.any { it.eventType == AsrNetworkEventType.REQUEST_BODY_END })
            assertTrue(outcome.trace.any { it.eventType == AsrNetworkEventType.RESPONSE_BODY_END })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `blank context is not included`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"job_id":"job-2","status":"queued"}"""))
        server.start()
        try {
            client(server).submit("segment-2", 1, byteArrayOf(1), context = "   ")
            val body = server.takeRequest().body.readUtf8()
            assertFalse(body.contains("name=\"context\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `polls job and parses completed text`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"job_id":"job-3","status":"completed","result":{"text":"识别成功","language":"Chinese"}}"""
            )
        )
        server.start()
        try {
            val outcome = client(server).poll("segment-3", 2, "job-3") as SttCallOutcome.Success
            assertEquals("识别成功", outcome.value.result?.text)
            assertEquals("/jobs/job-3", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `honors Retry-After on 503`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "7"))
        server.start()
        try {
            val outcome = client(server).submit("segment-4", 1, byteArrayOf(1)) as SttCallOutcome.HttpError
            assertEquals(503, outcome.httpCode)
            assertEquals(7_000L, outcome.retryAfterMs)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `health reports the actual server model`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ok","model":"Qwen/Qwen3-ASR-0.6B","queued_jobs":4,"processing_jobs":1,"max_queue_depth":10}"""
            )
        )
        server.start()
        try {
            val outcome = client(server).health() as SttCallOutcome.Success
            assertEquals("Qwen/Qwen3-ASR-0.6B", outcome.value.model)
            assertEquals(4, outcome.value.queuedJobs)
            assertEquals("/health", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `missing server job is returned as 404 for queue recovery`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val outcome = client(server).poll("segment-5", 3, "expired-job") as SttCallOutcome.HttpError
            assertEquals(404, outcome.httpCode)
        } finally {
            server.shutdown()
        }
    }

    private fun client(server: MockWebServer) = SttApiClient(
        credentialsProvider = { SttCredentials(server.url("/").toString().trimEnd('/'), "test-key") },
        httpClient = OkHttpClient()
    )
}
