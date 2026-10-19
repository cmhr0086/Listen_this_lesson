package com.cmhr.listen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SessionSummary
import com.cmhr.listen.data.publish.PUBLISH_BASE_URL
import com.cmhr.listen.data.publish.PublishApiClient
import com.cmhr.listen.data.publish.PublishCourse
import com.cmhr.listen.data.publish.PublishCourseLesson
import com.cmhr.listen.data.publish.PublishCourseState
import com.cmhr.listen.data.publish.PublishSubmission
import com.cmhr.listen.data.publish.buildSubmission
import com.cmhr.listen.data.publish.defaultPublishTitle
import com.cmhr.listen.data.publish.fingerprint
import com.cmhr.listen.data.publish.noteCandidates
import com.cmhr.listen.data.publish.publicNotes
import com.cmhr.listen.data.publish.termOf
import com.cmhr.listen.data.settings.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where one class stands between this device and the public reader. */
enum class ClassPublishState { LIVE, NO_TEXT, EXCLUDED, NEW, CHANGED, SYNCED }

data class PublishClassRow(
    val recordId: Long,
    val sessionId: String,
    val title: String,
    val startedAt: Long,
    val term: String,
    val segmentCount: Int,
    val hasNotes: Boolean,
    val state: ClassPublishState,
    /** The server's copy, once this class has been uploaded. */
    val remote: PublishCourseLesson? = null
)

/** One public course: a local course becomes one per term its classes fall in. */
data class PublishRemoteCourse(val term: String, val courseId: String, val status: String, val reviewNote: String? = null)

data class CoursePublishUiState(
    val courseId: Long? = null,
    val loaded: Boolean = false,
    val localName: String = "",
    val course: PublishCourseState = PublishCourseState(),
    val school: String = "",
    val hasToken: Boolean = false,
    val classes: List<PublishClassRow> = emptyList(),
    val remote: List<PublishRemoteCourse> = emptyList(),
    val refreshing: Boolean = false,
    val remoteError: String? = null,
    /** Classes done / to do while a sync runs. */
    val progress: Pair<Int, Int>? = null,
    val notice: String? = null,
    val error: String? = null
) {
    val publicName: String get() = course.publicName.ifBlank { localName }
    val toSync: List<PublishClassRow> get() = classes.filter { it.state == ClassPublishState.NEW || it.state == ClassPublishState.CHANGED }
    val missing: List<String> get() = listOfNotNull(
        "上传 Token".takeIf { !hasToken },
        "学校".takeIf { school.isBlank() },
        "教师".takeIf { course.teacherName.isBlank() }
    )
    val syncing: Boolean get() = progress != null
    val canSync: Boolean get() = loaded && !syncing && missing.isEmpty() && toSync.isNotEmpty()
}

class PublishViewModel(application: Application) : AndroidViewModel(application) {
    private val database = ListenDatabase.get(application)
    private val captureRuntime = com.cmhr.listen.recording.ClassroomCaptureRuntime.get(application)
    private val settings = AppSettingsRepository(application)
    private val client = PublishApiClient()
    private val _uiState = MutableStateFlow(CoursePublishUiState())
    val uiState = _uiState.asStateFlow()
    private var watchJob: Job? = null
    private var remoteJob: Job? = null
    /** The submission each class would send right now, rebuilt whenever its content changes. */
    private var drafts: Map<Long, PublishSubmission> = emptyMap()

    fun load(courseId: Long) {
        if (_uiState.value.courseId == courseId && watchJob?.isActive == true) { refreshRemote(); return }
        watchJob?.cancel()
        remoteJob?.cancel()
        remoteJob = null
        _uiState.value = CoursePublishUiState(courseId = courseId)
        watchJob = viewModelScope.launch {
            // Classes, AI results and settings all move the state, so recompute whenever any changes.
            combine(
                database.recordDao().summariesForCourse(courseId),
                database.aiDao().allResults(),
                settings.settings
            ) { summaries, _, appSettings -> summaries to appSettings }
                .collect { (summaries, appSettings) ->
                    val localName = database.courseDao().courseNow(courseId)?.name.orEmpty()
                    val publish = appSettings.publish
                    val course = publish.courses[courseId] ?: PublishCourseState()
                    val rows = withContext(Dispatchers.Default) { rows(summaries, localName, publish.schoolName, course) }
                    _uiState.update { state ->
                        state.copy(
                            loaded = true,
                            localName = localName,
                            course = course,
                            school = publish.schoolName,
                            hasToken = publish.hasToken,
                            classes = rows.map { row -> row.copy(remote = state.classes.firstOrNull { it.recordId == row.recordId }?.remote) },
                            remote = course.remoteCourses.map { (term, id) ->
                                state.remote.firstOrNull { it.courseId == id } ?: PublishRemoteCourse(term, id, course.remoteStatus[term] ?: "pending")
                            }.sortedByDescending { it.term }
                        )
                    }
                    if (_uiState.value.remote.isNotEmpty() && remoteJob == null) refreshRemote()
                }
        }
    }

    private suspend fun rows(
        summaries: List<SessionSummary>,
        localName: String,
        school: String,
        course: PublishCourseState
    ): List<PublishClassRow> {
        val built = mutableMapOf<Long, PublishSubmission>()
        val rows = summaries.sortedBy { it.session.startedAt }.map { summary ->
            val record = summary.session
            val segments = database.transcriptDao().segments(record.id).first()
            val notes = publicNotes(noteCandidates(database.aiDao().results(record.id).first()))
            val title = defaultPublishTitle(record.name, localName, record.startedAt, summary.classNumber)
            val term = termOf(record.startedAt)
            val submission = buildSubmission(
                record, title,
                PublishCourse(school, term, course.publicName.ifBlank { localName }, course.teacherName),
                segments, notes
            )
            val state = when {
                // Only the class being captured right now; an end time missing for any other
                // reason does not hold a class back from publishing.
                record.endedAt == null && record.id == captureRuntime.state.value.recordId -> ClassPublishState.LIVE
                submission.segments.isEmpty() -> ClassPublishState.NO_TEXT
                record.id in course.excluded -> ClassPublishState.EXCLUDED
                course.synced[record.id] == null -> ClassPublishState.NEW
                course.synced[record.id] != fingerprint(submission) -> ClassPublishState.CHANGED
                else -> ClassPublishState.SYNCED
            }
            if (state == ClassPublishState.NEW || state == ClassPublishState.CHANGED) built[record.id] = submission
            PublishClassRow(record.id, record.sessionId, title, record.startedAt, term, submission.segments.size, notes.isNotEmpty(), state)
        }
        drafts = built
        return rows.reversed()
    }

    fun setTeacher(value: String) = editCourse { it.copy(teacherName = value.trim()) }
    fun setPublicName(value: String) = editCourse { it.copy(publicName = value.trim()) }
    fun setSchool(value: String) = viewModelScope.launch { runCatching { settings.savePublishSchool(value) } }

    private fun editCourse(change: (PublishCourseState) -> PublishCourseState) {
        val id = _uiState.value.courseId ?: return
        viewModelScope.launch { settings.updatePublishCourse(id, change) }
    }

    /** Uploads every new or changed class, oldest first; stops at the first failure so it can be retried. */
    fun sync() {
        val state = _uiState.value
        val courseId = state.courseId ?: return
        if (!state.canSync) return
        val queue = state.toSync.sortedBy { it.startedAt }.mapNotNull { row -> drafts[row.recordId]?.let { row to it } }
        _uiState.update { it.copy(progress = 0 to queue.size, notice = null, error = null) }
        viewModelScope.launch {
            val token = settings.readPublishToken().orEmpty()
            var done = 0
            var failure: String? = null
            for ((row, submission) in queue) {
                val result = runCatching { client.submit(PUBLISH_BASE_URL, token, submission) }
                val submitted = result.getOrElse { failure = it.message ?: "上传失败。"; null } ?: break
                settings.updatePublishCourse(courseId) { course ->
                    course.copy(
                        synced = course.synced + (row.recordId to fingerprint(submission)),
                        remoteCourses = course.remoteCourses + (submission.course.term to submitted.courseId),
                        remoteStatus = course.remoteStatus + (submission.course.term to submitted.courseStatus),
                        lastSyncAt = System.currentTimeMillis()
                    )
                }
                done += 1
                _uiState.update { it.copy(progress = done to queue.size) }
            }
            _uiState.update {
                it.copy(
                    progress = null,
                    notice = if (failure == null) "已同步 $done 节课。" else null,
                    error = failure?.let { message -> "已同步 $done / ${queue.size} 节，其余未上传：$message" }
                )
            }
            refreshRemote()
        }
    }

    /** Review status of each public course and the server's copy of each class. */
    fun refreshRemote() {
        val state = _uiState.value
        val courseId = state.courseId ?: return
        if (state.remote.isEmpty()) return
        remoteJob?.cancel()
        remoteJob = viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            val token = settings.readPublishToken()
            if (token.isNullOrBlank()) {
                _uiState.update { it.copy(refreshing = false) }
                return@launch
            }
            val result = runCatching {
                state.remote.map { course ->
                    val status = client.course(PUBLISH_BASE_URL, token, course.courseId)
                    PublishRemoteCourse(course.term, course.courseId, status.status, status.reviewNote) to
                        client.courseLessons(PUBLISH_BASE_URL, token, course.courseId)
                }
            }
            result.onSuccess { pairs ->
                val lessons = pairs.flatMap { it.second }.associateBy { it.sessionId }
                settings.updatePublishCourse(courseId) { course ->
                    course.copy(remoteStatus = course.remoteStatus + pairs.associate { (remote, _) -> remote.term to remote.status })
                }
                _uiState.update { s ->
                    s.copy(
                        refreshing = false,
                        remoteError = null,
                        remote = pairs.map { it.first }.sortedByDescending { it.term },
                        classes = s.classes.map { row -> row.copy(remote = lessons[row.sessionId]) }
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(refreshing = false, remoteError = error.message ?: "读不到审核状态。") }
            }
        }
    }

    fun requestReview(remoteCourseId: String) = viewModelScope.launch {
        val result = runCatching { client.requestReview(PUBLISH_BASE_URL, settings.readPublishToken().orEmpty(), remoteCourseId) }
        _uiState.update {
            result.fold(
                { _ -> it.copy(notice = "已重新申请，等待管理员审核。", error = null) },
                { error -> it.copy(error = error.message ?: "申请失败。") }
            )
        }
        refreshRemote()
    }

    /**
     * Keeps a class off the public reader. A class already public stays visible until the admin
     * acts on the withdraw request this files.
     */
    fun setExcluded(row: PublishClassRow, excluded: Boolean) {
        editCourse { it.copy(excluded = if (excluded) it.excluded + row.recordId else it.excluded - row.recordId) }
        val lesson = row.remote ?: return
        if (!excluded || lesson.status != "active" || lesson.withdrawRequested) return
        viewModelScope.launch {
            val result = runCatching {
                client.requestWithdraw(PUBLISH_BASE_URL, settings.readPublishToken().orEmpty(), lesson.lessonId, "上传者不再公开这节课")
            }
            _uiState.update {
                result.fold(
                    { _ -> it.copy(notice = "「${row.title}」已申请撤回，管理员处理后读者就看不到了。") },
                    { error -> it.copy(error = error.message ?: "申请撤回失败。") }
                )
            }
            refreshRemote()
        }
    }
}
