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
import androidx.compose.ui.unit.dp
import com.cmhr.listen.AiUiState
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.CourseUiState
import com.cmhr.listen.CourseViewModel
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.TranscriptStatus
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.TranscriptEntity

@Composable
fun CoursesScreen(
    state: CourseUiState,
    listening: ListeningUiState,
    model: CourseViewModel,
    openCourse: (Long) -> Unit
) {
    var warning by remember { mutableStateOf<String?>(null) }
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
                editAsrPrompt = { model.updateCourseAsrPrompt(course.id, it) },
                delete = {
                    if (listening.activeRecordId != null) warning = "监听期间不能删除课程，请先停止监听。"
                    else model.deleteCourse(course.id)
                }
            )
        }
    }
}

@Composable
fun CourseRecordsScreen(
    courseId: Long,
    state: CourseUiState,
    listening: ListeningUiState,
    model: CourseViewModel,
    openRecord: (Long) -> Unit
) {
    var switchMessage by remember { mutableStateOf<String?>(null) }
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
        if (listening.activeRecordId != null) item("active-listening-hint") {
            Text("当前正在监听；停止后才能新建或切换课堂记录。", color = MaterialTheme.colorScheme.primary)
        }
        switchMessage?.let { item("switch-warning") { ErrorCard(it) } }
        if (records.isEmpty()) item("empty-records") { Text("尚无课堂记录，请使用右下角按钮新建。") }
        items(records, key = { "record-${it.id}" }) { record ->
            RecordCard(
                record = record,
                selected = state.selectedRecord?.id == record.id,
                select = {
                    val activeId = listening.activeRecordId
                    if (activeId == null || activeId == record.id) openRecord(record.id)
                    else switchMessage = "当前正在记录 ${listening.currentRecordName ?: "另一条课堂记录"}，请先停止监听。"
                },
                rename = { model.renameRecord(record.id, it) },
                delete = {
                    if (listening.activeRecordId == record.id) switchMessage = "当前记录正在监听，停止后才能删除。"
                    else model.deleteRecord(record.id)
                }
            )
        }
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
    openConversation: (Long) -> Unit
) {
    var debugExpanded by remember { mutableStateOf(false) }
    var showCustomQuestion by remember { mutableStateOf(false) }
    var customQuestion by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<AiActionType?>(null) }
    var pendingActionUsesFullRecord by remember { mutableStateOf(false) }
    var pendingPhotos by remember { mutableStateOf(emptyList<com.cmhr.listen.data.ai.PendingAiPhoto>()) }
    val currentPendingPhotos by rememberUpdatedState(pendingPhotos)
    val record = state.selectedRecord?.takeIf { it.id == recordId }
    val course = record?.let { selected -> state.courses.firstOrNull { it.id == selected.courseId } }
    val segments = state.detailSegments.filter { it.recordId == recordId }
    val temporarySegments = listening.transcriptSegments
        .filter { it.recordId == recordId && it.status != TranscriptStatus.SUCCESS }
        .asReversed()
    val selectedIds = aiState.takeIf { it.selectionRecordId == recordId }?.selectedSegmentIds.orEmpty()
    val selectionMode = aiState.selectionRecordId == recordId
    val isThisRecordListening = listening.activeRecordId == recordId && listening.isListening

    DisposableEffect(recordId) {
        onDispose {
            if (aiModel.uiState.value.selectionRecordId == recordId) aiModel.clearSelection()
            currentPendingPhotos.forEach(aiModel::discardPhoto)
        }
    }

    androidx.compose.runtime.LaunchedEffect(requestedFullAction) {
        requestedFullAction?.let {
            pendingAction = it
            pendingActionUsesFullRecord = true
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
                            pendingAction = action
                            pendingActionUsesFullRecord = false
                        },
                        enabled = !aiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(action.displayName) }
                }
                Button(
                    onClick = { dismissAiActions(); showCustomQuestion = true },
                    enabled = !aiState.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("自定义提问 / 与 AI 对话") }
            }
        }
    }

    pendingAction?.let { action ->
        ModalBottomSheet(onDismissRequest = {
            pendingPhotos.forEach(aiModel::discardPhoto)
            pendingPhotos = emptyList()
            pendingAction = null
        }) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(action.displayName, style = MaterialTheme.typography.titleLarge)
                Text("可选拍摄最多 3 张黑板或课件照片，与课堂原文一起交给 AI。", style = MaterialTheme.typography.bodyMedium)
                PhotoAttachmentEditor(pendingPhotos, aiModel) { pendingPhotos = it }
                Button(
                    onClick = {
                        val submittedPhotos = pendingPhotos
                        pendingPhotos = emptyList()
                        pendingAction = null
                        if (pendingActionUsesFullRecord) {
                            aiModel.runFullRecordAction(recordId, action, segments, submittedPhotos, openResult)
                        } else {
                            aiModel.runFixedAction(recordId, action, segments, submittedPhotos, openResult)
                        }
                    },
                    enabled = !aiState.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("开始处理") }
            }
        }
    }

    if (showCustomQuestion) AlertDialog(
        onDismissRequest = {
            pendingPhotos.forEach(aiModel::discardPhoto)
            pendingPhotos = emptyList()
            showCustomQuestion = false
        },
        title = { Text("向 AI 提问") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customQuestion,
                    onValueChange = { customQuestion = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("问题") },
                    minLines = 3,
                    maxLines = 8
                )
                PhotoAttachmentEditor(pendingPhotos, aiModel) { pendingPhotos = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val submittedPhotos = pendingPhotos
                    pendingPhotos = emptyList()
                    aiModel.createConversation(recordId, customQuestion, segments, submittedPhotos, openConversation)
                    customQuestion = ""
                    showCustomQuestion = false
                },
                enabled = customQuestion.isNotBlank() && !aiState.isBusy
            ) { Text("发送") }
        },
        dismissButton = { TextButton(onClick = {
            pendingPhotos.forEach(aiModel::discardPhoto)
            pendingPhotos = emptyList()
            showCustomQuestion = false
        }) { Text("取消") } }
    )

    LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (record == null || course == null) item("loading-record") { Text("正在加载课堂记录……") }
            else {
                item("record-summary") {
                    RecordDetailCard(course, record, segments, developerMode)
                }
                item("listening-status") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (isThisRecordListening) "正在监听" else "未监听", style = MaterialTheme.typography.titleLarge)
                            if (isThisRecordListening) {
                                Text("识别队列：${listening.pendingQueueCount} · ${if (listening.isRecognizing) "正在识别" else "等待语音"}")
                                Text("当前语音片段：${if (listening.isSpeechDetected) "活动中" else "未活动"}")
                            }
                            Text(if (isThisRecordListening) "可使用全局红色悬浮按钮停止。" else "使用右下角悬浮按钮开始监听。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                listening.error?.let { item("listening-error") { ErrorCard(it) } }
                aiState.error?.let { item("ai-error") { ErrorCard(it) } }
                if (developerMode) {
                    item("vad-debug-header") { ExpandHeader("VAD 调试", debugExpanded) { debugExpanded = !debugExpanded } }
                    if (debugExpanded) item("vad-debug-content") { VadDebugCard(listening) }
                }
                if (temporarySegments.isNotEmpty()) {
                    item("temporary-heading") { Text("实时处理", style = MaterialTheme.typography.titleMedium) }
                    items(temporarySegments, key = { "temporary-${it.id}" }) { RuntimeTranscriptCard(it) }
                }
                item("segment-heading") { Text("识别内容", style = MaterialTheme.typography.titleLarge) }
                if (segments.isEmpty()) item("empty-segments") { Text("该课堂记录暂无识别内容。") }
                items(segments.asReversed(), key = { "segment-${it.id}" }) { segment ->
                    val selected = segment.id in selectedIds
                    SelectableTranscriptCard(segment, selected, selectionMode) {
                        aiModel.toggleSelection(recordId, segment.id)
                    }
                }
            }
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SelectableTranscriptCard(
    segment: TranscriptEntity,
    selected: Boolean,
    selectionMode: Boolean,
    toggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("segment-${segment.id}")
            .semantics { this.selected = selected }
            .combinedClickable(
                onClick = { if (selectionMode) toggle() },
                onLongClick = toggle
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatDateTime(segment.startTime), style = MaterialTheme.typography.titleSmall)
            Text(segment.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CourseCard(
    course: CourseEntity,
    selected: Boolean,
    enter: () -> Unit,
    rename: (String) -> Unit,
    editAsrPrompt: (String) -> Unit,
    delete: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf(false) }
    var name by remember(course.id, course.name) { mutableStateOf(course.name) }
    var prompt by remember(course.id, course.asrPrompt) { mutableStateOf(course.asrPrompt) }
    if (renaming) NameDialog("重命名课程", name, { name = it }, { rename(name); renaming = false }, { renaming = false })
    if (editingPrompt) AsrPromptDialog(
        prompt = prompt,
        update = { prompt = it },
        save = { editAsrPrompt(prompt); editingPrompt = false },
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
    save: () -> Unit,
    dismiss: () -> Unit
) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text("编辑 ASR 提示词") },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("填写本课程的专业词、姓名或术语；从下一个尚未入队的片段开始生效。", style = MaterialTheme.typography.bodySmall)
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
                Text("开始：${formatDateTime(record.startedAt)}", style = MaterialTheme.typography.bodySmall)
                if (selected) Text("当前记录", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
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
    record: ClassRecordEntity,
    segments: List<TranscriptEntity>,
    developerMode: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(course.name, style = MaterialTheme.typography.titleSmall)
            Text(record.name, style = MaterialTheme.typography.titleLarge)
            if (developerMode) {
                Text("开始：${formatDateTime(record.startedAt)}")
                Text("结束：${record.endedAt?.let(::formatDateTime) ?: "进行中"}")
                Text("识别片段：${segments.size} 条")
            }
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
    segments.sortedBy { it.startTime }.forEach {
        appendLine(formatDateTime(it.startTime))
        appendLine(it.text)
        appendLine()
    }
}
