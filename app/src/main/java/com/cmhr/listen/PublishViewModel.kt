package com.cmhr.listen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.publish.PublishApiClient
import com.cmhr.listen.data.publish.PublishCourseInfo
import com.cmhr.listen.data.publish.PublishLessonStatus
import com.cmhr.listen.data.publish.PublishNoteCandidate
import com.cmhr.listen.data.publish.buildSubmission
import com.cmhr.listen.data.publish.defaultNoteSelection
import com.cmhr.listen.data.publish.defaultPublishTitle
import com.cmhr.listen.data.publish.noteCandidates
import com.cmhr.listen.data.publish.termSuggestions
import com.cmhr.listen.data.settings.AppSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the server says about this class; loaded separately so the form works offline. */
sealed interface PublishRemoteState {
    data object NotConfigured : PublishRemoteState
    data object Loading : PublishRemoteState
    data class Loaded(val status: PublishLessonStatus) : PublishRemoteState
    data class Failed(val message: String) : PublishRemoteState
}

data class PublishUiState(
    val recordId: Long? = null,
    val loaded: Boolean = false,
    val title: String = "",
    val course: PublishCourseInfo = PublishCourseInfo(),
    val termSuggestions: List<String> = emptyList(),
    val segmentCount: Int = 0,
    val markedCount: Int = 0,
    val correctedCount: Int = 0,
    val notes: List<PublishNoteCandidate> = emptyList(),
    val selectedNotes: Set<Long> = emptySet(),
    val remote: PublishRemoteState = PublishRemoteState.Loading,
    val submitting: Boolean = false,
    /** Result of the last submit or withdraw, shown on the status card until the next one. */
    val notice: String? = null,
    val error: String? = null
) {
    val canSubmit: Boolean get() = loaded && !submitting && course.complete && title.isNotBlank() &&
        (segmentCount > 0 || selectedNotes.isNotEmpty()) && remote !is PublishRemoteState.NotConfigured
}

class PublishViewModel(application: Application) : AndroidViewModel(application) {
    private val database = ListenDatabase.get(application)
    private val settings = AppSettingsRepository(application)
    private val client = PublishApiClient()
    private val _uiState = MutableStateFlow(PublishUiState())
    val uiState = _uiState.asStateFlow()
    private var session: SessionEntity? = null
    private var courseId: Long? = null
    private var statusJob: Job? = null

    fun load(recordId: Long) {
        if (_uiState.value.recordId == recordId && _uiState.value.loaded) { refreshStatus(); return }
        _uiState.value = PublishUiState(recordId = recordId)
        viewModelScope.launch {
            val record = database.recordDao().recordNow(recordId) ?: run {
                _uiState.update { it.copy(error = "这节课已不存在。") }
                return@launch
            }
            val course = database.courseDao().courseNow(record.courseId)
            val summary = database.recordDao().summary(recordId).first()
            val segments = database.transcriptDao().segments(recordId).first().filter { it.effectiveText.isNotBlank() }
            val candidates = noteCandidates(database.aiDao().results(recordId).first())
            val stored = settings.settings.first().publish.courseInfo
            // A course seen before keeps its labels; a new one inherits school and term from the last one used.
            val previous = stored[record.courseId]
            val latest = stored.values.lastOrNull()
            val terms = termSuggestions(record.startedAt)
            session = record
            courseId = record.courseId
            _uiState.update {
                it.copy(
                    loaded = true,
                    title = defaultPublishTitle(record.name, course?.name, record.startedAt, summary?.classNumber ?: 0),
                    course = previous ?: PublishCourseInfo(
                        schoolName = latest?.schoolName.orEmpty(),
                        term = latest?.term?.takeIf { term -> term in terms } ?: terms.first(),
                        courseName = course?.name.orEmpty()
                    ),
                    termSuggestions = terms,
                    segmentCount = segments.size,
                    markedCount = segments.count { s -> s.marked },
                    correctedCount = segments.count { s -> !s.correctedText.isNullOrBlank() },
                    notes = candidates,
                    selectedNotes = defaultNoteSelection(candidates)
                )
            }
            refreshStatus()
        }
    }

    fun refreshStatus() {
        val record = session ?: return
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            val token = settings.readPublishToken()
            val baseUrl = settings.settings.first().publishBaseUrl
            if (token.isNullOrBlank() || baseUrl.isBlank()) {
                _uiState.update { it.copy(remote = PublishRemoteState.NotConfigured) }
                return@launch
            }
            if (_uiState.value.remote !is PublishRemoteState.Loaded) _uiState.update { it.copy(remote = PublishRemoteState.Loading) }
            val result = runCatching { client.status(baseUrl, token, record.sessionId) }
            _uiState.update { state ->
                state.copy(remote = result.fold(
                    { PublishRemoteState.Loaded(it) },
                    { PublishRemoteState.Failed(it.message ?: "无法读取审核状态。") }
                ))
            }
        }
    }

    fun setTitle(value: String) = _uiState.update { it.copy(title = value) }
    fun setCourse(value: PublishCourseInfo) = _uiState.update { it.copy(course = value) }
    fun toggleNote(resultId: Long) = _uiState.update {
        it.copy(selectedNotes = if (resultId in it.selectedNotes) it.selectedNotes - resultId else it.selectedNotes + resultId)
    }

    fun submit() {
        val state = _uiState.value
        val record = session ?: return
        if (!state.canSubmit) return
        _uiState.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            val result = runCatching {
                val token = settings.readPublishToken().orEmpty()
                val baseUrl = settings.settings.first().publishBaseUrl
                // Read again at submit time so the copy matches what is on screen right now.
                val segments = database.transcriptDao().segments(record.id).first()
                val notes = state.notes.filter { it.resultId in state.selectedNotes }
                courseId?.let { settings.savePublishCourseInfo(it, state.course) }
                client.submit(baseUrl, token, buildSubmission(record, state.title, state.course, segments, notes))
            }
            _uiState.update {
                result.fold(
                    { submitted ->
                        it.copy(
                            submitting = false,
                            notice = if (submitted.status == "unchanged") "内容和已公开的版本一样，无需再次审核。"
                            else "已提交第 ${submitted.revision} 版，等待管理员审核。"
                        )
                    },
                    { error -> it.copy(submitting = false, error = error.message ?: "提交失败。") }
                )
            }
            refreshStatus()
        }
    }

    fun requestWithdraw(reason: String) {
        val lessonId = ((_uiState.value.remote as? PublishRemoteState.Loaded)?.status?.lessonId) ?: return
        viewModelScope.launch {
            val result = runCatching {
                client.requestWithdraw(settings.settings.first().publishBaseUrl, settings.readPublishToken().orEmpty(), lessonId, reason.trim())
            }
            _uiState.update {
                result.fold(
                    { _ -> it.copy(notice = "已申请撤回，管理员处理后读者将看不到这节课。", error = null) },
                    { error -> it.copy(error = error.message ?: "申请撤回失败。") }
                )
            }
            refreshStatus()
        }
    }
}
