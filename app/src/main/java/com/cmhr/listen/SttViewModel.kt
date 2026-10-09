package com.cmhr.listen

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.audio.CapturedPcmSegment
import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.VadConfig
import com.cmhr.listen.audio.VadSegmenter
import com.cmhr.listen.audio.VadPreset
import com.cmhr.listen.data.settings.VadConfigRepository
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.ServerSettings
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.stt.AsrHealthSnapshot
import com.cmhr.listen.data.stt.AsrHealthRefreshResult
import com.cmhr.listen.data.stt.AsrDiagnosticStateCounts
import com.cmhr.listen.data.stt.AsrNetworkEventEntity
import com.cmhr.listen.data.stt.AsrPromptAutoConfig
import com.cmhr.listen.data.stt.AsrPromptMode
import com.cmhr.listen.data.stt.AsrPromptPolicy
import com.cmhr.listen.data.stt.AsrQueueRuntime
import com.cmhr.listen.data.stt.AsrRuntimeSummary
import com.cmhr.listen.data.stt.AsrSegmentDiagnosticEntity
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.recording.ClassroomCaptureRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ListeningUiState(
    val isListening: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val isRecognizing: Boolean = false,
    val configuredVadConfig: VadConfig = VadConfig.Default,
    val selectedVadPreset: VadPreset? = VadPreset.DEFAULT,
    val pendingQueueCount: Int = 0,
    val activeRecordId: Long? = null,
    val listeningStartedAtElapsedRealtimeMs: Long? = null,
    val currentCourseName: String? = null,
    val currentRecordName: String? = null,
    val asrHealth: AsrHealthSnapshot? = null,
    val error: String? = null,
    val captureMode: CaptureMode? = null,
    /** Record-only: audio already fsynced to disk, i.e. what survives a process kill. */
    val recordOnlySavedMs: Long = 0,
    /** A class the user paused: not capturing, but not ended either (no filing, resumes in place). */
    val pausedClass: PausedClass? = null,
    /** Set between 继续 and the microphone actually running, so the class never looks "ended". */
    val resumingRecordId: Long? = null
)

data class PausedClass(
    val recordId: Long,
    val mode: CaptureMode,
    val courseName: String?,
    val recordName: String?,
    /** Capture time accumulated before the pause; the clock continues from here on resume. */
    val elapsedMs: Long
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SttViewModel(application: Application) : AndroidViewModel(application) {
    private val recorder = PcmRecorder()
    private val vadConfigRepository = VadConfigRepository(application)
    private val appSettingsRepository = AppSettingsRepository(application)
    private val courseRepository = CourseRepository(ListenDatabase.get(application))
    private val asrRuntime = AsrQueueRuntime.get(application)
    private val captureRuntime = ClassroomCaptureRuntime.get(application)
    private val _uiState = MutableStateFlow(ListeningUiState())
    val uiState: StateFlow<ListeningUiState> = _uiState.asStateFlow()
    private val _vadDiagnosticsState = MutableStateFlow(VadDiagnosticsUiState())
    val vadDiagnosticsState: StateFlow<VadDiagnosticsUiState> = _vadDiagnosticsState.asStateFlow()
    private val _isAsrHealthRefreshing = MutableStateFlow(false)
    val isAsrHealthRefreshing: StateFlow<Boolean> = _isAsrHealthRefreshing.asStateFlow()
    private val _asrMessages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val asrMessages: SharedFlow<String> = _asrMessages.asSharedFlow()

    private var listeningJob: Job? = null
    private var resumeOffsetMs = 0L
    /** Set by [pause] before stopping; turned into [ListeningUiState.pausedClass] once capture has stopped. */
    private var pendingPause: PausedClass? = null
    /** The paused class being resumed; restored if capture fails to start. */
    private var resumeFrom: PausedClass? = null
    private var sessionStartedAtMs = 0L
    private var sessionRecordId: Long? = null
    private var currentCapturingSegmentId: String? = null
    private var currentCaptureStartedAt: Long? = null
    private var currentCaptureStartedAtElapsedMs: Long? = null
    private var lastVadUiPublishElapsedMs = 0L
    @Volatile private var currentVadConfig = VadConfig.Default
    @Volatile private var currentServer = ServerSettings()
    @Volatile private var currentCourseAsrPrompt = ""
    @Volatile private var currentCoursePromptModeOverride: String? = null
    @Volatile private var currentGlobalPromptMode = AsrPromptMode.AUTO
    @Volatile private var currentPromptAutoConfig = AsrPromptAutoConfig()

    init {
        asrRuntime.kick()
        viewModelScope.launch {
            captureRuntime.state.collect { capture ->
                if (capture.mode == CaptureMode.RECORD_ONLY || capture.error != null || (_uiState.value.captureMode == CaptureMode.RECORD_ONLY && !capture.active)) {
                    val paused = if (!capture.active) takePendingPause() else null
                    if (capture.active) resumeFrom = null
                    else if (capture.error != null && resumeFrom != null) {
                        resumeFailed()
                        return@collect
                    }
                    _uiState.update { current ->
                        current.copy(
                            pausedClass = if (capture.active) null else paused ?: current.pausedClass,
                            resumingRecordId = if (capture.active) null else current.resumingRecordId,
                            isListening = capture.active,
                            activeRecordId = capture.recordId,
                            listeningStartedAtElapsedRealtimeMs = capture.startedElapsedMs,
                            currentCourseName = capture.courseName ?: current.currentCourseName,
                            currentRecordName = capture.recordName ?: current.currentRecordName,
                            isSpeechDetected = false,
                            captureMode = capture.mode,
                            recordOnlySavedMs = capture.durationMs,
                            error = capture.error ?: current.error
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            // Count only the class being captured, and only work that is really in progress:
            // segments waiting for manual confirmation (SUBMISSION_UNKNOWN) are not "queued".
            _uiState.map { it.activeRecordId }.distinctUntilChanged()
                .flatMapLatest { recordId -> recordId?.let(asrRuntime::observeRuntimeSummary) ?: flowOf(null) }
                .collect { summary ->
                    _uiState.update {
                        it.copy(
                            pendingQueueCount = summary?.inProgressCount ?: 0,
                            isRecognizing = (summary?.recognizingCount ?: 0) > 0
                        )
                    }
                    ListeningForegroundService.update(getApplication(), _uiState.value)
                }
        }
        viewModelScope.launch {
            asrRuntime.health.collect { health -> _uiState.update { it.copy(asrHealth = health) } }
        }
        viewModelScope.launch {
            vadConfigRepository.config.collect { config ->
                currentVadConfig = config
                _uiState.update { it.copy(configuredVadConfig = config) }
                if (!_uiState.value.isListening) {
                    _vadDiagnosticsState.update { it.copy(effectiveVadConfig = config) }
                }
            }
        }
        viewModelScope.launch {
            vadConfigRepository.presetId.collect { presetId ->
                _uiState.update { it.copy(selectedVadPreset = VadPreset.fromId(presetId)) }
            }
        }
        viewModelScope.launch {
            appSettingsRepository.settings.collect { settings ->
                currentServer = settings.server
                currentGlobalPromptMode = settings.globalAsrPromptMode
                currentPromptAutoConfig = settings.asrPromptAutoConfig
                val course = settings.selectedCourseId?.let { courseRepository.course(it).first() }
                val record = settings.selectedRecordId?.let { courseRepository.record(it).first() }
                _uiState.update { state ->
                    if (state.activeRecordId == null) state.copy(currentCourseName = course?.name, currentRecordName = record?.name) else state
                }
            }
        }
        viewModelScope.launch {
            ListeningControlBus.stopRequests.collect { stopListening() }
        }
    }

    fun startListening(recordId: Long) = startListening(recordId, elapsedOffsetMs = 0)

    private fun startListening(recordId: Long, elapsedOffsetMs: Long) {
        resumeOffsetMs = elapsedOffsetMs.coerceAtLeast(0)
        if (listeningJob?.isActive == true) {
            if (sessionRecordId != recordId) {
                _uiState.update { it.copy(error = "当前正在记录其他课堂，请先结束。") }
                resumeFailed()
            }
            return
        }
        when {
            !currentServer.hasApiKey -> {
                _uiState.update { it.copy(error = "请先在设置中填写 API Key。") }
                resumeFailed()
                return
            }
            currentServer.baseUrl.isBlank() -> {
                _uiState.update { it.copy(error = "请先在设置中填写服务器地址。") }
                resumeFailed()
                return
            }
        }
        if (!captureRuntime.tryClaimRealtime(recordId)) {
            _uiState.update { it.copy(error = "当前已有录音或识别任务正在运行。") }
            resumeFailed()
            return
        }
        _uiState.update { it.copy(pausedClass = null) }

        listeningJob = viewModelScope.launch {
            val record = courseRepository.record(recordId).first()
            if (record == null) {
                _uiState.update { it.copy(error = "课堂记录不存在或已被删除。") }
                captureRuntime.releaseRealtime()
                return@launch
            }
            val course = courseRepository.course(record.courseId).first()
            currentCourseAsrPrompt = course?.asrPrompt.orEmpty()
            currentCoursePromptModeOverride = course?.asrPromptModeOverride
            val promptObserver = launch {
                courseRepository.course(record.courseId).collect { updatedCourse ->
                    currentCourseAsrPrompt = updatedCourse?.asrPrompt.orEmpty()
                    currentCoursePromptModeOverride = updatedCourse?.asrPromptModeOverride
                }
            }
            sessionStartedAtMs = System.currentTimeMillis()
            sessionRecordId = recordId
            currentCapturingSegmentId = null
            currentCaptureStartedAt = null
            currentCaptureStartedAtElapsedMs = null
            lastVadUiPublishElapsedMs = 0L
            courseRepository.reopenRecord(recordId)
            _uiState.update {
                it.copy(
                    isListening = true,
                    resumingRecordId = null,
                    isSpeechDetected = false,
                    activeRecordId = recordId,
                    listeningStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime() - resumeOffsetMs,
                    configuredVadConfig = currentVadConfig,
                    currentCourseName = course?.name,
                    currentRecordName = record.name,
                    error = null,
                    captureMode = CaptureMode.REALTIME_ASR
                )
            }
            captureRuntime.updateRealtime(course?.name, record.name, _uiState.value.listeningStartedAtElapsedRealtimeMs ?: SystemClock.elapsedRealtime())
            _vadDiagnosticsState.update {
                it.copy(
                    vadProbability = 0f,
                    effectiveVadConfig = currentVadConfig,
                    isSpeechDetected = false,
                    silenceDurationMs = 0,
                    segmentStartReason = null,
                    segmentEndReason = null,
                    capturingSegmentId = null,
                    capturingStartedAt = null,
                    capturingStartedAtElapsedRealtimeMs = null
                )
            }

            try {
                ListeningForegroundService.start(getApplication(), _uiState.value)
                withContext(Dispatchers.Default) {
                    VadSegmenter(getApplication<Application>().assets) { currentVadConfig }.use { segmenter ->
                        recorder.listen(
                            onPcmChunk = { pcmChunk ->
                                val vadResult = segmenter.acceptPcm(pcmChunk)
                                val uiElapsedNow = SystemClock.elapsedRealtime()
                                if (vadResult.isSpeechDetected && currentCapturingSegmentId == null) {
                                    currentCapturingSegmentId = asrRuntime.newSegmentId()
                                    currentCaptureStartedAt = System.currentTimeMillis()
                                    currentCaptureStartedAtElapsedMs = uiElapsedNow
                                }
                                vadResult.completedSegments.forEach { captured ->
                                    val segmentId = currentCapturingSegmentId ?: asrRuntime.newSegmentId()
                                    enqueue(segmentId, captured)
                                    if (vadResult.isSpeechDetected) {
                                        currentCapturingSegmentId = asrRuntime.newSegmentId()
                                        currentCaptureStartedAt = timestampForSample(captured.pcmSlice.endSample)
                                        currentCaptureStartedAtElapsedMs = uiElapsedNow
                                    } else {
                                        currentCapturingSegmentId = null
                                        currentCaptureStartedAt = null
                                        currentCaptureStartedAtElapsedMs = null
                                    }
                                }
                                if (vadResult.discardedShortDurationsMs.isNotEmpty()) {
                                    val end = System.currentTimeMillis()
                                    vadResult.discardedShortDurationsMs.forEachIndexed { index, duration ->
                                        val droppedId = if (index == 0) currentCapturingSegmentId ?: asrRuntime.newSegmentId()
                                            else asrRuntime.newSegmentId()
                                        asrRuntime.recordDroppedCapture(
                                            segmentId = droppedId,
                                            recordId = recordId,
                                            audioStartTime = end - duration,
                                            audioEndTime = end,
                                            reason = "低于最短片段时长，未提交识别。"
                                        )
                                    }
                                    currentCapturingSegmentId = null
                                    currentCaptureStartedAt = null
                                    currentCaptureStartedAtElapsedMs = null
                                } else if (!vadResult.isSpeechDetected && vadResult.completedSegments.isEmpty()) {
                                    currentCapturingSegmentId = null
                                    currentCaptureStartedAt = null
                                    currentCaptureStartedAtElapsedMs = null
                                }

                                val speechChanged = _uiState.value.isSpeechDetected != vadResult.isSpeechDetected
                                if (speechChanged) {
                                    _uiState.update { it.copy(isSpeechDetected = vadResult.isSpeechDetected) }
                                    ListeningForegroundService.update(getApplication(), _uiState.value)
                                }
                                val hasBoundaryEvent = vadResult.completedSegments.isNotEmpty() ||
                                    vadResult.discardedShortDurationsMs.isNotEmpty()
                                if (speechChanged || hasBoundaryEvent ||
                                    uiElapsedNow - lastVadUiPublishElapsedMs >= VAD_UI_INTERVAL_MS
                                ) {
                                    lastVadUiPublishElapsedMs = uiElapsedNow
                                    _vadDiagnosticsState.update { state ->
                                        state.copy(
                                            isSpeechDetected = vadResult.isSpeechDetected,
                                            vadProbability = vadResult.probability,
                                            effectiveVadConfig = vadResult.effectiveConfig,
                                            silenceDurationMs = vadResult.silenceDurationMs,
                                            segmentStartReason = vadResult.segmentStartReason,
                                            segmentEndReason = vadResult.segmentEndReason,
                                            discardedShortSegments = state.discardedShortSegments +
                                                vadResult.discardedShortDurationsMs.size,
                                            capturingSegmentId = currentCapturingSegmentId,
                                            capturingStartedAt = currentCaptureStartedAt,
                                            capturingStartedAtElapsedRealtimeMs = currentCaptureStartedAtElapsedMs
                                        )
                                    }
                                }
                            },
                            onReadError = ::recordAudioReadError
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val failedSegmentId = currentCapturingSegmentId
                val captureStart = currentCaptureStartedAt
                if (failedSegmentId != null && captureStart != null) {
                    asrRuntime.recordCaptureFailure(
                        segmentId = failedSegmentId,
                        recordId = recordId,
                        audioStartTime = captureStart,
                        exceptionClass = exception::class.java.simpleName,
                        message = "音频采集异常终止。"
                    )
                }
                _uiState.update {
                    it.copy(
                        isListening = false,
                        isSpeechDetected = false,
                        error = exception.message ?: "监听过程中发生未知错误。"
                    )
                }
            } finally {
                promptObserver.cancel()
                currentCourseAsrPrompt = ""
                currentCoursePromptModeOverride = null
                withContext(NonCancellable) { courseRepository.finishRecord(recordId) }
                ListeningForegroundService.stop(getApplication())
                captureRuntime.releaseRealtime()
                sessionRecordId = null
                currentCapturingSegmentId = null
                currentCaptureStartedAt = null
                currentCaptureStartedAtElapsedMs = null
                val paused = takePendingPause()
                _uiState.update {
                    it.copy(
                        isListening = false,
                        activeRecordId = null,
                        listeningStartedAtElapsedRealtimeMs = null,
                        isSpeechDetected = false,
                        captureMode = null,
                        pausedClass = paused ?: it.pausedClass
                    )
                }
                _vadDiagnosticsState.update {
                    it.copy(
                        isSpeechDetected = false,
                        vadProbability = 0f,
                        silenceDurationMs = 0,
                        capturingSegmentId = null,
                        capturingStartedAt = null,
                        capturingStartedAtElapsedRealtimeMs = null
                    )
                }
            }
        }
    }

    /** Stops capturing but keeps the class open, so [resume] continues in the same record. */
    fun pause() {
        val state = _uiState.value
        val recordId = state.activeRecordId ?: return
        if (!state.isListening) return
        val mode = state.captureMode ?: return
        pendingPause = PausedClass(
            recordId = recordId,
            mode = mode,
            courseName = state.currentCourseName,
            recordName = state.currentRecordName,
            elapsedMs = state.listeningStartedAtElapsedRealtimeMs?.let { SystemClock.elapsedRealtime() - it } ?: 0
        )
        stopListening()
    }

    /** Continues a paused class in the same record, with the same mode and a continuous clock. */
    fun resume() {
        val paused = _uiState.value.pausedClass ?: return
        resumeFrom = paused
        _uiState.update { it.copy(pausedClass = null, resumingRecordId = paused.recordId, error = null) }
        if (paused.mode == CaptureMode.RECORD_ONLY) startRecordOnly(paused.recordId, paused.elapsedMs)
        else startListening(paused.recordId, paused.elapsedMs)
    }

    /** Ends the class whether it is capturing or paused. */
    fun endClass() {
        pendingPause = null
        resumeFrom = null
        _uiState.update { it.copy(pausedClass = null, resumingRecordId = null) }
        if (_uiState.value.isListening) stopListening()
    }

    private fun takePendingPause(): PausedClass? = pendingPause.also { pendingPause = null }

    /** Capture could not start: go back to the paused state so the user can retry or end. */
    private fun resumeFailed() {
        val paused = resumeFrom ?: return
        resumeFrom = null
        _uiState.update { it.copy(pausedClass = paused, resumingRecordId = null) }
    }

    fun stopListening() {
        _vadDiagnosticsState.update { it.copy(segmentEndReason = "用户停止录制") }
        if (captureRuntime.state.value.mode == CaptureMode.RECORD_ONLY) captureRuntime.stop()
        else {
            listeningJob?.cancel()
            listeningJob = null
        }
    }

    fun startRecordOnly(recordId: Long) = startRecordOnly(recordId, elapsedOffsetMs = 0)

    private fun startRecordOnly(recordId: Long, elapsedOffsetMs: Long) {
        if (captureRuntime.isBusy()) {
            _uiState.update { it.copy(error = "当前已有录音或识别任务正在运行。") }
            resumeFailed()
            return
        }
        // Starting any capture supersedes a paused class (a paused class elsewhere is simply ended).
        _uiState.update { it.copy(error = null, pausedClass = null) }
        captureRuntime.startRecordOnly(recordId, elapsedOffsetMs)
    }

    fun reportPermissionDenied() {
        _uiState.update { it.copy(error = "需要麦克风权限才能开始监听。") }
    }

    fun reportNotificationPermissionDenied() {
        _uiState.update { it.copy(error = "通知权限未授予；监听仍会继续，但系统可能不显示课堂监听通知。") }
    }

    fun updateVadConfig(config: VadConfig) {
        val validatedConfig = config.validated()
        // Apply locally first so a newly-idle segment uses the changed values immediately;
        // DataStore remains the durable source for subsequent launches.
        currentVadConfig = validatedConfig
        _uiState.update { it.copy(configuredVadConfig = validatedConfig) }
        _uiState.update { it.copy(selectedVadPreset = null) }
        viewModelScope.launch { vadConfigRepository.save(validatedConfig, "custom") }
    }

    fun applyVadPreset(preset: VadPreset) {
        val config = preset.config.validated()
        currentVadConfig = config
        _uiState.update { it.copy(configuredVadConfig = config, selectedVadPreset = preset) }
        viewModelScope.launch { vadConfigRepository.save(config, preset.id) }
    }

    fun restoreDefaultVadConfig() = applyVadPreset(VadPreset.DEFAULT)

    fun refreshAsrHealth() {
        if (_isAsrHealthRefreshing.value) return
        _isAsrHealthRefreshing.value = true
        viewModelScope.launch {
            try {
                when (val result = asrRuntime.refreshHealth()) {
                    is AsrHealthRefreshResult.Success -> _asrMessages.emit("服务状态已刷新。")
                    is AsrHealthRefreshResult.Failure -> _asrMessages.emit(result.safeMessage)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _asrMessages.emit("刷新服务状态失败。")
            } finally {
                _isAsrHealthRefreshing.value = false
            }
        }
    }

    fun confirmRetryUnknown(segmentId: String) {
        viewModelScope.launch { asrRuntime.confirmRetryUnknown(segmentId) }
    }

    fun observeAsrEvents(segmentId: String): Flow<List<AsrNetworkEventEntity>> =
        asrRuntime.observeEvents(segmentId)

    fun observeRecentAsrDiagnostics(recordId: Long, limit: Int = RECENT_DIAGNOSTIC_LIMIT): Flow<List<AsrSegmentDiagnosticEntity>> =
        asrRuntime.observeRecentForRecord(recordId, limit)

    fun observeAsrDiagnostics(recordId: Long): Flow<List<AsrSegmentDiagnosticEntity>> =
        asrRuntime.observeForRecord(recordId)

    fun observeAsrDiagnosticCount(recordId: Long): Flow<Int> =
        asrRuntime.observeCountForRecord(recordId)

    fun observeActiveAsrDiagnostics(recordId: Long): Flow<List<AsrSegmentDiagnosticEntity>> =
        asrRuntime.observeActiveForRecord(recordId)

    fun observeAsrStateCounts(recordId: Long, since: Long): Flow<AsrDiagnosticStateCounts> =
        asrRuntime.observeStateCountsForRecordSince(recordId, since)

    fun observeAsrRuntimeSummary(recordId: Long): Flow<AsrRuntimeSummary> =
        asrRuntime.observeRuntimeSummary(recordId)

    private fun enqueue(segmentId: String, captured: CapturedPcmSegment) {
        val audioStart = timestampForSample(captured.pcmSlice.startSample)
        val audioEnd = timestampForSample(captured.pcmSlice.endSample)
        val recordId = sessionRecordId ?: return
        val audioDurationMs = (audioEnd - audioStart).takeIf { it >= 0L } ?: return

        val promptDecision = AsrPromptPolicy.decide(
            globalMode = currentGlobalPromptMode,
            courseOverride = currentCoursePromptModeOverride,
            coursePrompt = currentCourseAsrPrompt,
            quality = captured.quality.copy(audioDurationMs = audioDurationMs),
            config = currentPromptAutoConfig
        )
        _vadDiagnosticsState.update {
            it.copy(
                lastSegmentQuality = captured.quality.copy(audioDurationMs = audioDurationMs),
                lastPromptDecision = promptDecision
            )
        }
        asrRuntime.persistAndEnqueue(
            segmentId = segmentId,
            recordId = recordId,
            audioStartTime = audioStart,
            audioEndTime = audioEnd,
            captureStartedAt = audioStart,
            captureFinishedAt = audioEnd,
            pcm = captured.pcmSlice.pcm,
            contextSnapshot = promptDecision.prompt
        )
        Log.d(TAG, "segment $segmentId persisted duration=${audioDurationMs}ms")
    }

    private fun recordAudioReadError(code: Int) {
        _vadDiagnosticsState.update { it.copy(audioReadErrors = it.audioReadErrors + 1) }
        Log.e(TAG, "AudioRecord.read returned $code")
    }

    private fun timestampForSample(sample: Long): Long =
        sessionStartedAtMs + sample * 1_000 / PcmRecorder.SAMPLE_RATE_HZ

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }

    private companion object {
        const val RECENT_DIAGNOSTIC_LIMIT = 15
        const val VAD_UI_INTERVAL_MS = 100L
        const val TAG = "ListenDebug"
    }
}
