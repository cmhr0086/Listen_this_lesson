package com.cmhr.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.StreamingWavRecorder
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingRepository
import com.cmhr.listen.data.recording.RecordingState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ListenDatabase
    private lateinit var directory: File
    private lateinit var repository: RecordingRepository
    private var recordId = 0L
    private lateinit var sessionId: String

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "recovery-test-${System.nanoTime()}").apply { mkdirs() }
        repository = RecordingRepository(context, db, directory)
        val courseId = db.courseDao().insert(CourseEntity(name = "课程", createdAt = 1))
        recordId = db.recordDao().insert(SessionEntity(courseId = courseId, name = "课堂", startedAt = 1_000))
        sessionId = db.recordDao().sessionId(recordId)!!
    }

    @After fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    /** Writes [seconds] of audio with a checkpoint, then "dies" without finish(). */
    private fun killedMidCapture(name: String, seconds: Int): File {
        val file = File(directory, name)
        val writer = StreamingWavRecorder(file)
        writer.append(ByteArray(seconds * PcmRecorder.SAMPLE_RATE_HZ * PcmRecorder.BYTES_PER_SAMPLE))
        writer.checkpoint()
        writer.close()
        return file
    }

    @Test fun captureKilledWithoutStopBecomesRecognizableAndEndsTheSession() = runBlocking {
        val row = repository.create(recordId, sessionId, now = 1_000)
        killedMidCapture(row.localPath, seconds = 3)

        repository.recoverInterrupted(now = 9_000)

        val recovered = db.recordingDao().recording(row.recordingId)!!
        assertEquals(RecordingState.INTERRUPTED, recovered.recordingState)
        assertTrue(recovered.recordingState.canStartRecognition)
        assertEquals(3_000L, recovered.durationMs)
        assertEquals(3L * PcmRecorder.SAMPLE_RATE_HZ, recovered.totalFrames)
        assertEquals(4_000L, db.recordDao().record(recordId).first()!!.endedAt)
    }

    @Test fun legacyPartFileIsRepairedAndRenamed() = runBlocking {
        // Shape written by builds before the checkpointed-WAV change.
        val legacy = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "legacy.wav.part", startedAt = 1_000)
            .let { it.copy(localPath = "${it.recordingId}.wav.part") }
        db.recordingDao().insert(legacy)
        killedMidCapture(legacy.localPath, seconds = 2)

        repository.recoverInterrupted()

        val recovered = db.recordingDao().recording(legacy.recordingId)!!
        assertEquals("${legacy.recordingId}.wav", recovered.localPath)
        assertTrue(File(directory, recovered.localPath).isFile)
        assertFalse(File(directory, legacy.localPath).exists())
        assertEquals(RecordingState.INTERRUPTED, recovered.recordingState)
    }

    @Test fun rowLeftPointingAtMissingPartFileIsRelinkedToTheRealAudio() = runBlocking {
        // Shape left by the old stop path: file renamed to .wav, row marked FAILED on .part.
        val broken = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "x", startedAt = 1_000, state = RecordingState.FAILED.name, errorMessage = "异常中止的录音没有可用音频。")
            .let { it.copy(localPath = "${it.recordingId}.wav.part") }
        db.recordingDao().insert(broken)
        killedMidCapture("${broken.recordingId}.wav", seconds = 4)

        repository.recoverInterrupted()

        val relinked = db.recordingDao().recording(broken.recordingId)!!
        assertEquals("${broken.recordingId}.wav", relinked.localPath)
        assertEquals(RecordingState.INTERRUPTED, relinked.recordingState)
        assertEquals(4_000L, relinked.durationMs)
        assertNull(relinked.errorMessage)
    }

    @Test fun captureWithNoAudioFailsInsteadOfStayingRecording() = runBlocking {
        val row = repository.create(recordId, sessionId, now = 1_000)
        repository.recoverInterrupted()
        assertEquals(RecordingState.FAILED, db.recordingDao().recording(row.recordingId)!!.recordingState)
    }

    @Test fun interruptedRecognitionIsPausedNotFailed() = runBlocking {
        val row = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "p.wav", startedAt = 1_000, state = RecordingState.RECORDED.name, totalFrames = 16_000)
        db.recordingDao().insert(row)
        File(directory, "p.wav").writeBytes(ByteArray(44 + 32_000))
        assertEquals(1, db.recordingDao().claimProcessing(row.recordingId, "run", 2_000))

        repository.recoverInterrupted()

        val paused = db.recordingDao().recording(row.recordingId)!!
        assertEquals(RecordingState.PAUSED, paused.recordingState)
        assertNull(paused.processingRunId)
        assertEquals(1, db.recordingDao().claimProcessing(row.recordingId, "run-2", 3_000))
    }

    @Test fun deletingOneRecordingRemovesItsAudioButNotActiveOnes() = runBlocking {
        val idle = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "idle.wav", startedAt = 1_000, state = RecordingState.COMPLETED.name)
        val active = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "active.wav", startedAt = 2_000)
        db.recordingDao().insert(idle)
        db.recordingDao().insert(active)
        File(directory, "idle.wav").writeBytes(ByteArray(100))

        assertTrue(repository.delete(idle.recordingId))
        assertFalse(repository.delete(active.recordingId))

        assertNull(db.recordingDao().recording(idle.recordingId))
        assertFalse(File(directory, "idle.wav").exists())
        assertNotNull(db.recordingDao().recording(active.recordingId))
    }
}
