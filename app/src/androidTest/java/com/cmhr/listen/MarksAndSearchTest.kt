package com.cmhr.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SyncStatus
import com.cmhr.listen.data.course.TranscriptEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarksAndSearchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ListenDatabase
    private lateinit var repository: CourseRepository

    @Before fun before() {
        database = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(ListenDatabase.TRIGGERS_CALLBACK)
            .build()
        repository = CourseRepository(database)
    }

    @After fun after() = database.close()

    private suspend fun record(courseId: Long, startedAt: Long): Long =
        database.recordDao().insert(ClassRecordEntity(courseId = courseId, name = "课", startedAt = startedAt))

    private suspend fun segment(recordId: Long, start: Long, text: String, corrected: String? = null): Long {
        val sessionId = database.recordDao().sessionId(recordId)!!
        return database.transcriptDao().insert(
            TranscriptEntity(recordId = recordId, sessionId = sessionId, startTime = start, endTime = start + 5_000, audioDurationMs = 5_000, recognitionDurationMs = null, text = text, correctedText = corrected)
        )
    }

    @Test
    fun aMarkCoversSpeechAlreadyRecognizedAndSpeechThatArrivesLater() = runBlocking {
        val course = database.courseDao().insert(CourseEntity(name = "高数", createdAt = 1))
        val id = record(course, 0)
        val before = segment(id, 10_000, "已经识别的那句")   // ends at 15 s
        val far = segment(id, 40_000, "很久以后才说的")
        repository.markMoment(id, at = 16_000)            // pressed just after it ended
        val after = segment(id, 20_000, "按下后紧接着说的")  // starts 4 s after the press
        val marked = database.transcriptDao().segments(id).first().associate { it.id to it.marked }
        assertEquals(true, marked[before])
        assertEquals(true, marked[after])
        assertEquals(false, marked[far])
        // Marks are synced like any other change.
        assertTrue(database.transcriptDao().segments(id).first().filter { it.marked }.all { it.syncStatus == SyncStatus.PENDING.name })
    }

    @Test
    fun marksCanBeToggledByHandAndAreCountedInSummaries() = runBlocking {
        val course = database.courseDao().insert(CourseEntity(name = "高数", createdAt = 1))
        val id = record(course, 0)
        val a = segment(id, 0, "一")
        val b = segment(id, 10_000, "二")
        repository.setMarked(id, setOf(a, b), true)
        assertEquals(2, database.recordDao().summary(id).first()!!.markCount)
        repository.setMarked(id, setOf(a), false)
        assertEquals(1, database.recordDao().summary(id).first()!!.markCount)
    }

    @Test
    fun classesAreNumberedWithinTheirCourseOldestFirst() = runBlocking {
        val math = database.courseDao().insert(CourseEntity(name = "高数", createdAt = 1))
        val english = database.courseDao().insert(CourseEntity(name = "英语", createdAt = 2))
        val second = record(math, 2_000)
        val first = record(math, 1_000)
        val other = record(english, 1_500)
        val numbers = database.recordDao().recentSummaries(10).first().associate { it.session.id to it.classNumber }
        assertEquals(1, numbers[first])
        assertEquals(2, numbers[second])
        assertEquals(1, numbers[other])
    }

    @Test
    fun searchFindsCorrectedTextNotesAndLiteralWildcards() = runBlocking {
        val course = database.courseDao().insert(CourseEntity(name = "高数", createdAt = 1))
        val id = record(course, 0)
        segment(id, 0, "原文 ASR 错字", corrected = "函数的极限")
        segment(id, 10_000, "及格线是 60% 左右")
        segment(id, 20_000, "60 分万岁")
        database.aiDao().insertResult(
            AiResultEntity(recordId = id, actionType = AiActionType.ORGANIZE_NOTES.name, requestPrompt = "", sourceTextSnapshot = "", output = "## 极限的定义", status = AiRequestStatus.SUCCESS.name, createdAt = 1)
        )
        assertEquals(listOf("函数的极限"), repository.searchSegments("极限", onlyMarked = false).first().map { it.text })
        assertEquals(listOf("及格线是 60% 左右"), repository.searchSegments("60%", onlyMarked = false).first().map { it.text })
        assertEquals(1, repository.searchNotes("极限").first().size)
        assertTrue(repository.searchSegments("", onlyMarked = true).first().isEmpty())
    }

    @Test
    fun topicIsStoredAndSynced() = runBlocking {
        val course = database.courseDao().insert(CourseEntity(name = "高数", createdAt = 1))
        val id = record(course, 0)
        database.recordDao().markSyncedIfUnchanged(database.recordDao().sessionId(id)!!, database.recordDao().recordNow(id)!!.updatedAt)
        repository.updateTopic(id, "  函数的极限 ")
        val stored = database.recordDao().recordNow(id)!!
        assertEquals("函数的极限", stored.topic)
        assertEquals(SyncStatus.PENDING.name, stored.syncStatus)
    }
}
