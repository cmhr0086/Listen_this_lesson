package com.cmhr.listen.data.ai

import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AiReasoningEffort
import com.cmhr.listen.data.settings.AiThinkingMode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AiCredentials(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val provider: AiProvider = AiProvider.OPENAI_COMPATIBLE
)

data class AiRequestOptions(
    val maxTokens: Int = 8_192,
    val temperature: Double = 0.2,
    val thinkingMode: AiThinkingMode = AiThinkingMode.DISABLED,
    val reasoningEffort: AiReasoningEffort = AiReasoningEffort.HIGH
)

data class AiCompletion(
    val content: String,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val reasoningPresent: Boolean,
    val durationMs: Long
)

sealed interface AiConnectionResult {
    data class Success(val statusCode: Int) : AiConnectionResult
    data class Failure(val message: String) : AiConnectionResult
}

sealed interface AiModelsResult {
    data class Success(val models: List<String>) : AiModelsResult
    data class Failure(val message: String) : AiModelsResult
}

data class AiChatMessage(
    val role: String,
    val content: String,
    val imageDataUrls: List<String> = emptyList()
)

@Serializable private data class ModelsResponse(val data: List<ModelItem> = emptyList())
@Serializable private data class ModelItem(val id: String = "")

class AiServiceClient(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val enforceHttps: Boolean = true
) {
    suspend fun testConnection(baseUrl: String, apiKey: String): AiConnectionResult = withContext(Dispatchers.IO) {
        val validation = validate(baseUrl, apiKey, model = "test")
        if (validation != null) return@withContext AiConnectionResult.Failure(validation)
        try {
            val request = Request.Builder()
                .url("${baseUrl.trim().trimEnd('/')}/models")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) AiConnectionResult.Success(response.code)
                else AiConnectionResult.Failure(httpFailure(response.code))
            }
        } catch (_: UnknownHostException) {
            AiConnectionResult.Failure("无法解析 AI 服务地址。")
        } catch (_: SocketTimeoutException) {
            AiConnectionResult.Failure("连接 AI 服务超时。")
        } catch (exception: IOException) {
            AiConnectionResult.Failure(exception.message?.takeIf { it.isNotBlank() } ?: "无法连接 AI 服务。")
        } catch (exception: IllegalArgumentException) {
            AiConnectionResult.Failure(exception.message ?: "AI 服务地址无效。")
        }
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String): AiModelsResult = withContext(Dispatchers.IO) {
        val validation = validate(baseUrl, apiKey, model = "test")
        if (validation != null) return@withContext AiModelsResult.Failure(validation)
        try {
            val request = Request.Builder()
                .url("${baseUrl.trim().trimEnd('/')}/models")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use AiModelsResult.Failure(httpFailure(response.code))
                val models = runCatching { json.decodeFromString(ModelsResponse.serializer(), body) }
                    .getOrElse { return@use AiModelsResult.Failure("AI 服务返回了无法解析的模型列表。") }
                    .data.map { it.id.trim() }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                if (models.isEmpty()) AiModelsResult.Failure("AI 服务没有返回可用模型。") else AiModelsResult.Success(models)
            }
        } catch (_: UnknownHostException) {
            AiModelsResult.Failure("无法解析 AI 服务地址。")
        } catch (_: SocketTimeoutException) {
            AiModelsResult.Failure("获取模型列表超时。")
        } catch (exception: IOException) {
            AiModelsResult.Failure(exception.message?.takeIf { it.isNotBlank() } ?: "无法获取模型列表。")
        } catch (exception: IllegalArgumentException) {
            AiModelsResult.Failure(exception.message ?: "AI 服务地址无效。")
        }
    }

    suspend fun chat(
        credentials: AiCredentials,
        messages: List<AiChatMessage>,
        options: AiRequestOptions = AiRequestOptions()
    ): AiCompletion = withContext(Dispatchers.IO) {
        validate(credentials.baseUrl, credentials.apiKey, credentials.model)?.let { throw IllegalStateException(it) }
        require(messages.isNotEmpty()) { "AI 请求内容不能为空。" }
        val hasImages = messages.any { it.imageDataUrls.isNotEmpty() }
        val request = Request.Builder()
            .url("${credentials.baseUrl.trim().trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${credentials.apiKey.trim()}")
            .post(buildRequest(credentials, messages, options).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val startedAt = System.nanoTime()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(httpFailure(response.code, hasImages))
            parseCompletion(responseBody, (System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun buildRequest(credentials: AiCredentials, messages: List<AiChatMessage>, options: AiRequestOptions): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "model" to JsonPrimitive(credentials.model.trim()),
            "messages" to JsonArray(messages.map(::payload)),
            "max_tokens" to JsonPrimitive(options.maxTokens.coerceIn(512, 32_768)),
            "stream" to JsonPrimitive(false)
        )
        val thinkingEnabled = credentials.provider == AiProvider.DEEPSEEK && options.thinkingMode == AiThinkingMode.ENABLED
        if (!thinkingEnabled) fields["temperature"] = JsonPrimitive(options.temperature.coerceIn(0.0, 2.0))
        if (credentials.provider == AiProvider.DEEPSEEK && options.thinkingMode != AiThinkingMode.SERVICE_DEFAULT) {
            fields["thinking"] = JsonObject(mapOf("type" to JsonPrimitive(if (thinkingEnabled) "enabled" else "disabled")))
            if (thinkingEnabled) fields["reasoning_effort"] = JsonPrimitive(options.reasoningEffort.name.lowercase())
        }
        return JsonObject(fields)
    }

    private fun parseCompletion(body: String, durationMs: Long): AiCompletion {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IOException("AI 服务返回了无法解析的数据。") }
        val choice = root["choices"]?.let { runCatching { it.jsonArray.firstOrNull()?.jsonObject }.getOrNull() }
            ?: throw IOException("AI 服务没有返回 choices。")
        val message = choice["message"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val content = message?.get("content")?.extractText().orEmpty().ifBlank {
            choice["text"]?.extractText().orEmpty()
        }.trim()
        val reasoningPresent = !message?.get("reasoning_content")?.extractText().isNullOrBlank()
        val finishReason = choice["finish_reason"]?.primitiveContent()
        val usage = root["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }
        if (finishReason in setOf("length", "content_filter", "insufficient_system_resource")) {
            throw IOException(emptyContentMessage(finishReason, reasoningPresent))
        }
        if (content.isBlank()) throw IOException(emptyContentMessage(finishReason, reasoningPresent))
        return AiCompletion(
            content = content,
            finishReason = finishReason,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
            reasoningPresent = reasoningPresent,
            durationMs = durationMs
        )
    }

    private fun JsonElement.extractText(): String = when (this) {
        JsonNull -> ""
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> part["text"]?.extractText() ?: part["content"]?.extractText()
                else -> null
            }
        }.joinToString("")
        is JsonObject -> this["text"]?.extractText().orEmpty()
    }

    private fun JsonElement.primitiveContent(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun emptyContentMessage(finishReason: String?, reasoningPresent: Boolean): String = when {
        finishReason == "length" -> "AI 输出达到 max_tokens 或上下文长度限制，未产生完整最终答案。"
        finishReason == "content_filter" -> "AI 输出被内容安全策略过滤。"
        finishReason == "insufficient_system_resource" -> "AI 服务资源不足，未产生最终答案。"
        reasoningPresent -> "AI 只返回了思考内容，没有最终答案；请关闭思考模式或提高 max_tokens 后重试。"
        else -> "AI 服务没有返回最终内容（finish_reason=${finishReason ?: "未知"}）。"
    }

    private fun payload(message: AiChatMessage): JsonObject {
        val content = if (message.imageDataUrls.isEmpty()) JsonPrimitive(message.content) else JsonArray(
            listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(message.content)))) +
                message.imageDataUrls.map { dataUrl ->
                    JsonObject(mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(mapOf("url" to JsonPrimitive(dataUrl)))
                    ))
                }
        )
        return JsonObject(mapOf("role" to JsonPrimitive(message.role), "content" to content))
    }

    private fun validate(baseUrl: String, apiKey: String, model: String): String? = when {
        baseUrl.trim().toHttpUrlOrNull() == null -> "AI 服务地址无效。"
        enforceHttps && !baseUrl.trim().startsWith("https://") -> "AI 服务地址必须使用 https://。"
        apiKey.isBlank() -> "请先填写 AI API Key。"
        model.isBlank() -> "请先填写 AI 模型名称。"
        else -> null
    }

    private fun httpFailure(code: Int, hasImages: Boolean = false): String = when {
        hasImages && code in listOf(400, 415, 422) -> "AI 服务拒绝了图片请求（HTTP $code），请检查当前模型是否支持视觉输入。"
        code == 401 -> "AI 服务鉴权失败（HTTP 401）。"
        code == 429 -> "AI 服务请求过于频繁或额度不足（HTTP 429）。"
        else -> "AI 服务请求失败（HTTP $code）。"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
