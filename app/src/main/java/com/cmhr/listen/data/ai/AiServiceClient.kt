package com.cmhr.listen.data.ai

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AiCredentials(val baseUrl: String, val apiKey: String, val model: String)

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

@Serializable
private data class ChatMessagePayload(val role: String, val content: JsonElement)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessagePayload>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int
)

@Serializable private data class ChatCompletionResponse(val choices: List<ChatChoice> = emptyList())
@Serializable private data class ChatChoice(val message: ChatResponseMessage)
@Serializable private data class ChatResponseMessage(val role: String = "assistant", val content: String = "")
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
                .get()
                .build()
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
                .get()
                .build()
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
        temperature: Double
    ): String = withContext(Dispatchers.IO) {
        validate(credentials.baseUrl, credentials.apiKey, credentials.model)?.let { throw IllegalStateException(it) }
        require(messages.isNotEmpty()) { "AI 请求内容不能为空。" }
        val hasImages = messages.any { it.imageDataUrls.isNotEmpty() }
        val body = json.encodeToString(
            ChatCompletionRequest(
                model = credentials.model.trim(),
                messages = messages.map(::payload),
                temperature = temperature,
                maxTokens = MAX_TOKENS
            )
        ).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${credentials.baseUrl.trim().trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${credentials.apiKey.trim()}")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(httpFailure(response.code, hasImages))
            val content = runCatching { json.decodeFromString(ChatCompletionResponse.serializer(), responseBody) }
                .getOrElse { throw IOException("AI 服务返回了无法解析的数据。") }
                .choices.firstOrNull()?.message?.content?.trim().orEmpty()
            if (content.isBlank()) throw IOException("AI 服务没有返回内容。")
            content
        }
    }

    private fun payload(message: AiChatMessage): ChatMessagePayload {
        val content = if (message.imageDataUrls.isEmpty()) JsonPrimitive(message.content) else JsonArray(
            listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(message.content)))) +
                message.imageDataUrls.map { dataUrl ->
                    JsonObject(mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(mapOf("url" to JsonPrimitive(dataUrl)))
                    ))
                }
        )
        return ChatMessagePayload(message.role, content)
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
        private const val MAX_TOKENS = 4096
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
