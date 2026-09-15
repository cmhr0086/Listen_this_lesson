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

data class RecordingUiState(
    val recordId: Long? = null,
    val recordings: List<RecordingEntity> = emptyList(),
    val processing: OfflineRecognitionState = OfflineRecognitionState()
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
    }

    fun selectRecord(recordId: Long) {
        selectedRecordId.value = recordId
        _uiState.update { it.copy(recordId = recordId) }
    }
    fun startRecognition(recordingId: String) = runtime.start(recordingId)
    fun stopRecognition() = runtime.stop()
}
