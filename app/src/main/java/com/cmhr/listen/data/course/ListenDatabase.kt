package com.cmhr.listen.data.course

import android.content.Context
import androidx.room.Database
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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val asrPrompt: String = "",
    val asrPromptModeOverride: String? = null
)

@Entity(tableName = "records", foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)], indices = [Index("courseId")])
data class ClassRecordEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val courseId: Long, val name: String, val startedAt: Long, val endedAt: Long? = null)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(entity = ClassRecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordId"), Index("sourceSegmentId"), Index("asrJobId")]
)
data class TranscriptEntity(
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
    val asrJobId: String? = null,
    val queueDurationMs: Long? = null,
    val uploadDurationMs: Long? = null,
    val responseWaitDurationMs: Long? = null,
    val totalAsrDurationMs: Long? = null,
    val serverModel: String? = null
) {
    val effectiveText: String get() = correctedText?.takeIf { it.isNotBlank() } ?: text
}

@Dao interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY createdAt DESC") fun courses(): Flow<List<CourseEntity>>
    @Query("SELECT * FROM courses WHERE id = :id") fun course(id: Long): Flow<CourseEntity?>
    @Insert suspend fun insert(course: CourseEntity): Long
    @Query("UPDATE courses SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("UPDATE courses SET asrPrompt = :prompt WHERE id = :id") suspend fun updateAsrPrompt(id: Long, prompt: String)
    @Query("UPDATE courses SET asrPromptModeOverride = :mode WHERE id = :id") suspend fun updateAsrPromptMode(id: Long, mode: String?)
    @Query("DELETE FROM courses WHERE id = :id") suspend fun delete(id: Long)
}
@Dao interface RecordDao {
    @Query("SELECT * FROM records WHERE courseId = :courseId ORDER BY startedAt DESC") fun records(courseId: Long): Flow<List<ClassRecordEntity>>
    @Query("SELECT * FROM records WHERE id = :id") fun record(id: Long): Flow<ClassRecordEntity?>
    @Insert suspend fun insert(record: ClassRecordEntity): Long
    @Query("UPDATE records SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("UPDATE records SET endedAt = :endedAt WHERE id = :id") suspend fun end(id: Long, endedAt: Long)
    @Query("UPDATE records SET endedAt = NULL WHERE id = :id") suspend fun reopen(id: Long)
    @Query("DELETE FROM records WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT id FROM records WHERE courseId = :courseId") suspend fun idsForCourse(courseId: Long): List<Long>
}
@Dao interface TranscriptDao {
    @Query("SELECT * FROM transcript_segments WHERE recordId = :recordId ORDER BY startTime ASC") fun segments(recordId: Long): Flow<List<TranscriptEntity>>
    @Query("SELECT * FROM transcript_segments WHERE id IN (:ids) ORDER BY startTime ASC") suspend fun segmentsByIds(ids: List<Long>): List<TranscriptEntity>
    @Query("SELECT t.* FROM transcript_segments t INNER JOIN ai_result_segments l ON l.segmentId = t.id WHERE l.resultId = :resultId ORDER BY t.startTime ASC")
    suspend fun segmentsForResult(resultId: Long): List<TranscriptEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(segment: TranscriptEntity): Long
    @Query("UPDATE transcript_segments SET correctedText = :correctedText, correctionResultId = :resultId, correctedAt = :correctedAt WHERE recordId = :recordId AND id = :segmentId")
    suspend fun applyCorrection(recordId: Long, segmentId: Long, resultId: Long, correctedText: String, correctedAt: Long): Int
    @Query("UPDATE transcript_segments SET correctedText = NULL, correctionResultId = NULL, correctedAt = NULL WHERE id = :segmentId")
    suspend fun restoreOriginal(segmentId: Long): Int
    @Query("UPDATE transcript_segments SET correctionResultId = NULL WHERE correctionResultId = :resultId")
    suspend fun detachCorrectionResult(resultId: Long)
    @Query("DELETE FROM transcript_segments WHERE recordId = :recordId AND id IN (:ids)") suspend fun deleteByIds(recordId: Long, ids: List<Long>): Int
}

@Database(
    entities = [
        CourseEntity::class,
        ClassRecordEntity::class,
        TranscriptEntity::class,
        AiResultEntity::class,
        AiResultSegmentEntity::class,
        AiConversationEntity::class,
        AiConversationSegmentEntity::class,
        AiMessageEntity::class,
        AiAttachmentEntity::class,
        AsrSegmentDiagnosticEntity::class,
        AsrNetworkEventEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class ListenDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun recordDao(): RecordDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun aiDao(): AiDao
    abstract fun asrDiagnosticsDao(): AsrDiagnosticsDao
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

        @Volatile private var instance: ListenDatabase? = null
        fun get(context: Context): ListenDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ListenDatabase::class.java, "listen.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
                .also { instance = it }
        }
    }
}
