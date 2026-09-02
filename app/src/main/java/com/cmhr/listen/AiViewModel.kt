package com.cmhr.listen

import android.app.Application
import android.net.Uri
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiChatMessage
import com.cmhr.listen.data.ai.AiCredentials
import com.cmhr.listen.data.ai.AiConversationEntity
import com.cmhr.listen.data.ai.AiImageAttachmentEntity
import com.cmhr.listen.data.ai.AiPhotoStore
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.ai.AiRepository
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiServiceClient
import com.cmhr.listen.data.ai.PendingAiPhoto
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.AiPromptSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiUiState(
    val selectionRecordId: Long? = null,
    val selectedSegmentIds: Set<Long> = emptySet(),
    val contentSelectionRecordId: Long? = null,
    val selectedContentKeys: Set<AiContentKey> = emptySet(),
    val isBusy: Boolean = false,
    val error: String? = null
)

enum class AiContentKind { RESULT, CONVERSATION }
data class AiContentKey(val kind: AiContentKind, val id: Long)
data class AiContentItem(
    val key: AiContentKey,
    val title: String,
    val updatedAt: Long,
    val status: String,
    val preview: String
)
data class AiCaptureTarget(val file: File, val uri: Uri)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val database = ListenDatabase.get(application)
    private val photoStore = AiPhotoStore(application)
    private val repository = AiRepository(database, photoStore)
    private val settingsRepository = AppSettingsRepository(application)
    private val client = AiServiceClient()
    private val _uiState = MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()
    private var aiJob: Job? = null

    fun results(recordId: Long) = repository.results(recordId)
    fun result(resultId: Long) = repository.result(resultId)
    fun resultSourceSegments(resultId: Long) = repository.resultSourceSegments(resultId)
    fun conversations(recordId: Long) = repository.conversations(recordId)
    fun conversation(conversationId: Long) = repository.conversation(conversationId)
    fun conversationSourceSegments(conversationId: Long) = repository.conversationSourceSegments(conversationId)
    fun messages(conversationId: Long) = repository.messages(conversationId)
    fun resultAttachments(resultId: Long) = repository.resultAttachments(resultId)
    fun messageAttachments(messageId: Long) = repository.messageAttachments(messageId)
    fun attachmentFile(value: AiImageAttachmentEntity) = photoStore.resolve(value.relativePath)
    fun contents(recordId: Long) = combine(repository.results(recordId), repository.conversations(recordId)) { results, conversations ->
        (results.map { result ->
            AiContentItem(
                key = AiContentKey(AiContentKind.RESULT, result.id),
                title = runCatching { AiActionType.valueOf(result.actionType).displayName }.getOrDefault(result.actionType),
                updatedAt = result.finishedAt ?: result.createdAt,
                status = result.status,
                preview = result.output ?: result.errorMessage.orEmpty()
            )
        } + conversations.map { conversation ->
            AiContentItem(
                key = AiContentKey(AiContentKind.CONVERSATION, conversation.id),
                title = conversation.title,
                updatedAt = conversation.updatedAt,
                status = AiRequestStatus.SUCCESS.name,
                preview = "AI 对话"
            )
        }).sortedByDescending { it.updatedAt }
    }

    fun beginSelection(recordId: Long) {
        _uiState.update {
            it.copy(selectionRecordId = recordId, selectedSegmentIds = emptySet(), error = null)
        }
    }

    fun toggleSelection(recordId: Long, segmentId: Long) {
        _uiState.update { state ->
            val current = if (state.selectionRecordId == recordId) state.selectedSegmentIds else emptySet()
            val updated = if (segmentId in current) current - segmentId else current + segmentId
            state.copy(
                selectionRecordId = recordId,
                selectedSegmentIds = updated,
                error = null
            )
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selectionRecordId = null, selectedSegmentIds = emptySet()) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    fun beginContentSelection(recordId: Long) = _uiState.update {
        it.copy(contentSelectionRecordId = recordId, selectedContentKeys = emptySet(), error = null)
    }

    fun toggleContentSelection(recordId: Long, key: AiContentKey) = _uiState.update { state ->
        val current = if (state.contentSelectionRecordId == recordId) state.selectedContentKeys else emptySet()
        state.copy(
            contentSelectionRecordId = recordId,
            selectedContentKeys = if (key in current) current - key else current + key,
            error = null
        )
    }

    fun clearContentSelection() = _uiState.update {
        it.copy(contentSelectionRecordId = null, selectedContentKeys = emptySet())
    }

    fun createCaptureTarget(): AiCaptureTarget {
        val file = photoStore.createCaptureFile()
        return AiCaptureTarget(file, photoStore.captureUri(file))
    }

    fun prepareCapturedPhoto(file: File, onReady: (PendingAiPhoto?) -> Unit) = viewModelScope.launch {
        val result = runCatching { photoStore.prepareCapture(file) }
        result.exceptionOrNull()?.let { exception ->
            _uiState.update { it.copy(error = safeError(exception as? Exception ?: Exception(exception))) }
        }
        onReady(result.getOrNull())
    }

    fun discardPhoto(photo: PendingAiPhoto) = photoStore.discard(photo)

    fun deleteSelectedContents(recordId: Long, onComplete: () -> Unit = {}) {
        val state = _uiState.value
        if (state.contentSelectionRecordId != recordId || state.selectedContentKeys.isEmpty()) return
        if (state.isBusy) {
            _uiState.update { it.copy(error = "AI 正在处理，请完成后再删除。") }
            return
        }
        val keys = state.selectedContentKeys
        viewModelScope.launch {
            keys.forEach { key ->
                when (key.kind) {
                    AiContentKind.RESULT -> repository.deleteResult(key.id)
                    AiContentKind.CONVERSATION -> repository.deleteConversation(key.id)
                }
            }
            clearContentSelection()
            onComplete()
        }
    }

    fun buildSelectedContentsTxt(
        recordId: Long,
        courseName: String,
        recordName: String,
        onReady: (String?) -> Unit
    ) {
        val state = _uiState.value
        if (state.contentSelectionRecordId != recordId || state.selectedContentKeys.isEmpty()) {
            onReady(null)
            return
        }
        val keys = state.selectedContentKeys
        viewModelScope.launch {
            data class ExportEntry(val time: Long, val body: String)
            val entries = keys.mapNotNull { key ->
                when (key.kind) {
                    AiContentKind.RESULT -> repository.resultOnce(key.id)?.let { result ->
                        val photoCount = repository.resultAttachmentsOnce(result.id).size
                        ExportEntry(result.createdAt, buildString {
                            appendLine("类型：${runCatching { AiActionType.valueOf(result.actionType).displayName }.getOrDefault(result.actionType)}")
                            appendLine("时间：${formatExportTime(result.createdAt)}")
                            if (photoCount > 0) appendLine("附加照片：$photoCount 张")
                            appendLine(result.output ?: result.errorMessage ?: "尚无输出")
                        })
                    }
                    AiContentKind.CONVERSATION -> repository.conversationOnce(key.id)?.let { conversation ->
                        val messages = repository.messagesOnce(conversation.id)
                        ExportEntry(conversation.createdAt, buildString {
                            appendLine("类型：AI 对话")
                            appendLine("标题：${conversation.title}")
                            appendLine("时间：${formatExportTime(conversation.createdAt)}")
                            messages.forEach { message ->
                                if (message.status == AiRequestStatus.SUCCESS.name) {
                                    appendLine()
                                    appendLine(if (message.role == "user") "我：" else "AI：")
                                    appendLine(message.content)
                                }
                            }
                        })
                    }
                }
            }.sortedBy { it.time }
            onReady(buildString {
                appendLine("课程：$courseName")
                appendLine("记录：$recordName")
                appendLine()
                entries.forEachIndexed { index, entry ->
                    if (index > 0) appendLine("--------------------")
                    appendLine(entry.body.trimEnd())
                    appendLine()
                }
            })
        }
    }

    fun runFixedAction(
        recordId: Long,
        action: AiActionType,
        availableSegments: List<TranscriptEntity>,
        photos: List<PendingAiPhoto> = emptyList(),
        onCreated: (Long) -> Unit
    ) {
        if (aiJob?.isActive == true) {
            discardPhotos(photos)
            _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        val selected = selectedSegments(recordId, availableSegments) ?: run {
            discardPhotos(photos)
            return
        }
        runAction(recordId, action, selected, photos, clearSelectionAfterCreate = true, onCreated)
    }

    fun runFullRecordAction(
        recordId: Long,
        action: AiActionType,
        availableSegments: List<TranscriptEntity>,
        photos: List<PendingAiPhoto> = emptyList(),
        onCreated: (Long) -> Unit
    ) {
        if (aiJob?.isActive == true) {
            discardPhotos(photos)
            _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        val segments = availableSegments.sortedBy { it.startTime }
        if (segments.isEmpty()) {
            discardPhotos(photos)
            _uiState.update { it.copy(error = "当前课堂记录还没有识别内容。") }
            return
        }
        runAction(recordId, action, segments, photos, clearSelectionAfterCreate = false, onCreated)
    }

    private fun runAction(
        recordId: Long,
        action: AiActionType,
        segments: List<TranscriptEntity>,
        photos: List<PendingAiPhoto>,
        clearSelectionAfterCreate: Boolean,
        onCreated: (Long) -> Unit
    ) {
        aiJob = viewModelScope.launch {
            val selected = segments.sortedBy { it.startTime }
            val snapshot = buildSourceSnapshot(selected)
            if (!validateSnapshot(snapshot, clearSelectionAfterCreate)) {
                discardPhotos(photos)
                return@launch
            }
            val credentials = credentialsOrReport() ?: run {
                discardPhotos(photos)
                return@launch
            }
            val promptSettings = settingsRepository.settings.first().aiPrompts
            val prompt = promptFor(action, promptSettings) + if (photos.isNotEmpty()) "\n\n${promptSettings.imageContext}" else ""
            val resultId = runCatching {
                repository.createResult(recordId, action, prompt, snapshot, selected.map { it.id }, photos)
            }.getOrElse { exception ->
                discardPhotos(photos)
                _uiState.update { it.copy(error = safeError(exception as? Exception ?: Exception(exception))) }
                return@launch
            }
            if (clearSelectionAfterCreate) clearSelection()
            onCreated(resultId)
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                val images = repository.resultAttachmentsOnce(resultId).map { photoStore.dataUrl(it.relativePath, it.mimeType) }
                val output = client.chat(
                    credentials,
                    listOf(
                        AiChatMessage("system", prompt),
                        AiChatMessage("user", "以下是按时间排列的课堂原文：\n\n$snapshot", images)
                    ),
                    temperature = 0.2
                )
                repository.completeResult(resultId, output)
            } catch (exception: Exception) {
                val message = safeError(exception)
                repository.failResult(resultId, message)
                _uiState.update { it.copy(error = message) }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun retryResult(resultId: Long) {
        if (aiJob?.isActive == true) return
        aiJob = viewModelScope.launch {
            val result = repository.resultOnce(resultId) ?: return@launch
            val credentials = credentialsOrReport() ?: return@launch
            repository.markResultPending(resultId)
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                val images = repository.resultAttachmentsOnce(resultId).map { photoStore.dataUrl(it.relativePath, it.mimeType) }
                val output = client.chat(
                    credentials,
                    listOf(
                        AiChatMessage("system", result.requestPrompt),
                        AiChatMessage("user", "以下是按时间排列的课堂原文：\n\n${result.sourceTextSnapshot}", images)
                    ),
                    temperature = 0.2
                )
                repository.completeResult(resultId, output)
            } catch (exception: Exception) {
                val message = safeError(exception)
                repository.failResult(resultId, message)
                _uiState.update { it.copy(error = message) }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun createConversation(
        recordId: Long,
        question: String,
        availableSegments: List<TranscriptEntity>,
        photos: List<PendingAiPhoto> = emptyList(),
        onCreated: (Long) -> Unit
    ) {
        if (aiJob?.isActive == true || question.isBlank()) {
            discardPhotos(photos)
            if (aiJob?.isActive == true) _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        aiJob = viewModelScope.launch {
            val selected = selectedSegments(recordId, availableSegments) ?: run {
                discardPhotos(photos)
                return@launch
            }
            val snapshot = buildSourceSnapshot(selected)
            if (!validateSnapshot(snapshot)) {
                discardPhotos(photos)
                return@launch
            }
            credentialsOrReport() ?: run {
                discardPhotos(photos)
                return@launch
            }
            val promptSettings = settingsRepository.settings.first().aiPrompts
            val title = question.trim().replace('\n', ' ').take(24).ifBlank { "课堂问答" }
            val conversationId = runCatching {
                repository.createConversation(recordId, title, snapshot, selected.map { it.id }, promptSettings.customConversation)
            }.getOrElse { exception ->
                discardPhotos(photos)
                _uiState.update { it.copy(error = safeError(exception as? Exception ?: Exception(exception))) }
                return@launch
            }
            clearSelection()
            onCreated(conversationId)
            sendMessageInternal(conversationId, question.trim(), photos)
        }
    }

    fun sendMessage(conversationId: Long, question: String, photos: List<PendingAiPhoto> = emptyList()) {
        if (aiJob?.isActive == true || question.isBlank()) {
            discardPhotos(photos)
            if (aiJob?.isActive == true) _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        aiJob = viewModelScope.launch { sendMessageInternal(conversationId, question.trim(), photos) }
    }

    private suspend fun sendMessageInternal(conversationId: Long, question: String, photos: List<PendingAiPhoto>) {
        val conversation = repository.conversationOnce(conversationId) ?: run {
            discardPhotos(photos)
            return
        }
        val credentials = credentialsOrReport() ?: run {
            discardPhotos(photos)
            return
        }
        val imagePrompt = if (photos.isNotEmpty()) settingsRepository.settings.first().aiPrompts.imageContext else ""
        val exchange = repository.insertUserAndPendingAssistant(
            conversationId = conversationId,
            recordId = conversation.recordId,
            question = question,
            contextPrompt = imagePrompt,
            photos = photos
        )
        _uiState.update { it.copy(isBusy = true, error = null) }
        try {
            val attachments = repository.conversationAttachmentsOnce(conversationId).groupBy { it.messageId }
            val history = repository.messagesOnce(conversationId)
                .filter { it.status == AiRequestStatus.SUCCESS.name }
                .map { message ->
                    val images = attachments[message.id].orEmpty().map { photoStore.dataUrl(it.relativePath, it.mimeType) }
                    val content = if (message.contextPrompt.isBlank()) message.content else "${message.content}\n\n${message.contextPrompt}"
                    AiChatMessage(message.role, content, images)
                }
            val output = client.chat(
                credentials,
                listOf(
                    AiChatMessage("system", conversation.systemPrompt),
                    AiChatMessage("user", "以下内容是本对话冻结的课堂原文，只能依据它回答：\n\n${conversation.sourceTextSnapshot}")
                ) + history,
                temperature = 0.7
            )
            repository.completeMessage(conversationId, exchange.assistantMessageId, output)
        } catch (exception: Exception) {
            val message = safeError(exception)
            repository.failMessage(conversationId, exchange.assistantMessageId, message)
            _uiState.update { it.copy(error = message) }
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    private fun selectedSegments(recordId: Long, available: List<TranscriptEntity>): List<TranscriptEntity>? {
        val ids = _uiState.value.takeIf { it.selectionRecordId == recordId }?.selectedSegmentIds.orEmpty()
        val selected = available.filter { it.id in ids }.sortedBy { it.startTime }
        if (selected.isEmpty()) _uiState.update { it.copy(error = "请先选择识别片段。") }
        return selected.takeIf { it.isNotEmpty() }
    }

    private fun validateSnapshot(snapshot: String, selectedOnly: Boolean = true): Boolean {
        if (isSourceWithinLimit(snapshot)) return true
        val message = if (selectedOnly) {
            "选中内容超过 20,000 个字符，请减少选择后重试。"
        } else {
            "整条记录超过 20,000 个字符，请使用“选择”缩小范围。"
        }
        _uiState.update { it.copy(error = message) }
        return false
    }

    private suspend fun credentialsOrReport(): AiCredentials? {
        val settings = settingsRepository.settings.first().ai
        val apiKey = runCatching { settingsRepository.readAiApiKey().orEmpty() }.getOrDefault("")
        val message = when {
            settings.baseUrl.isBlank() -> "请先在设置中配置 AI 服务地址。"
            settings.model.isBlank() -> "请先在设置中配置 AI 模型。"
            apiKey.isBlank() -> "请先在设置中填写 AI API Key。"
            else -> null
        }
        if (message != null) {
            _uiState.update { it.copy(error = message) }
            return null
        }
        return AiCredentials(settings.baseUrl, apiKey, settings.model)
    }

    private fun safeError(exception: Exception): String =
        exception.message?.takeIf { it.isNotBlank() } ?: "AI 请求失败。"

    private fun discardPhotos(photos: List<PendingAiPhoto>) = photos.forEach(photoStore::discard)

    private fun formatExportTime(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))

    companion object {
        const val MAX_SOURCE_CODE_POINTS = 20_000
        fun isSourceWithinLimit(value: String): Boolean =
            value.codePointCount(0, value.length) <= MAX_SOURCE_CODE_POINTS
        fun buildSourceSnapshot(segments: List<TranscriptEntity>): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return segments.sortedBy { it.startTime }.joinToString("\n\n") { segment ->
                "[${formatter.format(Date(segment.startTime))}]\n${segment.text}"
            }
        }

        fun promptFor(action: AiActionType): String = promptFor(action, AiPromptSettings())

        fun promptFor(action: AiActionType, prompts: AiPromptSettings): String = when (action) {
            AiActionType.SUMMARY -> prompts.summary
            AiActionType.CORRECT_ASR -> prompts.correctAsr
            AiActionType.EXTRACT_QUESTIONS -> "你是课堂问题提取助手。只提取老师在原文中明确提出的问题，按出现顺序列出；无法确定说话人或没有明确问题时如实说明，不得自行补充问题。"
            AiActionType.ORGANIZE_NOTES -> prompts.organizeNotes
            AiActionType.QUICK_ANSWER -> prompts.quickAnswer
        }
    }
}
