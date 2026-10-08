package com.cmhr.listen.data.sync

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer

class SyncApiClient(
    private val httpClient: OkHttpClient = defaultClient,
    private val json: Json = defaultJson
) : SyncRemoteDataSource {
    override suspend fun sync(baseUrl: String, apiToken: String, request: SyncRequest): SyncResponse = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().trimEnd('/')
        require(apiToken.isNotBlank()) { "请先配置云同步 Token。" }
        val url = "$normalized/api/v1/sync".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("云同步服务器地址无效。")
        val body = json.encodeToString(request).toByteArray(Charsets.UTF_8)
        val compress = url.capabilityKey() in gzipCapableHosts
        execute(url, apiToken, body, compress).use { response ->
            // A rolled-back server may stop accepting gzip; retry the same batch uncompressed once.
            if (compress && response.code in GZIP_REJECTED_CODES) {
                gzipCapableHosts.remove(url.capabilityKey())
                execute(url, apiToken, body, compress = false).use { retry -> parse(url, retry) }
            } else parse(url, response)
        }
    }

    private fun execute(url: HttpUrl, apiToken: String, body: ByteArray, compress: Boolean): Response {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiToken.trim()}")
        if (compress) builder.header("Content-Encoding", "gzip").post(gzip(body).toRequestBody(JSON_MEDIA_TYPE))
        else builder.post(body.toRequestBody(JSON_MEDIA_TYPE))
        return httpClient.newCall(builder.build()).execute()
    }

    private fun parse(url: HttpUrl, response: Response): SyncResponse {
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException("云同步服务请求失败（HTTP ${response.code}）。")
        }
        if (response.header(CAPABILITIES_HEADER).orEmpty().split(',').any { it.trim() == GZIP_REQUEST_CAPABILITY }) {
            gzipCapableHosts.add(url.capabilityKey())
        }
        return try {
            json.decodeFromString<SyncResponse>(responseBody)
        } catch (_: SerializationException) {
            throw IOException("云同步服务返回了无法解析的数据。")
        }
    }

    companion object {
        /** Hosts that advertised gzip request support (process-wide). Old servers never do, so they get plain JSON. */
        private val gzipCapableHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private const val CAPABILITIES_HEADER = "X-Listen-Sync-Capabilities"
        private const val GZIP_REQUEST_CAPABILITY = "gzip-request"
        private val GZIP_REJECTED_CODES = setOf(400, 415, 422)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        private fun HttpUrl.capabilityKey() = "$host:$port"

        internal fun gzip(bytes: ByteArray): ByteArray {
            val buffer = Buffer()
            GzipSink(buffer).buffer().use { it.write(bytes) }
            return buffer.readByteArray()
        }
    }
}
