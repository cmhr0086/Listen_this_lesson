package com.cmhr.listen.data.sync

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SyncApiClient(
    private val httpClient: OkHttpClient = defaultClient,
    private val json: Json = defaultJson
) : SyncRemoteDataSource {
    override suspend fun sync(baseUrl: String, apiToken: String, request: SyncRequest): SyncResponse = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().trimEnd('/')
        require(apiToken.isNotBlank()) { "请先配置云同步 Token。" }
        val url = "$normalized/api/v1/sync".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("云同步服务器地址无效。")
        val body = json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiToken.trim()}")
            .post(body)
            .build()
        try {
            httpClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("云同步服务请求失败（HTTP ${response.code}）。")
                }
                try {
                    json.decodeFromString<SyncResponse>(responseBody)
                } catch (_: SerializationException) {
                    throw IOException("云同步服务返回了无法解析的数据。")
                }
            }
        } catch (error: IOException) {
            throw error
        }
    }

    companion object {
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
    }
}
