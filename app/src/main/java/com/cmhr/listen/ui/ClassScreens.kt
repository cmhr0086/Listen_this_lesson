package com.cmhr.listen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmhr.listen.AiUiState
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.CourseUiState
import com.cmhr.listen.ListeningUiState
import com.cmhr.listen.RecordingUiState
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.course.SessionSummary
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.recording.CaptureMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class ClassTab(val label: String) { TEXT("文字"), NOTES("笔记"), QA("问答") }

/**
 * A class is one page with three tabs: its text, the notes made from it, and the questions asked
 * about it. AI work happens where the text is (select → act) and its results stay in this class.
 */
@Composable
fun RecordDetailsScreen(
    recordId: Long,
    state: CourseUiState,
    listening: ListeningUiState,
    developerMode: Boolean,
    aiState: AiUiState,
    aiModel: AiViewModel,
    openResult: (Long) -> Unit,
    openConversation: (Long) -> Unit,
    recordings: RecordingUiState,
    startCapture: (CaptureMode) -> Unit,
    stopCapture: () -> Unit,
    pauseCapture: () -> Unit = {},
    resumeCapture: () -> Unit = {},
    recognizeAll: () -> Unit = {},
    startOfflineRecognition: (String) -> Unit,
    stopOfflineRecognition: () -> Unit,
    deleteRecording: (String) -> Unit,
    markMoment: () -> Unit = {},
    setMarked: (Set<Long>, Boolean) -> Unit = { _, _ -> },
    copySelection: () -> Unit = {},
    deleteSelection: () -> Unit = {},
    summary: SessionSummary? = null
) {
    var tab by rememberSaveable(recordId) { mutableStateOf(ClassTab.TEXT) }
    val record = state.selectedRecord?.takeIf { it.id == recordId }
    val segments = state.detailSegments.filter { it.recordId == recordId }
    val selectionMode = aiState.selectionRecordId == recordId
    val conversations by remember(recordId) { aiModel.conversations(recordId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    // Selection belongs to the text tab; leaving it ends the selection.
    LaunchedEffect(tab) { if (tab != ClassTab.TEXT && aiModel.uiState.value.selectionRecordId == recordId) aiModel.clearSelection() }

    Column(Modifier.fillMaxSize()) {
        if (!selectionMode) SecondaryTabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("class-tabs")
        ) {
            ClassTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    modifier = Modifier.testTag("class-tab-${entry.name.lowercase()}"),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.label, fontWeight = if (tab == entry) FontWeight.Bold else FontWeight.Medium)
                            val count = when (entry) {
                                ClassTab.QA -> conversations.size
                                else -> 0
                            }
                            if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ClassTab.TEXT -> ClassTextTab(
                    recordId, record != null, segments, listening, aiState, aiModel, recordings,
                    startCapture, stopCapture, pauseCapture, resumeCapture, recognizeAll,
                    startOfflineRecognition, stopOfflineRecognition, deleteRecording, markMoment
                )
                ClassTab.NOTES -> ClassNotesTab(recordId, segments, aiState, aiModel, openResult)
                ClassTab.QA -> ClassQaTab(recordId, segments, conversations, aiState, aiModel, openConversation)
            }
        }
        if (selectionMode && tab == ClassTab.TEXT) {
            val selected = aiState.selectedSegmentIds
            val allMarked = selected.isNotEmpty() && segments.filter { it.id in selected }.all { it.marked }
            SelectionActionBar(
                count = selected.size,
                aiEnabled = selected.isNotEmpty() && !aiState.isBusy,
                allMarked = allMarked,
                copy = copySelection,
                askAi = { aiModel.createConversationDraft(recordId, segments, openConversation) },
                toggleMark = { setMarked(selected, !allMarked) },
                toNotes = {
                    aiModel.runFixedAction(recordId, AiActionType.ORGANIZE_NOTES, segments) { }
                    tab = ClassTab.NOTES
                },
                correct = { aiModel.runFixedAction(recordId, AiActionType.CORRECT_ASR, segments, onCreated = openResult) },
                delete = deleteSelection
            )
        }
    }
}

@Composable
private fun ClassTextTab(
    recordId: Long,
    recordLoaded: Boolean,
    segments: List<TranscriptEntity>,
    listening: ListeningUiState,
    aiState: AiUiState,
    aiModel: AiViewModel,
    recordings: RecordingUiState,
    startCapture: (CaptureMode) -> Unit,
    stopCapture: () -> Unit,
    pauseCapture: () -> Unit,
    resumeCapture: () -> Unit,
    recognizeAll: () -> Unit,
    startOfflineRecognition: (String) -> Unit,
    stopOfflineRecognition: () -> Unit,
    deleteRecording: (String) -> Unit,
    markMoment: () -> Unit
) {
    val selectedIds = aiState.takeIf { it.selectionRecordId == recordId }?.selectedSegmentIds.orEmpty()
    val selectionMode = aiState.selectionRecordId == recordId
    // Reading order: oldest first, merged into paragraphs; the newest text appears at the bottom.
    val orderedSegments = remember(segments) { AiViewModel.orderTranscriptSegments(segments) }
    val groups = remember(orderedSegments) { groupTranscript(orderedSegments) }
    val capturingHere = listening.isListening && listening.activeRecordId == recordId
    val realtimeHere = capturingHere && listening.captureMode == CaptureMode.REALTIME_ASR
    var recordingsExpanded by remember(recordId) { mutableStateOf(false) }
    val pausedHere = !listening.isListening && listening.pausedClass?.recordId == recordId
    val useControlBar = capturingHere || pausedHere || segments.isNotEmpty() || recordings.recordings.isNotEmpty()
    val listState = rememberLazyListState()
    val atEnd by remember { androidx.compose.runtime.derivedStateOf { !listState.canScrollForward } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val follow = rememberFollowLatest(listState, enabled = realtimeHere && !selectionMode, key = recordId)
    // Lines that arrived while the user was reading further up, shown on the jump button.
    var seenCount by remember(recordId) { mutableIntStateOf(orderedSegments.size) }
    LaunchedEffect(follow.following, atEnd, orderedSegments.size) {
        if (follow.following || atEnd) seenCount = orderedSegments.size
    }
    val dragSelection = rememberDragSelectionController(
        listState = listState,
        orderedKeys = orderedSegments.map { it.id },
        selectedKeys = selectedIds,
        onSelectionChanged = { aiModel.replaceSelection(recordId, it) }
    )
    androidx.compose.runtime.DisposableEffect(recordId) {
        onDispose { if (aiModel.uiState.value.selectionRecordId == recordId) aiModel.clearSelection() }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().dragSelectionViewport(dragSelection),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!recordLoaded) item("loading-record") { Text("正在加载课堂记录……") }
                else {
                    // Only a brand-new, empty class shows the big start cards; every other state uses the
                    // bottom control bar so pause/continue/end never require scrolling to the top.
                    if (!useControlBar) item("capture-panel") {
                        CapturePanel(recordId, listening, recordings.processing, startCapture, stopCapture, pause = pauseCapture, resume = resumeCapture)
                    }
                    aiState.error?.let { item("ai-error") { ErrorCard(it) } }
                    if (recordings.recordings.isNotEmpty()) item("recordings-summary") {
                        RecordingsSummaryCard(
                            recordings = recordings.recordings,
                            processing = recordings.processing,
                            recognizingAll = recordings.recognizingAllRecordId == recordId,
                            recognitionAllowed = !listening.isListening && !recordings.processing.isProcessing,
                            expanded = recordingsExpanded,
                            toggleExpanded = { recordingsExpanded = !recordingsExpanded },
                            recognizeAll = recognizeAll,
                            startRecognition = startOfflineRecognition,
                            pauseRecognition = stopOfflineRecognition,
                            delete = deleteRecording
                        )
                    }
                    if (segments.isEmpty()) item("empty-segments") {
                        Text(
                            when {
                                capturingHere && listening.captureMode == CaptureMode.RECORD_ONLY -> "正在仅录音。结束后点上方「全部识别」，文字会出现在这里。"
                                capturingHere -> "正在听，识别出的文字会出现在这里。"
                                recordings.recordings.isEmpty() -> "还没有文字。用「实时转写」上课，或先「仅录音」再识别。"
                                else -> "还没有文字。点上方录音文件的「全部识别」后，文字会出现在这里。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    items(groups, key = { "group-${it.segments.first().id}" }) { group ->
                        Column {
                            TranscriptGroupHeader(
                                group,
                                selectionMode = selectionMode,
                                selection = groupSelection(group, selectedIds),
                                toggleGroup = {
                                    val ids = group.segments.map { it.id }.toSet()
                                    val next = if (groupSelection(group, selectedIds) == GroupSelection.ALL) selectedIds - ids else selectedIds + ids
                                    aiModel.replaceSelection(recordId, next)
                                }
                            )
                            // No gaps between lines: a paragraph reads as one block, lines stay individually selectable.
                            Column(Modifier.border(groupBorder(), RoundedCornerShape(16.dp))) {
                                group.segments.forEachIndexed { index, segment ->
                                    TranscriptLine(
                                        segment = segment,
                                        position = linePosition(index, group.segments.size),
                                        selected = segment.id in selectedIds,
                                        selectionMode = selectionMode,
                                        dragSelectionEnabled = true,
                                        modifier = Modifier.dragSelectableItem(segment.id, dragSelection),
                                        toggle = { aiModel.toggleSelection(recordId, segment.id) },
                                        restoreOriginal = { aiModel.restoreOriginal(segment.id) }
                                    )
                                }
                            }
                        }
                    }
                    if (realtimeHere && (listening.pendingQueueCount > 0 || listening.isRecognizing)) {
                        item("recognizing-tail") { RecognizingTail(listening.pendingQueueCount) }
                    }
                }
            }
            JumpToLatestButton(
                visible = !atEnd && orderedSegments.isNotEmpty() && !(realtimeHere && follow.following) && !selectionMode,
                newCount = (orderedSegments.size - seenCount).coerceAtLeast(0),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                follow.resume()
                scope.launch { listState.scrollToBottom() }
            }
        }
        // While selecting text the bar only stays for a live or paused class.
        // While selecting, the selection actions take the bottom; the nav badge still shows a live class.
        if (recordLoaded && useControlBar && !selectionMode) {
            CaptureControlBar(
                recordId = recordId,
                listening = listening,
                processing = recordings.processing,
                start = startCapture,
                pause = pauseCapture,
                resume = resumeCapture,
                end = stopCapture,
                mark = markMoment
            )
        }
    }
}

/** Bottom bar while selecting text: the things you do with what you selected. */
@Composable
internal fun SelectionActionBar(
    count: Int,
    aiEnabled: Boolean,
    allMarked: Boolean,
    copy: () -> Unit,
    askAi: () -> Unit,
    toggleMark: () -> Unit,
    toNotes: () -> Unit,
    correct: () -> Unit,
    delete: () -> Unit
) {
    var more by remember { mutableStateOf(false) }
    Surface(color = groupColor(), modifier = Modifier.fillMaxWidth().testTag("selection-action-bar")) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionAction("复制", Icons.Outlined.ContentCopy, count > 0, Modifier.weight(1f).testTag("selection-copy"), onClick = copy)
                SelectionAction("问 AI", Icons.Outlined.AutoAwesome, aiEnabled, Modifier.weight(1f).testTag("selection-ask-ai"), primary = true, onClick = askAi)
                SelectionAction(
                    if (allMarked) "取消重点" else "标为重点",
                    if (allMarked) Icons.Outlined.BookmarkRemove else Icons.Outlined.Bookmark,
                    count > 0, Modifier.weight(1f).testTag("selection-mark"), onClick = toggleMark
                )
                SelectionAction("记成笔记", Icons.Outlined.Description, aiEnabled, Modifier.weight(1f).testTag("selection-notes"), onClick = toNotes)
                Box(Modifier.weight(1f)) {
                    SelectionAction("更多", Icons.Outlined.MoreHoriz, count > 0, Modifier.fillMaxWidth().testTag("selection-more")) { more = true }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text("AI 纠错") },
                            leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                            enabled = aiEnabled,
                            onClick = { more = false; correct() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { more = false; delete() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val container = if (primary && enabled) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = container, modifier = modifier.heightIn(min = 60.dp)) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
        }
    }
}

/** 笔记: the whole-class notes up front, other AI work on this class below. */
@Composable
private fun ClassNotesTab(
    recordId: Long,
    segments: List<TranscriptEntity>,
    aiState: AiUiState,
    aiModel: AiViewModel,
    openResult: (Long) -> Unit
) {
    val results by remember(recordId) { aiModel.results(recordId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val notes = results.filter { it.actionType == AiActionType.ORGANIZE_NOTES.name }.maxByOrNull { it.createdAt }
    val others = results.filter { it.id != notes?.id }.sortedByDescending { it.createdAt }
    val clipboard = LocalClipboardManager.current
    val regenerate = { aiModel.runFullRecordAction(recordId, AiActionType.ORGANIZE_NOTES, segments) { } }
    LazyColumn(
        Modifier.fillMaxSize().testTag("class-notes"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        aiState.error?.let { item("notes-error") { ErrorCard(it) } }
        item("main-notes") {
            Surface(shape = GroupShape, color = groupColor(), border = groupBorder(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (notes == null) {
                        Text("课堂笔记", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (segments.isEmpty()) "这节课还没有文字。有文字后可以整理成笔记；配置了 AI 服务时，下课归档后会自动整理。"
                            else "把整节课整理成笔记，标为重点的内容会被突出。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = regenerate, enabled = segments.isNotEmpty() && !aiState.isBusy, modifier = Modifier.testTag("organize-notes")) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("整理成笔记")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("课堂笔记", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when (notes.status) {
                                        AiRequestStatus.PENDING.name -> "正在整理……"
                                        AiRequestStatus.ERROR.name -> "整理失败"
                                        else -> "${notes.sourceSegmentLabel(segments)} · ${SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(notes.finishedAt ?: notes.createdAt))}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (notes.status == AiRequestStatus.ERROR.name) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (notes.status == AiRequestStatus.PENDING.name) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else {
                                FilledTonalIconButton(
                                    onClick = { notes.output?.let { clipboard.setText(AnnotatedString(it)) } },
                                    enabled = !notes.output.isNullOrBlank(),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                                ) { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制笔记") }
                                FilledTonalIconButton(
                                    onClick = regenerate,
                                    enabled = segments.isNotEmpty() && !aiState.isBusy,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                    modifier = Modifier.testTag("regenerate-notes")
                                ) { Icon(Icons.Outlined.Refresh, contentDescription = "重新整理") }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        when {
                            notes.status == AiRequestStatus.ERROR.name -> Text(notes.errorMessage ?: "请稍后重试。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            !notes.output.isNullOrBlank() -> {
                                MarkdownText(notes.output, maxLines = 18)
                                TextButton(onClick = { openResult(notes.id) }, modifier = Modifier.testTag("open-notes")) { Text("展开全文与追问") }
                            }
                        }
                    }
                }
            }
        }
        if (others.isNotEmpty()) {
            item("other-heading") { SectionHeader("其他整理") }
            item("other-group") {
                ListGroup {
                    others.forEachIndexed { index, result ->
                        if (index > 0) ListDivider()
                        ListRow(
                            title = resultTitle(result),
                            subtitle = listOfNotNull(
                                SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(result.createdAt)),
                                aiPreviewLine(result.output.orEmpty())
                            ).joinToString(" · "),
                            leading = {
                                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (result.actionType == AiActionType.CORRECT_ASR.name) Icons.Outlined.AutoFixHigh else Icons.Outlined.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            status = when {
                                result.status == AiRequestStatus.PENDING.name -> ({ StatusPill("处理中", StatusTone.ACTIVE) })
                                result.status == AiRequestStatus.ERROR.name -> ({ StatusPill("失败", StatusTone.PROBLEM) })
                                result.actionType == AiActionType.CORRECT_ASR.name && result.correctionPayload != null -> ({ StatusPill("待确认", StatusTone.READY) })
                                else -> null
                            },
                            onClick = { openResult(result.id) },
                            modifier = Modifier.testTag("result-${result.id}")
                        )
                    }
                }
            }
        }
    }
}

private fun AiResultEntity.sourceSegmentLabel(segments: List<TranscriptEntity>): String =
    if (segments.isEmpty()) "整节课" else "整节课 · ${segments.size} 段"

private fun resultTitle(result: AiResultEntity): String = when (result.actionType) {
    AiActionType.CORRECT_ASR.name -> "AI 纠错建议"
    AiActionType.ORGANIZE_NOTES.name -> "选段笔记"
    AiActionType.QUICK_ANSWER.name -> "快速回答"
    AiActionType.SUMMARY.name -> "总结"
    AiActionType.EXTRACT_QUESTIONS.name -> "老师的问题"
    else -> "AI 结果"
}

/** How much of the class a new question carries along. */
internal enum class AskScope(val label: String) { RECENT("最近 10 分钟"), WHOLE("整节课"), NONE("不带原文") }

internal const val RECENT_SCOPE_MS = 10 * 60_000L

/** Segment ids a question in [scope] is grounded on; the newest are kept if a class is long. */
internal fun scopeSegmentIds(scope: AskScope, segments: List<TranscriptEntity>): Set<Long> = when (scope) {
    AskScope.NONE -> emptySet()
    AskScope.WHOLE -> segments.mapTo(linkedSetOf()) { it.id }
    AskScope.RECENT -> {
        val newest = segments.maxOfOrNull { it.endTime } ?: 0L
        segments.filter { it.endTime >= newest - RECENT_SCOPE_MS }.mapTo(linkedSetOf()) { it.id }
    }
}

/** 问答: this class's conversations, and a box to ask a new question about it. */
@Composable
private fun ClassQaTab(
    recordId: Long,
    segments: List<TranscriptEntity>,
    conversations: List<com.cmhr.listen.data.ai.AiConversationEntity>,
    aiState: AiUiState,
    aiModel: AiViewModel,
    openConversation: (Long) -> Unit
) {
    var question by rememberSaveable(recordId) { mutableStateOf("") }
    var scope by rememberSaveable(recordId) { mutableStateOf(if (segments.isEmpty()) AskScope.NONE else AskScope.RECENT) }
    val send = {
        aiModel.createClassroomConversation(
            recordId = recordId,
            contextCodePointLimit = AiViewModel.MAX_SOURCE_CODE_POINTS,
            sourceSegmentIdsAtSend = scopeSegmentIds(scope, segments),
            question = question,
            onCreated = openConversation
        )
        question = ""
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("class-qa"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            aiState.error?.let { item("qa-error") { ErrorCard(it) } }
            if (conversations.isEmpty()) item("qa-empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(36.dp))
                    Text("问问这节课", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "在下面直接提问；也可以在「文字」里选中几句，点「问 AI」。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item("qa-heading") { SectionHeader("这节课的对话") }
                item("qa-group") {
                    ListGroup {
                        conversations.sortedByDescending { it.updatedAt }.forEachIndexed { index, conversation ->
                            if (index > 0) ListDivider(16.dp)
                            ListRow(
                                title = conversation.title,
                                subtitle = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(conversation.updatedAt)),
                                onClick = { openConversation(conversation.id) },
                                modifier = Modifier.testTag("conversation-${conversation.id}")
                            )
                        }
                    }
                }
            }
        }
        // The navigation bar stays put, so lift the box by however much the keyboard rises above it.
        val keyboardLift = bottomChromeLayout().composerBottomPadding
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp + keyboardLift), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AskScope.entries.forEach { option ->
                    FilterChip(
                        selected = scope == option,
                        onClick = { scope = option },
                        enabled = option == AskScope.NONE || segments.isNotEmpty(),
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("ask-scope-${option.name.lowercase()}")
                    )
                }
            }
            Surface(shape = RoundedCornerShape(28.dp), color = groupColor(), border = groupBorder()) {
                Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = question,
                        onValueChange = { question = it },
                        placeholder = { Text("问这节课…") },
                        modifier = Modifier.weight(1f).testTag("class-ask-input"),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (question.isNotBlank() && !aiState.isBusy) send() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    FilledIconButton(onClick = send, enabled = question.isNotBlank() && !aiState.isBusy, modifier = Modifier.testTag("class-ask-send")) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}
