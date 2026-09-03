package com.cmhr.listen.data.ai

import androidx.room.withTransaction
import com.cmhr.listen.data.course.ListenDatabase

data class PendingAiExchange(val userMessageId: Long, val assistantMessageId: Long)

class AiRepository(
    private val database: ListenDatabase,
    private val attachmentStore: AiAttachmentStore? = null
) {
    private val dao = database.aiDao()

    fun globalTimeline() = dao.globalTimeline()

    fun results(recordId: Long) = dao.results(recordId)
    fun allResults() = dao.allResults()
    fun result(id: Long) = dao.result(id)
    fun resultSourceSegments(id: Long) = dao.resultSourceSegments(id)
    suspend fun resultSourceSegmentsOnce(id: Long) = database.transcriptDao().segmentsForResult(id)
    fun conversations(recordId: Long) = dao.conversations(recordId)
    fun allConversations() = dao.allConversations()
    fun conversation(id: Long) = dao.conversation(id)
    fun conversationForResult(resultId: Long) = dao.conversationForResult(resultId)
    fun conversationSourceSegments(id: Long) = dao.conversationSourceSegments(id)
    fun messages(conversationId: Long) = dao.messages(conversationId)
    fun resultAttachments(resultId: Long) = dao.resultAttachments(resultId)
    fun messageAttachments(messageId: Long) = dao.messageAttachments(messageId)

    suspend fun createResult(
        recordId: Long,
        action: AiActionType,
        prompt: String,
        snapshot: String,
        segmentIds: List<Long>,
        attachments: List<PendingAiAttachment> = emptyList()
    ): Long = database.withTransaction {
        val id = dao.insertResult(AiResultEntity(
            recordId = recordId,
            actionType = action.name,
            requestPrompt = prompt,
            sourceTextSnapshot = snapshot,
            createdAt = System.currentTimeMillis()
        ))
        dao.insertResultSegments(segmentIds.distinct().map { AiResultSegmentEntity(id, it) })
        id
    }.also { resultId -> attach(recordId, resultId, null, "record_$recordId/result_$resultId", attachments) }

    suspend fun resultOnce(id: Long) = dao.resultOnce(id)
    suspend fun markResultPending(id: Long) = dao.markResultPending(id)
    suspend fun updateResultDraft(id: Long, output: String, reasoningContent: String) =
        dao.updateResultDraft(id, output, reasoningContent)
    suspend fun completeResult(
        id: Long,
        output: String,
        reasoningContent: String = "",
        correctionPayload: String? = null
    ) = dao.completeResult(id, output, reasoningContent, correctionPayload, System.currentTimeMillis())
    suspend fun failResult(id: Long, message: String) = dao.failResult(id, message, System.currentTimeMillis())
    suspend fun resultAttachmentsOnce(id: Long) = dao.resultAttachmentsOnce(id)
    suspend fun deleteResult(id: Long) {
        val paths = dao.resultAttachmentPaths(id)
        database.withTransaction {
            database.transcriptDao().detachCorrectionResult(id)
            dao.deleteResult(id)
        }
        attachmentStore?.deleteRelativePaths(paths)
    }

    suspend fun applyCorrections(resultId: Long, payload: CorrectionPayload) = database.withTransaction {
        val result = requireNotNull(dao.resultOnce(resultId)) { "AI 纠错结果不存在。" }
        require(result.actionType == AiActionType.CORRECT_ASR.name) { "该结果不是 ASR 纠错结果。" }
        val source = database.transcriptDao().segmentsForResult(resultId)
        val expected = source.map { it.id }.toSet()
        require(payload.segments.map { it.segmentId }.toSet() == expected) { "纠错内容与来源片段不一致。" }
        val appliedAt = System.currentTimeMillis()
        payload.segments.forEach { correction ->
            check(database.transcriptDao().applyCorrection(
                recordId = result.recordId,
                segmentId = correction.segmentId,
                resultId = resultId,
                correctedText = correction.correctedText.trim(),
                correctedAt = appliedAt
            ) == 1) { "无法应用片段 #${correction.segmentId} 的纠错。" }
        }
    }

    suspend fun restoreOriginal(segmentId: Long) {
        database.transcriptDao().restoreOriginal(segmentId)
    }

    suspend fun createConversation(
        recordId: Long?,
        title: String,
        snapshot: String,
        segmentIds: List<Long>,
        systemPrompt: String = DEFAULT_CONVERSATION_PROMPT,
        originResultId: Long? = null
    ): Long = database.withTransaction {
        val now = System.currentTimeMillis()
        val id = dao.insertConversation(AiConversationEntity(
            recordId = recordId,
            title = title,
            sourceTextSnapshot = snapshot,
            systemPrompt = systemPrompt,
            originResultId = originResultId,
            createdAt = now,
            updatedAt = now
        ))
        dao.insertConversationSegments(segmentIds.distinct().map { AiConversationSegmentEntity(id, it) })
        id
    }

    suspend fun conversationOnce(id: Long) = dao.conversationOnce(id)
    suspend fun conversationForResultOnce(resultId: Long) = dao.conversationForResultOnce(resultId)
    suspend fun messagesOnce(id: Long) = dao.messagesOnce(id)
    suspend fun renameConversation(id: Long, title: String) = dao.renameConversation(id, title, System.currentTimeMillis())
    suspend fun conversationAttachmentsOnce(id: Long) = dao.conversationAttachmentsOnce(id)
    suspend fun insertUserAndPendingAssistant(
        conversationId: Long,
        recordId: Long?,
        question: String,
        contextPrompt: String = "",
        attachments: List<PendingAiAttachment> = emptyList()
    ): PendingAiExchange {
        val exchange = database.withTransaction {
        val now = System.currentTimeMillis()
        val userId = dao.insertMessage(AiMessageEntity(
            conversationId = conversationId,
            role = "user",
            content = question,
            contextPrompt = contextPrompt,
            createdAt = now
        ))
        val pendingId = dao.insertMessage(AiMessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = "",
            status = AiRequestStatus.PENDING.name,
            createdAt = now + 1
        ))
        dao.touchConversation(conversationId, now)
        PendingAiExchange(userId, pendingId)
        }
        val owner = recordId?.let { "record_$it" } ?: "general"
        attach(recordId, null, exchange.userMessageId, "$owner/conversation_$conversationId/message_${exchange.userMessageId}", attachments)
        return exchange
    }

    suspend fun completeMessage(conversationId: Long, messageId: Long, content: String, reasoningContent: String = "") {
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.completeMessage(messageId, content, reasoningContent, now)
            dao.touchConversation(conversationId, now)
        }
    }

    suspend fun updateMessageDraft(messageId: Long, content: String, reasoningContent: String) =
        dao.updateMessageDraft(messageId, content, reasoningContent)

    suspend fun failMessage(conversationId: Long, messageId: Long, message: String) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.failMessage(messageId, message, now)
            dao.touchConversation(conversationId, now)
        }
    }

    suspend fun deleteConversation(id: Long) {
        val paths = dao.conversationAttachmentPaths(id)
        dao.deleteConversation(id)
        attachmentStore?.deleteRelativePaths(paths)
    }

    private suspend fun attach(
        recordId: Long?,
        resultId: Long?,
        messageId: Long?,
        ownerDirectory: String,
        attachments: List<PendingAiAttachment>
    ) {
        if (attachments.isEmpty()) return
        val store = requireNotNull(attachmentStore) { "附件存储未初始化。" }
        val persisted = store.persist(ownerDirectory, attachments)
        dao.insertAttachments(persisted.map { attachment ->
            AiAttachmentEntity(
                recordId = recordId,
                resultId = resultId,
                messageId = messageId,
                kind = attachment.kind.name,
                displayName = attachment.displayName,
                relativePath = attachment.absolutePath,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                width = attachment.width,
                height = attachment.height,
                createdAt = System.currentTimeMillis()
            )
        })
    }
}
