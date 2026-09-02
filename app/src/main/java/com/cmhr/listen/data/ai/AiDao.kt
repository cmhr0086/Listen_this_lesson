package com.cmhr.listen.data.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cmhr.listen.data.course.TranscriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiDao {
    @Insert suspend fun insertResult(result: AiResultEntity): Long
    @Insert suspend fun insertResultSegments(links: List<AiResultSegmentEntity>)
    @Query("SELECT * FROM ai_results WHERE recordId = :recordId ORDER BY createdAt DESC")
    fun results(recordId: Long): Flow<List<AiResultEntity>>
    @Query("SELECT * FROM ai_results WHERE id = :id") fun result(id: Long): Flow<AiResultEntity?>
    @Query("SELECT * FROM ai_results WHERE id = :id") suspend fun resultOnce(id: Long): AiResultEntity?
    @Query("SELECT t.* FROM transcript_segments t INNER JOIN ai_result_segments l ON l.segmentId = t.id WHERE l.resultId = :resultId ORDER BY t.startTime ASC")
    fun resultSourceSegments(resultId: Long): Flow<List<TranscriptEntity>>
    @Query("UPDATE ai_results SET status = 'PENDING', output = NULL, errorMessage = NULL, finishedAt = NULL WHERE id = :id")
    suspend fun markResultPending(id: Long)
    @Query("UPDATE ai_results SET status = 'SUCCESS', output = :output, errorMessage = NULL, finishedAt = :finishedAt WHERE id = :id")
    suspend fun completeResult(id: Long, output: String, finishedAt: Long)
    @Query("UPDATE ai_results SET status = 'ERROR', errorMessage = :message, finishedAt = :finishedAt WHERE id = :id")
    suspend fun failResult(id: Long, message: String, finishedAt: Long)
    @Query("DELETE FROM ai_results WHERE id = :id") suspend fun deleteResult(id: Long)

    @Insert suspend fun insertConversation(conversation: AiConversationEntity): Long
    @Insert suspend fun insertConversationSegments(links: List<AiConversationSegmentEntity>)
    @Query("SELECT * FROM ai_conversations WHERE recordId = :recordId AND originResultId IS NULL ORDER BY updatedAt DESC")
    fun conversations(recordId: Long): Flow<List<AiConversationEntity>>
    @Query("SELECT * FROM ai_conversations WHERE id = :id") fun conversation(id: Long): Flow<AiConversationEntity?>
    @Query("SELECT * FROM ai_conversations WHERE id = :id") suspend fun conversationOnce(id: Long): AiConversationEntity?
    @Query("SELECT * FROM ai_conversations WHERE originResultId = :resultId LIMIT 1") fun conversationForResult(resultId: Long): Flow<AiConversationEntity?>
    @Query("SELECT * FROM ai_conversations WHERE originResultId = :resultId LIMIT 1") suspend fun conversationForResultOnce(resultId: Long): AiConversationEntity?
    @Query("SELECT t.* FROM transcript_segments t INNER JOIN ai_conversation_segments l ON l.segmentId = t.id WHERE l.conversationId = :conversationId ORDER BY t.startTime ASC")
    fun conversationSourceSegments(conversationId: Long): Flow<List<TranscriptEntity>>
    @Query("UPDATE ai_conversations SET updatedAt = :updatedAt WHERE id = :id") suspend fun touchConversation(id: Long, updatedAt: Long)
    @Query("UPDATE ai_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id") suspend fun renameConversation(id: Long, title: String, updatedAt: Long)
    @Query("DELETE FROM ai_conversations WHERE id = :id") suspend fun deleteConversation(id: Long)

    @Insert suspend fun insertMessage(message: AiMessageEntity): Long
    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun messages(conversationId: Long): Flow<List<AiMessageEntity>>
    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun messagesOnce(conversationId: Long): List<AiMessageEntity>
    @Query("UPDATE ai_messages SET status = 'SUCCESS', content = :content, errorMessage = NULL, finishedAt = :finishedAt WHERE id = :id")
    suspend fun completeMessage(id: Long, content: String, finishedAt: Long)
    @Query("UPDATE ai_messages SET status = 'ERROR', errorMessage = :message, finishedAt = :finishedAt WHERE id = :id")
    suspend fun failMessage(id: Long, message: String, finishedAt: Long)

    @Insert suspend fun insertImageAttachments(attachments: List<AiImageAttachmentEntity>)
    @Query("SELECT * FROM ai_image_attachments WHERE resultId = :resultId ORDER BY createdAt ASC, id ASC")
    fun resultAttachments(resultId: Long): Flow<List<AiImageAttachmentEntity>>
    @Query("SELECT * FROM ai_image_attachments WHERE resultId = :resultId ORDER BY createdAt ASC, id ASC")
    suspend fun resultAttachmentsOnce(resultId: Long): List<AiImageAttachmentEntity>
    @Query("SELECT * FROM ai_image_attachments WHERE messageId = :messageId ORDER BY createdAt ASC, id ASC")
    fun messageAttachments(messageId: Long): Flow<List<AiImageAttachmentEntity>>
    @Query("SELECT a.* FROM ai_image_attachments a INNER JOIN ai_messages m ON m.id = a.messageId WHERE m.conversationId = :conversationId ORDER BY a.createdAt ASC, a.id ASC")
    suspend fun conversationAttachmentsOnce(conversationId: Long): List<AiImageAttachmentEntity>
    @Query("SELECT relativePath FROM ai_image_attachments WHERE resultId = :resultId")
    suspend fun resultAttachmentPaths(resultId: Long): List<String>
    @Query("SELECT a.relativePath FROM ai_image_attachments a INNER JOIN ai_messages m ON m.id = a.messageId WHERE m.conversationId = :conversationId")
    suspend fun conversationAttachmentPaths(conversationId: Long): List<String>
}
