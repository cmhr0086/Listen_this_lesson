package com.cmhr.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.RecordNameGenerator
import com.cmhr.listen.data.course.SessionEntity
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
class CourseSummaryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ListenDatabase
    private lateinit var repository: CourseRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java).allowMainThreadQueries().build()
        repository = CourseRepository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun record(courseId: Long, startedAt: Long, name: String = "r$startedAt", deleted: Boolean = false) =
        db.recordDao().insert(SessionEntity(courseId = courseId, name = name, startedAt = startedAt, deleted = deleted))

    @Test fun coursesAreOrderedByMostRecentClassAndCountOnlyLiveRecords() = runBlocking {
        val old = db.courseDao().insert(CourseEntity(name = "老课", createdAt = 1))
        val busy = db.courseDao().insert(CourseEntity(name = "常上", createdAt = 2))
        val empty = db.courseDao().insert(CourseEntity(name = "新建未上", createdAt = 50))
        record(old, 10)
        record(busy, 20); record(busy, 100); record(busy, 200, deleted = true)

        val summaries = repository.courseSummaries.first()

        assertEquals(listOf(busy, empty, old), summaries.map { it.course.id })
        assertEquals(2, summaries.first { it.course.id == busy }.recordCount)
        assertEquals(100L, summaries.first { it.course.id == busy }.lastStartedAt)
        assertEquals(0, summaries.first { it.course.id == empty }.recordCount)
    }

    @Test fun recentSummariesCarryCourseNameAndLiveSegmentCount() = runBlocking {
        val course = db.courseDao().insert(CourseEntity(name = "毛概", createdAt = 1))
        val recordId = record(course, 10)
        val sessionId = db.recordDao().sessionId(recordId)!!
        repeat(3) { i -> db.transcriptDao().insert(TranscriptEntity(recordId = recordId, sessionId = sessionId, startTime = i.toLong(), endTime = i + 1L, audioDurationMs = 1, recognitionDurationMs = null, text = "t$i", deleted = i == 2)) }

        val recent = repository.recentSummaries(3).first().single()

        assertEquals("毛概", recent.courseName)
        assertEquals(2, recent.segmentCount)
    }

    @Test fun movingAutoNamedRecordRenamesItAndMarksItForSync() = runBlocking {
        val from = db.courseDao().insert(CourseEntity(name = "英语", createdAt = 1))
        val to = db.courseDao().insert(CourseEntity(name = "毛概", createdAt = 2))
        val startedAt = 1_760_000_000_000
        val recordId = record(from, startedAt, name = RecordNameGenerator.defaultName("英语", startedAt))
        db.recordDao().markSyncedIfUnchanged(db.recordDao().sessionId(recordId)!!, startedAt)

        assertTrue(repository.moveRecord(recordId, to))

        val moved = db.recordDao().recordNow(recordId)!!
        assertEquals(to, moved.courseId)
        assertEquals(RecordNameGenerator.defaultName("毛概", startedAt), moved.name)
        assertEquals(SyncStatus.PENDING.name, moved.syncStatus)
        assertTrue(moved.updatedAt > startedAt)
    }

    @Test fun movingKeepsANameTheUserTyped() = runBlocking {
        val from = db.courseDao().insert(CourseEntity(name = "英语", createdAt = 1))
        val to = db.courseDao().insert(CourseEntity(name = "毛概", createdAt = 2))
        val recordId = record(from, 10, name = "期中复习课")

        repository.moveRecord(recordId, to)

        assertEquals("期中复习课", db.recordDao().recordNow(recordId)!!.name)
    }

    @Test fun movingToTheSameOrADeletedCourseChangesNothing() = runBlocking {
        val course = db.courseDao().insert(CourseEntity(name = "英语", createdAt = 1))
        val gone = db.courseDao().insert(CourseEntity(name = "已删", createdAt = 2, deleted = true))
        val recordId = record(course, 10)
        val before = db.recordDao().recordNow(recordId)!!

        assertTrue(repository.moveRecord(recordId, course))
        assertEquals(false, repository.moveRecord(recordId, gone))

        assertEquals(before, db.recordDao().recordNow(recordId))
    }
}
