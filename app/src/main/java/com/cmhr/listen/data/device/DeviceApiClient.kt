package com.cmhr.listen.data.device

import com.cmhr.listen.data.publish.PUBLISH_BASE_URL
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One service's settings as the cloud keeps them. A section the cloud has nothing for is null. */
@Serializable
data class CloudStt(val provider: String, val baseUrl: String, val model: String = "", val apiKey: String? = null)

@Serializable
data class CloudAi(val provider: String, val baseUrl: String, val model: String = "", val apiKey: String? = null)

@Serializable
data class CloudSync(val baseUrl: String, val apiToken: String? = null)

@Serializable
data class CloudPublish(val token: String? = null, val schoolName: String = "")

@Serializable
data class CloudConfig(
    val stt: CloudStt? = null,
    val ai: CloudAi? = null,
    val sync: CloudSync? = null,
    val publish: CloudPublish? = null
)

/** changed replaces the cloud copy; offered only fills sections the cloud has nothing for. */
@Serializable
data class ConfigSyncRequest(val changed: CloudConfig = CloudConfig(), val offered: CloudConfig = CloudConfig())

@Serializable
data class ConfigSyncResponse(val config: CloudConfig = CloudConfig(), val version: Int = 0, val updatedAt: Long? = null)

@Serializable
data class PairingTicket(val code: String, val claim: String, val expiresAt: Long)

@Serializable
data class PairingClaimResult(val status: String, val deviceId: String? = null, val deviceToken: String? = null)

@Serializable
private data class PairingRequest(val name: String)

@Serializable
private data class PairingClaim(val claim: String)

class DeviceApiException(message: String, val httpCode: Int? = null) : IOException(message)

/** Binds this phone to an account and keeps its service settings in the cloud. */
class DeviceApiClient(
    private val baseUrl: String = PUBLISH_BASE_URL,
    private val httpClient: OkHttpClient = defaultClient,
    /** Only tests talk plain HTTP (to MockWebServer); keys never leave without TLS. */
    private val requireHttps: Boolean = true
) {
    suspend fun createPairing(name: String): PairingTicket =
        parse(post("devices/pairings", json.encodeToString(PairingRequest(name)), null))

    suspend fun claim(claim: String): PairingClaimResult =
        parse(post("devices/pairings/claim", json.encodeToString(PairingClaim(claim)), null))

    suspend fun syncConfig(deviceToken: String, request: ConfigSyncRequest): ConfigSyncResponse =
        parse(post("devices/config/sync", json.encodeToString(request), deviceToken))

    suspend fun unbind(deviceToken: String) {
        call("devices/current", deviceToken) { delete() }
    }

    private suspend fun post(path: String, body: String, token: String?): String =
        call(path, token) { post(body.toRequestBody(JSON)) }

    private suspend fun call(path: String, token: String?, method: Request.Builder.() -> Request.Builder): String =
        withContext(Dispatchers.IO) {
            val parsed = baseUrl.trimEnd('/').toHttpUrlOrNull()
            if (parsed == null || (requireHttps && parsed.scheme != "https")) throw DeviceApiException("服务地址无效。")
            val url = parsed.newBuilder().apply { "api/public/v1/$path".split('/').forEach { addPathSegment(it) } }.build()
            val request = Request.Builder().url(url).apply { if (token != null) header("Authorization", "Bearer $token") }.method().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw DeviceApiException(errorMessage(response.code, text), response.code)
                    text
                }
            } catch (error: DeviceApiException) {
                throw error
            } catch (_: IOException) {
                throw DeviceApiException("连不上服务器，请检查网络后重试。")
            }
        }

    private inline fun <reified T> parse(text: String): T = try {
        json.decodeFromString(kotlinx.serialization.serializer<T>(), text)
    } catch (_: SerializationException) {
        throw DeviceApiException("服务器返回了无法解析的数据。")
    } catch (_: IllegalArgumentException) {
        throw DeviceApiException("服务器返回了无法解析的数据。")
    }

    private fun errorMessage(code: Int, text: String): String {
        val detail = runCatching { json.decodeFromString<JsonObject>(text)["detail"] as? JsonPrimitive }.getOrNull()
        return detail?.takeIf { it.isString }?.content ?: "服务暂时不可用（$code）。"
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        // Null sections stay out of the body: the server reads a missing section as "nothing sent".
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
