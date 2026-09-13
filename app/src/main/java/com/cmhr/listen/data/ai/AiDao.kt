package com.cmhr.listen.data.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cmhr.listen.data.course.TranscriptEntity
import kotlinx.coroutines.flow.Flow

data class AiTimelineRow(
    val kind: String,
    val id: Long,
    val recordId: Long?,
    val courseName: String,
    val title: String,
    val updatedAt: Long,
    val status: String,
    val preview: String
)

@Dao
interface AiDao {
    @Query("""
        SELECT 'RESULT' AS kind, r.id AS id, r.recordId AS recordId, c.name AS courseName,
               r.actionType AS title, COALESCE(r.finishedAt, r.createdAt) AS updatedAt,
               r.status AS status, COALESCE(r.output, r.errorMessage, '') AS preview
        FROM ai_results r
        INNER JOIN records cr ON cr.id = r.recordId
        INNER JOIN courses c ON c.id = cr.courseId
        WHERE cr.deleted = 0 AND c.deleted = 0
        UNION ALL
        SELECT 'CONVERSATION' AS kind, a.id AS id, a.recordId AS recordId,
               COALESCE(c.name, '通用对话') AS courseName, a.title AS title,
               a.updatedAt AS updatedAt, 'SUCCESS' AS status, 'AI 对话' AS preview
        FROM ai_conversations a
        LEFT JOIN records cr ON cr.id = a.recordId
        LEFT JOIN courses c ON c.id = cr.courseId
        WHERE a.originResultId IS NULL
          AND (a.recordId IS NULL OR (cr.deleted = 0 AND c.deleted = 0))
        ORDER BY updatedAt DESC
    """)
    fun globalTimeline(): Flow<List<AiTimelineRow>>

    @Insert suspend fun insertResult(result: AiResultEntity): Long
    @Insert suspend fun insertResultSegments(links: List<AiResultSegmentEntity>)
    @Query("SELECT r.* FROM ai_results r INNER JOIN records s ON s.id = r.recordId WHERE r.recordId = :recordId AND s.deleted = 0 ORDER BY r.createdAt DESC")
    fun results(recordId: Long): Flow<List<AiResultEntity>>
    @Query("SELECT r.* FROM ai_results r INNER JOIN records s ON s.id = r.recordId WHERE s.deleted = 0 ORDER BY r.createdAt DESC")
    fun allResults(): Flow<List<AiResultEntity>>
    @Query("SELECT r.* FROM ai_results r INNER JOIN records s ON s.id = r.recordId WHERE r.id = :id AND s.deleted = 0") fun result(id: Long): Flow<AiResultEntity?>
    @Query("SELECT r.* FROM ai_results r INNER JOIN records s ON s.id = r.recordId WHERE r.id = :id AND s.deleted = 0") suspend fun resultOnce(id: Long): AiResultEntity?
    @Query("SELECT t.* FROM transcript_segments t INNER JOIN ai_result_segments l ON l.segmentId = t.id WHERE l.resultId = :resultId AND t.deleted = 0 ORDER BY t.sequenceNumber ASC, t.startTime ASC, t.id ASC")
    fun resultSourceSegments(resultId: Long): Flow<List<TranscriptEntity>>
    @Query("UPDATE ai_results SET status = 'PENDING', output = NULL, reasoningContent = '', correctionPayload = NULL, errorMessage = NULL, finishedAt = NULL WHERE id = :id")
    suspend fun markResultPending(id: Long)
    @Query("UPDATE ai_results SET status = 'SUCCESS', output = :output, reasoningContent = :reasoningContent, correctionPayload = :correctionPayload, errorMessage = NULL, finishedAt = :finishedAt WHERE id = :id")
    suspend fun completeResult(id: Long, output: String, reasoningContent: String, correctionPayload: String?, finishedAt: Long)
    @Query("UPDATE ai_results SET output = :output, reasoningContent = :reasoningContent WHERE id = :id AND status = 'PENDING'")
    suspend fun updateResultDraft(id: Long, output: String, reasoningContent: String)
    @Query("UPDATE ai_results SET status = 'ERROR', errorMessage = :message, finishedAt = :finishedAt WHERE id = :id")
    suspend fun failResult(id: Long, message: String, finishedAt: Long)
    @Query("DELETE FROM ai_results WHERE id = :id") suspend fun deleteResult(id: Long)

    @Insert suspend fun insertConversation(conversation: AiConversationEntity): Long
    @Insert suspend fun insertConversationSegments(links: List<AiConversationSegmentEntity>)
    @Query("SELECT a.* FROM ai_conversations a INNER JOIN records s ON s.id = a.recordId WHERE a.recordId = :recordId AND a.originResultId IS NULL AND s.deleted = 0 ORDER BY a.updatedAt DESC")
    fun conversations(recordId: Long): Flow<List<AiConversationEntity>>
    @Query("SELECT a.* FROM ai_conversations a LEFT JOIN records s ON s.id = a.recordId WHERE a.originResultId IS NULL AND (a.recordId IS NULL OR s.deleted = 0) ORDER BY a.updatedAt DESC")
    fun allConversations(): Flow<List<AiConversationEntity>>
    @Query("SELECT a.* FROM ai_conversations a LEFT JOIN records s ON s.id = a.recordId WHERE a.id = :id AND (a.recordId IS NULL OR s.deleted = 0)") fun conversation(id: Long): Flow<AiConversationEntity?>
    @Query("SELECT a.* FROM ai_conversations a LEFT JOIN records s ON s.id = a.recordId WHERE a.id = :id AND (a.recordId IS NULL OR s.deleted = 0)") suspend fun conversationOnce(id: Long): AiConversationEntity?
    @Query("SELECT a.* FROM ai_conversations a LEFT JOIN records s ON s.id = a.recordId WHERE a.originResultId = :resultId AND (a.recordId IS NULL OR s.deleted = 0) LIMIT 1") fun conversationForResult(resultId: Long): Flow<AiConversationEntity?>
    @Query("SELECT a.* FROM ai_conversations a LEFT JOIN records s ON s.id = a.recordId WHERE a.originResultId = :resultId AND (a.recordId IS NULL OR s.deleted = 0) LIMIT 1") suspend fun conversationForResultOnce(resultId: Long): AiConversationEntity?
    @Query("SELECT t.* FROM transcript_segments t INNER JOIN ai_conversation_segments l ON l.segmentId = t.id WHERE l.conversationId = :conversationId AND t.deleted = 0 ORDER BY t.sequenceNumber ASC, t.startTime ASC, t.id ASC")
    fun conversationSourceSegments(conversationId: Long): Flow<List<TranscriptEntity>>
    @Query("UPDATE ai_conversations SET updatedAt = :updatedAt WHERE id = :id") suspend fun touchConversation(id: Long, updatedAt: Long)
    @Query("UPDATE ai_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id") suspend fun renameConversation(id: Long, title: String, updatedAt: Long)
    @Query("DELETE FROM ai_conversations WHERE id = :id") suspend fun deleteConversation(id: Long)

    @Insert suspend fun insertMessage(message: AiMessageEntity): Long
    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun messages(conversationId: Long): Flow<List<AiMessageEntity>>
    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun messagesOnce(conversationId: Long): List<AiMessageEntity>
    @Query("UPDATE ai_messages SET status = 'SUCCESS', content = :content, reasoningContent = :reasoningContent, errorMessage = NULL, finishedAt = :finishedAt WHERE id = :id")
    suspend fun completeMessage(id: Long, content: String, reasoningContent: String, finishedAt: Long)
    @Query("UPDATE ai_messages SET content = :content, reasoningContent = :reasoningContent WHERE id = :id AND status = 'PENDING'")
    suspend fun updateMessageDraft(id: Long, content: String, reasoningContent: String)
    @Query("UPDATE ai_messages SET status = 'ERROR', errorMessage = :message, finishedAt = :finishedAt WHERE id = :id")
    suspend fun failMessage(id: Long, message: String, finishedAt: Long)

    @Insert suspend fun insertAttachments(attachments: List<AiAttachmentEntity>)
    @Query("SELECT * FROM ai_attachments WHERE resultId = :resultId ORDER BY createdAt ASC, id ASC")
    fun resultAttachments(resultId: Long): Flow<List<AiAttachmentEntity>>
    @Query("SELECT * FROM ai_attachments WHERE resultId = :resultId ORDER BY createdAt ASC, id ASC")
    suspend fun resultAttachmentsOnce(resultId: Long): List<AiAttachmentEntity>
    @Query("SELECT * FROM ai_attachments WHERE messageId = :messageId ORDER BY createdAt ASC, id ASC")
    fun messageAttachments(messageId: Long): Flow<List<AiAttachmentEntity>>
    @Query("SELECT a.* FROM ai_attachments a INNER JOIN ai_messages m ON m.id = a.messageId WHERE m.conversationId = :conversationId ORDER BY a.createdAt ASC, a.id ASC")
    suspend fun conversationAttachmentsOnce(conversationId: Long): List<AiAttachmentEntity>
    @Query("SELECT relativePath FROM ai_attachments WHERE resultId = :resultId")
    suspend fun resultAttachmentPaths(resultId: Long): List<String>
    @Query("SELECT a.relativePath FROM ai_attachments a INNER JOIN ai_messages m ON m.id = a.messageId WHERE m.conversationId = :conversationId")
    suspend fun conversationAttachmentPaths(conversationId: Long): List<String>
}
