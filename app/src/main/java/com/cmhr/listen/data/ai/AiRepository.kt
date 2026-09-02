package com.cmhr.listen.data.ai

import androidx.room.withTransaction
import com.cmhr.listen.data.course.ListenDatabase

data class PendingAiExchange(val userMessageId: Long, val assistantMessageId: Long)

class AiRepository(
    private val database: ListenDatabase,
    private val photoStore: AiPhotoStore? = null
) {
    private val dao = database.aiDao()

    fun results(recordId: Long) = dao.results(recordId)
    fun result(id: Long) = dao.result(id)
    fun resultSourceSegments(id: Long) = dao.resultSourceSegments(id)
    fun conversations(recordId: Long) = dao.conversations(recordId)
    fun conversation(id: Long) = dao.conversation(id)
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
        photos: List<PendingAiPhoto> = emptyList()
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
    }.also { resultId -> attachPhotos(recordId, resultId, null, "result_$resultId", photos) }

    suspend fun resultOnce(id: Long) = dao.resultOnce(id)
    suspend fun markResultPending(id: Long) = dao.markResultPending(id)
    suspend fun completeResult(id: Long, output: String) = dao.completeResult(id, output, System.currentTimeMillis())
    suspend fun failResult(id: Long, message: String) = dao.failResult(id, message, System.currentTimeMillis())
    suspend fun resultAttachmentsOnce(id: Long) = dao.resultAttachmentsOnce(id)
    suspend fun deleteResult(id: Long) {
        val paths = dao.resultAttachmentPaths(id)
        dao.deleteResult(id)
        photoStore?.deleteRelativePaths(paths)
    }

    suspend fun createConversation(
        recordId: Long,
        title: String,
        snapshot: String,
        segmentIds: List<Long>,
        systemPrompt: String = DEFAULT_CONVERSATION_PROMPT
    ): Long = database.withTransaction {
        val now = System.currentTimeMillis()
        val id = dao.insertConversation(AiConversationEntity(
            recordId = recordId,
            title = title,
            sourceTextSnapshot = snapshot,
            systemPrompt = systemPrompt,
            createdAt = now,
            updatedAt = now
        ))
        dao.insertConversationSegments(segmentIds.distinct().map { AiConversationSegmentEntity(id, it) })
        id
    }

    suspend fun conversationOnce(id: Long) = dao.conversationOnce(id)
    suspend fun messagesOnce(id: Long) = dao.messagesOnce(id)
    suspend fun conversationAttachmentsOnce(id: Long) = dao.conversationAttachmentsOnce(id)
    suspend fun insertUserAndPendingAssistant(
        conversationId: Long,
        recordId: Long,
        question: String,
        contextPrompt: String = "",
        photos: List<PendingAiPhoto> = emptyList()
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
        attachPhotos(recordId, null, exchange.userMessageId, "conversation_${conversationId}/message_${exchange.userMessageId}", photos)
        return exchange
    }

    suspend fun completeMessage(conversationId: Long, messageId: Long, content: String) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.completeMessage(messageId, content, now)
            dao.touchConversation(conversationId, now)
        }
    }

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
        photoStore?.deleteRelativePaths(paths)
    }

    private suspend fun attachPhotos(
        recordId: Long,
        resultId: Long?,
        messageId: Long?,
        ownerDirectory: String,
        photos: List<PendingAiPhoto>
    ) {
        if (photos.isEmpty()) return
        val store = requireNotNull(photoStore) { "照片存储未初始化。" }
        val persisted = store.persist(recordId, ownerDirectory, photos)
        dao.insertImageAttachments(persisted.map { photo ->
            AiImageAttachmentEntity(
                recordId = recordId,
                resultId = resultId,
                messageId = messageId,
                relativePath = photo.absolutePath,
                mimeType = photo.mimeType,
                width = photo.width,
                height = photo.height,
                createdAt = System.currentTimeMillis()
            )
        })
    }
}
