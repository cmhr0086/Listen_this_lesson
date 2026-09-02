package com.cmhr.listen.data.settings

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConnectionTesterTest {
    @Test
    fun successfulTestUsesHealthPath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val result = ServerConnectionTester(OkHttpClient()).test(server.url("/").toString())

            assertEquals(ConnectionTestResult.Success(200), result)
            assertEquals("/health", server.takeRequest().path)
        }
    }

    @Test
    fun non2xxIncludesStatusCode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val result = ServerConnectionTester(OkHttpClient()).test(server.url("/").toString())

            assertEquals(ConnectionTestResult.Failure("健康检查失败（HTTP 404）。"), result)
        }
    }

    @Test
    fun invalidUrlDoesNotStartRequest() = runBlocking {
        val result = ServerConnectionTester(OkHttpClient()).test("不是地址")
        assertEquals(ConnectionTestResult.Failure("服务器地址格式无效。"), result)
    }

    @Test
    fun timeoutHasChineseDiagnostic() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            val result = ServerConnectionTester(client).test(server.url("/").toString())
            assertTrue(result is ConnectionTestResult.Failure && result.message.contains("超时"))
        }
    }
}
