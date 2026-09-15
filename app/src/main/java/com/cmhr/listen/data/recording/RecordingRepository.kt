package com.cmhr.listen.data.recording

import android.content.Context
import androidx.room.withTransaction
import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.StreamingWavRecorder
import com.cmhr.listen.data.course.ListenDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class RecordingRepository(private val context: Context, private val database: ListenDatabase = ListenDatabase.get(context)) {
    private val dao = database.recordingDao()
    val directory: File get() = File(context.filesDir, DIRECTORY)

    fun observeForSession(recordId: Long): Flow<List<RecordingEntity>> = dao.observeForSession(recordId)
    suspend fun recording(recordingId: String) = dao.recording(recordingId)

    suspend fun create(recordId: Long, sessionId: String, now: Long = System.currentTimeMillis()): RecordingEntity {
        val entity = RecordingEntity(recordId = recordId, sessionId = sessionId, localPath = "${java.util.UUID.randomUUID()}.wav.part", startedAt = now)
        val normalized = entity.copy(localPath = "${entity.recordingId}.wav.part")
        return normalized.copy(id = dao.insert(normalized))
    }

    suspend fun updateCaptureProgress(recordingId: String, frames: Long, now: Long = System.currentTimeMillis()) =
        dao.updateCaptureProgress(recordingId, frames * 1_000 / PcmRecorder.SAMPLE_RATE_HZ, frames, now)

    suspend fun finalizeCapture(recording: RecordingEntity, file: File, warning: String? = null): RecordingEntity {
        val frames = StreamingWavRecorder.framesIn(file)
        val now = System.currentTimeMillis()
        dao.finalizeCapture(recording.recordingId, file.name, now, frames * 1_000 / PcmRecorder.SAMPLE_RATE_HZ, frames, warning)
        return requireNotNull(dao.recording(recording.recordingId))
    }

    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        directory.mkdirs()
        dao.interruptedRecordings().forEach { item ->
            val part = File(directory, item.localPath)
            if (part.isFile && StreamingWavRecorder.repair(part)) {
                val final = File(directory, "${item.recordingId}.wav")
                if (part.renameTo(final)) finalizeCapture(item, final, "录音曾异常中止，已保留可用音频。")
                else dao.fail(item.recordingId, System.currentTimeMillis(), "无法恢复异常中止的录音文件。")
            } else dao.fail(item.recordingId, System.currentTimeMillis(), "异常中止的录音没有可用音频。")
        }
        dao.recoverInterruptedProcessing(System.currentTimeMillis(), "识别被中断，请点击继续识别。")
    }

    suspend fun deleteForSession(recordId: Long) = withContext(Dispatchers.IO) {
        val rows = dao.observeForSession(recordId).first()
        database.withTransaction { dao.deleteForSession(recordId) }
        rows.forEach { File(directory, it.localPath).delete() }
    }

    companion object { const val DIRECTORY = "recordings" }
}
