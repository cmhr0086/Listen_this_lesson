package com.cmhr.listen.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.AiContentKey
import com.cmhr.listen.AiContentKind
import com.cmhr.listen.AiContentSelectionScope
import com.cmhr.listen.data.ai.PendingAiAttachment
import com.cmhr.listen.data.ai.AiAttachmentEntity
import com.cmhr.listen.data.ai.AiAttachmentKind
import com.cmhr.listen.data.ai.AiAttachmentStore
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiStreamPhase
import com.cmhr.listen.data.ai.CorrectionPayload
import com.cmhr.listen.data.ai.CorrectionPayloadCodec
import com.cmhr.listen.data.course.TranscriptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiResultsScreen(recordId: Long, model: AiViewModel, openItem: (AiContentKey) -> Unit) {
    val contents by model.contents(recordId).collectAsStateWithLifecycle(initialValue = emptyList())
    val state by model.uiState.collectAsStateWithLifecycle()
    val selectionMode = state.contentSelectionScope?.recordId == recordId
    val listState = rememberLazyListState()
    val dragSelection = rememberDragSelectionController(
        listState = listState,
        orderedKeys = contents.map { it.key },
        selectedKeys = state.selectedContentKeys,
        onSelectionChanged = { model.replaceContentSelection(recordId, it) }
    )
    DisposableEffect(recordId) {
        onDispose { if (model.uiState.value.contentSelectionScope?.recordId == recordId) model.clearContentSelection() }
    }
    LazyColumn(
        Modifier.fillMaxSize().dragSelectionViewport(dragSelection),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.error?.let { message ->
            item("ai-content-error") {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        if (contents.isEmpty()) item("empty-ai-results") { Text("尚无 AI 结果。请在记录详情选择识别片段后进行处理。") }
        items(contents, key = { "ai-content-${it.key.kind}-${it.key.id}" }) { item ->
            val selected = item.key in state.selectedContentKeys
            Card(
                Modifier
                    .fillMaxWidth()
                    .dragSelectableItem(item.key, dragSelection)
                    .semantics {
                        this.selected = selected
                        onLongClick("选择 AI 内容") { model.toggleContentSelection(recordId, item.key); true }
                    }
                    .clickable { if (selectionMode) model.toggleContentSelection(recordId, item.key) else openItem(item.key) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (item.key.kind == AiContentKind.CONVERSATION) "AI 对话" else "AI 结果", style = MaterialTheme.typography.labelMedium)
                    Text(formatDateTime(item.updatedAt), style = MaterialTheme.typography.bodySmall)
                    if (item.key.kind == AiContentKind.RESULT) Text(statusName(item.status), color = statusColor(item.status))
                    if (item.preview.isNotBlank()) Text(item.preview, style = MaterialTheme.typography.bodyLarge, maxLines = 4)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlobalAiScreen(model: AiViewModel, openItem: (AiContentKey, Long?) -> Unit) {
    val contents by model.globalContents().collectAsStateWithLifecycle(initialValue = emptyList())
    val state by model.uiState.collectAsStateWithLifecycle()
    val scope = AiContentSelectionScope(null)
    val selectionMode = state.contentSelectionScope == scope
    val listState = rememberLazyListState()
    val dragSelection = rememberDragSelectionController(
        listState = listState,
        orderedKeys = contents.map { it.key },
        selectedKeys = state.selectedContentKeys,
        onSelectionChanged = { model.replaceContentSelection(null, it) }
    )
    DisposableEffect(Unit) {
        onDispose { if (model.uiState.value.contentSelectionScope == scope) model.clearContentSelection() }
    }
    val grouped = contents.groupBy { dayGroupLabel(it.updatedAt) }
    LazyColumn(
        Modifier.fillMaxSize().dragSelectionViewport(dragSelection),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.error?.let { message -> item("global-ai-error") { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (contents.isEmpty()) {
            item("global-ai-empty") { Text("还没有 AI 内容。使用右下角按钮开始新对话，或从课堂记录处理识别片段。") }
        }
        grouped.forEach { (label, itemsForDay) ->
            item("day-$label") { Text(label, style = MaterialTheme.typography.titleMedium) }
            items(itemsForDay, key = { "global-${it.key.kind}-${it.key.id}" }) { item ->
                val selected = item.key in state.selectedContentKeys
                Card(
                    Modifier
                        .fillMaxWidth()
                        .dragSelectableItem(item.key, dragSelection)
                        .semantics {
                            this.selected = selected
                            onLongClick("选择 AI 内容") { model.toggleContentSelection(null, item.key); true }
                        }
                        .clickable { if (selectionMode) model.toggleContentSelection(null, item.key) else openItem(item.key, item.recordId) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.courseName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FloatingComposerLayout(
    modifier: Modifier = Modifier,
    composerVisible: Boolean = true,
    content: @Composable (composerClearance: Dp) -> Unit,
    composer: @Composable (Modifier) -> Unit
) {
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(composerVisible) {
        if (!composerVisible) composerHeightPx = 0
    }
    val composerClearance = if (composerVisible && composerHeightPx > 0) {
        with(density) { composerHeightPx.toDp() } + 12.dp
    } else {
        16.dp
    }
    Box(modifier.fillMaxSize().testTag("floating-composer-layout")) {
        content(composerClearance)
        if (composerVisible) {
            composer(
                Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { size ->
                        if (composerHeightPx != size.height) composerHeightPx = size.height
                    }
            )
        }
    }
}

@Composable
fun NewAiConversationScreen(model: AiViewModel, onCreated: (Long) -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(emptyList<PendingAiAttachment>()) }
    val latestAttachments by rememberUpdatedState(attachments)
    DisposableEffect(Unit) { onDispose { latestAttachments.forEach(model::discardAttachment) } }
    FloatingComposerLayout(
        content = { composerClearance ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, bottom = composerClearance),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("有什么可以帮你？", style = MaterialTheme.typography.headlineSmall)
                Text("可以添加图片或文本附件。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        composer = { composerModifier ->
            AiChatComposer(
                value = question,
                onValueChange = { question = it },
                attachments = attachments,
                model = model,
                enabled = !state.isBusy,
                updateAttachments = { attachments = it },
                send = {
                    val submitted = attachments
                    model.createGeneralConversation(question, submitted, onCreated)
                    question = ""
                    attachments = emptyList()
                },
                modifier = composerModifier
            )
        }
    )
}

@Composable
fun AiResultDetailScreen(resultId: Long, model: AiViewModel, developerMode: Boolean = false) {
    val result by model.result(resultId).collectAsStateWithLifecycle(initialValue = null)
    val sourceSegments by model.resultSourceSegments(resultId).collectAsStateWithLifecycle(initialValue = emptyList())
    val conversation by model.conversationForResult(resultId).collectAsStateWithLifecycle(initialValue = null)
    val messages by (conversation?.let { model.messages(it.id) } ?: flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val attachments by model.resultAttachments(resultId).collectAsStateWithLifecycle(initialValue = emptyList())
    val aiState by model.uiState.collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(emptyList<PendingAiAttachment>()) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val correction = remember(result?.correctionPayload, sourceSegments) {
        result?.correctionPayload?.takeIf { it.isNotBlank() }?.let { encoded ->
            runCatching { CorrectionPayloadCodec.decode(encoded, sourceSegments) }.getOrNull()
        }
    }
    val latestAttachments by rememberUpdatedState(pendingAttachments)
    DisposableEffect(resultId) { onDispose { latestAttachments.forEach(model::discardAttachment) } }
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canScrollForward) ->
            if (scrolling) followLatest = !canScrollForward
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (followLatest && messages.isNotEmpty()) listState.scrollToItem(messages.size + 1)
    }
    val composerVisible = result?.status == AiRequestStatus.SUCCESS.name
    FloatingComposerLayout(
        composerVisible = composerVisible,
        content = { composerClearance ->
        LazyColumn(
            Modifier.fillMaxSize().testTag("ai-result-message-list"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = composerClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val value = result
            if (value == null) item("loading-result") { Text("正在加载 AI 结果……") }
            else {
                item("result-heading") { Text(actionName(value.actionType), style = MaterialTheme.typography.titleLarge) }
                item("result-summary") {
                    when (value.status) {
                        AiRequestStatus.PENDING.name -> AssistantBubble(
                            text = value.output.orEmpty(),
                            reasoning = value.reasoningContent,
                            status = value.status,
                            phase = aiState.resultStreamPhases[value.id],
                            photos = attachments,
                            model = model,
                            clipboard = clipboard
                        )
                        AiRequestStatus.SUCCESS.name -> AssistantBubble(
                            text = value.output.orEmpty(),
                            reasoning = value.reasoningContent,
                            status = value.status,
                            photos = attachments,
                            model = model,
                            clipboard = clipboard
                        )
                        else -> AssistantBubble(
                            text = value.output.orEmpty(),
                            reasoning = value.reasoningContent,
                            status = value.status,
                            error = value.errorMessage ?: "AI 请求失败。",
                            photos = attachments,
                            model = model,
                            clipboard = clipboard,
                            retry = { model.retryResult(value.id) },
                            retryEnabled = !aiState.isBusy
                        )
                    }
                }
                if (value.status == AiRequestStatus.SUCCESS.name && correction != null) {
                    item("correction-review") {
                        CorrectionReviewCard(
                            payload = requireNotNull(correction),
                            source = sourceSegments,
                            applied = sourceSegments.isNotEmpty() && sourceSegments.all { it.correctionResultId == value.id },
                            apply = {
                                model.applyCorrections(value.id) { success ->
                                    if (success) Toast.makeText(context, "已应用纠正，原始文本仍被保留。", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
                items(messages, key = { "result-message-${it.id}" }) { message ->
                    ChatMessageBubble(message, aiState.messageStreamPhases[message.id], model, clipboard)
                }
            }
            aiState.error?.let { item("result-error") { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (developerMode) aiState.lastDiagnostics?.let { diagnostics ->
                item("result-diagnostics") { AiDiagnosticsCard(diagnostics) }
            }
        }
        },
        composer = { composerModifier ->
            AiChatComposer(
                value = question,
                onValueChange = { question = it },
                attachments = pendingAttachments,
                model = model,
                enabled = !aiState.isBusy,
                updateAttachments = { pendingAttachments = it },
                send = {
                    val sentAttachments = pendingAttachments
                    model.sendResultFollowUp(resultId, question, sentAttachments)
                    question = ""
                    pendingAttachments = emptyList()
                },
                modifier = composerModifier
            )
        }
    )
}

@Composable
fun AiConversationsScreen(recordId: Long, model: AiViewModel, openConversation: (Long) -> Unit) {
    val conversations by model.conversations(recordId).collectAsStateWithLifecycle(initialValue = emptyList())
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (conversations.isEmpty()) item("empty-conversations") { Text("尚无 AI 对话。请先在记录详情选择片段并提出问题。") }
        items(conversations, key = { "conversation-${it.id}" }) { conversation ->
            Card(Modifier.fillMaxWidth().clickable { openConversation(conversation.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                    Text("更新：${formatDateTime(conversation.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun AiConversationScreen(conversationId: Long, model: AiViewModel, developerMode: Boolean = false) {
    val conversation by model.conversation(conversationId).collectAsStateWithLifecycle(initialValue = null)
    val messages by model.messages(conversationId).collectAsStateWithLifecycle(initialValue = emptyList())
    val aiState by model.uiState.collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(emptyList<PendingAiAttachment>()) }
    var contextExpanded by remember(conversationId) { mutableStateOf(false) }
    var contextInitialized by remember(conversationId) { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val latestAttachments by rememberUpdatedState(pendingAttachments)
    DisposableEffect(conversationId) {
        onDispose { latestAttachments.forEach(model::discardAttachment) }
    }
    LaunchedEffect(conversation?.id, messages.size) {
        if (!contextInitialized && conversation != null) {
            contextExpanded = messages.none { it.role == "user" }
            contextInitialized = true
        }
    }
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canScrollForward) ->
            if (scrolling) followLatest = !canScrollForward
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content, conversation?.id) {
        if (followLatest && messages.isNotEmpty()) {
            val titleOffset = if (conversation != null) 1 else 0
            listState.scrollToItem(messages.lastIndex + titleOffset)
        }
    }
    FloatingComposerLayout(
        content = { composerClearance ->
        LazyColumn(
            Modifier.fillMaxSize().testTag("ai-conversation-message-list"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = composerClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            conversation?.let { value ->
                item("conversation-title") { Text(value.title, style = MaterialTheme.typography.titleLarge) }
                if (value.sourceTextSnapshot.isNotBlank()) {
                    item("conversation-context-preview") {
                        ConversationContextCard(value.sourceTextSnapshot, contextExpanded) {
                            contextExpanded = !contextExpanded
                        }
                    }
                }
            }
            items(messages, key = { "message-${it.id}" }) { message ->
                ChatMessageBubble(message, aiState.messageStreamPhases[message.id], model, clipboard)
            }
            aiState.error?.let { item("conversation-error") { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (developerMode) aiState.lastDiagnostics?.let { diagnostics ->
                item("conversation-diagnostics") { AiDiagnosticsCard(diagnostics) }
            }
        }
        },
        composer = { composerModifier ->
            AiChatComposer(
                value = question,
                onValueChange = { question = it },
                attachments = pendingAttachments,
                model = model,
                enabled = !aiState.isBusy,
                updateAttachments = { pendingAttachments = it },
                send = {
                    val sentAttachments = pendingAttachments
                    model.sendMessage(conversationId, question, sentAttachments)
                    question = ""
                    pendingAttachments = emptyList()
                },
                modifier = composerModifier
            )
        }
    )
}

@Composable
private fun AiDiagnosticsCard(value: com.cmhr.listen.AiRequestDiagnostics) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("最近 AI 请求诊断", style = MaterialTheme.typography.titleSmall)
        Text("模型：${value.model}")
        Text("耗时：${value.durationMs?.let { "$it ms" } ?: "失败前未知"}")
        Text("结束原因：${value.finishReason ?: "未知"}")
        Text("Tokens：输入 ${value.promptTokens ?: "—"} / 输出 ${value.completionTokens ?: "—"}")
        Text("来源长度：${value.sourceCodePoints} 字符 · 照片：${value.imageCount} 张")
        Text("含推理内容：${if (value.reasoningPresent) "是" else "否"}")
        value.error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ChatMessageBubble(
    message: com.cmhr.listen.data.ai.AiMessageEntity,
    phase: AiStreamPhase?,
    model: AiViewModel,
    clipboard: ClipboardManager?
) {
    val photos by model.messageAttachments(message.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = if (isUser) Modifier.widthIn(max = 420.dp) else Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isUser) "我" else "AI", style = MaterialTheme.typography.labelLarge)
                if (isUser) {
                    Text(message.content, style = MaterialTheme.typography.bodyLarge)
                } else {
                    ReasoningSection(message.reasoningContent, message.status, phase)
                    if (message.content.isNotBlank()) MarkdownText(message.content)
                    StreamStatus(message.status, phase, message.errorMessage)
                }
                if (photos.isNotEmpty()) PersistedAttachmentRow(photos, model)
                if (!isUser && message.content.isNotBlank()) {
                    Box(Modifier.fillMaxWidth()) { CopyButton(message.content, clipboard, Modifier.align(Alignment.CenterEnd)) }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    reasoning: String,
    status: String,
    phase: AiStreamPhase? = null,
    error: String? = null,
    photos: List<AiAttachmentEntity>,
    model: AiViewModel,
    clipboard: ClipboardManager?,
    retry: (() -> Unit)? = null,
    retryEnabled: Boolean = true
) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI", style = MaterialTheme.typography.labelLarge)
            ReasoningSection(reasoning, status, phase)
            if (text.isNotBlank()) MarkdownText(text)
            StreamStatus(status, phase, error)
            if (photos.isNotEmpty()) PersistedAttachmentRow(photos, model)
            if (text.isNotBlank()) {
                Box(Modifier.fillMaxWidth()) { CopyButton(text, clipboard, Modifier.align(Alignment.CenterEnd)) }
            }
            if (status == AiRequestStatus.ERROR.name && retry != null) {
                Button(onClick = retry, enabled = retryEnabled) { Text("重试") }
            }
        }
    }
}

@Composable
private fun StreamStatus(status: String, phase: AiStreamPhase?, error: String?) {
    when (status) {
        AiRequestStatus.PENDING.name -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                when (phase) {
                    AiStreamPhase.THINKING -> "正在思考……"
                    AiStreamPhase.GENERATING -> "正在生成……"
                    else -> "正在连接 AI……"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AiRequestStatus.ERROR.name -> Text(error ?: "AI 请求失败。", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReasoningSection(reasoning: String, status: String, phase: AiStreamPhase?) {
    var expanded by remember { mutableStateOf(phase == AiStreamPhase.THINKING) }
    var generationObserved by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        when (phase) {
            AiStreamPhase.THINKING -> if (!generationObserved) expanded = true
            AiStreamPhase.GENERATING -> if (!generationObserved) {
                generationObserved = true
                expanded = false
            }
            else -> Unit
        }
    }
    if (reasoning.isBlank() && phase != AiStreamPhase.THINKING) return
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = reasoning.isNotBlank()) { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (status == AiRequestStatus.PENDING.name && phase == AiStreamPhase.THINKING) "正在思考" else "思考过程",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reasoning.isNotBlank()) Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                if (reasoning.isBlank()) Text("正在等待思考内容……", style = MaterialTheme.typography.bodyMedium)
                else MarkdownText(reasoning)
            }
        }
    }
}

@Composable
private fun CorrectionReviewCard(
    payload: CorrectionPayload,
    source: List<TranscriptEntity>,
    applied: Boolean,
    apply: () -> Unit
) {
    val originals = remember(source) { source.associateBy { it.id } }
    val hasTextChanges = payload.segments.any { correction ->
        correction.correctedText != originals[correction.segmentId]?.effectiveText
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("逐片段纠错", style = MaterialTheme.typography.titleMedium)
            payload.segments.forEach { correction ->
                val original = originals[correction.segmentId] ?: return@forEach
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("片段 #${correction.segmentId}", style = MaterialTheme.typography.labelLarge)
                    Text("原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(original.text, style = MaterialTheme.typography.bodyMedium)
                    Text("纠正文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(correction.correctedText, style = MaterialTheme.typography.bodyLarge)
                    if (correction.changes.isEmpty()) {
                        Text("未发现可确认的修改。", style = MaterialTheme.typography.bodySmall)
                    } else correction.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Button(onClick = apply, enabled = !applied && hasTextChanges, modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    applied -> "已应用纠正"
                    !hasTextChanges -> "未发现可应用的修改"
                    else -> "应用全部纠正"
                })
            }
            Text("应用后仍会永久保留原始 ASR 文本，可在识别卡片中查看或恢复。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val borderColor = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val headerColor = MaterialTheme.colorScheme.secondaryContainer.toArgb()
    val oddRowColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f).toArgb()
    val markwon = remember(context, borderColor, headerColor, oddRowColor) {
        MarkdownRenderer.get(context, borderColor, headerColor, oddRowColor)
    }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize.value
    AndroidView(
        modifier = modifier.fillMaxWidth().testTag("markdown-content"),
        factory = { viewContext ->
            TextView(viewContext).apply {
                setTextIsSelectable(true)
                includeFontPadding = false
                movementMethod = TableAwareMovementMethod.create()
                setLineSpacing(4.dp.value * resources.displayMetrics.density, 1f)
            }
        },
        update = { view ->
            view.setTextColor(color)
            view.setLinkTextColor(linkColor)
            view.textSize = fontSize
            markwon.setMarkdown(view, markdown)
        }
    )
}

private data class MarkdownRendererKey(
    val borderColor: Int,
    val headerColor: Int,
    val oddRowColor: Int
)

private object MarkdownRenderer {
    private val instances = ConcurrentHashMap<MarkdownRendererKey, Markwon>()

    fun get(context: Context, borderColor: Int, headerColor: Int, oddRowColor: Int): Markwon {
        val key = MarkdownRendererKey(borderColor, headerColor, oddRowColor)
        return instances.getOrPut(key) {
            val density = context.resources.displayMetrics.density
            val tableTheme = TableTheme.buildWithDefaults(context.applicationContext)
                .tableBorderColor(borderColor)
                .tableBorderWidth(density.roundToInt().coerceAtLeast(1))
                .tableCellPadding((8f * density).roundToInt())
                .tableHeaderRowBackgroundColor(headerColor)
                .tableOddRowBackgroundColor(oddRowColor)
                .build()
            Markwon.builder(context.applicationContext)
                .usePlugin(TablePlugin.create(tableTheme))
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TaskListPlugin.create(context.applicationContext))
                .usePlugin(HtmlPlugin.create())
                .build()
        }
    }
}

@Composable
private fun CopyButton(text: String, clipboard: ClipboardManager?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    IconButton(modifier = modifier.size(36.dp), onClick = {
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("AI 回复", text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制回复", modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AiChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<PendingAiAttachment>,
    model: AiViewModel,
    enabled: Boolean,
    updateAttachments: (List<PendingAiAttachment>) -> Unit,
    send: () -> Unit,
    modifier: Modifier = Modifier
) {
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    var refocusRequest by remember { mutableIntStateOf(0) }
    var textFieldFocused by remember { mutableStateOf(false) }
    var restoreImeAfterPicker by remember { mutableStateOf(false) }
    var lastMenuDismissedAt by remember { mutableStateOf(0L) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val bottomChrome = bottomChromeLayout()
    val density = LocalDensity.current
    val imeVisible = with(density) {
        WindowInsets.ime.getBottom(this) > WindowInsets.navigationBars.getBottom(this)
    }
    val latestAttachments by rememberUpdatedState(attachments)
    LaunchedEffect(refocusRequest) {
        if (refocusRequest > 0) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    fun accept(uri: android.net.Uri?, kind: AiAttachmentKind) {
        if (uri == null) return
        model.prepareAttachment(uri, kind) { attachment ->
            if (attachment != null) {
                val current = latestAttachments
                if (current.size < AiAttachmentStore.MAX_ATTACHMENTS_PER_REQUEST) {
                    val candidate = current + attachment
                    if (candidate.sumOf { it.textCodePoints } <= AiAttachmentStore.MAX_TEXT_CODE_POINTS) {
                        updateAttachments(candidate)
                    } else {
                        model.discardAttachment(attachment)
                        model.reportError("所有文本附件合计最多 20,000 个字符。")
                    }
                } else model.discardAttachment(attachment)
            }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        accept(uri, AiAttachmentKind.IMAGE)
        if (restoreImeAfterPicker) refocusRequest++
        restoreImeAfterPicker = false
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        accept(uri, AiAttachmentKind.TEXT)
        if (restoreImeAfterPicker) refocusRequest++
        restoreImeAfterPicker = false
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = bottomChrome.composerBottomPadding + 16.dp)
            .testTag("ai-chat-composer"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai-chat-composer-surface"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (attachments.isNotEmpty()) PendingAttachmentRow(attachments, model, updateAttachments)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box {
                        IconButton(
                            onClick = {
                                val now = android.os.SystemClock.elapsedRealtime()
                                attachmentMenuExpanded = if (now - lastMenuDismissedAt < 250L) {
                                    false
                                } else {
                                    !attachmentMenuExpanded
                                }
                            },
                            enabled = enabled && attachments.size < AiAttachmentStore.MAX_ATTACHMENTS_PER_REQUEST,
                            modifier = Modifier.testTag("attachment-button")
                        ) { Icon(Icons.Outlined.Add, contentDescription = "添加附件") }
                        DropdownMenu(
                            expanded = attachmentMenuExpanded,
                            onDismissRequest = {
                                lastMenuDismissedAt = android.os.SystemClock.elapsedRealtime()
                                attachmentMenuExpanded = false
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.widthIn(min = 200.dp).testTag("attachment-menu"),
                            properties = PopupProperties(focusable = false)
                        ) {
                            DropdownMenuItem(
                                text = { Text("选择图片") },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                onClick = {
                                    restoreImeAfterPicker = textFieldFocused && imeVisible
                                    attachmentMenuExpanded = false
                                    imageLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("选择文件") },
                                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                                onClick = {
                                    restoreImeAfterPicker = textFieldFocused && imeVisible
                                    attachmentMenuExpanded = false
                                    textLauncher.launch(arrayOf("text/plain", "text/markdown", "text/csv", "application/json"))
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { textFieldFocused = it.isFocused }
                            .testTag("composer-input"),
                        placeholder = { Text("输入消息") },
                        minLines = 1,
                        maxLines = 6,
                        shape = RoundedCornerShape(24.dp)
                    )
                    FilledIconButton(onClick = send, enabled = enabled && value.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationContextCard(snapshot: String, expanded: Boolean, toggle: () -> Unit) {
    val preview = snapshot.lineSequence().take(6).joinToString("\n")
    Card(Modifier.fillMaxWidth().clickable(onClick = toggle)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("课堂原文", style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
            }
            Text(
                if (expanded || preview.length == snapshot.length) snapshot else "$preview\n……",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiContextBottomSheet(snapshot: String, dismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("context-title") { Text("课堂原文上下文", style = MaterialTheme.typography.titleLarge) }
            item("context-help") {
                Text("这是创建当前 AI 结果或对话时冻结的课堂原文。", style = MaterialTheme.typography.bodySmall)
            }
            item("context-body") {
                Card(Modifier.fillMaxWidth()) {
                    Text(snapshot.ifBlank { "没有可显示的课堂原文。" }, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun actionName(value: String): String = runCatching { AiActionType.valueOf(value).displayName }.getOrDefault(value)

internal fun dayGroupLabel(timestamp: Long, today: LocalDate = LocalDate.now()): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (val days = ChronoUnit.DAYS.between(date, today)) {
        0L -> "今天"
        1L -> "昨天"
        in 2L..6L -> "${days}天前"
        else -> date.toString()
    }
}
private fun statusName(value: String): String = when (value) {
    AiRequestStatus.PENDING.name -> "处理中"
    AiRequestStatus.SUCCESS.name -> "成功"
    else -> "失败"
}

@Composable
private fun statusColor(value: String) = when (value) {
    AiRequestStatus.SUCCESS.name -> MaterialTheme.colorScheme.primary
    AiRequestStatus.ERROR.name -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun PendingAttachmentRow(
    attachments: List<PendingAiAttachment>,
    model: AiViewModel,
    update: (List<PendingAiAttachment>) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(attachments, key = { it.absolutePath }) { attachment ->
            Box {
                if (attachment.kind == AiAttachmentKind.IMAGE) {
                    PhotoThumbnail(attachment.absolutePath)
                } else {
                    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp) {
                        Row(
                            Modifier.widthIn(max = 220.dp).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Description, contentDescription = null)
                            Text(attachment.displayName, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                TextButton(
                    onClick = { model.discardAttachment(attachment); update(attachments - attachment) },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) { Text("移除") }
            }
        }
    }
}

@Composable
private fun PersistedAttachmentRow(attachments: List<AiAttachmentEntity>, model: AiViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(attachments, key = { "attachment-${it.id}" }) { attachment ->
            if (attachment.kind == AiAttachmentKind.IMAGE.name) {
                PhotoThumbnail(model.attachmentFile(attachment).absolutePath)
            } else {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp) {
                    Row(
                        Modifier.widthIn(max = 220.dp).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null)
                        Text(attachment.displayName, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(path: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
    }
    bitmap?.let { value ->
        Image(
            bitmap = value.asImageBitmap(),
            contentDescription = "课堂照片",
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
    }
}
