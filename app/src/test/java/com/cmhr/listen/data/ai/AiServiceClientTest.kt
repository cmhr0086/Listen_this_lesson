package com.cmhr.listen.data.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiServiceClientTest {
    @Test
    fun `chat sends OpenAI compatible request and parses content`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"课堂总结"}}]}"""
            ))
            val client = AiServiceClient(OkHttpClient(), enforceHttps = false)
            val result = client.chat(
                AiCredentials(server.url("/").toString().trimEnd('/'), "secret", "deepseek-chat"),
                listOf(AiChatMessage("user", "测试内容")),
                AiRequestOptions(temperature = 0.2)
            )

            assertEquals("课堂总结", result.content)
            val request = server.takeRequest()
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"model\":\"deepseek-chat\""))
            assertTrue(body.contains("\"temperature\":0.2"))
            assertTrue(body.contains("\"max_tokens\":8192"))
        }
    }

    @Test
    fun `models test reports authentication failure without leaking key`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val result = AiServiceClient(OkHttpClient(), enforceHttps = false)
                .testConnection(server.url("/").toString(), "do-not-leak")

            assertTrue(result is AiConnectionResult.Failure)
            val message = (result as AiConnectionResult.Failure).message
            assertTrue(message.contains("401"))
            assertTrue(!message.contains("do-not-leak"))
            assertEquals("/models", server.takeRequest().path)
        }
    }

    @Test
    fun `production client rejects cleartext AI address`() = runBlocking {
        val result = AiServiceClient().testConnection("http://127.0.0.1:1234", "key")
        assertEquals("AI 服务地址必须使用 https://。", (result as AiConnectionResult.Failure).message)
    }

    @Test
    fun `fetch models parses OpenAI compatible list`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """{"data":[{"id":"vision-b"},{"id":"chat-a"},{"id":"chat-a"}]}"""
            ))
            val result = AiServiceClient(OkHttpClient(), enforceHttps = false)
                .fetchModels(server.url("/").toString(), "secret") as AiModelsResult.Success

            assertEquals(listOf("chat-a", "vision-b"), result.models)
            assertEquals("/models", server.takeRequest().path)
        }
    }

    @Test
    fun `chat encodes image attachments as multimodal content`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"图片说明"}}]}"""
            ))
            AiServiceClient(OkHttpClient(), enforceHttps = false).chat(
                AiCredentials(server.url("/").toString().trimEnd('/'), "secret", "vision-model"),
                listOf(AiChatMessage("user", "分析照片", listOf("data:image/jpeg;base64,YQ=="))),
                AiRequestOptions(temperature = 0.2)
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("image_url"))
            assertTrue(body.contains("data:image/jpeg;base64,YQ=="))
            assertTrue(body.contains("分析照片"))
        }
    }

    @Test
    fun `chat parses array content and legacy text`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""
            ))
            server.enqueue(MockResponse().setBody("""{"choices":[{"text":"旧式结果"}]}"""))
            val client = AiServiceClient(OkHttpClient(), enforceHttps = false)
            val credentials = AiCredentials(server.url("/").toString().trimEnd('/'), "secret", "model")
            val first = client.chat(credentials, listOf(AiChatMessage("user", "问")))
            val second = client.chat(credentials, listOf(AiChatMessage("user", "问")))
            assertEquals("第一段第二段", first.content)
            assertEquals("stop", first.finishReason)
            assertEquals(9, first.promptTokens)
            assertEquals("旧式结果", second.content)
        }
    }

    @Test
    fun `reasoning only response reports a clear error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"content":"","reasoning_content":"hidden"},"finish_reason":"stop"}]}"""
            ))
            val failure = runCatching {
                AiServiceClient(OkHttpClient(), enforceHttps = false).chat(
                    AiCredentials(server.url("/").toString().trimEnd('/'), "secret", "model"),
                    listOf(AiChatMessage("user", "问"))
                )
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("思考"))
        }
    }
}
