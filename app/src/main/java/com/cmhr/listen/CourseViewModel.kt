package com.cmhr.listen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.RecordNameGenerator
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.ai.AiPhotoStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CourseUiState(
    val courses: List<CourseEntity> = emptyList(),
    val selectedCourse: CourseEntity? = null,
    val records: List<ClassRecordEntity> = emptyList(),
    val selectedRecord: ClassRecordEntity? = null,
    val detailSegments: List<TranscriptEntity> = emptyList()
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CourseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CourseRepository(ListenDatabase.get(application), AiPhotoStore(application))
    private val settings = AppSettingsRepository(application)
    private val _uiState = MutableStateFlow(CourseUiState())
    val uiState: StateFlow<CourseUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.courses.collect { _uiState.update { state -> state.copy(courses = it) } } }
        viewModelScope.launch {
            settings.settings.map { it.selectedCourseId }.distinctUntilChanged()
                .flatMapLatest { id -> id?.let(repository::course) ?: flowOf(null) }
                .collect { course -> _uiState.update { it.copy(selectedCourse = course) } }
        }
        viewModelScope.launch {
            settings.settings.map { it.selectedCourseId }.distinctUntilChanged()
                .flatMapLatest { id -> id?.let(repository::records) ?: flowOf(emptyList()) }
                .collect { records -> _uiState.update { it.copy(records = records) } }
        }
        viewModelScope.launch {
            settings.settings.map { it.selectedRecordId }.distinctUntilChanged()
                .flatMapLatest { id -> id?.let(repository::record) ?: flowOf(null) }
                .collect { record -> _uiState.update { it.copy(selectedRecord = record) } }
        }
        viewModelScope.launch {
            settings.settings.map { it.selectedRecordId }.distinctUntilChanged()
                .flatMapLatest { id -> id?.let(repository::segments) ?: flowOf(emptyList()) }
                .collect { segments -> _uiState.update { it.copy(detailSegments = segments) } }
        }
    }

    fun createCourse(name: String) = viewModelScope.launch { if (name.isNotBlank()) settings.selectCourse(repository.createCourse(name)) }
    fun renameCourse(id: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) repository.renameCourse(id, name) }
    fun updateCourseAsrPrompt(id: Long, prompt: String) = viewModelScope.launch { repository.updateCourseAsrPrompt(id, prompt) }
    fun deleteCourse(id: Long) = viewModelScope.launch { repository.deleteCourse(id); if (_uiState.value.selectedCourse?.id == id) settings.selectCourse(null) }
    fun enterCourse(id: Long) = viewModelScope.launch { settings.selectCourse(id) }
    fun createRecord(courseId: Long, name: String?) = viewModelScope.launch {
        val defaultName = RecordNameGenerator.defaultName()
        settings.selectRecord(courseId, repository.createRecord(courseId, name?.takeIf { it.isNotBlank() } ?: defaultName))
    }
    fun renameRecord(id: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) repository.renameRecord(id, name) }
    fun deleteRecord(id: Long) = viewModelScope.launch { repository.deleteRecord(id); if (_uiState.value.selectedRecord?.id == id) settings.selectCourse(_uiState.value.selectedCourse?.id) }
    fun selectRecord(courseId: Long, recordId: Long) = viewModelScope.launch { settings.selectRecord(courseId, recordId) }
}
