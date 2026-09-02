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
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
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
    fun migratesV1ThroughV5AndPreservesExistingClassroomData() = runBlocking {
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
                ListenDatabase.MIGRATION_4_5
            )
            .build()
        try {
            assertEquals("", database.courseDao().course(1).first()?.asrPrompt)
            assertEquals(null, database.courseDao().course(1).first()?.asrPromptModeOverride)
            assertEquals("第一课", database.recordDao().record(1).first()?.name)
            assertEquals("原始识别文本", database.transcriptDao().segments(1).first().single().text)

            val aiRepository = AiRepository(database)
            val resultId = aiRepository.createResult(1, AiActionType.SUMMARY, "提示词", "原始识别文本", listOf(1))
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

            aiRepository.createResult(1, AiActionType.SUMMARY, "提示词", "原始识别文本", listOf(1))

            CourseRepository(database).deleteRecord(1)
            assertTrue(aiRepository.results(1).first().isEmpty())
        } finally {
            database.close()
        }
    }
}
