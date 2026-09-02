package com.cmhr.listen.ui

import android.content.ClipboardManager
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.AiContentKey
import com.cmhr.listen.AiContentKind
import com.cmhr.listen.data.ai.PendingAiPhoto
import com.cmhr.listen.data.ai.AiImageAttachmentEntity
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiRequestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiResultsScreen(recordId: Long, model: AiViewModel, openItem: (AiContentKey) -> Unit) {
    val contents by model.contents(recordId).collectAsStateWithLifecycle(initialValue = emptyList())
    val state by model.uiState.collectAsStateWithLifecycle()
    val selectionMode = state.contentSelectionRecordId == recordId
    DisposableEffect(recordId) { onDispose { if (model.uiState.value.contentSelectionRecordId == recordId) model.clearContentSelection() } }
    LazyColumn(
        Modifier.fillMaxSize(),
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
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (selectionMode) model.toggleContentSelection(recordId, item.key) else openItem(item.key) },
                    onLongClick = { model.toggleContentSelection(recordId, item.key) }
                ),
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

@Composable
fun AiResultDetailScreen(resultId: Long, model: AiViewModel, developerMode: Boolean = false) {
    val result by model.result(resultId).collectAsStateWithLifecycle(initialValue = null)
    val conversation by model.conversationForResult(resultId).collectAsStateWithLifecycle(initialValue = null)
    val messages by (conversation?.let { model.messages(it.id) } ?: flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val attachments by model.resultAttachments(resultId).collectAsStateWithLifecycle(initialValue = emptyList())
    val aiState by model.uiState.collectAsStateWithLifecycle()
    var showSnapshot by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(emptyList<PendingAiPhoto>()) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val latestPhotos by rememberUpdatedState(photos)
    DisposableEffect(resultId) { onDispose { latestPhotos.forEach(model::discardPhoto) } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val value = result
            if (value == null) item("loading-result") { Text("正在加载 AI 结果……") }
            else {
                item("result-heading") { Text(actionName(value.actionType), style = MaterialTheme.typography.titleLarge) }
                item("result-summary") {
                    when (value.status) {
                        AiRequestStatus.PENDING.name -> AssistantBubble("AI 正在处理，请稍候……", emptyList(), model, clipboard)
                        AiRequestStatus.SUCCESS.name -> AssistantBubble(value.output.orEmpty(), attachments, model, clipboard)
                        else -> Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(value.errorMessage ?: "AI 请求失败。", color = MaterialTheme.colorScheme.error)
                                Button(onClick = { model.retryResult(value.id) }, enabled = !aiState.isBusy) { Text("重试") }
                            }
                        }
                    }
                }
                item("snapshot-toggle") { ExpandHeader("课堂原文上下文", showSnapshot) { showSnapshot = !showSnapshot } }
                if (showSnapshot) item("snapshot-content") {
                    Card(Modifier.fillMaxWidth()) { Text(value.sourceTextSnapshot, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
                }
                items(messages, key = { "result-message-${it.id}" }) { message ->
                    ChatMessageBubble(message, model, clipboard)
                }
            }
            aiState.error?.let { item("result-error") { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (developerMode) aiState.lastDiagnostics?.let { diagnostics ->
                item("result-diagnostics") { AiDiagnosticsCard(diagnostics) }
            }
        }
        if (result?.status == AiRequestStatus.SUCCESS.name) {
            AiChatComposer(
                value = question,
                onValueChange = { question = it },
                photos = photos,
                model = model,
                enabled = !aiState.isBusy,
                updatePhotos = { photos = it },
                send = {
                    val sentPhotos = photos
                    model.sendResultFollowUp(resultId, question, sentPhotos)
                    question = ""
                    photos = emptyList()
                }
            )
        }
    }
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
    var showSource by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf(emptyList<PendingAiPhoto>()) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val latestPhotos by rememberUpdatedState(photos)
    DisposableEffect(conversationId) {
        onDispose { latestPhotos.forEach(model::discardPhoto) }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            conversation?.let { value -> item("conversation-title") { Text(value.title, style = MaterialTheme.typography.titleLarge) } }
            item("conversation-source-toggle") { ExpandHeader("课堂原文上下文", showSource) { showSource = !showSource } }
            if (showSource) item("conversation-source-snapshot") {
                Card(Modifier.fillMaxWidth()) { Text(conversation?.sourceTextSnapshot.orEmpty(), Modifier.padding(16.dp)) }
            }
            items(messages, key = { "message-${it.id}" }) { message -> ChatMessageBubble(message, model, clipboard) }
            aiState.error?.let { item("conversation-error") { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (developerMode) aiState.lastDiagnostics?.let { diagnostics ->
                item("conversation-diagnostics") { AiDiagnosticsCard(diagnostics) }
            }
        }
        AiChatComposer(
            value = question,
            onValueChange = { question = it },
            photos = photos,
            model = model,
            enabled = !aiState.isBusy,
            updatePhotos = { photos = it },
            send = {
                val sentPhotos = photos
                model.sendMessage(conversationId, question, sentPhotos)
                question = ""
                photos = emptyList()
            }
        )
    }
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
        Text("含推理内容：${if (value.reasoningPresent) "是（未展示）" else "否"}")
        value.error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ChatMessageBubble(
    message: com.cmhr.listen.data.ai.AiMessageEntity,
    model: AiViewModel,
    clipboard: ClipboardManager?
) {
    val photos by model.messageAttachments(message.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            Modifier.widthIn(max = 340.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (isUser) "我" else "AI", style = MaterialTheme.typography.labelLarge)
                when (message.status) {
                    AiRequestStatus.PENDING.name -> Text("正在生成回复……")
                    AiRequestStatus.ERROR.name -> Text(message.errorMessage ?: "回复失败。", color = MaterialTheme.colorScheme.error)
                    else -> Text(message.content, style = MaterialTheme.typography.bodyLarge)
                }
                if (photos.isNotEmpty()) PersistedPhotoRow(photos, model)
                if (!isUser && message.status == AiRequestStatus.SUCCESS.name) CopyButton(message.content, clipboard)
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    photos: List<AiImageAttachmentEntity>,
    model: AiViewModel,
    clipboard: ClipboardManager?
) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
    Card(Modifier.widthIn(max = 340.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AI", style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (photos.isNotEmpty()) PersistedPhotoRow(photos, model)
            CopyButton(text, clipboard)
        }
    }
}

@Composable
private fun CopyButton(text: String, clipboard: ClipboardManager?) {
    val context = LocalContext.current
    TextButton(onClick = {
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("AI 回复", text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("复制")
    }
}

@Composable
private fun AiChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    photos: List<PendingAiPhoto>,
    model: AiViewModel,
    enabled: Boolean,
    updatePhotos: (List<PendingAiPhoto>) -> Unit,
    send: () -> Unit
) {
    var target by remember { mutableStateOf<com.cmhr.listen.AiCaptureTarget?>(null) }
    val latestPhotos by rememberUpdatedState(photos)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capture = target
        target = null
        if (capture != null && success) model.prepareCapturedPhoto(capture.file) { photo ->
            if (photo != null) updatePhotos(latestPhotos + photo)
        } else capture?.file?.delete()
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (photos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.absolutePath }) { photo ->
                    Box {
                        PhotoThumbnail(photo.absolutePath)
                        TextButton(onClick = { model.discardPhoto(photo); updatePhotos(photos - photo) }, Modifier.align(Alignment.TopEnd)) { Text("移除") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = { model.createCaptureTarget().also { target = it; launcher.launch(it.uri) } },
                    enabled = enabled && photos.size < com.cmhr.listen.data.ai.AiPhotoStore.MAX_PHOTOS_PER_REQUEST
                ) { Icon(Icons.Outlined.CameraAlt, contentDescription = "拍照补充") }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入问题") },
                    minLines = 1,
                    maxLines = 5
                )
                IconButton(onClick = send, enabled = enabled && value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                }
            }
        }
    }
}

private fun actionName(value: String): String = runCatching { AiActionType.valueOf(value).displayName }.getOrDefault(value)
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
internal fun PhotoAttachmentEditor(
    photos: List<PendingAiPhoto>,
    model: AiViewModel,
    update: (List<PendingAiPhoto>) -> Unit
) {
    var target by remember { mutableStateOf<com.cmhr.listen.AiCaptureTarget?>(null) }
    val latestPhotos by rememberUpdatedState(photos)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capture = target
        target = null
        if (capture != null && success) {
            model.prepareCapturedPhoto(capture.file) { photo ->
                if (photo != null) update(latestPhotos + photo)
            }
        } else capture?.file?.delete()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    model.createCaptureTarget().also { capture -> target = capture; launcher.launch(capture.uri) }
                },
                enabled = photos.size < com.cmhr.listen.data.ai.AiPhotoStore.MAX_PHOTOS_PER_REQUEST
            ) { Text("拍照补充") }
            Text("${photos.size}/3 张", style = MaterialTheme.typography.bodySmall)
        }
        if (photos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.absolutePath }) { photo ->
                Box {
                    PhotoThumbnail(photo.absolutePath)
                    TextButton(
                        onClick = { model.discardPhoto(photo); update(photos - photo) },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) { Text("移除") }
                }
            }
        }
    }
}

@Composable
private fun PersistedPhotoRow(photos: List<AiImageAttachmentEntity>, model: AiViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(photos, key = { "photo-${it.id}" }) { photo ->
            PhotoThumbnail(model.attachmentFile(photo).absolutePath)
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
