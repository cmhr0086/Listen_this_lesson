package com.cmhr.listen.ui

import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.dp
import com.cmhr.listen.AiUiState
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.CourseUiState
import com.cmhr.listen.CourseViewModel
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.RecordingUiState
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.data.recording.RecordingState
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.TranscriptEntity
import kotlinx.coroutines.launch
import com.cmhr.listen.data.stt.AsrPromptMode

@Composable
fun CourseRecordsScreen(
    courseId: Long,
    state: CourseUiState,
    listening: ListeningUiState,
    model: CourseViewModel,
    pendingRecordingCounts: Map<Long, Int> = emptyMap(),
    startClass: (CaptureMode) -> Unit = {},
    now: Long = System.currentTimeMillis(),
    openRecord: (Long) -> Unit
) {
    var switchMessage by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ClassRecordEntity?>(null) }
    var renameTarget by remember { mutableStateOf<ClassRecordEntity?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var moveTarget by remember { mutableStateOf<ClassRecordEntity?>(null) }
    var editingPrompt by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(courseId) {
        if (state.selectedCourse?.id != courseId) model.enterCourse(courseId)
    }
    val course = state.courseSummaries.firstOrNull { it.course.id == courseId }?.course ?: state.selectedCourse?.takeIf { it.id == courseId }
    val records = state.recordSummaries.filter { it.session.courseId == courseId }
    val totalMs = records.sumOf { r -> r.session.endedAt?.let { it - r.session.startedAt } ?: 0L }
    val groups = remember(records, now) { records.groupBy { weekGroupLabel(it.session.startedAt, now) } }
    var prompt by remember(course?.id, course?.asrPrompt) { mutableStateOf(course?.asrPrompt.orEmpty()) }
    var promptMode by remember(course?.id, course?.asrPromptModeOverride) { mutableStateOf(course?.asrPromptModeOverride) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("course-records-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (course != null) item("course-header") {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CourseBadge(course.id, course.name, 56.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(course.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            listOfNotNull("${records.size} 节课", formatSpanMinutes(totalMs)?.let { "共 $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val canStart = !listening.isListening && listening.pausedClass == null
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { startClass(CaptureMode.REALTIME_ASR) }, enabled = canStart, modifier = Modifier.weight(1f).testTag("course-start-realtime")) {
                        Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("实时转写")
                    }
                    OutlinedButton(onClick = { startClass(CaptureMode.RECORD_ONLY) }, enabled = canStart, modifier = Modifier.weight(1f).testTag("course-start-record-only")) {
                        Icon(Icons.Outlined.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Text("仅录音")
                    }
                }
                ListGroup {
                    val terms = course.asrPrompt.split(Regex("[，,、；;\\s]+")).count { it.isNotBlank() }
                    ListRow(
                        title = "专业词提示",
                        subtitle = if (terms == 0) "还没有专业词；填上人名和术语，识别会更准" else "$terms 个专业词",
                        leading = { Icon(Icons.Outlined.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { editingPrompt = true },
                        modifier = Modifier.testTag("course-asr-prompt")
                    )
                }
            }
        }
        if (listening.isListening && listening.activeRecordId != null) item("active-listening-hint") {
            Text("「${listening.currentRecordName ?: "当前课堂"}」正在录制，停止后才能打开其他课堂记录。", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        }
        switchMessage?.let { item("switch-warning") { ErrorCard(it) } }
        if (records.isEmpty()) item("empty-records") {
            Text("还没有课堂。点上方按钮开始第一节课。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
        groups.forEach { (label, rows) ->
            item("week-$label") { SectionHeader(label, Modifier.padding(top = 6.dp)) }
            item("week-group-$label") {
                ListGroup {
                    rows.forEachIndexed { index, summary ->
                        if (index > 0) ListDivider(68.dp)
                        val record = summary.session
                        val capturing = listening.isListening && listening.activeRecordId == record.id
                        SessionRow(
                            summary = summary,
                            showCourse = false,
                            capturing = capturing,
                            paused = !listening.isListening && listening.pausedClass?.recordId == record.id,
                            pendingRecordings = pendingRecordingCounts[record.id] ?: 0,
                            open = {
                                val activeId = listening.activeRecordId
                                if (activeId == null || activeId == record.id) openRecord(record.id)
                                else switchMessage = "「${listening.currentRecordName ?: "另一条课堂记录"}」正在录制，请先停止。"
                            },
                            menu = {
                                RowMenu(
                                    description = "${record.name} 的操作",
                                    actions = listOf(
                                        "重命名" to { renameDraft = record.name; renameTarget = record },
                                        "移到其他课程" to {
                                            if (capturing) switchMessage = "这条课堂记录正在录制，停止后才能移动。" else moveTarget = record
                                        },
                                        "删除" to {
                                            if (capturing) switchMessage = "这条课堂记录正在录制，停止后才能删除。" else deleteTarget = record
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
    if (editingPrompt && course != null) AsrPromptDialog(
        prompt = prompt,
        update = { prompt = it },
        modeOverride = promptMode,
        updateMode = { promptMode = it },
        save = {
            model.updateCourseAsrPrompt(course.id, prompt)
            model.updateCourseAsrPromptMode(course.id, promptMode)
            editingPrompt = false
        },
        dismiss = { editingPrompt = false }
    )
    renameTarget?.let { record ->
        NameDialog("重命名课堂记录", renameDraft, { renameDraft = it }, { model.renameRecord(record.id, renameDraft); renameTarget = null }, { renameTarget = null })
    }
    moveTarget?.let { record ->
        CoursePickerDialog(
            title = "移到哪门课？",
            message = "文字、录音和 AI 内容会一起移动。",
            courses = state.courseSummaries,
            initialCourseId = record.courseId,
            confirmLabel = "移动",
            confirm = { model.moveRecord(record.id, it); moveTarget = null },
            createNew = {
                moveTarget = null
                switchMessage = "请先在首页新建课程，再回来移动。"
            },
            dismiss = { moveTarget = null }
        )
    }
    deleteTarget?.let { record ->
        TimedDeleteDialog(
            title = "删除课堂记录",
            message = "将删除“${record.name}”及其识别内容和 AI 内容。",
            confirm = { model.deleteRecord(record.id); deleteTarget = null },
            dismiss = { deleteTarget = null }
        )
    }
}

@Composable
internal fun SectionHeading(title: String, detail: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }
    }
}

/** "⋮" for a course: ASR 提示词 / 重命名 / 删除, each with its dialog. */
@Composable
internal fun CourseMenu(course: CourseEntity, listening: ListeningUiState, model: CourseViewModel) {
    var renaming by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pickingColor by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    var name by remember(course.id, course.name) { mutableStateOf(course.name) }
    var prompt by remember(course.id, course.asrPrompt) { mutableStateOf(course.asrPrompt) }
    var promptMode by remember(course.id, course.asrPromptModeOverride) { mutableStateOf(course.asrPromptModeOverride) }
    RowMenu(
        description = "${course.name} 的操作",
        actions = listOf(
            "重命名" to { renaming = true },
            "更换颜色" to { pickingColor = true },
            "专业词提示" to { editingPrompt = true },
            "删除课程" to {
                if (listening.isListening) warning = "正在录制时不能删除课程，请先停止。" else deleting = true
            }
        )
    )
    if (renaming) NameDialog("重命名课程", name, { name = it }, { model.renameCourse(course.id, name); renaming = false }, { renaming = false })
    if (pickingColor) CourseColorDialog(
        course = course,
        current = CourseColors.indexFor(course.id, LocalCourseColors.current),
        pick = { model.setCourseColor(course.id, it); pickingColor = false },
        dismiss = { pickingColor = false }
    )
    if (editingPrompt) AsrPromptDialog(
        prompt = prompt,
        update = { prompt = it },
        modeOverride = promptMode,
        updateMode = { promptMode = it },
        save = {
            model.updateCourseAsrPrompt(course.id, prompt)
            model.updateCourseAsrPromptMode(course.id, promptMode)
            editingPrompt = false
        },
        dismiss = { editingPrompt = false }
    )
    if (deleting) TimedDeleteDialog(
        title = "删除课程",
        message = "将删除“${course.name}”及其所有课堂记录、识别内容和 AI 内容。",
        confirm = { model.deleteCourse(course.id); deleting = false },
        dismiss = { deleting = false }
    )
    warning?.let { message ->
        AlertDialog(
            onDismissRequest = { warning = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { warning = null }) { Text("知道了") } }
        )
    }
}

@Composable
internal fun AsrPromptDialog(
    prompt: String,
    update: (String) -> Unit,
    modeOverride: String?,
    updateMode: (String?) -> Unit,
    save: () -> Unit,
    dismiss: () -> Unit
) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text("编辑 ASR 提示词") },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("填写本课程的专业词、姓名或术语；从下一个尚未入队的片段开始生效。", style = MaterialTheme.typography.bodySmall)
            Text("课程提示词模式", style = MaterialTheme.typography.titleSmall)
            val promptModes = listOf<String?>(null) + AsrPromptMode.entries.map { it.name }
            HorizontalChoiceSelector(
                options = promptModes,
                selected = modeOverride,
                onSelect = updateMode,
                label = { mode -> mode?.let { AsrPromptMode.valueOf(it).displayName } ?: "跟随全局" },
                testTag = "course-asr-prompt-modes",
                optionTestTag = { "course-asr-prompt-${it?.lowercase() ?: "follow-global"}" }
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = update,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("专业词 / ASR Context") },
                supportingText = { Text("${prompt.codePointCount(0, prompt.length)} 个字符") },
                minLines = 4,
                maxLines = 10
            )
        }
    },
    confirmButton = { TextButton(onClick = save) { Text("保存") } },
    dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
)

/** The record name is in the top bar; this line only adds course and time. */
@Composable
private fun RecordDetailCard(
    course: CourseEntity,
    record: ClassRecordEntity
) {
    Text(
        "${course.name} · ${formatRecordSpan(record.startedAt, record.endedAt)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("record-summary-line")
    )
}

@Composable
internal fun ErrorCard(message: String) = GroupCard(Modifier.fillMaxWidth()) {
    Text("提示：$message", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
}

internal fun buildTxt(course: CourseEntity, record: ClassRecordEntity, segments: List<TranscriptEntity>) = buildString {
    appendLine("课程：${course.name}")
    appendLine("记录：${record.name}")
    appendLine("开始时间：${formatDateTime(record.startedAt)}")
    appendLine("结束时间：${record.endedAt?.let(::formatDateTime) ?: "进行中"}")
    appendLine()
    AiViewModel.orderTranscriptSegments(segments).forEach {
        appendLine(formatDateTime(it.startTime))
        appendLine(it.effectiveText)
        appendLine()
    }
}

/** 本周 / 上周 / 「10月」: weekly classes read best grouped by week, older ones by month. */
internal fun weekGroupLabel(startedAt: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
    val weekStart = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(java.time.DayOfWeek.MONDAY)
    return when {
        !day.isBefore(weekStart) -> "本周"
        !day.isBefore(weekStart.minusWeeks(1)) -> "上周"
        else -> "${day.monthValue}月"
    }
}

/** Picks one of the course colors; the badge previews the choice. */
@Composable
private fun CourseColorDialog(course: CourseEntity, current: Int, pick: (Int) -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("「${course.name}」的颜色") },
        text = {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(CourseColors.count) { index ->
                    val tone = CourseColors.tone(index, isDarkSurface())
                    androidx.compose.material3.Surface(
                        onClick = { pick(index) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = tone.background,
                        border = if (index == current) androidx.compose.foundation.BorderStroke(2.dp, tone.foreground) else null,
                        modifier = Modifier.size(52.dp).testTag("course-color-$index")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(courseInitial(course.name), color = tone.foreground, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("完成") } }
    )
}
