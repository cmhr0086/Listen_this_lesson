package com.cmhr.listen.data.ai

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.TranscriptEntity

enum class AiActionType(val displayName: String) {
    SUMMARY("总结"),
    CORRECT_ASR("修正明显 ASR 错误"),
    EXTRACT_QUESTIONS("提取老师的问题"),
    ORGANIZE_NOTES("整理成笔记"),
    QUICK_ANSWER("快速回答")
}

enum class AiRequestStatus { PENDING, SUCCESS, ERROR }

@Entity(
    tableName = "ai_results",
    foreignKeys = [ForeignKey(
        entity = ClassRecordEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordId")]
)
data class AiResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val actionType: String,
    val requestPrompt: String,
    val sourceTextSnapshot: String,
    val output: String? = null,
    val status: String = AiRequestStatus.PENDING.name,
    val errorMessage: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null
)

@Entity(
    tableName = "ai_result_segments",
    primaryKeys = ["resultId", "segmentId"],
    foreignKeys = [
        ForeignKey(entity = AiResultEntity::class, parentColumns = ["id"], childColumns = ["resultId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TranscriptEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("resultId"), Index("segmentId")]
)
data class AiResultSegmentEntity(val resultId: Long, val segmentId: Long)

@Entity(
    tableName = "ai_conversations",
    foreignKeys = [
        ForeignKey(
            entity = ClassRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AiResultEntity::class,
            parentColumns = ["id"],
            childColumns = ["originResultId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordId"), Index(value = ["originResultId"], unique = true)]
)
data class AiConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val title: String,
    val sourceTextSnapshot: String,
    val systemPrompt: String = DEFAULT_CONVERSATION_PROMPT,
    val originResultId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "ai_conversation_segments",
    primaryKeys = ["conversationId", "segmentId"],
    foreignKeys = [
        ForeignKey(entity = AiConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TranscriptEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("conversationId"), Index("segmentId")]
)
data class AiConversationSegmentEntity(val conversationId: Long, val segmentId: Long)

@Entity(
    tableName = "ai_messages",
    foreignKeys = [ForeignKey(
        entity = AiConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val contextPrompt: String = "",
    val status: String = AiRequestStatus.SUCCESS.name,
    val errorMessage: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null
)

@Entity(
    tableName = "ai_image_attachments",
    foreignKeys = [
        ForeignKey(entity = ClassRecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AiResultEntity::class, parentColumns = ["id"], childColumns = ["resultId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AiMessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("recordId"), Index("resultId"), Index("messageId")]
)
data class AiImageAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val resultId: Long? = null,
    val messageId: Long? = null,
    val relativePath: String,
    val mimeType: String = "image/jpeg",
    val width: Int,
    val height: Int,
    val createdAt: Long
)

const val DEFAULT_CONVERSATION_PROMPT =
    "你是课堂内容问答助手。只能依据提供的冻结课堂原文回答；若原文不足以回答，应明确说明，不得虚构。"
