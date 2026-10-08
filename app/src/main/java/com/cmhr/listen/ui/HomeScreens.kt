package com.cmhr.listen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.data.course.CourseSuggestion
import com.cmhr.listen.data.course.CourseSummary
import com.cmhr.listen.data.course.SessionSummary
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.recording.OfflineRecognitionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Content padding that keeps the last list row clear of the extended FAB. */
internal val ListWithFabPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp)

/**
 * Home: start a class first, file it under a course afterwards. The course is only a guess at
 * start time (the user confirms when recording stops), so starting never requires navigation.
 */
@Composable
fun HomeScreen(
    courses: List<CourseSummary>,
    recent: List<SessionSummary>,
    startCourse: CourseSummary?,
    suggestion: CourseSuggestion?,
    listening: ListeningUiState,
    processing: OfflineRecognitionState,
    pendingRecordingCounts: Map<Long, Int>,
    pickCourse: () -> Unit,
    start: (CaptureMode) -> Unit,
    stop: () -> Unit,
    openRecord: (SessionSummary) -> Unit,
    openActiveRecord: () -> Unit,
    openCourse: (Long) -> Unit,
    courseMenu: @Composable (CourseSummary) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("home-list"),
        contentPadding = ListWithFabPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("quick-start") {
            if (listening.isListening) ActiveClassCard(listening, openActiveRecord, stop)
            else StartClassCard(startCourse, suggestion, listening, processing, pickCourse, start)
        }
        if (recent.isNotEmpty()) {
            item("recent-heading") { HomeSectionTitle("最近课堂") }
            items(recent, key = { "recent-${it.session.id}" }) { summary ->
                SessionRow(
                    summary = summary,
                    showCourse = true,
                    capturing = listening.isListening && listening.activeRecordId == summary.session.id,
                    pendingRecordings = pendingRecordingCounts[summary.session.id] ?: 0,
                    open = { openRecord(summary) },
                    menu = null
                )
            }
        }
        item("courses-heading") { HomeSectionTitle("课程", if (courses.isEmpty()) null else "${courses.size} 门") }
        if (courses.isEmpty()) item("empty-courses") {
            Text("还没有课程。点上方「开始上课」，先给第一门课起个名字就能开始录；以后每次都能直接开始，录完再确认归到哪门课。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(courses, key = { "course-${it.course.id}" }) { summary ->
            CourseRow(summary, open = { openCourse(summary.course.id) }) { courseMenu(summary) }
        }
    }
}

@Composable
private fun StartClassCard(
    course: CourseSummary?,
    suggestion: CourseSuggestion?,
    listening: ListeningUiState,
    processing: OfflineRecognitionState,
    pickCourse: () -> Unit,
    start: (CaptureMode) -> Unit
) {
    val blocked = if (processing.isProcessing) "正在识别录音，暂停或完成后才能开始上课。" else null
    Card(Modifier.fillMaxWidth().testTag("start-class-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开始上课", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AssistChip(
                    onClick = pickCourse,
                    label = { Text(course?.course?.name ?: "选择课程", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Outlined.ExpandMore, contentDescription = null) },
                    modifier = Modifier.testTag("start-course-chip")
                )
            }
            Text(
                when {
                    course == null -> "第一次使用需要先给课程起个名字。"
                    suggestion?.courseId == course.course.id && suggestion.reason == CourseSuggestion.Reason.USUAL_TIME ->
                        "按你平时的上课时间猜的；录完会再确认一次。"
                    else -> "录完会再确认保存到哪门课。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StartModeButton(Modifier.weight(1f).fillMaxHeight(), CaptureMode.REALTIME_ASR, blocked == null, start)
                StartModeButton(Modifier.weight(1f).fillMaxHeight(), CaptureMode.RECORD_ONLY, blocked == null, start)
            }
            (blocked ?: listening.error)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (blocked != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StartModeButton(modifier: Modifier, mode: CaptureMode, enabled: Boolean, start: (CaptureMode) -> Unit) {
    val realtime = mode == CaptureMode.REALTIME_ASR
    CaptureModeButton(
        modifier = modifier.testTag(if (realtime) "home-start-realtime" else "home-start-record-only"),
        icon = if (realtime) Icons.Outlined.Mic else Icons.Outlined.FiberManualRecord,
        title = if (realtime) "实时转写" else "仅录音",
        subtitle = if (realtime) "边录边出文字" else "稍后再识别",
        enabled = enabled,
        emphasized = realtime
    ) { start(mode) }
}

@Composable
private fun ActiveClassCard(listening: ListeningUiState, open: () -> Unit, stop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clickable(onClick = open)) {
            CapturePanel(
                recordId = listening.activeRecordId ?: -1,
                listening = listening,
                processing = OfflineRecognitionState(),
                start = {},
                stop = stop
            )
        }
        TextButton(onClick = open, modifier = Modifier.testTag("open-active-record")) {
            Text("查看「${listening.currentRecordName ?: "当前课堂"}」")
        }
    }
}

@Composable
private fun HomeSectionTitle(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 1.dp))
        }
    }
}

/** A class record row: what, when (with weekday), how much text, and what still needs doing. */
@Composable
internal fun SessionRow(
    summary: SessionSummary,
    showCourse: Boolean,
    capturing: Boolean,
    pendingRecordings: Int,
    open: () -> Unit,
    menu: (@Composable () -> Unit)?
) {
    val session = summary.session
    Card(Modifier.fillMaxWidth().clickable(onClick = open).testTag("session-${session.id}")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (showCourse) summary.courseName else session.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        formatSessionWhen(session.startedAt, session.endedAt),
                        "${summary.segmentCount} 段".takeIf { summary.segmentCount > 0 }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    capturing -> Text("● 正在录制", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    pendingRecordings > 0 -> Text("$pendingRecordings 段录音待识别", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (menu != null) menu() else Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun CourseRow(summary: CourseSummary, open: () -> Unit, menu: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = open).testTag("course-${summary.course.id}")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(summary.course.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (summary.recordCount == 0) "还没有课堂"
                    else "${summary.recordCount} 节课" + (summary.lastStartedAt?.let { " · 最近 ${formatDayWithWeekday(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            menu()
        }
    }
}

/** Overflow menu with labelled actions; replaces rows of small unlabeled icon buttons. */
@Composable
internal fun RowMenu(description: String, actions: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = description) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() })
            }
        }
    }
}

/**
 * Picks a course from a radio list, with "新建课程" at the end. Used before starting (to change
 * the guess) and after stopping (to confirm where the class is filed).
 */
@Composable
internal fun CoursePickerDialog(
    title: String,
    message: String?,
    courses: List<CourseSummary>,
    initialCourseId: Long?,
    confirmLabel: String,
    confirm: (Long) -> Unit,
    createNew: () -> Unit,
    dismiss: () -> Unit,
    dismissLabel: String = "取消"
) {
    var selected by remember(initialCourseId) { mutableStateOf(initialCourseId) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup().testTag("course-picker")) {
                    items(courses, key = { it.course.id }) { summary ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected == summary.course.id, role = Role.RadioButton) { selected = summary.course.id }
                                .testTag("pick-course-${summary.course.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == summary.course.id, onClick = null)
                            Text(summary.course.name, Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item("create-course") {
                        if (courses.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = createNew).testTag("pick-new-course"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.padding(start = 12.dp))
                            Text("新建课程", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(confirm) }, enabled = selected != null, modifier = Modifier.testTag("course-picker-confirm")) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(dismissLabel) } }
    )
}

/** "10月8日 周四 10:20–12:00": weekday matters more than seconds for weekly classes. */
internal fun formatSessionWhen(startedAt: Long, endedAt: Long?): String {
    val start = "${formatDayWithWeekday(startedAt)} ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(startedAt))}"
    val sameDay = endedAt != null && SimpleDateFormat("yyyyMMdd", Locale.CHINA).let { it.format(Date(endedAt)) == it.format(Date(startedAt)) }
    return if (sameDay) "$start–${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(endedAt!!))}" else start
}

internal fun formatDayWithWeekday(epochMs: Long): String = SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date(epochMs))
