package com.cmhr.listen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiChatMessage
import com.cmhr.listen.data.ai.AiCredentials
import com.cmhr.listen.data.ai.AiCompletion
import com.cmhr.listen.data.ai.AiRequestOptions
import com.cmhr.listen.data.ai.AiConversationEntity
import com.cmhr.listen.data.ai.AiAttachmentEntity
import com.cmhr.listen.data.ai.AiAttachmentKind
import com.cmhr.listen.data.ai.AiAttachmentStore
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.ai.AiRepository
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiServiceClient
import com.cmhr.listen.data.ai.AiStreamPhase
import com.cmhr.listen.data.ai.CorrectionPayload
import com.cmhr.listen.data.ai.CorrectionPayloadCodec
import com.cmhr.listen.data.ai.PendingAiAttachment
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.AiPromptSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiUiState(
    val selectionRecordId: Long? = null,
    val selectedSegmentIds: Set<Long> = emptySet(),
    val contentSelectionScope: AiContentSelectionScope? = null,
    val selectedContentKeys: Set<AiContentKey> = emptySet(),
    val isBusy: Boolean = false,
    val resultStreamPhases: Map<Long, AiStreamPhase> = emptyMap(),
    val messageStreamPhases: Map<Long, AiStreamPhase> = emptyMap(),
    val lastDiagnostics: AiRequestDiagnostics? = null,
    val error: String? = null
)

data class AiRequestDiagnostics(
    val model: String,
    val durationMs: Long?,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val reasoningPresent: Boolean,
    val sourceCodePoints: Int,
    val imageCount: Int,
    val error: String? = null
)

enum class AiContentKind { RESULT, CONVERSATION }
data class AiContentKey(val kind: AiContentKind, val id: Long)
data class AiContentSelectionScope(val recordId: Long?)
data class AiContentItem(
    val key: AiContentKey,
    val recordId: Long?,
    val courseName: String,
    val title: String,
    val updatedAt: Long,
    val status: String,
    val preview: String
)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val database = ListenDatabase.get(application)
    private val attachmentStore = AiAttachmentStore(application)
    private val repository = AiRepository(database, attachmentStore)
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
    fun conversationForResult(resultId: Long) = repository.conversationForResult(resultId)
    fun conversationSourceSegments(conversationId: Long) = repository.conversationSourceSegments(conversationId)
    fun messages(conversationId: Long) = repository.messages(conversationId)
    fun resultAttachments(resultId: Long) = repository.resultAttachments(resultId)
    fun messageAttachments(messageId: Long) = repository.messageAttachments(messageId)
    fun attachmentFile(value: AiAttachmentEntity) = attachmentStore.resolve(value.relativePath)
    fun contents(recordId: Long) = combine(repository.results(recordId), repository.conversations(recordId)) { results, conversations ->
        (results.map { result ->
            AiContentItem(
                key = AiContentKey(AiContentKind.RESULT, result.id),
                recordId = result.recordId,
                courseName = "",
                title = runCatching { AiActionType.valueOf(result.actionType).displayName }.getOrDefault(result.actionType),
                updatedAt = result.finishedAt ?: result.createdAt,
                status = result.status,
                preview = result.output ?: result.errorMessage.orEmpty()
            )
        } + conversations.map { conversation ->
            AiContentItem(
                key = AiContentKey(AiContentKind.CONVERSATION, conversation.id),
                recordId = conversation.recordId,
                courseName = "",
                title = conversation.title,
                updatedAt = conversation.updatedAt,
                status = AiRequestStatus.SUCCESS.name,
                preview = "AI 对话"
            )
        }).sortedByDescending { it.updatedAt }
    }

    fun globalContents() = repository.globalTimeline().map { rows ->
        rows.map { row ->
            val kind = AiContentKind.valueOf(row.kind)
            AiContentItem(
                key = AiContentKey(kind, row.id),
                recordId = row.recordId,
                courseName = row.courseName,
                title = if (kind == AiContentKind.RESULT) {
                    runCatching { AiActionType.valueOf(row.title).displayName }.getOrDefault(row.title)
                } else row.title,
                updatedAt = row.updatedAt,
                status = row.status,
                preview = row.preview
            )
        }
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

    fun replaceSelection(recordId: Long, segmentIds: Set<Long>) {
        _uiState.update { state ->
            state.copy(selectionRecordId = recordId, selectedSegmentIds = segmentIds, error = null)
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selectionRecordId = null, selectedSegmentIds = emptySet()) }
    fun clearError() = _uiState.update { it.copy(error = null) }
    fun reportError(message: String) = _uiState.update { it.copy(error = message) }

    fun beginContentSelection(recordId: Long?) = _uiState.update {
        it.copy(contentSelectionScope = AiContentSelectionScope(recordId), selectedContentKeys = emptySet(), error = null)
    }

    fun toggleContentSelection(recordId: Long?, key: AiContentKey) = _uiState.update { state ->
        val scope = AiContentSelectionScope(recordId)
        val current = if (state.contentSelectionScope == scope) state.selectedContentKeys else emptySet()
        state.copy(
            contentSelectionScope = scope,
            selectedContentKeys = if (key in current) current - key else current + key,
            error = null
        )
    }

    fun replaceContentSelection(recordId: Long?, keys: Set<AiContentKey>) = _uiState.update { state ->
        state.copy(
            contentSelectionScope = AiContentSelectionScope(recordId),
            selectedContentKeys = keys,
            error = null
        )
    }

    fun clearContentSelection() = _uiState.update {
        it.copy(contentSelectionScope = null, selectedContentKeys = emptySet())
    }

    fun applyCorrections(resultId: Long, onComplete: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val result = repository.resultOnce(resultId)
        val encoded = result?.correctionPayload
        if (result == null || encoded.isNullOrBlank()) {
            _uiState.update { it.copy(error = "该 AI 结果没有可应用的结构化纠错内容。") }
            onComplete(false)
            return@launch
        }
        val source = database.transcriptDao().segmentsForResult(resultId)
        val outcome = runCatching {
            val payload = CorrectionPayloadCodec.decode(encoded, source)
            repository.applyCorrections(resultId, payload)
        }
        outcome.exceptionOrNull()?.let { exception ->
            _uiState.update { it.copy(error = safeError(exception as? Exception ?: Exception(exception))) }
        }
        onComplete(outcome.isSuccess)
    }

    fun restoreOriginal(segmentId: Long) = viewModelScope.launch {
        runCatching { repository.restoreOriginal(segmentId) }
            .onFailure { exception ->
                _uiState.update { it.copy(error = safeError(exception as? Exception ?: Exception(exception))) }
            }
    }

    fun prepareAttachment(uri: Uri, kind: AiAttachmentKind, onReady: (PendingAiAttachment?) -> Unit) = viewModelScope.launch {
        val result = runCatching { attachmentStore.prepare(uri, kind) }
        result.exceptionOrNull()?.let { exception ->
            _uiState.update { it.copy(error = safeError(exception as? Exception ?: Exception(exception))) }
        }
        onReady(result.getOrNull())
    }

    fun discardAttachment(attachment: PendingAiAttachment) = attachmentStore.discard(attachment)

    fun deleteSelectedContents(recordId: Long?, onComplete: () -> Unit = {}) {
        val state = _uiState.value
        if (state.contentSelectionScope != AiContentSelectionScope(recordId) || state.selectedContentKeys.isEmpty()) return
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
        if (state.contentSelectionScope != AiContentSelectionScope(recordId) || state.selectedContentKeys.isEmpty()) {
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

    fun buildSelectedGlobalContentsTxt(onReady: (String?) -> Unit) {
        val state = _uiState.value
        if (state.contentSelectionScope != AiContentSelectionScope(null) || state.selectedContentKeys.isEmpty()) {
            onReady(null)
            return
        }
        val keys = state.selectedContentKeys
        viewModelScope.launch {
            data class ExportEntry(val time: Long, val body: String)
            val entries = keys.mapNotNull { key ->
                when (key.kind) {
                    AiContentKind.RESULT -> repository.resultOnce(key.id)?.let { result ->
                        val (courseName, recordName) = ownerNames(result.recordId)
                        ExportEntry(result.createdAt, buildString {
                            appendLine("课程：$courseName")
                            appendLine("记录：$recordName")
                            appendLine("类型：${runCatching { AiActionType.valueOf(result.actionType).displayName }.getOrDefault(result.actionType)}")
                            appendLine("时间：${formatExportTime(result.createdAt)}")
                            appendLine(result.output ?: result.errorMessage ?: "尚无输出")
                        })
                    }
                    AiContentKind.CONVERSATION -> repository.conversationOnce(key.id)?.let { conversation ->
                        val (courseName, recordName) = conversation.recordId?.let { ownerNames(it) }
                            ?: Pair("通用对话", "—")
                        val messages = repository.messagesOnce(conversation.id)
                        ExportEntry(conversation.createdAt, buildString {
                            appendLine("课程：$courseName")
                            appendLine("记录：$recordName")
                            appendLine("类型：AI 对话")
                            appendLine("标题：${conversation.title}")
                            appendLine("时间：${formatExportTime(conversation.createdAt)}")
                            messages.filter { it.status == AiRequestStatus.SUCCESS.name }.forEach { message ->
                                appendLine()
                                appendLine(if (message.role == "user") "我：" else "AI：")
                                appendLine(message.content)
                            }
                        })
                    }
                }
            }.sortedBy { it.time }
            onReady(buildString {
                appendLine("Listen This Lesson · AI 内容导出")
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
        attachments: List<PendingAiAttachment> = emptyList(),
        onCreated: (Long) -> Unit
    ) {
        if (aiJob?.isActive == true) {
            discardAttachments(attachments)
            _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        val selected = selectedSegments(recordId, availableSegments) ?: run {
            discardAttachments(attachments)
            return
        }
        runAction(recordId, action, selected, attachments, clearSelectionAfterCreate = true, onCreated)
    }

    fun runFullRecordAction(
        recordId: Long,
        action: AiActionType,
        availableSegments: List<TranscriptEntity>,
        attachments: List<PendingAiAttachment> = emptyList(),
        onCreated: (Long) -> Unit
    ) {
        if (aiJob?.isActive == true) {
            discardAttachments(attachments)
            _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        val segments = availableSegments.sortedBy { it.startTime }
        if (segments.isEmpty()) {
            discardAttachments(attachments)
            _uiState.update { it.copy(error = "当前课堂记录还没有识别内容。") }
            return
        }
        runAction(recordId, action, segments, attachments, clearSelectionAfterCreate = false, onCreated)
    }

    private fun runAction(
        recordId: Long,
        action: AiActionType,
        segments: List<TranscriptEntity>,
        attachments: List<PendingAiAttachment>,
        clearSelectionAfterCreate: Boolean,
        onCreated: (Long) -> Unit
    ) {
        aiJob = viewModelScope.launch {
            val selected = segments.sortedBy { it.startTime }
            val snapshot = buildSourceSnapshot(selected)
            if (!validateSnapshot(snapshot, clearSelectionAfterCreate)) {
                discardAttachments(attachments)
                return@launch
            }
            val credentials = credentialsOrReport() ?: run {
                discardAttachments(attachments)
                return@launch
            }
            val promptSettings = settingsRepository.settings.first().aiPrompts
            val prompt = buildActionPrompt(
                action = action,
                prompts = promptSettings,
                coursePrompt = if (action == AiActionType.CORRECT_ASR) courseAsrPrompt(recordId) else ""
            )
            val resultId = runCatching {
                repository.createResult(recordId, action, prompt, snapshot, selected.map { it.id }, attachments)
            }.getOrElse { exception ->
                discardAttachments(attachments)
                _uiState.update { it.copy(error = safeError(exception as? Exception ?: Exception(exception))) }
                return@launch
            }
            if (clearSelectionAfterCreate) clearSelection()
            onCreated(resultId)
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                val images = repository.resultAttachmentsOnce(resultId)
                    .filter { it.kind == AiAttachmentKind.IMAGE.name }
                    .map { attachment -> attachmentStore.imageDataUrl(attachment) }
                val completion = streamResult(
                    resultId,
                    credentials,
                    listOf(
                        AiChatMessage("system", prompt),
                        AiChatMessage("user", actionUserMessage(action, selected, snapshot), images)
                    ),
                    requestOptions(chat = false)
                )
                val correction = if (action == AiActionType.CORRECT_ASR) {
                    CorrectionPayloadCodec.decode(completion.content, selected)
                } else null
                val output = correction?.let { CorrectionPayloadCodec.toMarkdown(it, selected) } ?: completion.content
                repository.completeResult(
                    id = resultId,
                    output = output,
                    reasoningContent = completion.reasoningContent,
                    correctionPayload = correction?.let(CorrectionPayloadCodec::encode)
                )
                recordDiagnostics(credentials, completion, snapshot, images.size)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val message = safeError(exception)
                repository.failResult(resultId, message)
                recordFailureDiagnostics(credentials, snapshot, attachments.count { it.kind == AiAttachmentKind.IMAGE }, message)
            } finally {
                clearResultStreamPhase(resultId)
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
                val action = runCatching { AiActionType.valueOf(result.actionType) }.getOrNull()
                val source = repository.resultSourceSegmentsOnce(resultId)
                val images = repository.resultAttachmentsOnce(resultId)
                    .filter { it.kind == AiAttachmentKind.IMAGE.name }
                    .map { attachment -> attachmentStore.imageDataUrl(attachment) }
                val completion = streamResult(
                    resultId,
                    credentials,
                    listOf(
                        AiChatMessage("system", result.requestPrompt),
                        AiChatMessage(
                            "user",
                            actionUserMessage(action, source, result.sourceTextSnapshot),
                            images
                        )
                    ),
                    requestOptions(chat = false)
                )
                val correction = if (action == AiActionType.CORRECT_ASR) {
                    CorrectionPayloadCodec.decode(completion.content, source)
                } else null
                val output = correction?.let { CorrectionPayloadCodec.toMarkdown(it, source) } ?: completion.content
                repository.completeResult(
                    id = resultId,
                    output = output,
                    reasoningContent = completion.reasoningContent,
                    correctionPayload = correction?.let(CorrectionPayloadCodec::encode)
                )
                recordDiagnostics(credentials, completion, result.sourceTextSnapshot, images.size)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val message = safeError(exception)
                repository.failResult(resultId, message)
                recordFailureDiagnostics(credentials, result.sourceTextSnapshot, 0, message)
            } finally {
                clearResultStreamPhase(resultId)
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun createConversation(
        recordId: Long,
        question: String,
        availableSegments: List<TranscriptEntity>,
        attachments: List<PendingAiAttachment> = emptyList(),
        onCreated: (Long) -> Unit
    ) {
        if (aiJob?.isActive == true || question.isBlank()) {
            discardAttachments(attachments)
            if (aiJob?.isActive == true) _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        aiJob = viewModelScope.launch {
            val selected = selectedSegments(recordId, availableSegments) ?: run {
                discardAttachments(attachments)
                return@launch
            }
            val snapshot = buildSourceSnapshot(selected)
            if (!validateSnapshot(snapshot)) {
                discardAttachments(attachments)
                return@launch
            }
            credentialsOrReport() ?: run {
                discardAttachments(attachments)
                return@launch
            }
            val promptSettings = settingsRepository.settings.first().aiPrompts
            val title = question.trim().replace('\n', ' ').take(24).ifBlank { "课堂问答" }
            val conversationId = runCatching {
                repository.createConversation(recordId, title, snapshot, selected.map { it.id }, promptSettings.customConversation)
            }.getOrElse { exception ->
                discardAttachments(attachments)
                _uiState.update { it.copy(error = safeError(exception as? Exception ?: Exception(exception))) }
                return@launch
            }
            clearSelection()
            onCreated(conversationId)
            sendMessageInternal(conversationId, question.trim(), attachments)
        }
    }

    fun createConversationDraft(
        recordId: Long,
        availableSegments: List<TranscriptEntity>,
        onCreated: (Long) -> Unit
    ) {
        if (aiJob?.isActive == true) {
            _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        aiJob = viewModelScope.launch {
            val selected = selectedSegments(recordId, availableSegments) ?: return@launch
            val snapshot = buildSourceSnapshot(selected)
            if (!validateSnapshot(snapshot)) return@launch
            val prompts = settingsRepository.settings.first().aiPrompts
            val conversationId = repository.createConversation(
                recordId = recordId,
                title = "新对话",
                snapshot = snapshot,
                segmentIds = selected.map { it.id },
                systemPrompt = prompts.customConversation
            )
            clearSelection()
            onCreated(conversationId)
        }
    }

    fun sendResultFollowUp(resultId: Long, question: String, attachments: List<PendingAiAttachment> = emptyList()) {
        if (aiJob?.isActive == true || question.isBlank()) {
            discardAttachments(attachments)
            if (aiJob?.isActive == true) _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        aiJob = viewModelScope.launch {
            val result = repository.resultOnce(resultId) ?: run {
                discardAttachments(attachments)
                return@launch
            }
            if (result.status != AiRequestStatus.SUCCESS.name || result.output.isNullOrBlank()) {
                discardAttachments(attachments)
                _uiState.update { it.copy(error = "AI 结果尚未成功生成，暂时不能继续追问。") }
                return@launch
            }
            val conversation = repository.conversationForResultOnce(resultId) ?: run {
                val prompts = settingsRepository.settings.first().aiPrompts
                val action = runCatching { AiActionType.valueOf(result.actionType).displayName }.getOrDefault(result.actionType)
                val id = repository.createConversation(
                    recordId = result.recordId,
                    title = action,
                    snapshot = result.sourceTextSnapshot,
                    segmentIds = emptyList(),
                    systemPrompt = "${prompts.customConversation}\n\n原始处理任务：${result.requestPrompt}",
                    originResultId = result.id
                )
                repository.conversationOnce(id) ?: run {
                    discardAttachments(attachments)
                    return@launch
                }
            }
            sendMessageInternal(conversation.id, question.trim(), attachments)
        }
    }

    fun sendMessage(conversationId: Long, question: String, attachments: List<PendingAiAttachment> = emptyList()) {
        if (aiJob?.isActive == true || question.isBlank()) {
            discardAttachments(attachments)
            if (aiJob?.isActive == true) _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        aiJob = viewModelScope.launch { sendMessageInternal(conversationId, question.trim(), attachments) }
    }

    fun createGeneralConversation(
        question: String,
        attachments: List<PendingAiAttachment> = emptyList(),
        onCreated: (Long) -> Unit
    ) {
        if (aiJob?.isActive == true || question.isBlank()) {
            discardAttachments(attachments)
            if (aiJob?.isActive == true) _uiState.update { it.copy(error = "已有 AI 请求正在处理。") }
            return
        }
        aiJob = viewModelScope.launch {
            credentialsOrReport() ?: run {
                discardAttachments(attachments)
                return@launch
            }
            val conversationId = repository.createConversation(
                recordId = null,
                title = conversationTitle(question, "新对话"),
                snapshot = "",
                segmentIds = emptyList(),
                systemPrompt = settingsRepository.settings.first().aiPrompts.generalConversation
            )
            onCreated(conversationId)
            sendMessageInternal(conversationId, question.trim(), attachments)
        }
    }

    private suspend fun sendMessageInternal(conversationId: Long, question: String, attachments: List<PendingAiAttachment>) {
        val conversation = repository.conversationOnce(conversationId) ?: run {
            discardAttachments(attachments)
            return
        }
        val credentials = credentialsOrReport() ?: run {
            discardAttachments(attachments)
            return
        }
        val messagesBefore = repository.messagesOnce(conversationId)
        val imagePrompt = if (attachments.any { it.kind == AiAttachmentKind.IMAGE }) {
            settingsRepository.settings.first().aiPrompts.imageContext
        } else ""
        val exchange = try {
            repository.insertUserAndPendingAssistant(
                conversationId = conversationId,
                recordId = conversation.recordId,
                question = question,
                contextPrompt = imagePrompt,
                attachments = attachments
            )
        } catch (exception: Exception) {
            discardAttachments(attachments)
            _uiState.update { it.copy(error = safeError(exception)) }
            return
        }
        if (messagesBefore.none { it.role == "user" } && conversation.originResultId == null) {
            repository.renameConversation(conversationId, conversationTitle(question, "课堂问答"))
        }
        _uiState.update { it.copy(isBusy = true, error = null) }
        try {
            val persistedAttachments = repository.conversationAttachmentsOnce(conversationId).groupBy { it.messageId }
            val history = repository.messagesOnce(conversationId)
                .filter { it.status == AiRequestStatus.SUCCESS.name }
                .map { message ->
                    val messageAttachments = persistedAttachments[message.id].orEmpty()
                    val images = messageAttachments.filter { it.kind == AiAttachmentKind.IMAGE.name }
                        .map { attachment -> attachmentStore.imageDataUrl(attachment) }
                    val textFiles = messageAttachments.filter { it.kind == AiAttachmentKind.TEXT.name }
                    val content = buildMessageContent(message.content, message.contextPrompt, textFiles)
                    AiChatMessage(message.role, content, images)
                }
            val originResult = conversation.originResultId?.let { resultId ->
                repository.resultOnce(resultId)
            }
            val contextMessages = buildList {
                add(AiChatMessage("system", conversation.systemPrompt))
                if (conversation.sourceTextSnapshot.isNotBlank()) {
                    add(AiChatMessage("user", "以下内容是本对话冻结的课堂原文，只能依据它回答：\n\n${conversation.sourceTextSnapshot}"))
                }
                originResult?.output?.takeIf { it.isNotBlank() }?.let { add(AiChatMessage("assistant", it)) }
            }
            val completion = streamMessage(
                exchange.assistantMessageId,
                credentials,
                contextMessages + history,
                requestOptions(chat = true)
            )
            repository.completeMessage(
                conversationId,
                exchange.assistantMessageId,
                completion.content,
                completion.reasoningContent
            )
            recordDiagnostics(credentials, completion, conversation.sourceTextSnapshot, attachments.count { it.kind == AiAttachmentKind.IMAGE })
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val message = safeError(exception)
            repository.failMessage(conversationId, exchange.assistantMessageId, message)
            recordFailureDiagnostics(credentials, conversation.sourceTextSnapshot, attachments.count { it.kind == AiAttachmentKind.IMAGE }, message)
        } finally {
            clearMessageStreamPhase(exchange.assistantMessageId)
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    private suspend fun streamResult(
        resultId: Long,
        credentials: AiCredentials,
        messages: List<AiChatMessage>,
        options: AiRequestOptions
    ): AiCompletion {
        var lastDraft = ""
        var lastReasoningDraft = ""
        var latestDraft = ""
        var latestReasoningDraft = ""
        var lastWriteAt = 0L
        return try {
            client.streamChat(credentials, messages, options) { update ->
                latestDraft = update.content
                latestReasoningDraft = update.reasoningContent
                _uiState.update { state ->
                    state.copy(resultStreamPhases = state.resultStreamPhases + (resultId to update.phase))
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if ((latestDraft != lastDraft || latestReasoningDraft != lastReasoningDraft) &&
                    now - lastWriteAt >= STREAM_DRAFT_INTERVAL_MS
                ) {
                    repository.updateResultDraft(resultId, latestDraft, latestReasoningDraft)
                    lastDraft = latestDraft
                    lastReasoningDraft = latestReasoningDraft
                    lastWriteAt = now
                }
            }
        } catch (exception: Exception) {
            if (exception !is CancellationException &&
                (latestDraft != lastDraft || latestReasoningDraft != lastReasoningDraft)
            ) {
                repository.updateResultDraft(resultId, latestDraft, latestReasoningDraft)
            }
            throw exception
        }
    }

    private suspend fun streamMessage(
        messageId: Long,
        credentials: AiCredentials,
        messages: List<AiChatMessage>,
        options: AiRequestOptions
    ): AiCompletion {
        var lastDraft = ""
        var lastReasoningDraft = ""
        var latestDraft = ""
        var latestReasoningDraft = ""
        var lastWriteAt = 0L
        return try {
            client.streamChat(credentials, messages, options) { update ->
                latestDraft = update.content
                latestReasoningDraft = update.reasoningContent
                _uiState.update { state ->
                    state.copy(messageStreamPhases = state.messageStreamPhases + (messageId to update.phase))
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if ((latestDraft != lastDraft || latestReasoningDraft != lastReasoningDraft) &&
                    now - lastWriteAt >= STREAM_DRAFT_INTERVAL_MS
                ) {
                    repository.updateMessageDraft(messageId, latestDraft, latestReasoningDraft)
                    lastDraft = latestDraft
                    lastReasoningDraft = latestReasoningDraft
                    lastWriteAt = now
                }
            }
        } catch (exception: Exception) {
            if (exception !is CancellationException &&
                (latestDraft != lastDraft || latestReasoningDraft != lastReasoningDraft)
            ) {
                repository.updateMessageDraft(messageId, latestDraft, latestReasoningDraft)
            }
            throw exception
        }
    }

    private fun clearResultStreamPhase(resultId: Long) = _uiState.update { state ->
        state.copy(resultStreamPhases = state.resultStreamPhases - resultId)
    }

    private fun clearMessageStreamPhase(messageId: Long) = _uiState.update { state ->
        state.copy(messageStreamPhases = state.messageStreamPhases - messageId)
    }

    private suspend fun courseAsrPrompt(recordId: Long): String {
        val record = database.recordDao().record(recordId).first() ?: return ""
        return database.courseDao().course(record.courseId).first()?.asrPrompt.orEmpty()
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
        return AiCredentials(settings.baseUrl, apiKey, settings.model, settings.provider)
    }

    private suspend fun requestOptions(chat: Boolean): AiRequestOptions {
        val settings = settingsRepository.settings.first().aiGeneration
        return AiRequestOptions(
            maxTokens = settings.maxTokens,
            temperature = (if (chat) settings.chatTemperature else settings.fixedTemperature).toDouble(),
            thinkingMode = settings.deepSeekThinkingMode,
            reasoningEffort = settings.reasoningEffort
        )
    }

    private fun recordDiagnostics(
        credentials: AiCredentials,
        completion: AiCompletion,
        source: String,
        imageCount: Int
    ) = _uiState.update {
        it.copy(
            error = null,
            lastDiagnostics = AiRequestDiagnostics(
                model = credentials.model,
                durationMs = completion.durationMs,
                finishReason = completion.finishReason,
                promptTokens = completion.promptTokens,
                completionTokens = completion.completionTokens,
                reasoningPresent = completion.reasoningPresent,
                sourceCodePoints = source.codePointCount(0, source.length),
                imageCount = imageCount
            )
        )
    }

    private fun recordFailureDiagnostics(
        credentials: AiCredentials,
        source: String,
        imageCount: Int,
        message: String
    ) = _uiState.update {
        it.copy(
            error = message,
            lastDiagnostics = AiRequestDiagnostics(
                model = credentials.model,
                durationMs = null,
                finishReason = null,
                promptTokens = null,
                completionTokens = null,
                reasoningPresent = false,
                sourceCodePoints = source.codePointCount(0, source.length),
                imageCount = imageCount,
                error = message
            )
        )
    }

    private fun safeError(exception: Exception): String =
        exception.message?.takeIf { it.isNotBlank() } ?: "AI 请求失败。"

    private suspend fun ownerNames(recordId: Long): Pair<String, String> {
        val record = database.recordDao().record(recordId).first() ?: return Pair("未知课程", "未知记录")
        val course = database.courseDao().course(record.courseId).first()
        return Pair(course?.name ?: "未知课程", record.name)
    }

    private suspend fun buildMessageContent(
        content: String,
        contextPrompt: String,
        textAttachments: List<AiAttachmentEntity>
    ): String = buildString {
        append(content)
        if (contextPrompt.isNotBlank()) append("\n\n").append(contextPrompt)
        textAttachments.forEach { attachment ->
            val text = attachmentStore.textContent(attachment)
            append("\n\n附件：").append(attachment.displayName).append("\n```\n")
            append(text)
            append("\n```")
        }
    }

    private fun discardAttachments(attachments: List<PendingAiAttachment>) = attachments.forEach(attachmentStore::discard)

    private fun formatExportTime(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))

    companion object {
        private const val STREAM_DRAFT_INTERVAL_MS = 100L
        const val MAX_SOURCE_CODE_POINTS = 20_000
        fun isSourceWithinLimit(value: String): Boolean =
            value.codePointCount(0, value.length) <= MAX_SOURCE_CODE_POINTS

        fun conversationTitle(question: String, fallback: String = "新对话"): String {
            val normalized = question.trim().replace(Regex("\\s+"), " ")
            if (normalized.isBlank()) return fallback
            val count = normalized.codePointCount(0, normalized.length).coerceAtMost(24)
            return normalized.substring(0, normalized.offsetByCodePoints(0, count))
        }

        fun buildSourceSnapshot(segments: List<TranscriptEntity>): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return segments.sortedBy { it.startTime }.joinToString("\n\n") { segment ->
                "[${formatter.format(Date(segment.startTime))}]\n${segment.effectiveText}"
            }
        }

        fun buildCorrectionSource(segments: List<TranscriptEntity>): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return segments.sortedBy { it.startTime }.joinToString("\n\n") { segment ->
                "<segment id=\"${segment.id}\" time=\"${formatter.format(Date(segment.startTime))}\">\n" +
                    segment.effectiveText + "\n</segment>"
            }
        }

        fun buildActionPrompt(action: AiActionType, prompts: AiPromptSettings, coursePrompt: String = ""): String {
            if (action != AiActionType.CORRECT_ASR) return promptFor(action, prompts)
            return buildString {
                appendLine("你是严谨的中文课堂 ASR 校对器。必须逐段审校明显的错字、同音词、专业术语、断句和标点，同时严格保持原意。")
                appendLine("不要复述时间标签，不要用（?）或类似占位符代替校对；无法可靠判断时保留原词，并在 changes 中说明不确定。")
                appendLine("只返回一个 JSON 对象，不得使用 Markdown 代码围栏或附加说明。格式必须是：")
                appendLine("{\"segments\":[{\"segmentId\":123,\"correctedText\":\"纠正后的完整片段\",\"changes\":[\"原词 → 新词：理由\"]}]}")
                appendLine("每个输入 segment 必须且只能出现一次，segmentId 必须原样返回；即使无需修改也必须返回该片段，changes 使用空数组。")
                appendLine()
                appendLine("补充校对规则：${prompts.correctAsr.trim()}")
                if (coursePrompt.isNotBlank()) {
                    appendLine()
                    appendLine("课程专业词参考（只能用于辅助判断，不得凭空插入）：${coursePrompt.trim()}")
                }
            }.trim()
        }

        fun actionUserMessage(action: AiActionType?, segments: List<TranscriptEntity>, snapshot: String): String =
            if (action == AiActionType.CORRECT_ASR) {
                "请按协议校对以下课堂转写：\n\n${buildCorrectionSource(segments)}"
            } else {
                "以下是按时间排列的课堂原文：\n\n$snapshot"
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
