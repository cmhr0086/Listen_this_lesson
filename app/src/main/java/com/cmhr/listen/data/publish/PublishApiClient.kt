package com.cmhr.listen.data.publish

import com.cmhr.listen.data.sync.SyncApiClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A failure worth showing as-is: the message is already written for the user. */
class PublishException(message: String, val httpCode: Int? = null) : IOException(message)

class PublishApiClient(
    private val httpClient: OkHttpClient = defaultClient,
    private val json: Json = defaultJson,
    /** Only tests talk plain HTTP (to MockWebServer); a real token never leaves without TLS. */
    private val requireHttps: Boolean = true
) {
    suspend fun me(baseUrl: String, token: String): PublishUploader =
        call(baseUrl, token, "me") { get() }.let { parse(it) }

    suspend fun status(baseUrl: String, token: String, sessionId: String): PublishLessonStatus =
        call(baseUrl, token, "submissions", "sessionId" to sessionId) { get() }.let { parse(it) }

    suspend fun submit(baseUrl: String, token: String, submission: PublishSubmission): PublishSubmissionResult {
        // The publish service always reads gzip, and a long class compresses several times over.
        val body = SyncApiClient.gzip(json.encodeToString(submission).toByteArray(Charsets.UTF_8))
        return call(baseUrl, token, "submissions") {
            header("Content-Encoding", "gzip").post(body.toRequestBody(JSON))
        }.let { parse(it) }
    }

    suspend fun requestWithdraw(baseUrl: String, token: String, lessonId: String, reason: String) {
        val body = json.encodeToString(PublishWithdrawRequest(reason)).toRequestBody(JSON)
        call(baseUrl, token, "lessons/$lessonId/withdraw-request") { post(body) }
    }

    private suspend fun call(
        baseUrl: String,
        token: String,
        path: String,
        vararg query: Pair<String, String>,
        method: Request.Builder.() -> Request.Builder
    ): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "请先填写上传 Token。" }
        val url = endpoint(baseUrl, path, *query, requireHttps = requireHttps)
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${token.trim()}").method().build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw PublishException(errorMessage(response.code, text), response.code)
                text
            }
        } catch (error: PublishException) {
            throw error
        } catch (error: IOException) {
            throw PublishException("连不上发布服务，请检查网络后重试。")
        }
    }.also { if (it.isBlank()) throw PublishException("发布服务返回了空内容。") }

    private inline fun <reified T> parse(text: String): T = try {
        json.decodeFromString(kotlinx.serialization.serializer<T>(), text)
    } catch (_: SerializationException) {
        throw PublishException("发布服务返回了无法解析的数据。")
    } catch (_: IllegalArgumentException) {
        throw PublishException("发布服务返回了无法解析的数据。")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val defaultJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

        internal fun endpoint(baseUrl: String, path: String, vararg query: Pair<String, String>, requireHttps: Boolean = true): HttpUrl {
            val parsed = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            if (parsed == null || (requireHttps && parsed.scheme != "https")) throw PublishException("发布服务地址必须是有效的 https:// 地址。")
            return parsed.newBuilder().apply {
                "api/publish/v1/$path".split('/').forEach { addPathSegment(it) }
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
        }

        internal fun errorMessage(code: Int, body: String): String {
            val detail = detail(body)
            return when (code) {
                401 -> "上传 Token 无效或已被停用，请向管理员确认。"
                404 -> detail ?: "发布服务上没有这个接口，请检查地址。"
                409 -> detail ?: "这次提交与服务器上的记录冲突。"
                413 -> "这节课内容太多，超过了发布服务的上限。"
                422 -> "提交的内容不符合要求" + (detail?.let { "：$it" } ?: "。")
                429 -> detail ?: "提交过于频繁，请稍后再试。"
                else -> "发布服务请求失败（HTTP $code）" + (detail?.let { "：$it" } ?: "。")
            }
        }

        /** FastAPI puts a message in `detail`, or a list of field errors for validation failures. */
        private fun detail(body: String): String? = runCatching {
            when (val detail = (defaultJson.parseToJsonElement(body) as? JsonObject)?.get("detail")) {
                is JsonPrimitive -> detail.content
                is JsonArray -> detail.mapNotNull { (it as? JsonObject)?.get("msg")?.jsonPrimitive?.content }.firstOrNull()
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
