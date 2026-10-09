package com.cmhr.listen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingRepository
import com.cmhr.listen.recording.OfflineRecognitionRuntime
import com.cmhr.listen.recording.OfflineRecognitionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.cmhr.listen.data.recording.RecordingState
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job

data class RecordingUiState(
    val recordId: Long? = null,
    val recordings: List<RecordingEntity> = emptyList(),
    val processing: OfflineRecognitionState = OfflineRecognitionState(),
    /** Session local id -> recordings that still need recognition. */
    val pendingCounts: Map<Long, Int> = emptyMap(),
    /** 全部识别 is working through this record's recordings one by one. */
    val recognizingAllRecordId: Long? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository(application)
    private val runtime = OfflineRecognitionRuntime.get(application)
    private val selectedRecordId = MutableStateFlow<Long?>(null)
    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            selectedRecordId.flatMapLatest { it?.let(repository::observeForSession) ?: flowOf(emptyList()) }
                .collect { values -> _uiState.update { it.copy(recordings = values) } }
        }
        viewModelScope.launch { runtime.state.collect { processing -> _uiState.update { it.copy(processing = processing) } } }
        viewModelScope.launch {
            repository.observePendingCounts().collect { rows ->
                _uiState.update { it.copy(pendingCounts = rows.associate { row -> row.recordId to row.count }) }
            }
        }
    }

    fun selectRecord(recordId: Long) {
        selectedRecordId.value = recordId
        _uiState.update { it.copy(recordId = recordId) }
    }
    fun startRecognition(recordingId: String) = runtime.start(recordingId)
    fun stopRecognition() {
        batchJob?.cancel()
        runtime.stop()
    }

    private var batchJob: Job? = null

    /**
     * Recognizes every recording of a class that still needs it, oldest first. Pause/resume creates
     * several recordings per class, so one tap should cover them all. Each recording is tried once
     * per run; a user pause or a failure to start ends the run instead of looping.
     */
    fun recognizeAll(recordId: Long) {
        if (batchJob?.isActive == true) return
        batchJob = viewModelScope.launch {
            _uiState.update { it.copy(recognizingAllRecordId = recordId) }
            try {
                val attempted = mutableSetOf<String>()
                while (true) {
                    val next = repository.observeForSession(recordId).first()
                        .sortedBy { it.startedAt }
                        .firstOrNull { it.recordingState.canStartRecognition && it.recordingId !in attempted }
                        ?: break
                    attempted += next.recordingId
                    runtime.start(next.recordingId)
                    withTimeoutOrNull(START_TIMEOUT_MS) { runtime.state.first { it.activeRecordingId == next.recordingId } } ?: break
                    runtime.state.first { it.activeRecordingId != next.recordingId }
                    if (repository.recording(next.recordingId)?.recordingState == RecordingState.PAUSED) break
                }
            } finally {
                _uiState.update { it.copy(recognizingAllRecordId = null) }
            }
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 10_000L
    }
    fun deleteRecording(recordingId: String) = viewModelScope.launch { repository.delete(recordingId) }
}
