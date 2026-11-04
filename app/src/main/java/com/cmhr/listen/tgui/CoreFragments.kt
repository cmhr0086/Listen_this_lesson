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
import com.cmhr.listen.recording.CaptureMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.telegram.tgui.R
import org.telegram.ui.ActionBar.BaseFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun day(at: Long) = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))
internal fun duration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

class HomeFragment : ListenListFragment("Listen") {
    override val route = "home"
    override fun onReady() {
        scope.launch { combine(host.stt.uiState, host.courses.uiState) { _, _ -> Unit }.collect { render() } }
        scope.launch { while (true) { delay(1000); if (host.stt.uiState.value.isListening) render() } }
    }
    private fun render() = submit(buildList {
        val state = host.stt.uiState.value
        val live = state.activeRecordId.takeIf { state.isListening } ?: state.pausedClass?.recordId ?: state.resumingRecordId
        if (live != null) {
            add(ListenRow.header("当前课堂"))
            add(ListenRow.dialog(live, state.currentRecordName ?: state.pausedClass?.recordName ?: "当前课堂", captureStatus(state), onClick = { host.open("record/$live") }))
            addAll(captureRows(this@HomeFragment, host, live))
            add(ListenRow.shadow())
        } else add(ListenRow.action("开始课堂") { chooseCourseForCapture(this@HomeFragment, host) })
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
    override fun onReady() { scope.launch { host.courses.uiState.collect { render() } } }
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
                val active = liveRecord(host.stt.uiState.value)
                val activeCourse = host.courses.uiState.value.recentSessions.firstOrNull { it.session.id == active }?.session?.courseId
                if (activeCourse in selected) message("请先结束当前课堂")
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
                }, onLongClick = { courseMenu(summary.course) }).copy(checked = summary.course.id in selected))
            }
        })
    }
    private fun courseMenu(course: CourseEntity) = choices(course.name, listOf("重命名", "多选", "导出全部录音", "删除课程")) { option ->
        when (option) {
            0 -> prompt("重命名", course.name) { host.courses.renameCourse(course.id, it) }
            1 -> { selected = selected + course.id; render() }
            2 -> exportRecordings(this, host, scope, RecordingExportTarget.Course(course.id))
            3 -> { selected = setOf(course.id); menuClick(3) }
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
    override fun onReady() {
        host.courses.enterCourse(courseId)
        scope.launch { ListenDatabase.get(host).recordDao().summariesForCourse(courseId).collect { classes = it; render() } }
        scope.launch { combine(host.courses.uiState, host.stt.uiState, host.recordings.uiState) { _, _, _ -> Unit }.collect { render() } }
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
            add(ListenRow.action("开始课堂") { chooseMode(this@CourseFragment) { host.startCapture(courseId, it, newClass = true) } })
            add(ListenRow.action("笔记与资料") { host.open("materials/$courseId") })
            add(ListenRow.action("导出全部录音") { exportRecordings(this@CourseFragment, host, scope, RecordingExportTarget.Course(courseId)) })
            add(ListenRow.action("重命名课程") { prompt("重命名课程", name) { host.courses.renameCourse(courseId, it) } })
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
    override fun onReady() {
        host.recordings.selectRecord(recordId)
        scope.launch { host.recordings.uiState.collect { render() } }
        scope.launch { host.stt.uiState.collect { render() } }
    }
    private fun render() = submit(buildList {
        val state = host.recordings.uiState.value
        add(ListenRow.header("队列 ${host.stt.uiState.value.pendingQueueCount}"))
        add(ListenRow.action("全部识别") { host.recordings.recognizeAll(recordId) })
        if (state.recognizingAllRecordId != null || state.processing.activeRecordingId != null) add(ListenRow.action("暂停识别") { host.recordings.stopRecognition() })
        if (state.recordings.isEmpty()) add(ListenRow.header("还没有录音"))
        state.recordings.filter { it.recordId == recordId }.forEachIndexed { index, recording ->
            val status = when (recording.recordingState) {
                RecordingState.RECORDING -> "录制中"; RecordingState.RECORDED -> "待识别"; RecordingState.INTERRUPTED -> "已恢复"
                RecordingState.PROCESSING -> "识别中"; RecordingState.PAUSED -> "已暂停"; RecordingState.COMPLETED -> "已完成"; RecordingState.FAILED -> "识别失败"
            }
            add(ListenRow.action("录音 ${index + 1}", "$status · ${duration(recording.durationMs)}" + recording.errorMessage?.let { "\n$it" }.orEmpty()) {
                val options = buildList {
                    if (recording.recordingState.canStartRecognition) add("补识别")
                    if (recording.recordingState == RecordingState.PROCESSING) add("暂停识别")
                    if (recording.recordingState.canDelete) add("删除录音")
                }
                if (options.isNotEmpty()) choices("录音 ${index + 1}", options) { choice ->
                    when (options[choice]) {
                        "补识别" -> host.recordings.startRecognition(recording.recordingId)
                        "暂停识别" -> host.recordings.stopRecognition()
                        "删除录音" -> confirm("删除录音？") { host.recordings.deleteRecording(recording.recordingId) }
                    }
                }
            })
        }
        add(ListenRow.action("ASR 诊断") { host.open("settings/asr-history/$recordId") })
    })
}

internal fun liveRecord(state: ListeningUiState) = state.activeRecordId.takeIf { state.isListening } ?: state.pausedClass?.recordId ?: state.resumingRecordId
internal fun captureStatus(state: ListeningUiState): String = when {
    state.isListening -> "录制中 · " + duration(state.listeningStartedAtElapsedRealtimeMs?.let { SystemClock.elapsedRealtime() - it } ?: state.recordOnlySavedMs)
    state.pausedClass != null -> "已暂停 · " + duration(state.pausedClass.elapsedMs)
    state.resumingRecordId != null -> "正在继续"
    else -> "已结束"
}
private fun captureRows(fragment: BaseFragment, host: ListenHostActivity, recordId: Long): List<ListenRow> = buildList {
    val state = host.stt.uiState.value
    if (state.isListening && state.activeRecordId == recordId) add(ListenRow.action("暂停") { host.stt.pause() })
    else if (state.pausedClass?.recordId == recordId) add(ListenRow.action("继续") { host.stt.resume() })
    else if (liveRecord(state) == null) add(ListenRow.action("开始录音") { chooseMode(fragment) { host.startCapture(recordId, it) } })
    if (liveRecord(state) == recordId) add(ListenRow.action("结束课堂") {
        fragment.confirm("结束课堂？") { host.stt.endClass(); host.ai.autoOrganizeNotes(recordId) }
    })
}
private fun chooseMode(fragment: BaseFragment, start: (CaptureMode) -> Unit) = fragment.choices("录音方式", listOf("即时识别", "仅录音")) {
    start(if (it == 0) CaptureMode.REALTIME_ASR else CaptureMode.RECORD_ONLY)
}
private fun chooseCourseForCapture(fragment: BaseFragment, host: ListenHostActivity) {
    val courses = host.courses.uiState.value.courses
    fragment.choices("选择课程", courses.map { it.name } + "新建课程") { index ->
        if (index == courses.size) fragment.prompt("新建课程") { name -> host.courses.createCourse(name) { id -> chooseMode(fragment) { host.startCapture(id, it, true) } } }
        else chooseMode(fragment) { host.startCapture(courses[index].id, it, true) }
    }
}
private fun chooseMoveCourse(fragment: BaseFragment, host: ListenHostActivity, recordId: Long) {
    val courses = host.courses.uiState.value.courses
    fragment.choices("换课程", courses.map { it.name } + "新建课程") { index ->
        if (index == courses.size) fragment.prompt("新建课程") { name -> host.courses.createCourse(name) { host.courses.moveRecord(recordId, it) } }
        else host.courses.moveRecord(recordId, courses[index].id)
    }
}
private fun toggle(ids: Set<Long>, id: Long) = if (id in ids) ids - id else ids + id

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
