package com.cmhr.listen.tgui

import android.os.SystemClock
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SessionSummary
import com.cmhr.listen.data.recording.RecordingExportTarget
import com.cmhr.listen.data.recording.RecordingExporter
import com.cmhr.listen.data.recording.RecordingState
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.recording.OfflineRecognitionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.telegram.tgui.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun day(at: Long) = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))
internal fun duration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

class HomeFragment : ListenListFragment("Listen") {
    override val route = "home"
    private var startCourseOverride: Long? = null
    override fun onReady() {
        host.courses.refreshSuggestion()
        scope.launch { combine(host.stt.uiState, host.courses.uiState, host.recordings.uiState) { _, _, _ -> Unit }.collect { render() } }
        scope.launch { while (true) { delay(1000); if (host.stt.uiState.value.isListening) render() } }
    }
    override fun onResume() { super.onResume(); host.courses.refreshSuggestion(); render() }
    private fun render(): Unit = submit(buildList {
        val state = host.stt.uiState.value
        val live = state.activeRecordId.takeIf { state.isListening } ?: state.pausedClass?.recordId ?: state.resumingRecordId
        if (live != null) {
            add(ListenRow.header("当前课堂"))
            add(ListenRow.dialog(live, state.currentRecordName ?: state.pausedClass?.recordName ?: "当前课堂", captureStatus(state), onClick = { host.open("record/$live") }))
            addAll(captureRows(this@HomeFragment, host, live))
            add(ListenRow.shadow())
        } else {
            val courses = host.courses.uiState.value
            val selectedId = startCourseOverride?.takeIf { id -> courses.courses.any { it.id == id } }
                ?: courses.suggestion?.courseId
            val selectedCourse = courses.courses.firstOrNull { it.id == selectedId }
            add(ListenRow.header("开始课堂"))
            add(ListenRow.action("更换课程", selectedCourse?.name ?: "选择课程") {
                chooseCourseForCapture(this@HomeFragment, host) { startCourseOverride = it; render() }
            })
            val blocked = host.recordings.uiState.value.processing.isProcessing
            val start: (CaptureMode) -> Unit = { mode ->
                if (selectedCourse != null) host.startCapture(selectedCourse.id, mode, newClass = true)
                else prompt("新建课程") { name -> host.courses.createCourse(name) { id -> host.startCapture(id, mode, true) } }
            }
            add(ListenRow.action("即时识别", if (blocked) "正在补识别，暂停后可开始" else "") { start(CaptureMode.REALTIME_ASR) }
                .let { if (blocked) it.copy(onClick = null) else it })
            add(ListenRow.action("仅录音", if (blocked) "正在补识别，暂停后可开始" else "") { start(CaptureMode.RECORD_ONLY) }
                .let { if (blocked) it.copy(onClick = null) else it })
            if (blocked) host.recordings.uiState.value.processing.recordId?.let { processingRecord ->
                add(ListenRow.action("查看识别任务") { host.open("recordings/$processingRecord") })
            }
            add(ListenRow.shadow())
        }
        add(ListenRow.action("课程") { host.open("courses") })
        add(ListenRow.action("搜索") { host.open("search") })
        add(ListenRow.action("AI 会话") { host.open("ai") })
        add(ListenRow.action("公开发布") { host.open("publish") })
        add(ListenRow.action("设置") { host.open("settings") })
        state.error?.let { add(ListenRow.action("录音失败", it) { message(it) }) }
    })
}

class CoursesFragment : ListenListFragment("课程") {
    override val route = "courses"
    private var selected = emptySet<Long>()
    private var colors = emptyMap<Long, Int>()
    override fun onReady() {
        scope.launch { host.courses.uiState.collect { render() } }
        scope.launch { host.courses.courseColors.collect { colors = it; render() } }
    }
    override fun configureMenu() {
        actionBar.createMenu().addItem(1, "新建").contentDescription = "新建课程"
        actionBar.createActionMode().apply {
            addItem(2, "全选")
            addItem(3, R.drawable.msg_delete).contentDescription = "删除所选课程"
        }
    }
    override fun menuClick(id: Int) {
        when (id) {
            1 -> prompt("新建课程") { host.courses.createCourse(it) }
            2 -> { selected = host.courses.uiState.value.courses.map { it.id }.toSet(); render() }
            3 -> confirm("删除 ${selected.size} 门课程？", "课堂、原文与录音将一并删除") {
                if (liveRecord(host.stt.uiState.value) != null) message("请先结束当前课堂")
                else { selected.forEach(host.courses::deleteCourse); selected = emptySet(); render() }
            }
        }
    }
    private fun render() {
        val summaries = host.courses.uiState.value.courseSummaries
        selected = selected.intersect(summaries.map { it.course.id }.toSet())
        if (actionBar != null) { if (selected.isEmpty()) actionBar.hideActionMode() else actionBar.showActionMode() }
        submit(buildList {
            add(ListenRow.action("新建课程") { prompt("新建课程") { host.courses.createCourse(it) } })
            add(ListenRow.action("课表 PDF 导入") { host.open("timetable") })
            if (summaries.isEmpty()) add(ListenRow.header("还没有课程"))
            summaries.forEach { summary ->
                add(ListenRow.dialog(summary.course.id, summary.course.name, "${summary.recordCount} 节课堂", summary.lastStartedAt?.let(::day).orEmpty(), onClick = {
                    if (selected.isNotEmpty()) { selected = toggle(selected, summary.course.id); render() }
                    else host.open("course/${summary.course.id}")
                }, onLongClick = { courseMenu(summary.course) }).copy(checked = summary.course.id in selected,
                    avatarColorIndex = nativeCourseColorIndex(summary.course.id, colors)))
            }
        })
    }
    private fun courseMenu(course: CourseEntity) = choices(course.name, listOf("重命名", "多选", "导出全部录音", "删除课程", "更换颜色")) { option ->
        when (option) {
            0 -> prompt("重命名", course.name) { host.courses.renameCourse(course.id, it) }
            1 -> { selected = selected + course.id; render() }
            2 -> exportRecordings(this, host, scope, RecordingExportTarget.Course(course.id))
            3 -> { selected = setOf(course.id); menuClick(3) }
            4 -> chooseCourseColor(this, host, course.id)
        }
    }
    override fun onBackPressed(): Boolean {
        if (selected.isNotEmpty()) { selected = emptySet(); render(); return false }
        return super.onBackPressed()
    }
}

class CourseFragment(private val courseId: Long) : ListenListFragment("课堂") {
    override val route = "course/$courseId"
    private var classes = emptyList<SessionSummary>()
    private var selected = emptySet<Long>()
    private var colors = emptyMap<Long, Int>()
    override fun onReady() {
        host.courses.enterCourse(courseId)
        scope.launch { ListenDatabase.get(host).recordDao().summariesForCourse(courseId).collect { classes = it; render() } }
        scope.launch { combine(host.courses.uiState, host.stt.uiState, host.recordings.uiState) { _, _, _ -> Unit }.collect { render() } }
        scope.launch { host.courses.courseColors.collect { colors = it; render() } }
    }
    override fun configureMenu() {
        actionBar.createActionMode().apply {
            addItem(2, "全选"); addItem(3, R.drawable.msg_delete).contentDescription = "删除所选课堂"
        }
    }
    override fun menuClick(id: Int) {
        if (id == 2) { selected = classes.map { it.session.id }.toSet(); render() }
        if (id == 3) confirm("删除 ${selected.size} 节课堂？", "原文与录音将一并删除") {
            if (liveRecord(host.stt.uiState.value) in selected) message("请先结束当前课堂")
            else { selected.forEach(host.courses::deleteRecord); selected = emptySet(); render() }
        }
    }
    private fun render() {
        val name = host.courses.uiState.value.courses.firstOrNull { it.id == courseId }?.name ?: "课堂"
        selected = selected.intersect(classes.map { it.session.id }.toSet())
        if (actionBar != null) { actionBar.setTitle(name); if (selected.isEmpty()) actionBar.hideActionMode() else actionBar.showActionMode() }
        submit(buildList {
            val live = liveRecord(host.stt.uiState.value)
            val blocked = live != null || host.recordings.uiState.value.processing.isProcessing
            val reason = if (live != null) "请先结束「${activeClassName(host.stt.uiState.value)}」" else if (blocked) "正在补识别，暂停后可开始" else ""
            add(ListenRow.action("开始课堂", reason) { chooseMode(this@CourseFragment) { host.startCapture(courseId, it, newClass = true) } }
                .let { if (blocked) it.copy(onClick = null) else it })
            if (live != null) add(ListenRow.action("当前课堂", activeClassName(host.stt.uiState.value)) { host.open("record/$live") })
            add(ListenRow.action("笔记与资料") { host.open("materials/$courseId") })
            add(ListenRow.action("导出全部录音") { exportRecordings(this@CourseFragment, host, scope, RecordingExportTarget.Course(courseId)) })
            add(ListenRow.action("重命名课程") { prompt("重命名课程", name) { host.courses.renameCourse(courseId, it) } })
            add(ListenRow.action("更换颜色", nativeCourseColorNames[nativeCourseColorIndex(courseId, colors)]) { chooseCourseColor(this@CourseFragment, host, courseId) })
            add(ListenRow.header("${classes.size} 节课堂"))
            classes.forEach { summary ->
                val session = summary.session
                val pending = host.recordings.uiState.value.pendingCounts[session.id] ?: 0
                val detail = "${summary.segmentCount} 段原文 · ${summary.noteCount} 份笔记" + if (pending > 0) " · $pending 段待识别" else ""
                add(ListenRow.dialog(session.id, "第${summary.classNumber}节 ${session.name}", detail, day(session.startedAt), onClick = {
                    if (selected.isNotEmpty()) { selected = toggle(selected, session.id); render() }
                    else host.open("record/${session.id}")
                }, onLongClick = { classMenu(session) }).copy(checked = session.id in selected))
            }
        })
    }
    private fun classMenu(record: ClassRecordEntity) = choices(record.name, listOf("重命名", "换课程", "多选", "导出录音", "删除课堂")) { index ->
        when (index) {
            0 -> prompt("重命名", record.name) { host.courses.renameRecord(record.id, it) }
            1 -> chooseMoveCourse(this, host, record.id)
            2 -> { selected = selected + record.id; render() }
            3 -> exportRecordings(this, host, scope, RecordingExportTarget.Class(record.id))
            4 -> { selected = setOf(record.id); menuClick(3) }
        }
    }
    override fun onBackPressed(): Boolean {
        if (selected.isNotEmpty()) { selected = emptySet(); render(); return false }
        return super.onBackPressed()
    }
}

class ClassroomFragment(private val recordId: Long) : ListenListFragment("课堂") {
    override val route = "record/$recordId"
    private var summary: SessionSummary? = null
    override fun onResume() { super.onResume(); host.recordings.selectRecord(recordId) }
    override fun onReady() {
        host.recordings.selectRecord(recordId)
        scope.launch {
            host.courses.recordSummary(recordId).collect {
                summary = it
                it?.let { row -> host.courses.selectRecord(row.session.courseId, recordId) }
                render()
            }
        }
        scope.launch { combine(host.stt.uiState, host.recordings.uiState, host.ai.uiState) { _, _, _ -> Unit }.collect { render() } }
        scope.launch { while (true) { delay(1000); if (host.stt.uiState.value.isListening) render() } }
    }
    private fun render() {
        val record = summary?.session
        if (actionBar != null) actionBar.setTitle(record?.name ?: "课堂")
        submit(buildList {
            add(ListenRow.header(summary?.courseName ?: "课堂"))
            addAll(captureRows(this@ClassroomFragment, host, recordId))
            val state = host.stt.uiState.value
            if (liveRecord(state) == recordId) add(ListenRow.header(captureStatus(state)))
            state.error?.let { add(ListenRow.action("录音失败", it) { message(it) }) }
            add(ListenRow.action("原文", "${summary?.segmentCount ?: 0} 段") { host.open("transcript/$recordId") })
            add(ListenRow.action("笔记", "${summary?.noteCount ?: 0} 份") { host.open("record/$recordId/ai-results") })
            add(ListenRow.action("生成笔记") { openNoteGeneration(this@ClassroomFragment, recordId) })
            record?.let { add(ListenRow.action("上传笔记与资料") { host.open("materials/${it.courseId}/$recordId") }) }
            add(ListenRow.action("AI 问答") { host.open("ai/new/$recordId") })
            add(ListenRow.action("会话历史") { host.open("record/$recordId/ai-conversations") })
            val pending = host.recordings.uiState.value.pendingCounts[recordId] ?: 0
            add(ListenRow.action("录音与补识别", if (pending > 0) "$pending 段待识别" else "录音文件") { host.open("recordings/$recordId") })
            add(ListenRow.action("导出录音") { exportRecordings(this@ClassroomFragment, host, scope, RecordingExportTarget.Class(recordId)) })
            record?.let { session ->
                add(ListenRow.action("重命名") { prompt("重命名", session.name) { host.courses.renameRecord(recordId, it) } })
                add(ListenRow.action("换课程") { chooseMoveCourse(this@ClassroomFragment, host, recordId) })
            }
        })
    }
}

class RecordingsFragment(private val recordId: Long) : ListenListFragment("录音与补识别") {
    override val route = "recordings/$recordId"
    override fun onResume() { super.onResume(); host.recordings.selectRecord(recordId) }
    override fun onReady() {
        host.recordings.selectRecord(recordId)
        scope.launch { host.recordings.uiState.collect { render() } }
        scope.launch { host.stt.uiState.collect { render() } }
    }
    private fun render() = submit(buildList {
        val state = host.recordings.uiState.value
        val recordings = state.recordings.filter { it.recordId == recordId }.sortedBy { it.startedAt }
        val pending = recordings.filter { it.recordingState.canStartRecognition }
        val active = recordings.firstOrNull { it.recordingId == state.processing.activeRecordingId }
        val capture = host.stt.uiState.value
        val blocked = capture.isListening || capture.resumingRecordId != null || state.processing.isProcessing
        add(ListenRow.header("录音文件 ${recordings.size} 段 · ${duration(recordings.sumOf { it.durationMs })}"))
        add(ListenRow.action("识别状态", nativeRecordingsSummary(recordings, state.processing, state.recognizingAllRecordId == recordId)) {}.copy(onClick = null))
        add(ListenRow.action("识别队列", "${capture.pendingQueueCount} 段") {}.copy(onClick = null))
        if (active != null || state.recognizingAllRecordId == recordId) add(ListenRow.action("暂停识别") { host.recordings.stopRecognition() })
        else if (pending.isNotEmpty()) {
            val label = if (pending.size == 1) nativeRecognitionAction(pending.single().recordingState) else "全部识别"
            add(ListenRow.action(label, if (blocked) "请先暂停当前录音或识别任务" else "") {
                if (pending.size == 1) host.recordings.startRecognition(pending.single().recordingId)
                else host.recordings.recognizeAll(recordId)
            }.let { if (blocked) it.copy(onClick = null) else it })
        }
        state.processing.error?.let { add(ListenRow.action("补识别失败", it) { message(it) }) }
        if (recordings.isEmpty()) add(ListenRow.header("还没有录音"))
        recordings.forEachIndexed { index, recording ->
            val processingHere = recording.recordingId == state.processing.activeRecordingId
            val status = nativeRecordingStatus(recording, state.processing)
            add(ListenRow.action("录音 ${index + 1}", "$status · ${duration(recording.durationMs)}") {
                val options = buildList {
                    if (recording.recordingState.canStartRecognition && !blocked) add(nativeRecognitionAction(recording.recordingState))
                    if (processingHere) add("暂停识别")
                    if (recording.recordingState.canDelete && !processingHere) add("删除录音")
                }
                if (options.isNotEmpty()) choices("录音 ${index + 1}", options) { choice ->
                    when (options[choice]) {
                        "开始识别", "继续识别", "重试识别" -> host.recordings.startRecognition(recording.recordingId)
                        "暂停识别" -> host.recordings.stopRecognition()
                        "删除录音" -> confirm("删除录音？") { host.recordings.deleteRecording(recording.recordingId) }
                    }
                }
            })
            if (recording.recordingState == RecordingState.INTERRUPTED) add(ListenRow.action("录音 ${index + 1} 已恢复", "录音意外中断，已保留到 ${duration(recording.durationMs)}。") {}.copy(onClick = null))
            recording.errorMessage?.let { add(ListenRow.action("录音 ${index + 1} 识别失败", it) { message(it) }) }
        }
        add(ListenRow.action("ASR 诊断") { host.open("settings/asr-history/$recordId") })
    })
}

internal fun liveRecord(state: ListeningUiState) = state.activeRecordId.takeIf { state.isListening } ?: state.pausedClass?.recordId ?: state.resumingRecordId
internal fun captureStatus(state: ListeningUiState): String = when {
    state.isListening -> {
        val elapsed = duration(state.listeningStartedAtElapsedRealtimeMs?.let { SystemClock.elapsedRealtime() - it } ?: state.recordOnlySavedMs)
        if (state.captureMode == CaptureMode.RECORD_ONLY) "仅录音中 · $elapsed · 已安全保存 ${duration(state.recordOnlySavedMs)}"
        else {
            val phase = when { state.isSpeechDetected -> "正在收音"; state.isRecognizing -> "正在识别"; else -> "等待语音" }
            "即时识别中 · $elapsed · $phase" + if (state.pendingQueueCount > 0) " · ${state.pendingQueueCount} 段排队识别" else ""
        }
    }
    state.resumingRecordId != null -> "正在继续"
    state.pausedClass != null -> "已暂停 · ${if (state.pausedClass.mode == CaptureMode.RECORD_ONLY) "仅录音" else "即时识别"} · ${duration(state.pausedClass.elapsedMs)}"
    else -> "已结束"
}
private fun captureRows(fragment: BaseFragment, host: ListenHostActivity, recordId: Long): List<ListenRow> = buildList {
    val state = host.stt.uiState.value
    if (state.isListening && state.activeRecordId == recordId) add(ListenRow.action("暂停") { host.stt.pause() })
    else if (state.pausedClass?.recordId == recordId) add(ListenRow.action("继续") { host.stt.resume() })
    else if (liveRecord(state) == null) {
        val blocked = host.recordings.uiState.value.processing.isProcessing
        add(ListenRow.action("开始录音", if (blocked) "正在补识别，暂停后可开始" else "") { chooseMode(fragment) { host.startCapture(recordId, it) } }
            .let { if (blocked) it.copy(onClick = null) else it })
    } else if (liveRecord(state) != recordId) {
        add(ListenRow.action("开始录音", "请先结束「${activeClassName(state)}」") {}.copy(onClick = null))
        add(ListenRow.action("当前课堂", activeClassName(state)) { liveRecord(state)?.let { host.open("record/$it") } })
    }
    if (liveRecord(state) == recordId) add(ListenRow.action("结束课堂") {
        fragment.confirm("结束课堂？") { host.stt.endClass() }
    })
}
private fun chooseMode(fragment: BaseFragment, start: (CaptureMode) -> Unit) = fragment.choices("录音方式", listOf("即时识别", "仅录音")) {
    start(if (it == 0) CaptureMode.REALTIME_ASR else CaptureMode.RECORD_ONLY)
}
private fun chooseCourseForCapture(fragment: BaseFragment, host: ListenHostActivity, selected: (Long) -> Unit) {
    val courses = host.courses.uiState.value.courses
    fragment.choices("选择课程", courses.map { it.name } + "新建课程") { index ->
        if (index == courses.size) fragment.prompt("新建课程") { name -> host.courses.createCourse(name, selected) }
        else selected(courses[index].id)
    }
}
private fun chooseMoveCourse(fragment: BaseFragment, host: ListenHostActivity, recordId: Long) {
    if (liveRecord(host.stt.uiState.value) == recordId) { fragment.message("请先结束当前课堂"); return }
    val courses = host.courses.uiState.value.courses
    fragment.choices("换课程", courses.map { it.name } + "新建课程") { index ->
        if (liveRecord(host.stt.uiState.value) == recordId) fragment.message("请先结束当前课堂")
        else if (index == courses.size) fragment.prompt("新建课程") { name -> host.courses.createCourse(name) { host.courses.moveRecord(recordId, it) } }
        else host.courses.moveRecord(recordId, courses[index].id)
    }
}
private fun toggle(ids: Set<Long>, id: Long) = if (id in ids) ids - id else ids + id

private fun activeClassName(state: ListeningUiState): String =
    state.currentRecordName ?: state.pausedClass?.recordName ?: state.currentCourseName ?: state.pausedClass?.courseName ?: "当前课堂"

internal fun nativeRecordingProgress(recording: RecordingEntity, processing: OfflineRecognitionState): Int {
    val processed = if (processing.activeRecordingId == recording.recordingId) processing.processedFrames else recording.processedFrames
    return (processed.toDouble() / recording.totalFrames.coerceAtLeast(1) * 100).toInt().coerceIn(0, 100)
}

internal fun nativeRecordingStatus(recording: RecordingEntity, processing: OfflineRecognitionState): String {
    val progress = nativeRecordingProgress(recording, processing)
    return when (if (processing.activeRecordingId == recording.recordingId) RecordingState.PROCESSING else recording.recordingState) {
        RecordingState.RECORDING -> "录音中"
        RecordingState.RECORDED, RecordingState.INTERRUPTED -> "待识别"
        RecordingState.PROCESSING -> "识别中 $progress%"
        RecordingState.PAUSED -> "已暂停 $progress%"
        RecordingState.COMPLETED -> "已识别"
        RecordingState.FAILED -> "识别出错"
    }
}

internal fun nativeRecognitionAction(state: RecordingState): String = when (state) {
    RecordingState.PAUSED -> "继续识别"
    RecordingState.FAILED -> "重试识别"
    else -> "开始识别"
}

internal fun nativeRecordingsSummary(recordings: List<RecordingEntity>, processing: OfflineRecognitionState, recognizingAll: Boolean): String {
    val active = recordings.firstOrNull { it.recordingId == processing.activeRecordingId }
    val pending = recordings.count { it.recordingState.canStartRecognition }
    val failures = recordings.count { it.recordingState == RecordingState.FAILED }
    return when {
        active != null -> "正在识别录音 ${recordings.indexOf(active) + 1} · ${nativeRecordingProgress(active, processing)}%" +
            if (recognizingAll && pending > 0) " · 之后还有 $pending 段" else ""
        recordings.any { it.recordingState == RecordingState.RECORDING } -> "录音中"
        failures > 0 -> "$failures 段识别出错 · $pending 段待识别"
        pending > 0 -> "$pending 段待识别"
        recordings.isEmpty() -> "暂无录音"
        else -> "已全部识别"
    }
}

internal val nativeCourseColorNames = listOf("蓝", "玫红", "橙", "绿", "紫", "青", "棕", "灰蓝")
internal fun nativeCourseColorIndex(courseId: Long, overrides: Map<Long, Int>): Int =
    overrides[courseId]?.takeIf { it in nativeCourseColorNames.indices }
        ?: Math.floorMod(courseId - 1, nativeCourseColorNames.size.toLong()).toInt()

private fun chooseCourseColor(fragment: BaseFragment, host: ListenHostActivity, courseId: Long) {
    fragment.choices("课程颜色", nativeCourseColorNames + "恢复默认") { choice ->
        host.courses.setCourseColor(courseId, choice.takeIf { it < nativeCourseColorNames.size })
    }
}

/** Same eight persisted palette indices as the Compose badges, rendered by tgui's AvatarDrawable. */
internal fun nativeCourseAvatar(id: Long, title: String, colorIndex: Int): AvatarDrawable {
    val lightForeground = longArrayOf(0xFF2F64A8, 0xFFA33A4F, 0xFFA35A12, 0xFF2F7448, 0xFF6A4FA3, 0xFF00696B, 0xFF7A5C2E, 0xFF48607A)
    val lightBackground = longArrayOf(0xFFE3ECF7, 0xFFF6E4E8, 0xFFF7EADC, 0xFFE2F0E6, 0xFFECE7F6, 0xFFDDF0F0, 0xFFF1E9DC, 0xFFE4EAF0)
    val darkForeground = longArrayOf(0xFFA9C7FF, 0xFFFFB1C1, 0xFFFFB871, 0xFF96D5A6, 0xFFD0BCFF, 0xFF80D4D6, 0xFFE2C38F, 0xFFB5C8DE)
    val darkBackground = longArrayOf(0xFF1E2C40, 0xFF3A1F27, 0xFF3A2814, 0xFF1A3022, 0xFF2C2440, 0xFF0F3132, 0xFF33291A, 0xFF232C36)
    val index = Math.floorMod(colorIndex, nativeCourseColorNames.size)
    val dark = Theme.isCurrentThemeDark()
    val foreground = (if (dark) darkForeground else lightForeground)[index].toInt()
    val background = (if (dark) darkBackground else lightBackground)[index].toInt()
    return AvatarDrawable(Theme.ResourcesProvider { key -> if (key == Theme.key_avatar_text) foreground else Theme.getColor(key) }).apply {
        setInfo(id, title, null)
        setColor(background)
    }
}

private fun exportRecordings(fragment: BaseFragment, host: ListenHostActivity, scope: kotlinx.coroutines.CoroutineScope, target: RecordingExportTarget) {
    scope.launch {
        val exporter = RecordingExporter(host)
        val plan = runCatching { exporter.plan(target) }.getOrElse { fragment.message(it.message ?: "无法导出"); return@launch }
        if (plan.items.isEmpty()) { fragment.message("还没有可导出的录音"); return@launch }
        host.chooseDirectory { uri ->
            val progress = org.telegram.ui.ActionBar.AlertDialog.Builder(host).setTitle("导出录音").setMessage("0 / ${plan.items.size}").setNegativeButton("取消", null).create()
            var job: Job? = null
            progress.setOnDismissListener { job?.cancel() }
            fragment.showDialog(progress)
            job = scope.launch {
                try {
                    exporter.export(uri, plan) { done, _ -> host.runOnUiThread { progress.setMessage("$done / ${plan.items.size}") } }
                    fragment.message("已导出 ${plan.items.size} 个录音")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { fragment.message(error.message ?: "导出失败") }
                finally { progress.setOnDismissListener(null); progress.dismiss() }
            }
        }
    }
}
