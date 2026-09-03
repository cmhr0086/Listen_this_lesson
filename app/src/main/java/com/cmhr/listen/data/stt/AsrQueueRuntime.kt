package com.cmhr.listen.data.stt

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.audio.WavEncoder
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AsrHealthSnapshot(
    val model: String,
    val dtype: String?,
    val queuedJobs: Int,
    val processingJobs: Int,
    val maxQueueDepth: Int,
    val observedAt: Long
)

sealed interface AsrHealthRefreshResult {
    data class Success(val snapshot: AsrHealthSnapshot) : AsrHealthRefreshResult
    data class Failure(
        val safeMessage: String,
        val previousSnapshot: AsrHealthSnapshot?
    ) : AsrHealthRefreshResult
}

object AppVisibilityTracker : DefaultLifecycleObserver {
    @Volatile var isForeground: Boolean = true
        private set
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
    }

    override fun onStart(owner: LifecycleOwner) { isForeground = true }
    override fun onStop(owner: LifecycleOwner) { isForeground = false }
}

class AsrQueueRuntime private constructor(private val context: Context) {
    private val database = ListenDatabase.get(context)
    private val dao = database.asrDiagnosticsDao()
    private val settings = AppSettingsRepository(context)
    private val client = SttApiClient(credentialsProvider = {
        val server = settings.settings.first().server
        SttCredentials(server.baseUrl, settings.readApiKey().orEmpty())
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val drainMutex = Mutex()
    private val recovered = AtomicBoolean(false)
    private val _health = MutableStateFlow<AsrHealthSnapshot?>(null)
    val health: StateFlow<AsrHealthSnapshot?> = _health.asStateFlow()

    fun observeAll(): Flow<List<AsrSegmentDiagnosticEntity>> = dao.observeAll()
    fun observeForRecord(recordId: Long): Flow<List<AsrSegmentDiagnosticEntity>> = dao.observeForRecord(recordId)
    fun observeRecentForRecord(recordId: Long, limit: Int = 15): Flow<List<AsrSegmentDiagnosticEntity>> =
        dao.observeRecentForRecord(recordId, limit)
    fun observeCountForRecord(recordId: Long): Flow<Int> = dao.observeCountForRecord(recordId)
    fun observeActiveForRecord(recordId: Long): Flow<List<AsrSegmentDiagnosticEntity>> =
        dao.observeActiveForRecord(recordId)
    fun observeStateCountsForRecordSince(recordId: Long, since: Long): Flow<AsrDiagnosticStateCounts> =
        dao.observeStateCountsForRecordSince(recordId, since)
    fun observeEvents(segmentId: String): Flow<List<AsrNetworkEventEntity>> = dao.observeEvents(segmentId)
    fun observeQueueCount(): Flow<Int> = dao.observeQueueCount(ACTIVE_ASR_STATES)
    fun observeRuntimeSummary(): Flow<AsrRuntimeSummary> = dao.observeRuntimeSummary()

    fun newSegmentId(): String = UUID.randomUUID().toString()

    fun recordDroppedCapture(
        segmentId: String,
        recordId: Long,
        audioStartTime: Long,
        audioEndTime: Long,
        reason: String
    ) {
        persistenceScope.launch {
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            runCatching {
                dao.insertSegment(
                    AsrSegmentDiagnosticEntity(
                        segmentId = segmentId,
                        recordId = recordId,
                        state = AsrLifecycleState.DROPPED.name,
                        audioStartTime = audioStartTime,
                        audioEndTime = audioEndTime,
                        audioDurationMs = (audioEndTime - audioStartTime).coerceAtLeast(0),
                        captureStartedAt = audioStartTime,
                        captureFinishedAt = audioEndTime,
                        queuedLocalAt = now,
                        captureStartedElapsedMs = (elapsed - (audioEndTime - audioStartTime).coerceAtLeast(0)).coerceAtLeast(0),
                        captureFinishedElapsedMs = elapsed,
                        queuedLocalElapsedMs = elapsed,
                        finishedAt = now,
                        finishedElapsedMs = elapsed,
                        failureStage = AsrFailureStage.AUDIO_CAPTURE.name,
                        safeErrorMessage = reason
                    )
                )
            }
        }
    }

    fun recordCaptureFailure(
        segmentId: String,
        recordId: Long,
        audioStartTime: Long,
        exceptionClass: String,
        message: String
    ) {
        persistenceScope.launch {
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            runCatching {
                dao.insertSegment(
                    AsrSegmentDiagnosticEntity(
                        segmentId = segmentId,
                        recordId = recordId,
                        state = AsrLifecycleState.FAILED.name,
                        audioStartTime = audioStartTime,
                        audioEndTime = now,
                        audioDurationMs = (now - audioStartTime).coerceAtLeast(0),
                        captureStartedAt = audioStartTime,
                        captureFinishedAt = now,
                        queuedLocalAt = now,
                        captureStartedElapsedMs = (elapsed - (now - audioStartTime).coerceAtLeast(0)).coerceAtLeast(0),
                        captureFinishedElapsedMs = elapsed,
                        queuedLocalElapsedMs = elapsed,
                        finishedAt = now,
                        finishedElapsedMs = elapsed,
                        failureStage = AsrFailureStage.AUDIO_CAPTURE.name,
                        exceptionClass = exceptionClass,
                        safeErrorMessage = message
                    )
                )
            }
        }
    }

    fun persistAndEnqueue(
        segmentId: String,
        recordId: Long,
        audioStartTime: Long,
        audioEndTime: Long,
        captureStartedAt: Long,
        captureFinishedAt: Long,
        pcm: ByteArray,
        contextSnapshot: String?
    ) {
        persistenceScope.launch {
            val queuedAt = System.currentTimeMillis()
            val queuedElapsed = SystemClock.elapsedRealtime()
            val directory = File(context.noBackupFilesDir, QUEUE_DIRECTORY)
            val destination = File(directory, "$segmentId.wav")
            val temporary = File(directory, "$segmentId.tmp")
            val base = AsrSegmentDiagnosticEntity(
                segmentId = segmentId,
                recordId = recordId,
                audioStartTime = audioStartTime,
                audioEndTime = audioEndTime,
                audioDurationMs = (audioEndTime - audioStartTime).coerceAtLeast(0),
                captureStartedAt = captureStartedAt,
                captureFinishedAt = captureFinishedAt,
                queuedLocalAt = queuedAt,
                captureStartedElapsedMs = (queuedElapsed - (captureFinishedAt - captureStartedAt).coerceAtLeast(0)).coerceAtLeast(0),
                captureFinishedElapsedMs = queuedElapsed,
                queuedLocalElapsedMs = queuedElapsed,
                contextSnapshot = contextSnapshot?.takeIf { it.isNotBlank() }
            )
            try {
                val wav = WavEncoder.encodePcm16Mono(pcm)
                check(directory.exists() || directory.mkdirs()) { "无法创建 ASR 临时目录。" }
                FileOutputStream(temporary).use { output ->
                    output.write(wav)
                    output.fd.sync()
                }
                check(temporary.renameTo(destination)) { "无法提交 ASR 临时音频。" }
                dao.insertSegment(base.copy(wavRelativePath = destination.name))
                kick()
            } catch (error: Exception) {
                temporary.delete()
                destination.delete()
                runCatching {
                    dao.insertSegment(
                        base.copy(
                            state = AsrLifecycleState.FAILED.name,
                            finishedAt = System.currentTimeMillis(),
                            finishedElapsedMs = SystemClock.elapsedRealtime(),
                            failureStage = AsrFailureStage.LOCAL_PERSISTENCE.name,
                            exceptionClass = error::class.java.simpleName,
                            safeErrorMessage = "无法持久化待识别音频。"
                        )
                    )
                }
            }
        }
    }

    fun kick() {
        AppVisibilityTracker.initialize()
        scope.launch { drain() }
        AsrQueueWorker.enqueue(context)
    }

    suspend fun confirmRetryUnknown(segmentId: String) {
        if (dao.confirmRetryUnknown(segmentId) > 0) kick()
    }

    suspend fun refreshHealth(): AsrHealthRefreshResult {
        return when (val result = client.health()) {
            is SttCallOutcome.Success -> {
                val snapshot = result.value.toSnapshot()
                _health.value = snapshot
                AsrHealthRefreshResult.Success(snapshot)
            }
            is SttCallOutcome.HttpError -> AsrHealthRefreshResult.Failure(
                safeMessage = "无法获取服务状态（HTTP ${result.httpCode}）。",
                previousSnapshot = _health.value
            )
            is SttCallOutcome.TransportError -> AsrHealthRefreshResult.Failure(
                safeMessage = result.safeMessage,
                previousSnapshot = _health.value
            )
            is SttCallOutcome.ParseError -> AsrHealthRefreshResult.Failure(
                safeMessage = result.safeMessage,
                previousSnapshot = _health.value
            )
        }
    }

    suspend fun drain(maxRunMs: Long = MAX_DRAIN_RUN_MS) = drainMutex.withLock {
        AppVisibilityTracker.initialize()
        if (recovered.compareAndSet(false, true)) dao.recoverInterruptedSubmissions()
        val deadline = System.currentTimeMillis() + maxRunMs
        while (System.currentTimeMillis() < deadline) {
            val segment = dao.oldestRunnable(RUNNABLE_ASR_STATES) ?: break
            val waitMs = ((segment.nextAttemptAt ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0)
            if (waitMs > 0) delay(waitMs.coerceAtMost(MAX_RETRY_DELAY_MS))
            val fresh = dao.segment(segment.segmentId) ?: continue
            if (fresh.lifecycleState !in RUNNABLE_ASR_STATES.map(AsrLifecycleState::valueOf)) continue
            try {
                if (fresh.jobId == null) submit(fresh) else poll(fresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                dao.updateSegment(
                    fresh.copy(
                        state = AsrLifecycleState.RETRY_WAIT.name,
                        nextAttemptAt = System.currentTimeMillis() + retryDelay(fresh.submitAttempts + fresh.pollAttempts),
                        exceptionClass = error::class.java.simpleName,
                        safeErrorMessage = "ASR 后台处理发生异常，将自动重试。"
                    )
                )
            }
        }
    }

    private suspend fun submit(segment: AsrSegmentDiagnosticEntity) {
        val path = segment.wavRelativePath?.let { File(File(context.noBackupFilesDir, QUEUE_DIRECTORY), it) }
        if (path == null || !path.isFile) {
            fail(segment, AsrFailureStage.LOCAL_PERSISTENCE, "FileNotFoundException", "待识别音频文件不存在。")
            return
        }
        val attempt = segment.submitAttempts + 1
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val submitting = segment.copy(
            state = AsrLifecycleState.SUBMITTING.name,
            submitStartedAt = segment.submitStartedAt ?: now,
            submitStartedElapsedMs = segment.submitStartedElapsedMs ?: nowElapsed,
            submitAttempts = attempt,
            nextAttemptAt = null,
            serverModel = _health.value?.model ?: segment.serverModel,
            failureStage = null,
            exceptionClass = null,
            safeErrorMessage = null
        )
        dao.updateSegment(submitting)
        when (val outcome = client.submit(segment.segmentId, attempt, path.readBytes(), segment.language, segment.contextSnapshot)) {
            is SttCallOutcome.Success -> {
                persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
                val tracedSubmitting = submitting.withSubmitCallStart(outcome.trace)
                val completedAt = outcome.trace.lastOrNull { it.eventType == AsrNetworkEventType.RESPONSE_BODY_END }?.timestampMs
                    ?: System.currentTimeMillis()
                val completedElapsed = outcome.trace.lastOrNull { it.eventType == AsrNetworkEventType.RESPONSE_BODY_END }?.monotonicTimestampMs
                    ?: SystemClock.elapsedRealtime()
                val status = outcome.value.status.lowercase()
                dao.updateSegment(
                    tracedSubmitting.copy(
                        jobId = outcome.value.jobId,
                        state = if (status == "processing") AsrLifecycleState.PROCESSING.name else AsrLifecycleState.QUEUED_SERVER.name,
                        submitCompletedAt = completedAt,
                        submitCompletedElapsedMs = completedElapsed,
                        firstServerQueuedAt = if (status == "queued") completedAt else null,
                        firstServerProcessingAt = if (status == "processing") completedAt else null,
                        firstServerQueuedElapsedMs = if (status == "queued") completedElapsed else null,
                        firstServerProcessingElapsedMs = if (status == "processing") completedElapsed else null,
                        nextAttemptAt = completedAt + POLL_INTERVAL_MS,
                        lastHttpStatus = outcome.httpCode,
                        postDurationMs = outcome.trace.durationBetween(AsrNetworkEventType.CALL_START, AsrNetworkEventType.RESPONSE_BODY_END),
                        uploadDurationMs = outcome.trace.durationBetween(AsrNetworkEventType.REQUEST_BODY_START, AsrNetworkEventType.REQUEST_BODY_END),
                        submitResponseWaitDurationMs = outcome.trace.durationBetween(AsrNetworkEventType.REQUEST_BODY_END, AsrNetworkEventType.RESPONSE_HEADERS_START)
                    )
                )
            }
            is SttCallOutcome.HttpError -> {
                persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
                handleHttpFailure(submitting.withSubmitCallStart(outcome.trace), outcome, AsrFailureStage.SUBMIT_RESPONSE)
            }
            is SttCallOutcome.TransportError -> {
                persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
                dao.updateSegment(
                    submitting.withSubmitCallStart(outcome.trace).copy(
                        state = AsrLifecycleState.SUBMISSION_UNKNOWN.name,
                        failureStage = submitFailureStage(outcome.trace).name,
                        exceptionClass = outcome.exceptionClass,
                        safeErrorMessage = "提交连接中断，无法确认服务端是否已接收；请人工确认重试。"
                    )
                )
            }
            is SttCallOutcome.ParseError -> {
                persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
                dao.updateSegment(
                    submitting.withSubmitCallStart(outcome.trace).copy(
                        state = AsrLifecycleState.SUBMISSION_UNKNOWN.name,
                        failureStage = AsrFailureStage.RESULT_PARSE.name,
                        exceptionClass = outcome.exceptionClass,
                        safeErrorMessage = "提交成功响应无法解析，无法确认 job_id；请人工确认重试。"
                    )
                )
            }
        }
    }

    private suspend fun poll(segment: AsrSegmentDiagnosticEntity) {
        val jobId = segment.jobId ?: return
        val attempt = segment.pollAttempts + 1
        when (val outcome = client.poll(segment.segmentId, attempt, jobId)) {
            is SttCallOutcome.Success -> {
                persistEvents(segment.segmentId, AsrRequestKind.POLL, attempt, outcome.trace)
                val observedAt = outcome.trace.lastOrNull { it.eventType == AsrNetworkEventType.RESPONSE_BODY_END }?.timestampMs
                    ?: System.currentTimeMillis()
                val observedElapsed = outcome.trace.lastOrNull { it.eventType == AsrNetworkEventType.RESPONSE_BODY_END }?.monotonicTimestampMs
                    ?: SystemClock.elapsedRealtime()
                when (outcome.value.status.lowercase()) {
                    "queued" -> dao.updateSegment(
                        segment.copy(
                            state = AsrLifecycleState.QUEUED_SERVER.name,
                            firstServerQueuedAt = segment.firstServerQueuedAt ?: observedAt,
                            firstServerQueuedElapsedMs = segment.firstServerQueuedElapsedMs ?: observedElapsed,
                            pollAttempts = attempt,
                            nextAttemptAt = observedAt + POLL_INTERVAL_MS,
                            lastHttpStatus = outcome.httpCode
                        )
                    )
                    "processing" -> dao.updateSegment(
                        segment.copy(
                            state = AsrLifecycleState.PROCESSING.name,
                            firstServerProcessingAt = segment.firstServerProcessingAt ?: observedAt,
                            firstServerProcessingElapsedMs = segment.firstServerProcessingElapsedMs ?: observedElapsed,
                            pollAttempts = attempt,
                            nextAttemptAt = observedAt + POLL_INTERVAL_MS,
                            lastHttpStatus = outcome.httpCode
                        )
                    )
                    "completed" -> complete(segment, outcome.value, outcome.trace, attempt, observedAt, observedElapsed)
                    "failed" -> fail(
                        segment.copy(pollAttempts = attempt, lastHttpStatus = outcome.httpCode),
                        AsrFailureStage.MODEL_PROCESSING,
                        "ServerJobFailed",
                        "服务端模型处理失败。"
                    )
                    else -> fail(segment, AsrFailureStage.RESULT_PARSE, "UnknownJobState", "识别服务返回了未知任务状态。")
                }
            }
            is SttCallOutcome.HttpError -> {
                persistEvents(segment.segmentId, AsrRequestKind.POLL, attempt, outcome.trace)
                if (outcome.httpCode == 404) {
                    dao.updateSegment(
                        segment.copy(
                            jobId = null,
                            state = AsrLifecycleState.QUEUED_LOCAL.name,
                            pollAttempts = attempt,
                            nextAttemptAt = null,
                            safeErrorMessage = "服务端任务已过期或服务已重启，将重新提交保留的音频。"
                        )
                    )
                } else handleHttpFailure(segment.copy(pollAttempts = attempt), outcome, AsrFailureStage.JOB_POLLING)
            }
            is SttCallOutcome.TransportError -> {
                persistEvents(segment.segmentId, AsrRequestKind.POLL, attempt, outcome.trace)
                retry(segment.copy(pollAttempts = attempt), AsrFailureStage.JOB_POLLING, outcome.exceptionClass, outcome.safeMessage, null)
            }
            is SttCallOutcome.ParseError -> {
                persistEvents(segment.segmentId, AsrRequestKind.POLL, attempt, outcome.trace)
                fail(segment.copy(pollAttempts = attempt), AsrFailureStage.RESULT_PARSE, outcome.exceptionClass, outcome.safeMessage)
            }
        }
    }

    private suspend fun complete(
        segment: AsrSegmentDiagnosticEntity,
        response: SttJobResponse,
        trace: List<SttNetworkEvent>,
        pollAttempt: Int,
        observedAt: Long,
        observedElapsed: Long
    ) {
        val text = response.result?.text?.trim().orEmpty()
        if (text.isEmpty()) {
            fail(segment, AsrFailureStage.RESULT_PARSE, "EmptyTranscription", "识别服务没有返回文本。")
            return
        }
        val terminalState = if (TranscriptTextFilter.isFillerOnly(text)) AsrLifecycleState.DROPPED else AsrLifecycleState.COMPLETED
        val completed = segment.copy(
            state = terminalState.name,
            firstServerCompletedAt = segment.firstServerCompletedAt ?: observedAt,
            firstServerCompletedElapsedMs = segment.firstServerCompletedElapsedMs ?: observedElapsed,
            finishedAt = observedAt,
            finishedElapsedMs = observedElapsed,
            pollAttempts = pollAttempt,
            nextAttemptAt = null,
            lastHttpStatus = 200,
            resultResponseDurationMs = trace.durationBetween(AsrNetworkEventType.RESPONSE_HEADERS_START, AsrNetworkEventType.RESPONSE_BODY_END),
            wavRelativePath = null,
            failureStage = if (terminalState == AsrLifecycleState.DROPPED) AsrFailureStage.RESULT_PARSE.name else null,
            safeErrorMessage = if (terminalState == AsrLifecycleState.DROPPED) "识别结果仅包含语气词。" else null
        )
        database.withTransaction {
            dao.updateSegment(completed)
            if (terminalState == AsrLifecycleState.COMPLETED) {
                val queueDuration = completed.clientQueueDurationMs
                val total = completed.totalEndToEndDurationMs
                database.transcriptDao().insert(
                    TranscriptEntity(
                        recordId = segment.recordId,
                        startTime = segment.audioStartTime,
                        endTime = segment.audioEndTime,
                        audioDurationMs = segment.audioDurationMs,
                        recognitionDurationMs = total,
                        text = text,
                        sourceSegmentId = segment.segmentId,
                        asrJobId = segment.jobId,
                        queueDurationMs = queueDuration,
                        uploadDurationMs = completed.uploadDurationMs,
                        responseWaitDurationMs = completed.submitResponseWaitDurationMs,
                        totalAsrDurationMs = total,
                        serverModel = segment.serverModel
                    )
                )
            }
        }
        deleteAudio(segment.wavRelativePath)
    }

    private suspend fun handleHttpFailure(
        segment: AsrSegmentDiagnosticEntity,
        outcome: SttCallOutcome.HttpError,
        stage: AsrFailureStage
    ) {
        val code = outcome.httpCode
        when (code) {
            408, 429, 500, 502, 503, 504 -> retry(segment, stage, "HttpException", "识别服务暂时不可用（HTTP $code），将自动重试。", outcome.retryAfterMs)
            401, 403 -> retry(segment, stage, "HttpException", "识别服务鉴权失败，请检查 API Key。", 30_000)
            else -> fail(segment, stage, "HttpException", "识别服务请求失败（HTTP $code）。")
        }
    }

    private suspend fun retry(segment: AsrSegmentDiagnosticEntity, stage: AsrFailureStage, exception: String, message: String, requestedDelay: Long?) {
        val attempts = (segment.submitAttempts + segment.pollAttempts).coerceAtLeast(1)
        dao.updateSegment(
            segment.copy(
                state = AsrLifecycleState.RETRY_WAIT.name,
                nextAttemptAt = System.currentTimeMillis() + (requestedDelay ?: retryDelay(attempts)).coerceIn(0, MAX_RETRY_DELAY_MS),
                failureStage = stage.name,
                exceptionClass = exception,
                safeErrorMessage = message
            )
        )
    }

    private suspend fun fail(segment: AsrSegmentDiagnosticEntity, stage: AsrFailureStage, exception: String, message: String) {
        dao.updateSegment(
            segment.copy(
                state = AsrLifecycleState.FAILED.name,
                finishedAt = System.currentTimeMillis(),
                finishedElapsedMs = SystemClock.elapsedRealtime(),
                nextAttemptAt = null,
                failureStage = stage.name,
                exceptionClass = exception,
                safeErrorMessage = message
            )
        )
    }

    private suspend fun persistEvents(segmentId: String, kind: AsrRequestKind, attempt: Int, trace: List<SttNetworkEvent>) {
        if (trace.isEmpty()) return
        dao.insertEvents(trace.map { event ->
            AsrNetworkEventEntity(
                segmentId = segmentId,
                requestKind = kind.name,
                attempt = attempt,
                eventType = event.eventType.name,
                timestampMs = event.timestampMs,
                elapsedSinceCallStartMs = event.elapsedSinceCallStartMs,
                exceptionClass = event.exceptionClass,
                appInForeground = event.appInForeground
            )
        })
    }

    private fun submitFailureStage(trace: List<SttNetworkEvent>): AsrFailureStage = when {
        trace.none { it.eventType == AsrNetworkEventType.REQUEST_BODY_START } -> AsrFailureStage.CONNECT
        trace.any { it.eventType == AsrNetworkEventType.REQUEST_BODY_START } && trace.none { it.eventType == AsrNetworkEventType.REQUEST_BODY_END } -> AsrFailureStage.AUDIO_UPLOAD
        else -> AsrFailureStage.SUBMIT_RESPONSE
    }

    private fun AsrSegmentDiagnosticEntity.withSubmitCallStart(trace: List<SttNetworkEvent>): AsrSegmentDiagnosticEntity {
        val callStart = trace.firstOrNull { it.eventType == AsrNetworkEventType.CALL_START }?.timestampMs
        val callStartElapsed = trace.firstOrNull { it.eventType == AsrNetworkEventType.CALL_START }?.monotonicTimestampMs
        return copy(
            submitStartedAt = if (submitAttempts == 1) callStart ?: submitStartedAt else submitStartedAt,
            submitStartedElapsedMs = if (submitAttempts == 1) callStartElapsed ?: submitStartedElapsedMs else submitStartedElapsedMs
        )
    }

    private fun deleteAudio(relativePath: String?) {
        relativePath ?: return
        runCatching { File(File(context.noBackupFilesDir, QUEUE_DIRECTORY), relativePath).delete() }
    }

    private fun retryDelay(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_RETRY_DELAY_MS)

    companion object {
        private const val QUEUE_DIRECTORY = "asr_queue"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val MAX_DRAIN_RUN_MS = 8 * 60_000L
        @Volatile private var instance: AsrQueueRuntime? = null

        fun get(context: Context): AsrQueueRuntime = instance ?: synchronized(this) {
            instance ?: AsrQueueRuntime(context.applicationContext).also { instance = it }
        }
    }
}

private fun SttHealthResponse.toSnapshot() = AsrHealthSnapshot(
    model = model,
    dtype = dtype,
    queuedJobs = queuedJobs,
    processingJobs = processingJobs,
    maxQueueDepth = maxQueueDepth,
    observedAt = System.currentTimeMillis()
)

class AsrQueueWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AsrQueueRuntime.get(applicationContext).drain()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "asr-persistent-queue"
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AsrQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
