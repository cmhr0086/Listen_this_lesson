package com.cmhr.listen.data.recording

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.StreamingWavRecorder
import com.cmhr.listen.data.course.ListenDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class RecordingRepository(
    private val context: Context,
    private val database: ListenDatabase = ListenDatabase.get(context),
    private val baseDirectory: File = File(context.filesDir, DIRECTORY)
) {
    private val dao = database.recordingDao()
    val directory: File get() = baseDirectory

    fun observeForSession(recordId: Long): Flow<List<RecordingEntity>> = dao.observeForSession(recordId)
    fun observePendingCounts(): Flow<List<PendingRecordingCount>> = dao.observePendingCounts()
    suspend fun recording(recordingId: String) = dao.recording(recordingId)

    /** Audio is written straight to its final name; there is no rename step that a crash could interrupt. */
    suspend fun create(recordId: Long, sessionId: String, now: Long = System.currentTimeMillis()): RecordingEntity {
        val entity = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "", startedAt = now)
        val normalized = entity.copy(localPath = audioFileName(entity.recordingId))
        return normalized.copy(id = dao.insert(normalized))
    }

    suspend fun updateCaptureProgress(recordingId: String, frames: Long, now: Long = System.currentTimeMillis()) =
        dao.updateCaptureProgress(recordingId, frames.toDurationMs(), frames, now)

    /** Marks a normally stopped capture as ready. Repairs the header first in case the final write failed. */
    suspend fun finalizeCapture(recording: RecordingEntity, file: File): RecordingEntity? {
        StreamingWavRecorder.repair(file)
        val frames = StreamingWavRecorder.framesIn(file)
        val now = System.currentTimeMillis()
        if (frames <= 0) dao.fail(recording.recordingId, now, "录音没有采集到音频。")
        else dao.finalizeCapture(recording.recordingId, file.name, now, frames.toDurationMs(), frames)
        return dao.recording(recording.recordingId)
    }

    /**
     * Repairs everything a previous process left behind. Must run before this process starts any
     * capture or recognition, so every RECORDING/PROCESSING row it sees is stale by construction.
     */
    suspend fun recoverInterrupted(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        dao.interruptedRecordings().forEach { item ->
            val audio = locateAudio(item)?.takeIf(StreamingWavRecorder::repair)?.let { canonicalize(item, it) }
            if (audio != null) {
                val frames = StreamingWavRecorder.framesIn(audio)
                val endedAt = item.startedAt + frames.toDurationMs()
                database.withTransaction {
                    dao.finalizeCapture(item.recordingId, audio.name, endedAt, frames.toDurationMs(), frames, RecordingState.INTERRUPTED.name)
                    database.recordDao().endIfOpen(item.recordId, endedAt, now)
                }
            } else {
                database.withTransaction {
                    dao.fail(item.recordingId, now, "录音意外中断，且没有保存到可用音频。")
                    database.recordDao().endIfOpen(item.recordId, item.startedAt + item.durationMs, now)
                }
            }
        }
        // Older builds could leave a finished row pointing at a file name that no longer exists
        // while the audio sits next to it under another name. Point those rows back at the audio.
        dao.all().forEach { item ->
            val state = item.recordingState
            if (state == RecordingState.RECORDING || state == RecordingState.PROCESSING) return@forEach
            if (File(directory, item.localPath).isFile) return@forEach
            val audio = locateAudio(item)?.takeIf(StreamingWavRecorder::repair)?.let { canonicalize(item, it) } ?: return@forEach
            val frames = StreamingWavRecorder.framesIn(audio)
            val repairedState = when {
                state != RecordingState.FAILED -> state
                item.processedFrames > 0 -> RecordingState.PAUSED
                else -> RecordingState.INTERRUPTED
            }
            dao.relink(item.recordingId, audio.name, frames.toDurationMs(), frames, repairedState.name, null, now)
        }
        dao.recoverInterruptedProcessing(now)
    }

    /** Deletes one recording and its audio. Transcript segments already produced are kept. */
    suspend fun delete(recordingId: String): Boolean = withContext(Dispatchers.IO) {
        val row = dao.recording(recordingId) ?: return@withContext false
        if (!row.recordingState.canDelete || dao.deleteIdle(recordingId) == 0) return@withContext false
        candidateNames(row).forEach { File(directory, it).delete() }
        true
    }

    suspend fun deleteForSession(recordId: Long) = withContext(Dispatchers.IO) {
        val rows = dao.observeForSession(recordId).first()
        database.withTransaction { dao.deleteForSession(recordId) }
        rows.flatMap(::candidateNames).distinct().forEach { File(directory, it).delete() }
    }

    private fun candidateNames(item: RecordingEntity) =
        listOf(item.localPath, audioFileName(item.recordingId), legacyPartName(item.recordingId)).filter(String::isNotBlank).distinct()

    private fun locateAudio(item: RecordingEntity): File? = candidateNames(item)
        .map { File(directory, it) }
        .firstOrNull { it.isFile && it.length() > StreamingWavRecorder.HEADER_BYTES }

    private fun canonicalize(item: RecordingEntity, audio: File): File {
        val target = File(directory, audioFileName(item.recordingId))
        return if (audio == target || (!target.exists() && audio.renameTo(target))) target else audio
    }

    companion object {
        const val DIRECTORY = "recordings"
        fun audioFileName(recordingId: String) = "$recordingId.wav"
        private fun legacyPartName(recordingId: String) = "$recordingId.wav.part"
        private fun Long.toDurationMs() = this * 1_000 / PcmRecorder.SAMPLE_RATE_HZ
    }
}

/**
 * Process-wide one-shot gate: recovery runs exactly once per process, and capture or recognition
 * waits for it, so recovery can never touch a recording that this process is actively writing.
 */
object RecordingRecovery {
    private val mutex = Mutex()
    @Volatile private var done = false

    suspend fun ensure(repository: RecordingRepository) {
        if (done) return
        mutex.withLock {
            if (done) return
            runCatching { repository.recoverInterrupted() }
                .onFailure { Log.e("RecordingRecovery", "Recording recovery failed", it) }
            done = true
        }
    }
}
