package com.cmhr.listen.data.device

import com.cmhr.listen.data.settings.SECTION_AI
import com.cmhr.listen.data.settings.SECTION_STT
import com.cmhr.listen.data.settings.sectionsStillDirty
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DeviceBindingTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DeviceApiClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = DeviceApiClient(baseUrl = server.url("/").toString(), requireHttps = false)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `a pairing asks for a code without any credentials`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"482913","claim":"lc_x","expiresAt":100}"""))
        val ticket = client.createPairing("HUAWEI MHA-AL00")
        assertEquals("482913", ticket.code)
        val sent = server.takeRequest()
        assertEquals("/api/public/v1/devices/pairings", sent.path)
        assertEquals(null, sent.getHeader("Authorization"))
        assertEquals("""{"name":"HUAWEI MHA-AL00"}""", sent.body.readUtf8())
    }

    @Test fun `a claim reports pending then hands over the token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved","deviceId":"d","deviceToken":"ld_x"}"""))
        assertEquals(null, client.claim("lc_x").deviceToken)
        assertEquals("ld_x", client.claim("lc_x").deviceToken)
    }

    @Test fun `an expired claim keeps its status and the server's words`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"detail":"绑定码已过期，请重新获取"}"""))
        try {
            client.claim("lc_x")
            fail("expected an error")
        } catch (error: DeviceApiException) {
            assertEquals(410, error.httpCode)
            assertEquals("绑定码已过期，请重新获取", error.message)
        }
    }

    @Test fun `config sync leaves out sections it has nothing for`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"config":{"ai":{"provider":"DEEPSEEK","baseUrl":"https://a","model":"m","apiKey":"k"}},"version":3,"future":1}"""))
        val response = client.syncConfig(
            "ld_x",
            ConfigSyncRequest(changed = CloudConfig(stt = CloudStt("SELF_HOSTED", "http://mac", apiKey = null)))
        )
        assertEquals("k", response.config.ai?.apiKey)
        assertEquals(null, response.config.stt)
        val sent = server.takeRequest()
        assertEquals("Bearer ld_x", sent.getHeader("Authorization"))
        val body = sent.body.readUtf8()
        // The server treats a missing section as untouched, and rejects unknown fields.
        assertFalse(body.contains("\"ai\""))
        assertFalse(body.contains("apiKey"))
        assertTrue(body.contains("\"stt\""))
    }

    @Test fun `a section edited while a sync was out stays dirty`() {
        // Sent: stt and ai. stt was edited again meanwhile, and publish was edited after the send.
        val left = sectionsStillDirty(setOf(SECTION_STT, SECTION_AI, "publish"), setOf(SECTION_STT, SECTION_AI), setOf(SECTION_STT))
        assertEquals(setOf(SECTION_STT, "publish"), left)
    }

    @Test fun `changed sections are found by comparing copies`() {
        val before = CloudConfig(stt = CloudStt("SELF_HOSTED", "http://a"), ai = CloudAi("DEEPSEEK", "https://b"))
        val now = before.copy(stt = CloudStt("SELF_HOSTED", "http://c"))
        assertEquals(setOf(SECTION_STT), changedSections(before, now))
    }
}
