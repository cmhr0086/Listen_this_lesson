package com.cmhr.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.recording.RecordingChunkEntity
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingState
import com.cmhr.listen.data.recording.RecordingChunkState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun recordingBelongsToSessionAndProcessingClaimIsAtomic() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java).allowMainThreadQueries().build()
        try {
            val courseId = db.courseDao().insert(CourseEntity(name = "课程", createdAt = 1))
            val recordId = db.recordDao().insert(SessionEntity(courseId = courseId, name = "课堂", startedAt = 2))
            val sessionId = db.recordDao().sessionId(recordId)!!
            val recording = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "local.wav", startedAt = 2, state = RecordingState.RECORDED.name, totalFrames = 16_000)
            db.recordingDao().insert(recording)
            val saved = db.recordingDao().observeForSession(recordId).first().single()
            assertEquals(sessionId, saved.sessionId)
            assertEquals(1, db.recordingDao().claimProcessing(saved.recordingId, "run-1", 3))
            assertEquals(0, db.recordingDao().claimProcessing(saved.recordingId, "run-2", 4))
        } finally { db.close() }
    }

    @Test fun persistedChunksKeepStableUuidSequenceAndWindowAcrossRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java).allowMainThreadQueries().build()
        try {
            val courseId = db.courseDao().insert(CourseEntity(name = "课程", createdAt = 1))
            val recordId = db.recordDao().insert(SessionEntity(courseId = courseId, name = "课堂", startedAt = 2))
            val sessionId = db.recordDao().sessionId(recordId)!!
            val recording = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "local.wav", startedAt = 2, state = RecordingState.RECORDED.name)
            db.recordingDao().insert(recording)
            val chunk = RecordingChunkEntity(recordingId = recording.recordingId, windowStartFrame = 0, windowEndFrame = 4_800_000, startFrame = 1_000, endFrame = 20_000, sequenceNumber = 7)
            db.recordingDao().insertChunks(listOf(chunk))
            db.recordingDao().insertChunks(listOf(chunk))
            val restored = db.recordingDao().chunksForWindow(recording.recordingId, 0)
            assertEquals(1, restored.size)
            assertEquals(chunk.chunkId, restored.single().chunkId)
            assertEquals(7, restored.single().sequenceNumber)
            assertTrue(restored.single().endFrame > restored.single().startFrame)
        } finally { db.close() }
    }

    @Test fun midWindowFailurePreservesCompletedChunksAndResumesWithoutAdvancingCursor() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java).allowMainThreadQueries().build()
        try {
            val courseId = db.courseDao().insert(CourseEntity(name = "课程", createdAt = 1))
            val recordId = db.recordDao().insert(SessionEntity(courseId = courseId, name = "课堂", startedAt = 2))
            val sessionId = db.recordDao().sessionId(recordId)!!
            val recording = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "local.wav", startedAt = 2, state = RecordingState.RECORDED.name, totalFrames = 4_800_000)
            db.recordingDao().insert(recording)
            val chunks = listOf(
                RecordingChunkEntity(recordingId = recording.recordingId, windowStartFrame = 0, windowEndFrame = 4_800_000, startFrame = 100, endFrame = 1_000, sequenceNumber = 1),
                RecordingChunkEntity(recordingId = recording.recordingId, windowStartFrame = 0, windowEndFrame = 4_800_000, startFrame = 2_000, endFrame = 3_000, sequenceNumber = 2)
            )
            db.recordingDao().insertChunks(chunks)
            assertEquals(1, db.recordingDao().claimProcessing(recording.recordingId, "first-run", 3))
            db.recordingDao().setActiveWindow(recording.recordingId, "first-run", 0, 4_800_000, "vad", 3)
            db.recordingDao().updateChunkState(chunks[0].chunkId, RecordingChunkState.COMPLETED.name)
            db.recordingDao().updateChunkState(chunks[1].chunkId, RecordingChunkState.FAILED.name, "network")
            db.recordingDao().fail(recording.recordingId, 4, "network")

            val failed = db.recordingDao().recording(recording.recordingId)!!
            assertEquals(0L, failed.processedFrames)
            assertEquals(0L, failed.activeWindowStartFrame)
            assertEquals(1, db.recordingDao().claimProcessing(recording.recordingId, "second-run", 5))
            val restored = db.recordingDao().chunksForWindow(recording.recordingId, 0)
            assertEquals(RecordingChunkState.COMPLETED.name, restored[0].state)
            assertEquals(RecordingChunkState.FAILED.name, restored[1].state)
            assertEquals(chunks.map { it.chunkId }, restored.map { it.chunkId })
        } finally { db.close() }
    }
}
