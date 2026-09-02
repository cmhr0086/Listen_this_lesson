package com.cmhr.listen.data.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SttApiClientTest {
    @Test
    fun `parses transcription text and ignores extra JSON fields`() {
        val response = SttApiClient.parseResponse("""{"text":"测试文本","id":"unused"}""")

        assertEquals("测试文本", response.text)
    }

    @Test
    fun `adds non-blank course prompt to transcription multipart`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"识别成功"}"""))
        server.start()
        try {
            val client = SttApiClient(
                credentialsProvider = {
                    SttCredentials(server.url("/").toString().trimEnd('/'), "test-key")
                },
                httpClient = OkHttpClient()
            )

            assertEquals("识别成功", client.transcribe(byteArrayOf(1, 2, 3), "线性代数 特征向量"))

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("/v1/audio/transcriptions", request.path)
            assertTrue(body.contains("name=\"prompt\""))
            assertTrue(body.contains("线性代数 特征向量"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `does not add prompt field when course prompt is blank`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"识别成功"}"""))
        server.start()
        try {
            val client = SttApiClient(
                credentialsProvider = {
                    SttCredentials(server.url("/").toString().trimEnd('/'), "test-key")
                },
                httpClient = OkHttpClient()
            )

            client.transcribe(byteArrayOf(1), "   ")

            assertFalse(server.takeRequest().body.readUtf8().contains("name=\"prompt\""))
        } finally {
            server.shutdown()
        }
    }
}
