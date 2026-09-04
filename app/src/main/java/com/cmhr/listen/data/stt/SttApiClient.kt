package com.cmhr.listen.data.stt

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class SttCredentials(val baseUrl: String, val apiKey: String)

data class SttNetworkEvent(
    val eventType: AsrNetworkEventType,
    val timestampMs: Long,
    val monotonicTimestampMs: Long,
    val elapsedSinceCallStartMs: Long?,
    val exceptionClass: String? = null,
    val appInForeground: Boolean = AppVisibilityTracker.isForeground
)

data class SttCallTraceTag(
    val segmentId: String?,
    val requestKind: AsrRequestKind,
    val attempt: Int,
    val trace: SttCallTrace
)

class SttCallTrace(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val onEvent: (AsrNetworkEventType) -> Unit = {}
) {
    private val values = mutableListOf<SttNetworkEvent>()
    private var callStartElapsedMs: Long? = null

    @Synchronized
    fun add(type: AsrNetworkEventType, exceptionClass: String? = null) {
        // Every persisted monotonic timestamp must use the same Android clock as
        // capture/queue timestamps. System.nanoTime() excludes deep sleep on
        // Android and cannot safely be subtracted from elapsedRealtime().
        val elapsed = elapsedRealtime()
        if (type == AsrNetworkEventType.CALL_START) callStartElapsedMs = elapsed
        val sinceCallStart = callStartElapsedMs?.let { start ->
            if (start > 0L && elapsed >= start) elapsed - start else null
        }
        values += SttNetworkEvent(
            eventType = type,
            timestampMs = wallTime(),
            monotonicTimestampMs = elapsed,
            elapsedSinceCallStartMs = sinceCallStart,
            exceptionClass = exceptionClass,
            appInForeground = AppVisibilityTracker.isForeground
        )
        onEvent(type)
    }

    @Synchronized fun snapshot(): List<SttNetworkEvent> = values.toList()
}

sealed interface SttCallOutcome<out T> {
    val trace: List<SttNetworkEvent>

    data class Success<T>(val value: T, val httpCode: Int, val retryAfterMs: Long?, override val trace: List<SttNetworkEvent>) : SttCallOutcome<T>
    data class HttpError(val httpCode: Int, val retryAfterMs: Long?, override val trace: List<SttNetworkEvent>) : SttCallOutcome<Nothing>
    data class TransportError(val exceptionClass: String, val safeMessage: String, override val trace: List<SttNetworkEvent>) : SttCallOutcome<Nothing>
    data class ParseError(val exceptionClass: String, val safeMessage: String, override val trace: List<SttNetworkEvent>) : SttCallOutcome<Nothing>
}

class SttApiClient(
    private val credentialsProvider: suspend () -> SttCredentials,
    httpClient: OkHttpClient = defaultHttpClient,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallTime: () -> Long = System::currentTimeMillis
) {
    private val httpClient = httpClient.newBuilder().eventListenerFactory(SttEventListenerFactory).build()

    suspend fun health(segmentId: String? = null): SttCallOutcome<SttHealthResponse> {
        val credentials = credentialsProvider()
        if (credentials.baseUrl.isBlank()) return configurationError("请先在设置中填写服务器地址。")
        val tag = traceTag(segmentId, AsrRequestKind.HEALTH, 1)
        val request = runCatching {
            Request.Builder()
                .url("${credentials.baseUrl.trimEnd('/')}/health")
                .tag(SttCallTraceTag::class.java, tag)
                .get()
                .build()
        }.getOrElse { return configurationError("识别服务器地址无效。") }
        return executeJson(request, tag, SttHealthResponse.serializer())
    }

    suspend fun submit(
        segmentId: String,
        attempt: Int,
        wavAudio: ByteArray,
        language: String = LANGUAGE,
        context: String? = null,
        onNetworkEvent: (AsrNetworkEventType) -> Unit = {}
    ): SttCallOutcome<SttSubmitResponse> {
        val credentials = credentialsProvider()
        validateCredentials(credentials)?.let { return it }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", "recording.wav", wavAudio.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("language", language)
        context?.trim()?.takeIf(String::isNotEmpty)?.let { multipart.addFormDataPart("context", it) }
        val tag = traceTag(segmentId, AsrRequestKind.SUBMIT, attempt, onNetworkEvent)
        val request = runCatching {
            Request.Builder()
                .url("${credentials.baseUrl.trimEnd('/')}/transcribe")
                .header("Authorization", "Bearer ${credentials.apiKey}")
                .tag(SttCallTraceTag::class.java, tag)
                .post(multipart.build())
                .build()
        }.getOrElse { return configurationError("识别服务器地址无效。") }
        return executeJson(request, tag, SttSubmitResponse.serializer())
    }

    suspend fun poll(segmentId: String, attempt: Int, jobId: String): SttCallOutcome<SttJobResponse> {
        val credentials = credentialsProvider()
        validateCredentials(credentials)?.let { return it }
        val tag = traceTag(segmentId, AsrRequestKind.POLL, attempt)
        val request = runCatching {
            Request.Builder()
                .url("${credentials.baseUrl.trimEnd('/')}/jobs/$jobId")
                .header("Authorization", "Bearer ${credentials.apiKey}")
                .tag(SttCallTraceTag::class.java, tag)
                .get()
                .build()
        }.getOrElse { return configurationError("识别服务器地址无效。") }
        return executeJson(request, tag, SttJobResponse.serializer())
    }

    private fun validateCredentials(value: SttCredentials): SttCallOutcome.ParseError? = when {
        value.baseUrl.isBlank() -> configurationError("请先在设置中填写服务器地址。")
        value.apiKey.isBlank() -> configurationError("请先在设置中填写 API Key。")
        else -> null
    }

    private fun configurationError(message: String) = SttCallOutcome.ParseError("IllegalStateException", message, emptyList())

    private fun traceTag(
        segmentId: String?,
        kind: AsrRequestKind,
        attempt: Int,
        onNetworkEvent: (AsrNetworkEventType) -> Unit = {}
    ) =
        SttCallTraceTag(
            segmentId = segmentId,
            requestKind = kind,
            attempt = attempt,
            trace = SttCallTrace(elapsedRealtime, wallTime, onNetworkEvent)
        )

    private suspend fun <T> executeJson(request: Request, tag: SttCallTraceTag, serializer: KSerializer<T>): SttCallOutcome<T> =
        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val retryAfter = response.retryAfterMs()
                    val body = response.body?.string().orEmpty()
                    val trace = tag.trace.snapshot()
                    if (!response.isSuccessful) return@withContext SttCallOutcome.HttpError(response.code, retryAfter, trace)
                    try {
                        SttCallOutcome.Success(json.decodeFromString(serializer, body), response.code, retryAfter, trace)
                    } catch (error: SerializationException) {
                        SttCallOutcome.ParseError(error::class.java.simpleName, "识别服务返回了无法解析的数据。", trace)
                    }
                }
            } catch (error: IOException) {
                SttCallOutcome.TransportError(error::class.java.simpleName, safeNetworkMessage(error), tag.trace.snapshot())
            }
        }

    companion object {
        private const val LANGUAGE = "Chinese"
        private const val TAG = "AsrNetwork"
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        fun parseResponse(responseBody: String): TranscriptionResponse = json.decodeFromString(TranscriptionResponse.serializer(), responseBody)

        internal fun safeNetworkMessage(error: IOException): String = when (error) {
            is UnknownHostException -> "无法解析识别服务器地址。"
            is ConnectException -> "无法连接识别服务器。"
            is SocketTimeoutException -> "识别服务网络请求超时。"
            is InterruptedIOException -> "识别服务网络请求超时或被中断。"
            else -> "识别服务网络连接失败。"
        }

        private object SttEventListenerFactory : EventListener.Factory {
            override fun create(call: Call): EventListener = object : EventListener() {
                private fun record(call: Call, type: AsrNetworkEventType, exceptionClass: String? = null) {
                    val tag = call.request().tag(SttCallTraceTag::class.java) ?: return
                    tag.trace.add(type, exceptionClass)
                    runCatching {
                        Log.d(TAG, "segment=${tag.segmentId ?: "health"} kind=${tag.requestKind.name} attempt=${tag.attempt} event=${type.name}" + (exceptionClass?.let { " exception=$it" } ?: ""))
                    }
                }

                override fun callStart(call: Call) = record(call, AsrNetworkEventType.CALL_START)
                override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = record(call, AsrNetworkEventType.CONNECT_END)
                override fun requestBodyStart(call: Call) = record(call, AsrNetworkEventType.REQUEST_BODY_START)
                override fun requestBodyEnd(call: Call, byteCount: Long) = record(call, AsrNetworkEventType.REQUEST_BODY_END)
                override fun responseHeadersStart(call: Call) = record(call, AsrNetworkEventType.RESPONSE_HEADERS_START)
                override fun responseBodyEnd(call: Call, byteCount: Long) = record(call, AsrNetworkEventType.RESPONSE_BODY_END)
                override fun callFailed(call: Call, ioe: IOException) = record(call, AsrNetworkEventType.CALL_FAILED, ioe::class.java.simpleName)
            }
        }
    }
}

private fun Response.retryAfterMs(nowMs: Long = System.currentTimeMillis()): Long? {
    val raw = header("Retry-After")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    raw.toLongOrNull()?.let { return it.coerceAtLeast(0) * 1_000 }
    return runCatching {
        val epochMs = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (epochMs - nowMs).coerceAtLeast(0)
    }.getOrNull()
}

fun List<SttNetworkEvent>.durationBetween(start: AsrNetworkEventType, end: AsrNetworkEventType): Long? {
    val startMs = firstOrNull { it.eventType == start }?.elapsedSinceCallStartMs ?: return null
    val endMs = lastOrNull { it.eventType == end }?.elapsedSinceCallStartMs ?: return null
    return if (startMs >= 0L && endMs >= startMs) endMs - startMs else null
}

@Serializable
data class SttHealthResponse(
    val status: String,
    val model: String,
    val dtype: String? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Long? = null,
    @SerialName("queued_jobs") val queuedJobs: Int = 0,
    @SerialName("processing_jobs") val processingJobs: Int = 0,
    @SerialName("max_queue_depth") val maxQueueDepth: Int = 0
)

@Serializable
data class SttSubmitResponse(
    @SerialName("job_id") val jobId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class SttJobResponse(
    @SerialName("job_id") val jobId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val result: SttJobResult? = null,
    val error: String? = null
)

@Serializable
data class SttJobResult(
    val text: String,
    val language: String? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    val truncated: Boolean? = null
)

@Serializable
data class TranscriptionResponse(val text: String)
