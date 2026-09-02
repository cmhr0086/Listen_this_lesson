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
import com.cmhr.listen.audio.WavEncoder
import com.cmhr.listen.data.settings.VadConfigRepository
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.ServerSettings
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.stt.SttApiClient
import com.cmhr.listen.data.stt.SttCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TranscriptStatus { QUEUED, RECOGNIZING, SUCCESS, ERROR, DROPPED }

data class TranscriptSegment(
    val id: Long,
    val recordId: Long,
    val audioStartTime: Long,
    val audioEndTime: Long,
    val audioDurationMs: Long,
    val text: String? = null,
    val queuedAt: Long,
    val recognitionStartedAt: Long? = null,
    val recognitionFinishedAt: Long? = null,
    val recognitionDurationMs: Long? = null,
    val status: TranscriptStatus,
    val gapFromPreviousMs: Long? = null,
    val hitMaxDuration: Boolean = false,
    val startReason: String? = null,
    val endReason: String? = null,
    val error: String? = null
)

data class ListeningUiState(
    val isListening: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val isRecognizing: Boolean = false,
    val vadProbability: Float = 0f,
    val configuredVadConfig: VadConfig = VadConfig.Default,
    val effectiveVadConfig: VadConfig = VadConfig.Default,
    val selectedVadPreset: VadPreset? = VadPreset.DEFAULT,
    val silenceDurationMs: Long = 0,
    val segmentStartReason: String? = null,
    val segmentEndReason: String? = null,
    val pendingQueueCount: Int = 0,
    val transcriptSegments: List<TranscriptSegment> = emptyList(),
    val droppedSegments: Int = 0,
    val discardedShortSegments: Int = 0,
    val audioReadErrors: Int = 0,
    val activeRecordId: Long? = null,
    val listeningStartedAtElapsedRealtimeMs: Long? = null,
    val currentCourseName: String? = null,
    val currentRecordName: String? = null,
    val error: String? = null
)

private data class PendingSegment(
    val transcript: TranscriptSegment,
    val pcm: ByteArray,
    val asrPrompt: String?
)

class SttViewModel(application: Application) : AndroidViewModel(application) {
    private val recorder = PcmRecorder()
    private val vadConfigRepository = VadConfigRepository(application)
    private val appSettingsRepository = AppSettingsRepository(application)
    private val courseRepository = CourseRepository(ListenDatabase.get(application))
    private val sttApiClient = SttApiClient(credentialsProvider = {
        SttCredentials(currentServer.baseUrl, appSettingsRepository.readApiKey().orEmpty())
    })
    private val _uiState = MutableStateFlow(ListeningUiState())
    val uiState: StateFlow<ListeningUiState> = _uiState.asStateFlow()

    private var listeningJob: Job? = null
    private var nextSegmentId = 1L
    private var sessionStartedAtMs = 0L
    private var previousAudioEndTimeMs: Long? = null
    private var sessionRecordId: Long? = null
    @Volatile private var currentVadConfig = VadConfig.Default
    @Volatile private var currentServer = ServerSettings()
    @Volatile private var currentCourseAsrPrompt = ""

    init {
        viewModelScope.launch {
            vadConfigRepository.config.collect { config ->
                currentVadConfig = config
                _uiState.update { it.copy(configuredVadConfig = config) }
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
                val course = settings.selectedCourseId?.let { courseRepository.course(it).first() }
                val record = settings.selectedRecordId?.let { courseRepository.record(it).first() }
                _uiState.update { state ->
                    if (state.activeRecordId == null) state.copy(currentCourseName = course?.name, currentRecordName = record?.name) else state
                }
            }
        }
    }

    fun startListening(recordId: Long) {
        if (listeningJob?.isActive == true) {
            if (sessionRecordId != recordId) {
                _uiState.update { it.copy(error = "当前正在记录其他课堂，请先停止监听。") }
            }
            return
        }
        when {
            !currentServer.hasApiKey -> {
                _uiState.update { it.copy(error = "请先在设置中填写 API Key。") }
                return
            }
            currentServer.baseUrl.isBlank() -> {
                _uiState.update { it.copy(error = "请先在设置中填写服务器地址。") }
                return
            }
        }

        listeningJob = viewModelScope.launch {
            val record = courseRepository.record(recordId).first()
            if (record == null) {
                _uiState.update { it.copy(error = "课堂记录不存在或已被删除。") }
                return@launch
            }
            val course = courseRepository.course(record.courseId).first()
            currentCourseAsrPrompt = course?.asrPrompt.orEmpty()
            val queue = Channel<PendingSegment>(capacity = QUEUE_CAPACITY)
            val worker = launch { consumeQueue(queue) }
            val promptObserver = launch {
                courseRepository.course(record.courseId).collect { updatedCourse ->
                    currentCourseAsrPrompt = updatedCourse?.asrPrompt.orEmpty()
                }
            }
            sessionStartedAtMs = System.currentTimeMillis()
            previousAudioEndTimeMs = null
            sessionRecordId = recordId
            courseRepository.reopenRecord(recordId)
            _uiState.value = ListeningUiState(
                isListening = true,
                activeRecordId = recordId,
                listeningStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                configuredVadConfig = currentVadConfig,
                effectiveVadConfig = currentVadConfig,
                selectedVadPreset = _uiState.value.selectedVadPreset,
                currentCourseName = course?.name,
                currentRecordName = record.name
            )

            try {
                withContext(Dispatchers.Default) {
                    VadSegmenter(getApplication<Application>().assets) { currentVadConfig }.use { segmenter ->
                        recorder.listen(
                            onPcmChunk = { pcmChunk ->
                                val vadResult = segmenter.acceptPcm(pcmChunk)
                                _uiState.update { state ->
                                    state.copy(
                                        isSpeechDetected = vadResult.isSpeechDetected,
                                        vadProbability = vadResult.probability,
                                        effectiveVadConfig = vadResult.effectiveConfig,
                                        silenceDurationMs = vadResult.silenceDurationMs,
                                        segmentStartReason = vadResult.segmentStartReason,
                                        segmentEndReason = vadResult.segmentEndReason,
                                        discardedShortSegments = state.discardedShortSegments +
                                            vadResult.discardedShortDurationsMs.size
                                    )
                                }
                                vadResult.completedSegments.forEach { enqueue(queue, it) }
                            },
                            onReadError = ::recordAudioReadError
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isListening = false,
                        isSpeechDetected = false,
                        error = exception.message ?: "监听过程中发生未知错误。"
                    )
                }
            } finally {
                queue.close()
                worker.cancel()
                promptObserver.cancel()
                currentCourseAsrPrompt = ""
                withContext(NonCancellable) { courseRepository.finishRecord(recordId) }
                sessionRecordId = null
                _uiState.update {
                    it.copy(
                        isListening = false,
                        activeRecordId = null,
                        listeningStartedAtElapsedRealtimeMs = null,
                        isSpeechDetected = false,
                        isRecognizing = false,
                        pendingQueueCount = 0
                    )
                }
            }
        }
    }

    fun stopListening() {
        _uiState.update { it.copy(segmentEndReason = "用户停止监听") }
        listeningJob?.cancel()
        listeningJob = null
    }

    fun reportPermissionDenied() {
        _uiState.update { it.copy(error = "需要麦克风权限才能开始监听。") }
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

    private fun enqueue(queue: Channel<PendingSegment>, captured: CapturedPcmSegment) {
        val audioStart = timestampForSample(captured.pcmSlice.startSample)
        val audioEnd = timestampForSample(captured.pcmSlice.endSample)
        val now = System.currentTimeMillis()
        val recordId = sessionRecordId ?: return
        val transcript = TranscriptSegment(
            id = nextSegmentId++,
            recordId = recordId,
            audioStartTime = audioStart,
            audioEndTime = audioEnd,
            audioDurationMs = audioEnd - audioStart,
            queuedAt = now,
            status = TranscriptStatus.QUEUED,
            gapFromPreviousMs = previousAudioEndTimeMs?.let { audioStart - it },
            hitMaxDuration = captured.hitMaxDuration,
            startReason = _uiState.value.segmentStartReason,
            endReason = captured.endReason
        )
        previousAudioEndTimeMs = audioEnd

        val promptForSegment = currentCourseAsrPrompt.trim().takeIf { it.isNotEmpty() }
        if (queue.trySend(PendingSegment(transcript, captured.pcmSlice.pcm, promptForSegment)).isSuccess) {
            appendTranscript(transcript)
            _uiState.update { it.copy(pendingQueueCount = it.pendingQueueCount + 1) }
            Log.d(TAG, "segment #${transcript.id} queued duration=${transcript.audioDurationMs}ms")
        } else {
            val dropped = transcript.copy(status = TranscriptStatus.DROPPED, error = "Queue full")
            appendTranscript(dropped)
            _uiState.update {
                it.copy(
                    droppedSegments = it.droppedSegments + 1,
                    error = "识别队列已满，已丢弃 segment #${dropped.id}。"
                )
            }
            Log.w(TAG, "segment #${dropped.id} dropped duration=${dropped.audioDurationMs}ms at=$now")
        }
    }

    private suspend fun consumeQueue(queue: Channel<PendingSegment>) {
        for (pending in queue) {
            val startedAt = System.currentTimeMillis()
            replaceTranscript(pending.transcript.id) {
                it.copy(status = TranscriptStatus.RECOGNIZING, recognitionStartedAt = startedAt)
            }
            _uiState.update {
                it.copy(
                    pendingQueueCount = (it.pendingQueueCount - 1).coerceAtLeast(0),
                    isRecognizing = true
                )
            }
            try {
                val text = withContext(Dispatchers.IO) {
                    sttApiClient.transcribe(
                        WavEncoder.encodePcm16Mono(pending.pcm),
                        pending.asrPrompt
                    )
                }
                val finishedAt = System.currentTimeMillis()
                replaceTranscript(pending.transcript.id) {
                    it.copy(
                        text = text,
                        recognitionFinishedAt = finishedAt,
                        recognitionDurationMs = finishedAt - startedAt,
                        status = TranscriptStatus.SUCCESS,
                        error = null
                    )
                }
                courseRepository.saveSegment(
                    recordId = pending.transcript.recordId,
                    start = pending.transcript.audioStartTime,
                    end = pending.transcript.audioEndTime,
                    duration = pending.transcript.audioDurationMs,
                    recognitionDuration = finishedAt - startedAt,
                    text = text
                )
                Log.d(TAG, "segment #${pending.transcript.id} recognized in ${finishedAt - startedAt}ms")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val finishedAt = System.currentTimeMillis()
                val message = exception.message ?: "语音识别请求失败。"
                replaceTranscript(pending.transcript.id) {
                    it.copy(
                        recognitionFinishedAt = finishedAt,
                        recognitionDurationMs = finishedAt - startedAt,
                        status = TranscriptStatus.ERROR,
                        error = message
                    )
                }
                _uiState.update { it.copy(error = message) }
                Log.e(TAG, "segment #${pending.transcript.id} recognition failed", exception)
            } finally {
                _uiState.update { it.copy(isRecognizing = false) }
            }
        }
    }

    private fun recordAudioReadError(code: Int) {
        _uiState.update { it.copy(audioReadErrors = it.audioReadErrors + 1) }
        Log.e(TAG, "AudioRecord.read returned $code")
    }

    private fun appendTranscript(segment: TranscriptSegment) {
        _uiState.update { state ->
            state.copy(transcriptSegments = (state.transcriptSegments + segment).takeLast(MAX_VISIBLE_SEGMENTS))
        }
    }

    private fun replaceTranscript(id: Long, transform: (TranscriptSegment) -> TranscriptSegment) {
        _uiState.update { state ->
            state.copy(transcriptSegments = state.transcriptSegments.map {
                if (it.id == id) transform(it) else it
            })
        }
    }

    private fun timestampForSample(sample: Long): Long =
        sessionStartedAtMs + sample * 1_000 / PcmRecorder.SAMPLE_RATE_HZ

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }

    private companion object {
        const val QUEUE_CAPACITY = 5
        const val MAX_VISIBLE_SEGMENTS = 30
        const val TAG = "ListenDebug"
    }
}
