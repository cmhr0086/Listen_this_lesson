package com.cmhr.listen

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiRepository
import com.cmhr.listen.data.ai.CorrectionPayload
import com.cmhr.listen.data.ai.CorrectionSegment
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.stt.ACTIVE_ASR_STATES
import com.cmhr.listen.data.stt.AsrSegmentDiagnosticEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListenDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-test.db"

    @Before fun before() { context.deleteDatabase(databaseName) }
    @After fun after() { context.deleteDatabase(databaseName) }

    @Test
    fun migratesV1ThroughV8AndSupportsCorrectionsReasoningGeneralConversationsAndAsrDiagnostics() = runBlocking {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS courses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER NOT NULL, name TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, FOREIGN KEY(courseId) REFERENCES courses(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_courseId ON records(courseId)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS transcript_segments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER NOT NULL, audioDurationMs INTEGER NOT NULL, recognitionDurationMs INTEGER, text TEXT NOT NULL, FOREIGN KEY(recordId) REFERENCES records(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segments_recordId ON transcript_segments(recordId)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase.apply {
                execSQL("INSERT INTO courses(id, name, createdAt) VALUES(1, '高等数学', 1000)")
                execSQL("INSERT INTO records(id, courseId, name, startedAt, endedAt) VALUES(1, 1, '第一课', 2000, 3000)")
                execSQL("INSERT INTO transcript_segments(id, recordId, startTime, endTime, audioDurationMs, recognitionDurationMs, text) VALUES(1, 1, 2100, 2600, 500, 100, '原始识别文本')")
            }
        }

        val database = Room.databaseBuilder(context, ListenDatabase::class.java, databaseName)
            .addMigrations(
                ListenDatabase.MIGRATION_1_2,
                ListenDatabase.MIGRATION_2_3,
                ListenDatabase.MIGRATION_3_4,
                ListenDatabase.MIGRATION_4_5,
                ListenDatabase.MIGRATION_5_6,
                ListenDatabase.MIGRATION_6_7,
                ListenDatabase.MIGRATION_7_8
            )
            .build()
        try {
            assertEquals("", database.courseDao().course(1).first()?.asrPrompt)
            assertEquals(null, database.courseDao().course(1).first()?.asrPromptModeOverride)
            assertEquals("第一课", database.recordDao().record(1).first()?.name)
            assertEquals("原始识别文本", database.transcriptDao().segments(1).first().single().text)
            assertEquals(null, database.transcriptDao().segments(1).first().single().correctedText)
            assertEquals(null, database.transcriptDao().segments(1).first().single().sourceSegmentId)

            repeat(6) { index ->
                database.asrDiagnosticsDao().insertSegment(
                    AsrSegmentDiagnosticEntity(
                        segmentId = "persisted-$index",
                        recordId = 1,
                        audioStartTime = 10_000L + index,
                        audioEndTime = 11_000L + index,
                        audioDurationMs = 1_000,
                        captureStartedAt = 10_000L + index,
                        captureFinishedAt = 11_000L + index,
                        queuedLocalAt = 11_000L + index
                    )
                )
            }
            assertEquals(6, database.asrDiagnosticsDao().queueCount(ACTIVE_ASR_STATES))

            val aiRepository = AiRepository(database)
            val resultId = aiRepository.createResult(1, AiActionType.SUMMARY, "提示词", "原始识别文本", listOf(1))
            aiRepository.completeResult(resultId, "总结", "先分析原文")
            assertEquals("先分析原文", aiRepository.resultOnce(resultId)?.reasoningContent)
            val linkedConversationId = aiRepository.createConversation(
                recordId = 1,
                title = "总结追问",
                snapshot = "原始识别文本",
                segmentIds = emptyList(),
                originResultId = resultId
            )
            assertEquals(1, aiRepository.results(1).first().size)
            assertEquals(resultId, aiRepository.conversationOnce(linkedConversationId)?.originResultId)

            aiRepository.deleteResult(resultId)
            assertEquals(null, aiRepository.conversationOnce(linkedConversationId))

            val correctionId = aiRepository.createResult(1, AiActionType.CORRECT_ASR, "纠错提示", "原始识别文本", listOf(1))
            aiRepository.applyCorrections(
                correctionId,
                CorrectionPayload(listOf(CorrectionSegment(1, "纠正后的文本", listOf("识别错误 → 正确文本"))))
            )
            val corrected = database.transcriptDao().segments(1).first().single()
            assertEquals("原始识别文本", corrected.text)
            assertEquals("纠正后的文本", corrected.effectiveText)
            aiRepository.restoreOriginal(1)
            assertEquals("原始识别文本", database.transcriptDao().segments(1).first().single().effectiveText)

            val generalConversationId = aiRepository.createConversation(
                recordId = null,
                title = "通用测试",
                snapshot = "",
                segmentIds = emptyList(),
                systemPrompt = "通用助手"
            )
            assertEquals(null, aiRepository.conversationOnce(generalConversationId)?.recordId)
            assertTrue(aiRepository.globalTimeline().first().any { row ->
                row.kind == "CONVERSATION" && row.id == generalConversationId && row.courseName == "通用对话"
            })

            aiRepository.createResult(1, AiActionType.SUMMARY, "提示词", "原始识别文本", listOf(1))

            CourseRepository(database).deleteRecord(1)
            assertTrue(aiRepository.results(1).first().isEmpty())
            assertEquals("通用测试", aiRepository.conversationOnce(generalConversationId)?.title)
        } finally {
            database.close()
        }
    }

    @Test
    fun asrDiagnosticsRecentQueryIsLimitedOrderedAndScopedToCurrentRecord() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val courseId = database.courseDao().insert(
                CourseEntity(name = "诊断测试课程", createdAt = 1_000)
            )
            val currentRecordId = database.recordDao().insert(
                ClassRecordEntity(
                    courseId = courseId,
                    name = "当前课堂",
                    startedAt = 2_000
                )
            )
            val otherRecordId = database.recordDao().insert(
                ClassRecordEntity(
                    courseId = courseId,
                    name = "其他课堂",
                    startedAt = 3_000
                )
            )

            repeat(20) { index ->
                val capturedAt = 10_000L + index
                database.asrDiagnosticsDao().insertSegment(
                    AsrSegmentDiagnosticEntity(
                        segmentId = "current-$index",
                        recordId = currentRecordId,
                        audioStartTime = capturedAt,
                        audioEndTime = capturedAt + 1_000,
                        audioDurationMs = 1_000,
                        captureStartedAt = capturedAt,
                        captureFinishedAt = capturedAt + 1_000,
                        queuedLocalAt = capturedAt + 1_000
                    )
                )
            }
            database.asrDiagnosticsDao().insertSegment(
                AsrSegmentDiagnosticEntity(
                    segmentId = "other-newest",
                    recordId = otherRecordId,
                    audioStartTime = 99_999,
                    audioEndTime = 100_999,
                    audioDurationMs = 1_000,
                    captureStartedAt = 99_999,
                    captureFinishedAt = 100_999,
                    queuedLocalAt = 100_999
                )
            )

            val recent = database.asrDiagnosticsDao()
                .observeRecentForRecord(currentRecordId, limit = 15)
                .first()
            val count = database.asrDiagnosticsDao()
                .observeCountForRecord(currentRecordId)
                .first()

            assertEquals(15, recent.size)
            assertEquals(20, count)
            assertEquals((19 downTo 5).map { "current-$it" }, recent.map { it.segmentId })
            assertTrue(recent.none { it.recordId == otherRecordId })
        } finally {
            database.close()
        }
    }
}
