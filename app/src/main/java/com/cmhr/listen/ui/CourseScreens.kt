package com.cmhr.listen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.data.stt.AsrPromptMode

@Composable
fun CoursesScreen(
    state: CourseUiState,
    listening: ListeningUiState,
    model: CourseViewModel,
    openCourse: (Long) -> Unit
) {
    var warning by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CourseEntity?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        warning?.let { message ->
            item("course-warning") { ErrorCard(message) }
        }
        if (state.courses.isEmpty()) item("empty-courses") { Text("尚未创建课程，请使用右下角按钮新建。") }
        items(state.courses, key = { "course-${it.id}" }) { course ->
            CourseCard(
                course = course,
                selected = state.selectedCourse?.id == course.id,
                enter = { openCourse(course.id) },
                rename = { model.renameCourse(course.id, it) },
                editAsrPrompt = { prompt, mode ->
                    model.updateCourseAsrPrompt(course.id, prompt)
                    model.updateCourseAsrPromptMode(course.id, mode)
                },
                delete = {
                    if (listening.activeRecordId != null) warning = "监听期间不能删除课程，请先停止监听。"
                    else deleteTarget = course
                }
            )
        }
    }
    deleteTarget?.let { course ->
        TimedDeleteDialog(
            title = "删除课程",
            message = "将删除“${course.name}”及其所有课堂记录、识别内容和 AI 内容。",
            confirm = { model.deleteCourse(course.id); deleteTarget = null },
            dismiss = { deleteTarget = null }
        )
    }
}

@Composable
fun CourseRecordsScreen(
    courseId: Long,
    state: CourseUiState,
    listening: ListeningUiState,
    model: CourseViewModel,
    pendingRecordingCounts: Map<Long, Int> = emptyMap(),
    openRecord: (Long) -> Unit
) {
    var switchMessage by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ClassRecordEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(courseId) {
        if (state.selectedCourse?.id != courseId) model.enterCourse(courseId)
    }
    val course = state.courses.firstOrNull { it.id == courseId }
        ?: state.selectedCourse?.takeIf { it.id == courseId }
    val records = state.records.filter { it.courseId == courseId }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("course-name") { Text(course?.name ?: "课程", style = MaterialTheme.typography.titleLarge) }
        if (listening.isListening && listening.activeRecordId != null) item("active-listening-hint") {
            Text("「${listening.currentRecordName ?: "当前课堂"}」正在录制，停止后才能打开其他课堂记录。", color = MaterialTheme.colorScheme.primary)
        }
        switchMessage?.let { item("switch-warning") { ErrorCard(it) } }
        if (records.isEmpty()) item("empty-records") { Text("尚无课堂记录，请使用右下角按钮新建。") }
        items(records, key = { "record-${it.id}" }) { record ->
            RecordCard(
                record = record,
                selected = state.selectedRecord?.id == record.id,
                capturing = listening.isListening && listening.activeRecordId == record.id,
                pendingRecordings = pendingRecordingCounts[record.id] ?: 0,
                select = {
                    val activeId = listening.activeRecordId
                    if (activeId == null || activeId == record.id) openRecord(record.id)
                    else switchMessage = "「${listening.currentRecordName ?: "另一条课堂记录"}」正在录制，请先停止。"
                },
                rename = { model.renameRecord(record.id, it) },
                delete = {
                    if (listening.activeRecordId == record.id) switchMessage = "这条课堂记录正在录制，停止后才能删除。"
                    else deleteTarget = record
                }
            )
        }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordDetailsScreen(
    recordId: Long,
    state: CourseUiState,
    listening: ListeningUiState,
    developerMode: Boolean,
    aiState: AiUiState,
    aiModel: AiViewModel,
    showAiActions: Boolean,
    dismissAiActions: () -> Unit,
    requestedFullAction: AiActionType?,
    consumeFullAction: () -> Unit,
    openResult: (Long) -> Unit,
    openConversation: (Long) -> Unit,
    recordings: RecordingUiState,
    startCapture: (CaptureMode) -> Unit,
    stopCapture: () -> Unit,
    startOfflineRecognition: (String) -> Unit,
    stopOfflineRecognition: () -> Unit,
    deleteRecording: (String) -> Unit
) {
    val record = state.selectedRecord?.takeIf { it.id == recordId }
    val course = record?.let { selected -> state.courses.firstOrNull { it.id == selected.courseId } }
    val segments = state.detailSegments.filter { it.recordId == recordId }
    val selectedIds = aiState.takeIf { it.selectionRecordId == recordId }?.selectedSegmentIds.orEmpty()
    val selectionMode = aiState.selectionRecordId == recordId
    val displayedSegments = segments.asReversed()
    val listState = rememberLazyListState()
    val dragSelection = rememberDragSelectionController(
        listState = listState,
        orderedKeys = displayedSegments.map { it.id },
        selectedKeys = selectedIds,
        onSelectionChanged = { aiModel.replaceSelection(recordId, it) }
    )

    DisposableEffect(recordId) {
        onDispose {
            if (aiModel.uiState.value.selectionRecordId == recordId) aiModel.clearSelection()
        }
    }

    androidx.compose.runtime.LaunchedEffect(requestedFullAction) {
        requestedFullAction?.let {
            aiModel.runFullRecordAction(recordId, it, segments, onCreated = openResult)
            consumeFullAction()
        }
    }

    if (showAiActions) {
        ModalBottomSheet(onDismissRequest = dismissAiActions) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("AI 处理", style = MaterialTheme.typography.titleLarge)
                listOf(AiActionType.CORRECT_ASR, AiActionType.QUICK_ANSWER).forEach { action ->
                    OutlinedButton(
                        onClick = {
                            dismissAiActions()
                            aiModel.runFixedAction(recordId, action, segments, onCreated = openResult)
                        },
                        enabled = !aiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(action.displayName) }
                }
                Button(
                    onClick = {
                        dismissAiActions()
                        aiModel.createConversationDraft(recordId, segments, openConversation)
                    },
                    enabled = !aiState.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("自定义提问 / 与 AI 对话") }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().dragSelectionViewport(dragSelection),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (record == null || course == null) item("loading-record") { Text("正在加载课堂记录……") }
            else {
                item("record-summary") {
                    RecordDetailCard(course, record)
                }
                item("capture-panel") {
                    CapturePanel(recordId, listening, recordings.processing, startCapture, stopCapture)
                }
                aiState.error?.let { item("ai-error") { ErrorCard(it) } }
                if (recordings.recordings.isNotEmpty()) {
                    item("recordings-heading") {
                        SectionHeading("录音", "${recordings.recordings.size} 段 · 共 ${formatClockDuration(recordings.recordings.totalDurationMs())}")
                    }
                    val recognitionAllowed = !listening.isListening && !recordings.processing.isProcessing
                    val numbered = recordings.recordings.sortedBy { it.startedAt }.withIndex().associate { (index, value) -> value.recordingId to index + 1 }
                    items(recordings.recordings, key = { "recording-${it.recordingId}" }) { recording ->
                        RecordingItem(
                            recording = recording,
                            number = numbered.getValue(recording.recordingId),
                            processing = recordings.processing,
                            recognitionAllowed = recognitionAllowed,
                            startRecognition = startOfflineRecognition,
                            pauseRecognition = stopOfflineRecognition,
                            delete = deleteRecording
                        )
                    }
                }
                item("segment-heading") { SectionHeading("文字", if (segments.isEmpty()) null else "${segments.size} 段") }
                if (segments.isEmpty()) item("empty-segments") {
                    Text(
                        if (recordings.recordings.isEmpty()) "还没有文字。用「实时转写」上课，或先「仅录音」再识别。"
                        else "还没有文字。点上方录音的「开始识别」后，文字会出现在这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(displayedSegments, key = { "segment-${it.id}" }) { segment ->
                    val selected = segment.id in selectedIds
                    SelectableTranscriptCard(
                        segment = segment,
                        selected = selected,
                        selectionMode = selectionMode,
                        developerMode = false,
                        dragSelectionEnabled = true,
                        modifier = Modifier.dragSelectableItem(segment.id, dragSelection),
                        toggle = { aiModel.toggleSelection(recordId, segment.id) },
                        restoreOriginal = { aiModel.restoreOriginal(segment.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, detail: String?) {
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

@Composable
internal fun SelectableTranscriptCard(
    segment: TranscriptEntity,
    selected: Boolean,
    selectionMode: Boolean,
    developerMode: Boolean = false,
    dragSelectionEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    restoreOriginal: () -> Unit = {},
    toggle: () -> Unit
) {
    var showCorrection by remember(segment.id) { mutableStateOf(false) }
    if (showCorrection && segment.correctedText != null) {
        AlertDialog(
            onDismissRequest = { showCorrection = false },
            title = { Text("AI 纠错对照") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("原始 ASR", style = MaterialTheme.typography.labelLarge)
                    Text(segment.text)
                    Text("当前纠正文", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(segment.correctedText)
                }
            },
            confirmButton = {
                TextButton(onClick = { restoreOriginal(); showCorrection = false }) { Text("恢复原文") }
            },
            dismissButton = { TextButton(onClick = { showCorrection = false }) { Text("关闭") } }
        )
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("segment-${segment.id}")
            .semantics { this.selected = selected }
            .semantics { onLongClick("选择片段") { toggle(); true } }
            .then(
                if (dragSelectionEnabled) Modifier.clickable(enabled = selectionMode) { toggle() }
                else Modifier.combinedClickable(
                    onClick = { if (selectionMode) toggle() },
                    onLongClick = toggle
                )
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatDateTime(segment.startTime), style = MaterialTheme.typography.titleSmall)
            if (developerMode) {
                Text(
                    "开始：${formatDateTime(segment.startTime)}  ·  结束：${formatDateTime(segment.endTime)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "音频：${formatClockDuration(segment.audioDurationMs)}  ·  ASR：${segment.recognitionDurationMs?.let(::formatDuration) ?: "—"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(segment.effectiveText, style = MaterialTheme.typography.bodyLarge)
            if (segment.correctedText != null) {
                TextButton(
                    onClick = { showCorrection = true },
                    enabled = !selectionMode,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("AI 已纠错") }
            }
        }
    }
}

@Composable
private fun CourseCard(
    course: CourseEntity,
    selected: Boolean,
    enter: () -> Unit,
    rename: (String) -> Unit,
    editAsrPrompt: (String, String?) -> Unit,
    delete: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf(false) }
    var name by remember(course.id, course.name) { mutableStateOf(course.name) }
    var prompt by remember(course.id, course.asrPrompt) { mutableStateOf(course.asrPrompt) }
    var promptMode by remember(course.id, course.asrPromptModeOverride) { mutableStateOf(course.asrPromptModeOverride) }
    if (renaming) NameDialog("重命名课程", name, { name = it }, { rename(name); renaming = false }, { renaming = false })
    if (editingPrompt) AsrPromptDialog(
        prompt = prompt,
        update = { prompt = it },
        modeOverride = promptMode,
        updateMode = { promptMode = it },
        save = { editAsrPrompt(prompt, promptMode); editingPrompt = false },
        dismiss = { editingPrompt = false }
    )
    Card(Modifier.fillMaxWidth().clickable(onClick = enter)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium)
                if (selected) Text("当前课程", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (course.asrPrompt.isNotBlank()) Text("已配置 ASR 提示词", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { editingPrompt = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Description, contentDescription = "编辑 ASR 提示词")
            }
            IconButton(onClick = { renaming = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "重命名课程")
            }
            IconButton(onClick = delete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除课程", tint = MaterialTheme.colorScheme.error)
            }
        }
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

@Composable
private fun RecordCard(
    record: ClassRecordEntity,
    selected: Boolean,
    capturing: Boolean,
    pendingRecordings: Int,
    select: () -> Unit,
    rename: (String) -> Unit,
    delete: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember(record.id, record.name) { mutableStateOf(record.name) }
    if (editing) NameDialog("重命名课堂记录", name, { name = it }, { rename(name); editing = false }, { editing = false })
    Card(Modifier.fillMaxWidth().clickable(onClick = select)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(record.name, style = MaterialTheme.typography.titleMedium)
                Text(formatDateTime(record.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    capturing -> Text("● 正在录制", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    pendingRecordings > 0 -> Text("$pendingRecordings 段录音待识别", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                    selected -> Text("上次打开", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = { editing = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "重命名课堂记录")
            }
            IconButton(onClick = delete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除课堂记录", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RecordDetailCard(
    course: CourseEntity,
    record: ClassRecordEntity
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(course.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(record.name, style = MaterialTheme.typography.titleLarge)
            Text(
                formatRecordSpan(record.startedAt, record.endedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) = Card(Modifier.fillMaxWidth()) {
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
