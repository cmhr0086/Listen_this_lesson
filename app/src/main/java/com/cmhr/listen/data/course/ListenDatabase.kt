package com.cmhr.listen.data.course

import android.content.Context
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cmhr.listen.data.ai.AiConversationEntity
import com.cmhr.listen.data.ai.AiConversationSegmentEntity
import com.cmhr.listen.data.ai.AiDao
import com.cmhr.listen.data.ai.AiMessageEntity
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.ai.AiResultSegmentEntity
import com.cmhr.listen.data.ai.AiAttachmentEntity
import com.cmhr.listen.data.ai.DEFAULT_CONVERSATION_PROMPT
import com.cmhr.listen.data.stt.AsrDiagnosticsDao
import com.cmhr.listen.data.stt.AsrNetworkEventEntity
import com.cmhr.listen.data.stt.AsrSegmentDiagnosticEntity
import com.cmhr.listen.data.recording.RecordingChunkEntity
import com.cmhr.listen.data.recording.RecordingDao
import com.cmhr.listen.data.recording.RecordingEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class SyncStatus {
    SYNCED,
    PENDING
}

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val asrPrompt: String = "",
    val asrPromptModeOverride: String? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false
)

@Entity(
    tableName = "records",
    foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("courseId"), Index(value = ["sessionId"], unique = true)]
)
data class SessionEntity(
    /** Local-only relational key. Use [sessionId] for cross-device identity. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val sessionId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val createdAt: Long = startedAt,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "'PENDING'") val syncStatus: String = SyncStatus.PENDING.name
)

typealias ClassRecordEntity = SessionEntity

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("recordId"),
        Index(value = ["segmentId"], unique = true),
        Index("sessionId"),
        Index("sourceSegmentId"),
        Index("asrJobId"),
        Index("sequenceNumber")
    ]
)
data class SegmentEntity(
    /** Local-only relational key. Use [segmentId] for cross-device identity. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val startTime: Long,
    val endTime: Long,
    val audioDurationMs: Long,
    val recognitionDurationMs: Long?,
    val text: String,
    val correctedText: String? = null,
    val correctionResultId: Long? = null,
    val correctedAt: Long? = null,
    val sourceSegmentId: String? = null,
    val sequenceNumber: Long? = null,
    val asrJobId: String? = null,
    val queueDurationMs: Long? = null,
    val uploadDurationMs: Long? = null,
    val responseWaitDurationMs: Long? = null,
    val totalAsrDurationMs: Long? = null,
    val serverModel: String? = null,
    @ColumnInfo(defaultValue = "''") val segmentId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "''") val sessionId: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = endTime,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "'PENDING'") val syncStatus: String = SyncStatus.PENDING.name
) {
    val effectiveText: String get() = correctedText?.takeIf { it.isNotBlank() } ?: text
}

typealias TranscriptEntity = SegmentEntity

data class SessionSyncProjection(
    @Embedded val session: SessionEntity,
    val courseName: String
)

@Dao interface CourseDao {
    @Query("SELECT * FROM courses WHERE deleted = 0 ORDER BY createdAt DESC") fun courses(): Flow<List<CourseEntity>>
    @Query("SELECT * FROM courses WHERE id = :id AND deleted = 0") fun course(id: Long): Flow<CourseEntity?>
    @Query("SELECT id FROM courses WHERE name = :name AND deleted = 0 ORDER BY id LIMIT 1") suspend fun idByName(name: String): Long?
    @Insert suspend fun insert(course: CourseEntity): Long
    @Query("UPDATE courses SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("UPDATE courses SET asrPrompt = :prompt WHERE id = :id") suspend fun updateAsrPrompt(id: Long, prompt: String)
    @Query("UPDATE courses SET asrPromptModeOverride = :mode WHERE id = :id") suspend fun updateAsrPromptMode(id: Long, mode: String?)
    @Query("UPDATE courses SET deleted = 1 WHERE id = :id") suspend fun softDelete(id: Long)
}
@Dao interface RecordDao {
    @Query("SELECT * FROM records WHERE courseId = :courseId AND deleted = 0 ORDER BY startedAt DESC") fun records(courseId: Long): Flow<List<SessionEntity>>
    @Query("SELECT * FROM records WHERE id = :id AND deleted = 0") fun record(id: Long): Flow<SessionEntity?>
    @Query("SELECT sessionId FROM records WHERE id = :id AND deleted = 0") suspend fun sessionId(id: Long): String?
    @Query("SELECT * FROM records WHERE sessionId = :sessionId LIMIT 1") suspend fun sessionBySessionId(sessionId: String): SessionEntity?
    @Query("SELECT r.*, c.name AS courseName FROM records r INNER JOIN courses c ON c.id = r.courseId WHERE r.syncStatus = 'PENDING' ORDER BY r.updatedAt, r.id LIMIT :limit")
    suspend fun pendingSessions(limit: Int): List<SessionSyncProjection>
    @Query("SELECT COUNT(*) FROM records WHERE syncStatus = 'PENDING'") suspend fun pendingSessionCount(): Int
    @Query("SELECT sessionId FROM records WHERE sessionId IN (:sessionIds) AND syncStatus = 'PENDING'")
    suspend fun pendingSessionIds(sessionIds: List<String>): List<String>
    @Insert suspend fun insert(session: SessionEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRemote(session: SessionEntity): Long
    @Query("UPDATE records SET name = :name, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE id = :id AND deleted = 0") suspend fun rename(id: Long, name: String, updatedAt: Long)
    @Query("UPDATE records SET endedAt = :endedAt, updatedAt = :endedAt, syncStatus = 'PENDING' WHERE id = :id AND deleted = 0") suspend fun end(id: Long, endedAt: Long)
    @Query("UPDATE records SET endedAt = NULL, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE id = :id AND deleted = 0") suspend fun reopen(id: Long, updatedAt: Long)
    @Query("UPDATE records SET deleted = 1, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE id = :id AND deleted = 0") suspend fun softDelete(id: Long, updatedAt: Long): Int
    @Query("UPDATE records SET deleted = 1, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE courseId = :courseId AND deleted = 0") suspend fun softDeleteForCourse(courseId: Long, updatedAt: Long): Int
    @Query("SELECT id FROM records WHERE courseId = :courseId AND deleted = 0") suspend fun idsForCourse(courseId: Long): List<Long>
    @Query("UPDATE records SET courseId = :courseId, name = :name, startedAt = :startedAt, endedAt = :endedAt, createdAt = :createdAt, updatedAt = :updatedAt, deleted = :deleted, syncStatus = 'SYNCED' WHERE sessionId = :sessionId AND updatedAt < :updatedAt")
    suspend fun applyRemote(
        sessionId: String,
        courseId: Long,
        name: String,
        startedAt: Long,
        endedAt: Long?,
        createdAt: Long,
        updatedAt: Long,
        deleted: Boolean
    ): Int
    @Query("UPDATE records SET syncStatus = 'SYNCED' WHERE sessionId = :sessionId AND updatedAt = :uploadedUpdatedAt AND syncStatus = 'PENDING'")
    suspend fun markSyncedIfUnchanged(sessionId: String, uploadedUpdatedAt: Long): Int
}
@Dao interface TranscriptDao {
    @Query("SELECT * FROM transcript_segments WHERE recordId = :recordId AND deleted = 0 ORDER BY sequenceNumber ASC, startTime ASC, id ASC") fun segments(recordId: Long): Flow<List<SegmentEntity>>
    @Query("SELECT * FROM transcript_segments WHERE id IN (:ids) AND deleted = 0 ORDER BY sequenceNumber ASC, startTime ASC, id ASC") suspend fun segmentsByIds(ids: List<Long>): List<SegmentEntity>
    @Query("SELECT t.* FROM transcript_segments t INNER JOIN ai_result_segments l ON l.segmentId = t.id WHERE l.resultId = :resultId AND t.deleted = 0 ORDER BY t.sequenceNumber ASC, t.startTime ASC, t.id ASC")
    suspend fun segmentsForResult(resultId: Long): List<SegmentEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(segment: SegmentEntity): Long
    @Query("SELECT id FROM transcript_segments WHERE sourceSegmentId = :sourceSegmentId LIMIT 1")
    suspend fun idForSourceSegment(sourceSegmentId: String): Long?
    @Query("SELECT * FROM transcript_segments WHERE segmentId = :segmentId LIMIT 1") suspend fun segmentBySegmentId(segmentId: String): SegmentEntity?
    @Query("SELECT COALESCE(MAX(sequenceNumber), 0) + 1 FROM transcript_segments WHERE recordId = :recordId") suspend fun nextSequenceNumber(recordId: Long): Long
    @Query("SELECT t.* FROM transcript_segments t INNER JOIN records r ON r.id = t.recordId WHERE t.syncStatus = 'PENDING' AND r.syncStatus = 'SYNCED' ORDER BY t.updatedAt, t.id LIMIT :limit")
    suspend fun pendingSegments(limit: Int): List<SegmentEntity>
    @Query("SELECT COUNT(*) FROM transcript_segments WHERE syncStatus = 'PENDING'") suspend fun pendingSegmentCount(): Int
    @Query("SELECT segmentId FROM transcript_segments WHERE segmentId IN (:segmentIds) AND syncStatus = 'PENDING'")
    suspend fun pendingSegmentIds(segmentIds: List<String>): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRemote(segment: SegmentEntity): Long
    @Query("UPDATE transcript_segments SET correctedText = :correctedText, correctionResultId = :resultId, correctedAt = :correctedAt, updatedAt = :correctedAt, syncStatus = 'PENDING' WHERE recordId = :recordId AND id = :segmentId AND deleted = 0")
    suspend fun applyCorrection(recordId: Long, segmentId: Long, resultId: Long, correctedText: String, correctedAt: Long): Int
    @Query("UPDATE transcript_segments SET correctedText = NULL, correctionResultId = NULL, correctedAt = NULL, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE id = :segmentId AND deleted = 0")
    suspend fun restoreOriginal(segmentId: Long, updatedAt: Long): Int
    @Query("UPDATE transcript_segments SET correctionResultId = NULL, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE correctionResultId = :resultId")
    suspend fun detachCorrectionResult(resultId: Long, updatedAt: Long)
    @Query("UPDATE transcript_segments SET deleted = 1, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE recordId = :recordId AND id IN (:ids) AND deleted = 0") suspend fun softDeleteByIds(recordId: Long, ids: List<Long>, updatedAt: Long): Int
    @Query("UPDATE transcript_segments SET deleted = 1, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE recordId = :recordId AND deleted = 0") suspend fun softDeleteForSession(recordId: Long, updatedAt: Long): Int
    @Query("UPDATE transcript_segments SET deleted = 1, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE recordId IN (:recordIds) AND deleted = 0") suspend fun softDeleteForSessions(recordIds: List<Long>, updatedAt: Long): Int
    @Query("""
        UPDATE transcript_segments SET
            recordId = :recordId,
            sessionId = :sessionId,
            startTime = :startTime,
            endTime = :endTime,
            audioDurationMs = :audioDurationMs,
            recognitionDurationMs = :recognitionDurationMs,
            text = :text,
            correctedText = :correctedText,
            correctionResultId = NULL,
            correctedAt = :correctedAt,
            sourceSegmentId = :sourceSegmentId,
            sequenceNumber = :sequenceNumber,
            asrJobId = :asrJobId,
            queueDurationMs = :queueDurationMs,
            uploadDurationMs = :uploadDurationMs,
            responseWaitDurationMs = :responseWaitDurationMs,
            totalAsrDurationMs = :totalAsrDurationMs,
            serverModel = :serverModel,
            createdAt = :createdAt,
            updatedAt = :updatedAt,
            deleted = :deleted,
            syncStatus = 'SYNCED'
        WHERE segmentId = :segmentId AND updatedAt < :updatedAt
    """)
    suspend fun applyRemote(
        segmentId: String,
        recordId: Long,
        sessionId: String,
        startTime: Long,
        endTime: Long,
        audioDurationMs: Long,
        recognitionDurationMs: Long?,
        text: String,
        correctedText: String?,
        correctedAt: Long?,
        sourceSegmentId: String?,
        sequenceNumber: Long?,
        asrJobId: String?,
        queueDurationMs: Long?,
        uploadDurationMs: Long?,
        responseWaitDurationMs: Long?,
        totalAsrDurationMs: Long?,
        serverModel: String?,
        createdAt: Long,
        updatedAt: Long,
        deleted: Boolean
    ): Int
    @Query("UPDATE transcript_segments SET syncStatus = 'SYNCED' WHERE segmentId = :segmentId AND updatedAt = :uploadedUpdatedAt AND syncStatus = 'PENDING'")
    suspend fun markSyncedIfUnchanged(segmentId: String, uploadedUpdatedAt: Long): Int
}

@Database(
    entities = [
        CourseEntity::class,
        SessionEntity::class,
        SegmentEntity::class,
        AiResultEntity::class,
        AiResultSegmentEntity::class,
        AiConversationEntity::class,
        AiConversationSegmentEntity::class,
        AiMessageEntity::class,
        AiAttachmentEntity::class,
        AsrSegmentDiagnosticEntity::class,
        AsrNetworkEventEntity::class,
        RecordingEntity::class,
        RecordingChunkEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class ListenDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun recordDao(): RecordDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun aiDao(): AiDao
    abstract fun asrDiagnosticsDao(): AsrDiagnosticsDao
    abstract fun recordingDao(): RecordingDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN asrPrompt TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_results (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER NOT NULL, actionType TEXT NOT NULL, requestPrompt TEXT NOT NULL, sourceTextSnapshot TEXT NOT NULL, output TEXT, status TEXT NOT NULL, errorMessage TEXT, createdAt INTEGER NOT NULL, finishedAt INTEGER, FOREIGN KEY(recordId) REFERENCES records(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_results_recordId ON ai_results(recordId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_result_segments (resultId INTEGER NOT NULL, segmentId INTEGER NOT NULL, PRIMARY KEY(resultId, segmentId), FOREIGN KEY(resultId) REFERENCES ai_results(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(segmentId) REFERENCES transcript_segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_result_segments_resultId ON ai_result_segments(resultId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_result_segments_segmentId ON ai_result_segments(segmentId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER NOT NULL, title TEXT NOT NULL, sourceTextSnapshot TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(recordId) REFERENCES records(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_conversations_recordId ON ai_conversations(recordId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_conversation_segments (conversationId INTEGER NOT NULL, segmentId INTEGER NOT NULL, PRIMARY KEY(conversationId, segmentId), FOREIGN KEY(conversationId) REFERENCES ai_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(segmentId) REFERENCES transcript_segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_conversation_segments_conversationId ON ai_conversation_segments(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_conversation_segments_segmentId ON ai_conversation_segments(segmentId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT, createdAt INTEGER NOT NULL, finishedAt INTEGER, FOREIGN KEY(conversationId) REFERENCES ai_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_messages_conversationId ON ai_messages(conversationId)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val escapedPrompt = DEFAULT_CONVERSATION_PROMPT.replace("'", "''")
                db.execSQL("ALTER TABLE ai_conversations ADD COLUMN systemPrompt TEXT NOT NULL DEFAULT '$escapedPrompt'")
                db.execSQL("ALTER TABLE ai_messages ADD COLUMN contextPrompt TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_image_attachments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER NOT NULL, resultId INTEGER, messageId INTEGER, relativePath TEXT NOT NULL, mimeType TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(recordId) REFERENCES records(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(resultId) REFERENCES ai_results(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(messageId) REFERENCES ai_messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_image_attachments_recordId ON ai_image_attachments(recordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_image_attachments_resultId ON ai_image_attachments(resultId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_image_attachments_messageId ON ai_image_attachments(messageId)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN asrPromptModeOverride TEXT")
                db.execSQL("ALTER TABLE ai_conversations ADD COLUMN originResultId INTEGER REFERENCES ai_results(id) ON DELETE CASCADE")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ai_conversations_originResultId ON ai_conversations(originResultId)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_conversations_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER, title TEXT NOT NULL, sourceTextSnapshot TEXT NOT NULL, systemPrompt TEXT NOT NULL, originResultId INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(recordId) REFERENCES records(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(originResultId) REFERENCES ai_results(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO ai_conversations_new(id, recordId, title, sourceTextSnapshot, systemPrompt, originResultId, createdAt, updatedAt) SELECT id, recordId, title, sourceTextSnapshot, systemPrompt, originResultId, createdAt, updatedAt FROM ai_conversations")

                // Child rows are staged without foreign keys. Android's bundled SQLite does not
                // rewrite child references when a temporary parent table is renamed in a migration.
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_messages_staging (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, contextPrompt TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT, createdAt INTEGER NOT NULL, finishedAt INTEGER)")
                db.execSQL("INSERT INTO ai_messages_staging(id, conversationId, role, content, contextPrompt, status, errorMessage, createdAt, finishedAt) SELECT id, conversationId, role, content, contextPrompt, status, errorMessage, createdAt, finishedAt FROM ai_messages")

                db.execSQL("CREATE TABLE IF NOT EXISTS ai_conversation_segments_staging (conversationId INTEGER NOT NULL, segmentId INTEGER NOT NULL, PRIMARY KEY(conversationId, segmentId))")
                db.execSQL("INSERT INTO ai_conversation_segments_staging(conversationId, segmentId) SELECT conversationId, segmentId FROM ai_conversation_segments")

                db.execSQL("CREATE TABLE IF NOT EXISTS ai_attachments_staging (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER, resultId INTEGER, messageId INTEGER, kind TEXT NOT NULL, displayName TEXT NOT NULL, relativePath TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, width INTEGER, height INTEGER, createdAt INTEGER NOT NULL)")
                db.execSQL("INSERT INTO ai_attachments_staging(id, recordId, resultId, messageId, kind, displayName, relativePath, mimeType, sizeBytes, width, height, createdAt) SELECT id, recordId, resultId, messageId, 'IMAGE', '课堂照片.jpg', relativePath, mimeType, 0, width, height, createdAt FROM ai_image_attachments")

                db.execSQL("DROP TABLE ai_image_attachments")
                db.execSQL("DROP TABLE ai_conversation_segments")
                db.execSQL("DROP TABLE ai_messages")
                db.execSQL("DROP TABLE ai_conversations")

                db.execSQL("ALTER TABLE ai_conversations_new RENAME TO ai_conversations")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, contextPrompt TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT, createdAt INTEGER NOT NULL, finishedAt INTEGER, FOREIGN KEY(conversationId) REFERENCES ai_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO ai_messages(id, conversationId, role, content, contextPrompt, status, errorMessage, createdAt, finishedAt) SELECT id, conversationId, role, content, contextPrompt, status, errorMessage, createdAt, finishedAt FROM ai_messages_staging")
                db.execSQL("DROP TABLE ai_messages_staging")

                db.execSQL("CREATE TABLE IF NOT EXISTS ai_conversation_segments (conversationId INTEGER NOT NULL, segmentId INTEGER NOT NULL, PRIMARY KEY(conversationId, segmentId), FOREIGN KEY(conversationId) REFERENCES ai_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(segmentId) REFERENCES transcript_segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO ai_conversation_segments(conversationId, segmentId) SELECT conversationId, segmentId FROM ai_conversation_segments_staging")
                db.execSQL("DROP TABLE ai_conversation_segments_staging")

                db.execSQL("CREATE TABLE IF NOT EXISTS ai_attachments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER, resultId INTEGER, messageId INTEGER, kind TEXT NOT NULL, displayName TEXT NOT NULL, relativePath TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, width INTEGER, height INTEGER, createdAt INTEGER NOT NULL, FOREIGN KEY(recordId) REFERENCES records(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(resultId) REFERENCES ai_results(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(messageId) REFERENCES ai_messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO ai_attachments(id, recordId, resultId, messageId, kind, displayName, relativePath, mimeType, sizeBytes, width, height, createdAt) SELECT id, recordId, resultId, messageId, kind, displayName, relativePath, mimeType, sizeBytes, width, height, createdAt FROM ai_attachments_staging")
                db.execSQL("DROP TABLE ai_attachments_staging")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_conversations_recordId ON ai_conversations(recordId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ai_conversations_originResultId ON ai_conversations(originResultId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_messages_conversationId ON ai_messages(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_conversation_segments_conversationId ON ai_conversation_segments(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_conversation_segments_segmentId ON ai_conversation_segments(segmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_attachments_recordId ON ai_attachments(recordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_attachments_resultId ON ai_attachments(resultId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_attachments_messageId ON ai_attachments(messageId)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN correctedText TEXT")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN correctionResultId INTEGER")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN correctedAt INTEGER")
                db.execSQL("ALTER TABLE ai_results ADD COLUMN reasoningContent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ai_results ADD COLUMN correctionPayload TEXT")
                db.execSQL("ALTER TABLE ai_messages ADD COLUMN reasoningContent TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN sourceSegmentId TEXT")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN asrJobId TEXT")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN queueDurationMs INTEGER")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN uploadDurationMs INTEGER")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN responseWaitDurationMs INTEGER")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN totalAsrDurationMs INTEGER")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN serverModel TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segments_sourceSegmentId ON transcript_segments(sourceSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segments_asrJobId ON transcript_segments(asrJobId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS asr_segment_diagnostics (segmentId TEXT NOT NULL PRIMARY KEY, recordId INTEGER NOT NULL, jobId TEXT, state TEXT NOT NULL, audioStartTime INTEGER NOT NULL, audioEndTime INTEGER NOT NULL, audioDurationMs INTEGER NOT NULL, wavRelativePath TEXT, language TEXT NOT NULL, contextSnapshot TEXT, captureStartedAt INTEGER NOT NULL, captureFinishedAt INTEGER NOT NULL, queuedLocalAt INTEGER NOT NULL, captureStartedElapsedMs INTEGER, captureFinishedElapsedMs INTEGER, queuedLocalElapsedMs INTEGER, submitStartedAt INTEGER, submitCompletedAt INTEGER, submitStartedElapsedMs INTEGER, submitCompletedElapsedMs INTEGER, firstServerQueuedAt INTEGER, firstServerProcessingAt INTEGER, firstServerCompletedAt INTEGER, firstServerQueuedElapsedMs INTEGER, firstServerProcessingElapsedMs INTEGER, firstServerCompletedElapsedMs INTEGER, finishedAt INTEGER, finishedElapsedMs INTEGER, submitAttempts INTEGER NOT NULL, pollAttempts INTEGER NOT NULL, nextAttemptAt INTEGER, lastHttpStatus INTEGER, serverModel TEXT, postDurationMs INTEGER, uploadDurationMs INTEGER, submitResponseWaitDurationMs INTEGER, resultResponseDurationMs INTEGER, failureStage TEXT, exceptionClass TEXT, safeErrorMessage TEXT, FOREIGN KEY(recordId) REFERENCES records(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asr_segment_diagnostics_recordId ON asr_segment_diagnostics(recordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asr_segment_diagnostics_state ON asr_segment_diagnostics(state)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_asr_segment_diagnostics_jobId ON asr_segment_diagnostics(jobId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS asr_network_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, segmentId TEXT NOT NULL, requestKind TEXT NOT NULL, attempt INTEGER NOT NULL, eventType TEXT NOT NULL, timestampMs INTEGER NOT NULL, elapsedSinceCallStartMs INTEGER, exceptionClass TEXT, appInForeground INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(segmentId) REFERENCES asr_segment_diagnostics(segmentId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asr_network_events_segmentId ON asr_network_events(segmentId)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN sequenceNumber INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segments_sequenceNumber ON transcript_segments(sequenceNumber)")
                db.execSQL("ALTER TABLE asr_segment_diagnostics ADD COLUMN sequenceNumber INTEGER")
                db.execSQL("ALTER TABLE asr_segment_diagnostics ADD COLUMN clockBasis TEXT NOT NULL DEFAULT 'LEGACY_WALL_FALLBACK'")
                db.execSQL("ALTER TABLE asr_segment_diagnostics ADD COLUMN bootCount INTEGER")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_asr_segment_diagnostics_recordId_sequenceNumber ON asr_segment_diagnostics(recordId, sequenceNumber)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE records ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE records ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE records ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE records ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE records ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("""
                    UPDATE records
                    SET sessionId = lower(hex(randomblob(4))) || '-' ||
                        lower(hex(randomblob(2))) || '-4' ||
                        substr(lower(hex(randomblob(2))), 2) || '-' ||
                        substr('89ab', (random() & 3) + 1, 1) ||
                        substr(lower(hex(randomblob(2))), 2) || '-' ||
                        lower(hex(randomblob(6))),
                        createdAt = startedAt,
                        updatedAt = COALESCE(endedAt, startedAt)
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_records_sessionId ON records(sessionId)")

                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN segmentId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("""
                    UPDATE transcript_segments
                    SET segmentId = lower(hex(randomblob(4))) || '-' ||
                        lower(hex(randomblob(2))) || '-4' ||
                        substr(lower(hex(randomblob(2))), 2) || '-' ||
                        substr('89ab', (random() & 3) + 1, 1) ||
                        substr(lower(hex(randomblob(2))), 2) || '-' ||
                        lower(hex(randomblob(6))),
                        sessionId = (SELECT records.sessionId FROM records WHERE records.id = transcript_segments.recordId),
                        createdAt = endTime,
                        updatedAt = endTime
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transcript_segments_segmentId ON transcript_segments(segmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segments_sessionId ON transcript_segments(sessionId)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS recordings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordingId TEXT NOT NULL, recordId INTEGER NOT NULL, sessionId TEXT NOT NULL, localPath TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, durationMs INTEGER NOT NULL, totalFrames INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'RECORDING', processedFrames INTEGER NOT NULL, activeWindowStartFrame INTEGER, activeWindowEndFrame INTEGER, vadConfigSnapshot TEXT, vadAlgorithmVersion INTEGER NOT NULL, processingRunId TEXT, errorMessage TEXT, FOREIGN KEY(recordId) REFERENCES records(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recordings_recordId ON recordings(recordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recordings_sessionId ON recordings(sessionId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recordings_recordingId ON recordings(recordingId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS recording_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, chunkId TEXT NOT NULL, recordingId TEXT NOT NULL, windowStartFrame INTEGER NOT NULL, windowEndFrame INTEGER NOT NULL, startFrame INTEGER NOT NULL, endFrame INTEGER NOT NULL, sequenceNumber INTEGER NOT NULL, contextSnapshot TEXT, state TEXT NOT NULL DEFAULT 'PLANNED', errorMessage TEXT, FOREIGN KEY(recordingId) REFERENCES recordings(recordingId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recording_chunks_recordingId ON recording_chunks(recordingId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recording_chunks_chunkId ON recording_chunks(chunkId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recording_chunks_recordingId_windowStartFrame_startFrame_endFrame ON recording_chunks(recordingId, windowStartFrame, startFrame, endFrame)")
            }
        }

        @Volatile private var instance: ListenDatabase? = null
        fun get(context: Context): ListenDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ListenDatabase::class.java, "listen.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .build()
                .also { instance = it }
        }
    }
}
