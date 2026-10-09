package com.cmhr.listen.ui

import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.OutlinedButton
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
internal val ListWithFabPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 104.dp)

/**
 * 录音 tab: start a class first, file it under a course afterwards. The course is only a guess at
 * start time (the user confirms when recording stops), so starting never requires navigation.
 */
@Composable
fun RecordHomeScreen(
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
    pause: () -> Unit = {},
    resume: () -> Unit = {},
    now: Long = System.currentTimeMillis()
) {
    val groups = remember(recent, now) { recent.groupBy { recentGroupLabel(it.session.startedAt, now) } }
    LazyColumn(
        Modifier.fillMaxSize().testTag("record-home-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("quick-start") {
            val paused = listening.pausedClass
            when {
                listening.isListening -> ActiveClassCard(listening, openActiveRecord, pause, stop)
                paused != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PausedCapturePanel(paused, resume, stop, title = "已暂停「${paused.recordName ?: "当前课堂"}」")
                    TextButton(onClick = openActiveRecord, modifier = Modifier.testTag("open-paused-record")) { Text("查看这节课") }
                }
                else -> StartClassCard(startCourse, suggestion, listening, processing, pickCourse, start)
            }
        }
        if (recent.isEmpty()) item("empty-recent") {
            Text(
                "录过的课会按时间显示在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
        groups.forEach { (label, sessions) ->
            item("recent-heading-$label") { SectionHeader(label, Modifier.padding(top = 6.dp)) }
            item("recent-group-$label") {
                ListGroup {
                    sessions.forEachIndexed { index, summary ->
                        if (index > 0) ListDivider()
                        SessionRow(
                            summary = summary,
                            showCourse = true,
                            capturing = listening.isListening && listening.activeRecordId == summary.session.id,
                            paused = !listening.isListening && listening.pausedClass?.recordId == summary.session.id,
                            pendingRecordings = pendingRecordingCounts[summary.session.id] ?: 0,
                            open = { openRecord(summary) },
                            menu = null
                        )
                    }
                }
            }
        }
    }
}

/** 今天 / 本周 / 更早, so a long history still opens on this week's classes. */
internal fun recentGroupLabel(startedAt: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val weekStart = today.with(java.time.DayOfWeek.MONDAY)
    return when {
        day == today -> "今天"
        !day.isBefore(weekStart) && day.isBefore(today) -> "本周"
        else -> "更早"
    }
}

/** 课程 tab: every course, most recently attended first. */
@Composable
fun CoursesTabScreen(
    courses: List<CourseSummary>,
    openCourse: (Long) -> Unit,
    courseMenu: @Composable (CourseSummary) -> Unit,
    pendingByCourse: Map<Long, Int> = emptyMap()
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("courses-tab-list"),
        contentPadding = ListWithFabPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (courses.isEmpty()) item("empty-courses") {
            Text(
                "还没有课程。在「录音」页开始上课时起个名字，或用右下角按钮新建。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp)
            )
        } else {
            item("courses-heading") { SectionHeader("按最近上课排序") }
            item("courses-group") {
                ListGroup {
                    courses.forEachIndexed { index, summary ->
                        if (index > 0) ListDivider(72.dp)
                        CourseRow(summary, pendingByCourse[summary.course.id] ?: 0, open = { openCourse(summary.course.id) }) { courseMenu(summary) }
                    }
                }
            }
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
    Surface(
        Modifier.fillMaxWidth().testTag("start-class-card"),
        shape = RoundedCornerShape(24.dp),
        color = groupColor(),
        border = groupBorder()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(
                onClick = pickCourse,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("start-course-chip")
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (course != null) CourseBadge(course.course.id, course.course.name)
                    else Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(course?.course?.name ?: "选择课程", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                course == null -> "先给课程起个名字"
                                suggestion?.courseId == course.course.id && suggestion.reason == CourseSuggestion.Reason.USUAL_TIME ->
                                    "按你平时的上课时间猜的 · 点此更换"
                                else -> "点此更换课程"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StartModeButton(Modifier.weight(1f), CaptureMode.REALTIME_ASR, blocked == null, start)
                StartModeButton(Modifier.weight(1f), CaptureMode.RECORD_ONLY, blocked == null, start)
            }
            Text(
                blocked ?: listening.error ?: "录完会再确认保存到哪门课。",
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked == null && listening.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StartModeButton(modifier: Modifier, mode: CaptureMode, enabled: Boolean, start: (CaptureMode) -> Unit) {
    val realtime = mode == CaptureMode.REALTIME_ASR
    val tag = if (realtime) "home-start-realtime" else "home-start-record-only"
    val content: @Composable () -> Unit = {
        if (realtime) Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
        else Box(Modifier.size(12.dp).clip(CircleShape).background(if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(8.dp))
        Text(if (realtime) "实时转写" else "仅录音", style = MaterialTheme.typography.titleMedium)
    }
    if (realtime) Button(onClick = { start(mode) }, enabled = enabled, modifier = modifier.height(56.dp).testTag(tag), shape = RoundedCornerShape(18.dp)) { content() }
    else OutlinedButton(onClick = { start(mode) }, enabled = enabled, modifier = modifier.height(56.dp).testTag(tag), shape = RoundedCornerShape(18.dp)) { content() }
}

@Composable
private fun ActiveClassCard(listening: ListeningUiState, open: () -> Unit, pause: () -> Unit, stop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clickable(onClick = open)) {
            CapturePanel(
                recordId = listening.activeRecordId ?: -1,
                listening = listening,
                processing = OfflineRecognitionState(),
                start = {},
                stop = stop,
                pause = pause
            )
        }
        TextButton(onClick = open, modifier = Modifier.testTag("open-active-record")) {
            Text("查看「${listening.currentRecordName ?: "当前课堂"}」")
        }
    }
}

/**
 * A class in a list. On home it leads with the course badge; inside a course (where the course is
 * implied) it leads with the date, which is what you scan for there.
 */
@Composable
internal fun SessionRow(
    summary: SessionSummary,
    showCourse: Boolean,
    capturing: Boolean,
    pendingRecordings: Int,
    open: () -> Unit,
    menu: (@Composable () -> Unit)?,
    paused: Boolean = false
) {
    val session = summary.session
    // Home names the course and number; inside a course the number and topic say enough.
    val title = if (showCourse) classTitle(summary.courseName, summary.classNumber, null, session.name)
    else classTitle(null, summary.classNumber, session.topic, session.name)
    ListRow(
        title = title,
        subtitle = sessionSubtitle(summary, includeDay = showCourse, includeTopic = showCourse),
        modifier = Modifier.testTag("session-${session.id}"),
        leading = {
            if (showCourse) CourseBadge(session.courseId, summary.courseName, 38.dp) else DateBlock(session.startedAt)
        },
        status = when {
            capturing -> ({ StatusPill("录制中", StatusTone.PROBLEM) })
            paused -> ({ StatusPill("已暂停", StatusTone.READY) })
            pendingRecordings > 0 -> ({ StatusPill("$pendingRecordings 段待识别", StatusTone.READY) })
            else -> null
        },
        trailing = menu ?: { ChevronIcon() },
        onClick = open
    )
}

/** "函数的极限 · 周二 10:05–11:40 · 笔记 · 2 处重点"; today's classes leave out the day. */
internal fun sessionSubtitle(summary: SessionSummary, includeDay: Boolean, includeTopic: Boolean = false, now: Long = System.currentTimeMillis()): String {
    val session = summary.session
    val time = SimpleDateFormat("HH:mm", Locale.CHINA)
    val day = when {
        !includeDay -> ""
        recentGroupLabel(session.startedAt, now) == "今天" -> ""
        recentGroupLabel(session.startedAt, now) == "本周" -> SimpleDateFormat("EEE ", Locale.CHINA).format(Date(session.startedAt))
        else -> SimpleDateFormat("M月d日 ", Locale.CHINA).format(Date(session.startedAt))
    }
    val span = "$day${time.format(Date(session.startedAt))}" + (session.endedAt?.let { "–${time.format(Date(it))}" } ?: "")
    return listOfNotNull(
        session.topic?.takeIf { includeTopic && it.isNotBlank() },
        span,
        when {
            summary.noteCount > 0 -> "笔记"
            summary.segmentCount > 0 -> "${summary.segmentCount} 段文字"
            else -> null
        },
        summary.markCount.takeIf { it > 0 }?.let { "$it 处重点" }
    ).joinToString(" · ")
}

internal fun formatSpanMinutes(durationMs: Long): String? {
    val minutes = (durationMs / 60_000).toInt()
    return when {
        minutes <= 0 -> null
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}

@Composable
private fun DateBlock(at: Long) {
    Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(SimpleDateFormat("EEE", Locale.CHINA).format(Date(at)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(SimpleDateFormat("d", Locale.CHINA).format(Date(at)), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun CourseRow(summary: CourseSummary, pendingRecordings: Int, open: () -> Unit, menu: @Composable () -> Unit) {
    ListRow(
        title = summary.course.name,
        subtitle = if (summary.recordCount == 0) "还没有课堂"
        else "${summary.recordCount} 节课" + (summary.lastStartedAt?.let { " · 最近 ${formatDayWithWeekday(it)}" } ?: ""),
        modifier = Modifier.testTag("course-${summary.course.id}"),
        leading = { CourseBadge(summary.course.id, summary.course.name, 44.dp) },
        status = if (pendingRecordings > 0) ({ StatusPill("$pendingRecordings 段待识别", StatusTone.READY) }) else null,
        trailing = menu,
        onClick = open
    )
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
                            CourseBadge(summary.course.id, summary.course.name, 28.dp, Modifier.padding(start = 8.dp))
                            Text(summary.course.name, Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
