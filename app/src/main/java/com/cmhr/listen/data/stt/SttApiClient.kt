package com.cmhr.listen.data.stt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SttCredentials(val baseUrl: String, val apiKey: String)

class SttApiClient(
    private val credentialsProvider: suspend () -> SttCredentials,
    private val httpClient: OkHttpClient = defaultHttpClient
) {
    /** Kept for local parser/request tests; production uses the secure settings provider. */
    constructor(apiKey: String, httpClient: OkHttpClient = defaultHttpClient) : this(
        credentialsProvider = { SttCredentials(DEFAULT_BASE_URL, apiKey) }, httpClient = httpClient
    )

    suspend fun transcribe(wavAudio: ByteArray, prompt: String? = null): String {
        val credentials = credentialsProvider()
        check(credentials.baseUrl.isNotBlank()) { "请先在设置中填写服务器地址。" }
        check(credentials.apiKey.isNotBlank()) { "请先在设置中填写 API Key。" }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "recording.wav",
                wavAudio.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", LANGUAGE)
        prompt?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            multipart.addFormDataPart("prompt", value)
        }
        val requestBody = multipart.build()

        val request = Request.Builder()
            .url("${credentials.baseUrl.trimEnd('/')}/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("识别服务请求失败（HTTP ${response.code}）。")
            }
            val text = parseResponse(body).text.trim()
            check(text.isNotEmpty()) { "识别服务没有返回文本。" }
            return text
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "http://10.0.0.195:8765"
        private const val MODEL = "Qwen/Qwen3-ASR-0.6B"
        private const val LANGUAGE = "Chinese"

        private val json = Json { ignoreUnknownKeys = true }
        private val defaultHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun parseResponse(responseBody: String): TranscriptionResponse =
            json.decodeFromString(TranscriptionResponse.serializer(), responseBody)
    }
}

@Serializable
data class TranscriptionResponse(val text: String)
