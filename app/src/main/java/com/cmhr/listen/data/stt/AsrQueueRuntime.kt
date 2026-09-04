package com.cmhr.listen.data.stt

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

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
    private val schedulerMutex = Mutex()
    private val schedulerWakeups = Channel<Unit>(Channel.CONFLATED)
    private val taskCompletions = Channel<Unit>(Channel.CONFLATED)
    private val httpPermits = Semaphore(MAX_CONCURRENT_HTTP_CALLS)
    private val activePollingRecords = mutableMapOf<String, Long>()
    private val activeSubmittingIds = mutableSetOf<String>()
    private val activeTaskLock = Any()
    private val _activePollingByRecord = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private val recovered = AtomicBoolean(false)
    private val _health = MutableStateFlow<AsrHealthSnapshot?>(null)
    private val currentBootCount: Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()
    val health: StateFlow<AsrHealthSnapshot?> = _health.asStateFlow()

    init {
        scope.launch {
            for (ignored in schedulerWakeups) {
                val remaining = try {
                    drain()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "ASR scheduler pass failed: ${error::class.java.simpleName}")
                    true
                }
                if (remaining) {
                    delay(POLL_INTERVAL_MS)
                    schedulerWakeups.trySend(Unit)
                }
            }
        }
        // Also recover durable work when a new app process is created, even if
        // WorkManager's previous KEEP request finished during an enqueue race.
        schedulerWakeups.trySend(Unit)
    }

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
    fun observeRuntimeSummary(): Flow<AsrRuntimeSummary> =
        combine(dao.observeRuntimeSummary(), _activePollingByRecord) { summary, pollingByRecord ->
            summary.copy(
                pollingCount = pollingByRecord.values.sum(),
                globalInFlightCount = summary.inFlightCount
            )
        }

    fun observeRuntimeSummary(recordId: Long): Flow<AsrRuntimeSummary> =
        combine(
            dao.observeRuntimeSummaryForRecord(recordId),
            dao.observeRuntimeSummary(),
            _activePollingByRecord
        ) { summary, globalSummary, pollingByRecord ->
            summary.copy(
                pollingCount = pollingByRecord[recordId] ?: 0,
                globalInFlightCount = globalSummary.inFlightCount
            )
        }

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
            val audioDuration = safeDifference(audioStartTime, audioEndTime) ?: return@launch
            runCatching {
                insertNewSegment(
                    AsrSegmentDiagnosticEntity(
                        segmentId = segmentId,
                        recordId = recordId,
                        state = AsrLifecycleState.DROPPED.name,
                        audioStartTime = audioStartTime,
                        audioEndTime = audioEndTime,
                        audioDurationMs = audioDuration,
                        captureStartedAt = audioStartTime,
                        captureFinishedAt = audioEndTime,
                        queuedLocalAt = now,
                        captureStartedElapsedMs = elapsedStart(elapsed, audioDuration),
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
            val audioDuration = safeDifference(audioStartTime, now) ?: return@launch
            runCatching {
                insertNewSegment(
                    AsrSegmentDiagnosticEntity(
                        segmentId = segmentId,
                        recordId = recordId,
                        state = AsrLifecycleState.FAILED.name,
                        audioStartTime = audioStartTime,
                        audioEndTime = now,
                        audioDurationMs = audioDuration,
                        captureStartedAt = audioStartTime,
                        captureFinishedAt = now,
                        queuedLocalAt = now,
                        captureStartedElapsedMs = elapsedStart(elapsed, audioDuration),
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
            val encodedAudioDuration = pcm.size.toLong() * 1_000L / (16_000L * 2L)
            val audioDuration = safeDifference(audioStartTime, audioEndTime) ?: encodedAudioDuration
            val captureDuration = safeDifference(captureStartedAt, captureFinishedAt) ?: encodedAudioDuration
            val base = AsrSegmentDiagnosticEntity(
                segmentId = segmentId,
                recordId = recordId,
                audioStartTime = audioStartTime,
                audioEndTime = audioEndTime,
                audioDurationMs = audioDuration,
                captureStartedAt = captureStartedAt,
                captureFinishedAt = captureFinishedAt,
                queuedLocalAt = queuedAt,
                captureStartedElapsedMs = elapsedStart(queuedElapsed, captureDuration),
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
                insertNewSegment(base.copy(wavRelativePath = destination.name))
                kick()
            } catch (error: Exception) {
                temporary.delete()
                destination.delete()
                runCatching {
                    insertNewSegment(
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

    private suspend fun insertNewSegment(segment: AsrSegmentDiagnosticEntity) {
        database.withTransaction {
            val sequenceNumber = dao.nextSequenceNumber(segment.recordId)
            dao.insertSegment(
                segment.copy(
                    sequenceNumber = sequenceNumber,
                    clockBasis = AsrClockBasis.ELAPSED_REALTIME.name,
                    bootCount = currentBootCount
                )
            )
        }
    }

    fun kick() {
        AppVisibilityTracker.initialize()
        schedulerWakeups.trySend(Unit)
        AsrQueueWorker.enqueue(context)
    }

    suspend fun confirmRetryUnknown(segmentId: String) {
        if (dao.confirmRetryUnknown(segmentId) > 0) kick()
    }

    suspend fun refreshHealth(): AsrHealthRefreshResult {
        return when (val result = httpPermits.withPermit { client.health() }) {
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

    /**
     * Runs the persistent queue until it becomes idle or [maxRunMs] is exhausted.
     * Returns true when automatic work remains and WorkManager should retry later.
     */
    suspend fun drain(maxRunMs: Long = MAX_DRAIN_RUN_MS): Boolean {
        if (!schedulerMutex.tryLock()) return dao.automaticWorkCount() > 0
        return try {
            drainLocked(maxRunMs)
        } finally {
            schedulerMutex.unlock()
        }
    }

    private suspend fun drainLocked(maxRunMs: Long): Boolean {
        AppVisibilityTracker.initialize()
        if (!recovered.get()) {
            // elapsedRealtime resets on a device reboot. Any task inherited
            // from a previous process therefore uses its paired wall-clock
            // fields, while tasks created in this process remain monotonic.
            recoverJobSidecars()
            currentBootCount?.let { dao.markTasksFromOtherBootAsLegacyClock(it) }
                ?: dao.markAllRecoveredActiveTasksAsLegacyClock()
            dao.recoverInterruptedSubmissions()
            recovered.set(true)
        }
        val deadlineElapsed = SystemClock.elapsedRealtime() + maxRunMs

        return supervisorScope {
            val activeTasks = mutableMapOf<String, Job>()

            fun launchTask(key: String, segment: AsrSegmentDiagnosticEntity, polling: Boolean) {
                if (activeTasks[key]?.isActive == true) return
                if (!polling) setSubmissionActive(segment.segmentId, true)
                activeTasks[key] = launch {
                    if (polling) setPollingActive(segment.segmentId, segment.recordId, true)
                    try {
                        if (polling) poll(segment) else submit(segment)
                    } catch (cancelled: CancellationException) {
                        if (!polling) markCancelledSubmissionUnknown(segment.segmentId)
                        throw cancelled
                    } catch (error: Exception) {
                        val fresh = dao.segment(segment.segmentId) ?: return@launch
                        if (!polling && fresh.lifecycleState == AsrLifecycleState.SUBMITTING) {
                            // A local failure after POST may happen after the
                            // server accepted the audio. Never automatically
                            // turn that ambiguous state into a second POST.
                            markSubmissionUnknown(
                                fresh,
                                error::class.java.simpleName,
                                "提交过程发生异常，无法确认服务端是否已接收；请人工确认重试。"
                            )
                        } else {
                            retry(
                                fresh,
                                if (polling) AsrFailureStage.JOB_POLLING else AsrFailureStage.SUBMIT_RESPONSE,
                                error::class.java.simpleName,
                                "ASR 后台处理发生异常，将自动重试。",
                                null
                            )
                        }
                    } finally {
                        if (polling) setPollingActive(segment.segmentId, segment.recordId, false)
                        else setSubmissionActive(segment.segmentId, false)
                        taskCompletions.trySend(Unit)
                    }
                }
            }

            while (SystemClock.elapsedRealtime() < deadlineElapsed) {
                activeTasks.entries.removeAll { !it.value.isActive }
                recoverJobSidecars()
                recoverOrphanedSubmissions()
                val now = System.currentTimeMillis()
                var launchedTask = false

                var availableSlots = (MAX_IN_FLIGHT_JOBS - dao.inFlightCount()).coerceAtLeast(0)
                while (availableSlots > 0) {
                    val candidate = dao.nextDueSubmission(now) ?: break
                    val claimWall = System.currentTimeMillis()
                    val claimElapsed = SystemClock.elapsedRealtime()
                    if (dao.claimSubmission(candidate.segmentId, claimWall, claimElapsed) == 0) continue
                    val claimed = dao.segment(candidate.segmentId) ?: continue
                    launchTask("submit:${claimed.segmentId}", claimed, polling = false)
                    launchedTask = true
                    availableSlots--
                }

                dao.duePolls(now, MAX_IN_FLIGHT_JOBS).forEach { candidate ->
                    val key = "poll:${candidate.segmentId}"
                    if (activeTasks[key]?.isActive != true && !isPollingActive(candidate.segmentId)) {
                        launchTask(key, candidate, polling = true)
                        launchedTask = true
                    }
                }

                if (launchedTask) continue

                val remaining = dao.automaticWorkCount()
                if (remaining == 0 && activeTasks.isEmpty()) return@supervisorScope false

                val nextAttemptAt = dao.nextAutomaticAttemptAt()
                val untilNextAttempt = nextAttemptAt?.let { (it - System.currentTimeMillis()).coerceAtLeast(1L) }
                val untilDeadline = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                val waitMs = minOf(untilNextAttempt ?: POLL_INTERVAL_MS, untilDeadline, POLL_INTERVAL_MS)
                withTimeoutOrNull(waitMs) { taskCompletions.receive() }
            }

            activeTasks.values.forEach { it.join() }
            dao.automaticWorkCount() > 0
        }
    }

    private suspend fun submit(segment: AsrSegmentDiagnosticEntity) {
        val path = segment.wavRelativePath?.let { File(File(context.noBackupFilesDir, QUEUE_DIRECTORY), it) }
        if (path == null || !path.isFile) {
            fail(segment, AsrFailureStage.LOCAL_PERSISTENCE, "FileNotFoundException", "待识别音频文件不存在。")
            return
        }
        val audioBytes = try {
            path.readBytes()
        } catch (error: Exception) {
            fail(
                segment,
                AsrFailureStage.LOCAL_PERSISTENCE,
                error::class.java.simpleName,
                "无法读取已持久化的待识别音频。"
            )
            return
        }
        val attempt = segment.submitAttempts
        val submitting = segment.copy(
            serverModel = _health.value?.model ?: segment.serverModel,
        )
        val uploadStarted = AtomicBoolean(false)
        val outcome = try {
            httpPermits.withPermit {
                client.submit(
                    segment.segmentId,
                    attempt,
                    audioBytes,
                    segment.language,
                    segment.contextSnapshot
                ) { event ->
                    if (event == AsrNetworkEventType.REQUEST_BODY_START) uploadStarted.set(true)
                }
            }
        } catch (cancelled: CancellationException) {
            if (!uploadStarted.get()) {
                withContext(NonCancellable) {
                    retry(
                        segment,
                        AsrFailureStage.CLIENT_QUEUE,
                        cancelled::class.java.simpleName,
                        "提交尚未开始，将自动重试。",
                        null
                    )
                }
            } else {
                markCancelledSubmissionUnknown(segment.segmentId)
            }
            throw cancelled
        }
        when (outcome) {
            is SttCallOutcome.Success -> {
                val tracedSubmitting = submitting.withSubmitCallStart(outcome.trace)
                val acceptedJobId = outcome.value.jobId.trim()
                if (acceptedJobId.isEmpty()) {
                    markSubmissionUnknown(
                        tracedSubmitting,
                        "EmptyJobId",
                        "提交响应未包含有效 job_id；为避免重复提交，请人工确认。",
                        AsrFailureStage.RESULT_PARSE
                    )
                    persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
                    return
                }
                val completedAt = outcome.trace.lastOrNull { it.eventType == AsrNetworkEventType.RESPONSE_BODY_END }?.timestampMs
                    ?: System.currentTimeMillis()
                val completedElapsed = outcome.trace.lastOrNull { it.eventType == AsrNetworkEventType.RESPONSE_BODY_END }?.monotonicTimestampMs
                    ?: SystemClock.elapsedRealtime()
                val status = outcome.value.status.lowercase()
                val accepted = tracedSubmitting.copy(
                    jobId = acceptedJobId,
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
                persistJobSidecar(segment.segmentId, acceptedJobId)
                // The job id is the idempotency boundary. Persist it before
                // optional diagnostics; after a successful ACK this code path
                // must never fall back to another automatic POST.
                if (!updateSegmentWithRetry(accepted)) {
                    // Keep the sidecar and the DB row in SUBMITTING. The next
                    // scheduler pass restores the known job id from disk and
                    // resumes polling; it must never offer a second POST.
                    Log.w(TAG, "ASR accepted job is awaiting sidecar recovery")
                    return
                }
                deleteJobSidecar(segment.segmentId)
                persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
            }
            is SttCallOutcome.HttpError -> {
                handleHttpFailure(submitting.withSubmitCallStart(outcome.trace), outcome, AsrFailureStage.SUBMIT_RESPONSE)
                persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
            }
            is SttCallOutcome.TransportError -> {
                val traced = submitting.withSubmitCallStart(outcome.trace)
                if (outcome.trace.none { it.eventType == AsrNetworkEventType.REQUEST_BODY_START }) {
                    retry(traced, submitFailureStage(outcome.trace), outcome.exceptionClass, outcome.safeMessage, null)
                } else {
                    markSubmissionUnknown(
                        traced,
                        outcome.exceptionClass,
                        "提交连接中断，无法确认服务端是否已接收；请人工确认重试。",
                        submitFailureStage(outcome.trace)
                    )
                }
                persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
            }
            is SttCallOutcome.ParseError -> {
                val traced = submitting.withSubmitCallStart(outcome.trace)
                if (outcome.trace.none { it.eventType == AsrNetworkEventType.REQUEST_BODY_START }) {
                    retry(traced, AsrFailureStage.CONNECT, outcome.exceptionClass, outcome.safeMessage, 30_000L)
                } else {
                    markSubmissionUnknown(
                        traced,
                        outcome.exceptionClass,
                        "提交成功响应无法解析，无法确认 job_id；请人工确认重试。",
                        AsrFailureStage.RESULT_PARSE
                    )
                }
                persistEvents(segment.segmentId, AsrRequestKind.SUBMIT, attempt, outcome.trace)
            }
        }
    }

    private suspend fun poll(segment: AsrSegmentDiagnosticEntity) {
        val jobId = segment.jobId ?: return
        val attempt = segment.pollAttempts + 1
        when (val outcome = httpPermits.withPermit { client.poll(segment.segmentId, attempt, jobId) }) {
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
            if (
                terminalState == AsrLifecycleState.COMPLETED &&
                database.transcriptDao().idForSourceSegment(segment.segmentId) == null
            ) {
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
                        sequenceNumber = segment.sequenceNumber,
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
        // The 30-second ceiling applies only to our local exponential
        // fallback. An explicit Retry-After is the server's contract and must
        // not be shortened by the client.
        val delayMs = requestedDelay?.takeIf { it >= 0L } ?: retryDelay(attempts)
        val now = System.currentTimeMillis()
        dao.updateSegment(
            segment.copy(
                state = AsrLifecycleState.RETRY_WAIT.name,
                nextAttemptAt = now + delayMs.coerceAtMost(Long.MAX_VALUE - now),
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

    private suspend fun updateSegmentWithRetry(
        segment: AsrSegmentDiagnosticEntity,
        attempts: Int = 3
    ): Boolean = withContext(NonCancellable) {
        repeat(attempts) { index ->
            try {
                return@withContext dao.updateSegment(segment) == 1
            } catch (error: Exception) {
                Log.w(TAG, "ASR diagnostic state persistence failed: ${error::class.java.simpleName}")
                if (index + 1 < attempts) delay(50L * (index + 1))
            }
        }
        false
    }

    private suspend fun persistEvents(segmentId: String, kind: AsrRequestKind, attempt: Int, trace: List<SttNetworkEvent>) {
        if (trace.isEmpty()) return
        withContext(NonCancellable) {
            try {
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
            } catch (error: Exception) {
                // Network events are diagnostic-only and must never change the
                // idempotency decision of an already acknowledged submission.
                Log.w(TAG, "ASR network event persistence failed: ${error::class.java.simpleName}")
            }
        }
    }

    private fun setPollingActive(segmentId: String, recordId: Long, active: Boolean) {
        synchronized(activeTaskLock) {
            if (active) activePollingRecords[segmentId] = recordId else activePollingRecords.remove(segmentId)
            _activePollingByRecord.value = activePollingRecords.values.groupingBy { it }.eachCount()
        }
    }

    private fun isPollingActive(segmentId: String): Boolean =
        synchronized(activeTaskLock) { segmentId in activePollingRecords }

    private fun setSubmissionActive(segmentId: String, active: Boolean) {
        synchronized(activeTaskLock) {
            if (active) activeSubmittingIds += segmentId else activeSubmittingIds -= segmentId
        }
    }

    private suspend fun recoverOrphanedSubmissions() {
        val active = synchronized(activeTaskLock) { activeSubmittingIds.toSet() }
        dao.submittingSegments()
            .filterNot { it.segmentId in active }
            .filterNot { jobSidecar(it.segmentId).isFile }
            .forEach { orphan ->
                markSubmissionUnknown(
                    orphan,
                    "InterruptedSubmission",
                    "提交过程已中断，无法确认服务端是否已接收；请人工确认重试。"
                )
            }
    }

    private suspend fun markCancelledSubmissionUnknown(segmentId: String) {
        withContext(NonCancellable) {
            val fresh = runCatching { dao.segment(segmentId) }.getOrNull() ?: return@withContext
            if (fresh.lifecycleState != AsrLifecycleState.SUBMITTING || fresh.jobId != null) return@withContext
            markSubmissionUnknown(
                fresh,
                CancellationException::class.java.simpleName,
                "提交过程被中断，无法确认服务端是否已接收；请人工确认重试。"
            )
        }
    }

    private suspend fun markSubmissionUnknown(
        segment: AsrSegmentDiagnosticEntity,
        exceptionClass: String,
        message: String,
        failureStage: AsrFailureStage = AsrFailureStage.SUBMIT_RESPONSE
    ): Boolean = withContext(NonCancellable) {
        updateSegmentWithRetry(
            segment.copy(
                state = AsrLifecycleState.SUBMISSION_UNKNOWN.name,
                nextAttemptAt = null,
                failureStage = failureStage.name,
                exceptionClass = exceptionClass,
                safeErrorMessage = message
            )
        )
    }

    private fun persistJobSidecar(segmentId: String, jobId: String) {
        val directory = File(context.noBackupFilesDir, QUEUE_DIRECTORY).apply { mkdirs() }
        val target = File(directory, "$segmentId$JOB_SIDECAR_SUFFIX")
        val temporary = File(directory, "$segmentId$JOB_SIDECAR_SUFFIX.tmp")
        runCatching {
            FileOutputStream(temporary).use { output ->
                output.write(jobId.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target))
            }
        }.onFailure { error ->
            temporary.delete()
            Log.w(TAG, "ASR job sidecar persistence failed: ${error::class.java.simpleName}")
        }
    }

    private suspend fun recoverJobSidecars() {
        val directory = File(context.noBackupFilesDir, QUEUE_DIRECTORY)
        directory.listFiles { file -> file.isFile && file.name.endsWith(JOB_SIDECAR_SUFFIX) }
            .orEmpty()
            .forEach { sidecar ->
                val segmentId = sidecar.name.removeSuffix(JOB_SIDECAR_SUFFIX)
                val jobId = runCatching { sidecar.readText(Charsets.UTF_8).trim() }.getOrNull()
                    ?.takeIf(String::isNotEmpty)
                    ?: return@forEach
                val segment = dao.segment(segmentId) ?: run {
                    sidecar.delete()
                    return@forEach
                }
                if (segment.jobId != null) {
                    sidecar.delete()
                    return@forEach
                }
                if (segment.lifecycleState in TERMINAL_ASR_STATES) {
                    sidecar.delete()
                    return@forEach
                }
                val recoveredSegment = segment.copy(
                    jobId = jobId,
                    state = AsrLifecycleState.QUEUED_SERVER.name,
                    nextAttemptAt = null,
                    failureStage = null,
                    exceptionClass = null,
                    safeErrorMessage = "已从本地恢复服务端 job_id，将继续轮询。"
                )
                if (updateSegmentWithRetry(recoveredSegment)) sidecar.delete()
            }
    }

    private fun deleteJobSidecar(segmentId: String) {
        jobSidecar(segmentId).delete()
    }

    private fun jobSidecar(segmentId: String): File =
        File(File(context.noBackupFilesDir, QUEUE_DIRECTORY), "$segmentId$JOB_SIDECAR_SUFFIX")

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
        private const val TAG = "AsrQueueRuntime"
        private const val QUEUE_DIRECTORY = "asr_queue"
        private const val JOB_SIDECAR_SUFFIX = ".job_id"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val MAX_DRAIN_RUN_MS = 8 * 60_000L
        private val TERMINAL_ASR_STATES = setOf(
            AsrLifecycleState.COMPLETED,
            AsrLifecycleState.FAILED,
            AsrLifecycleState.DROPPED
        )
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

private fun safeDifference(start: Long, end: Long): Long? =
    if (start > 0L && end >= start) end - start else null

private fun elapsedStart(endElapsed: Long, duration: Long?): Long? =
    duration?.takeIf { it >= 0L && endElapsed >= it }?.let { endElapsed - it }

class AsrQueueWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val remaining = AsrQueueRuntime.get(applicationContext).drain()
            if (remaining) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "asr-persistent-queue"
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AsrQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
