package com.cmhr.listen.data.recording

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert suspend fun insert(recording: RecordingEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertChunks(chunks: List<RecordingChunkEntity>): List<Long>

    @Query("SELECT * FROM recordings WHERE recordId = :recordId ORDER BY startedAt DESC, id DESC")
    fun observeForSession(recordId: Long): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE recordingId = :recordingId LIMIT 1")
    suspend fun recording(recordingId: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE state = 'RECORDING'")
    suspend fun interruptedRecordings(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE state = 'PROCESSING' AND processingRunId IS NOT NULL")
    suspend fun interruptedProcessing(): List<RecordingEntity>

    @Query("UPDATE recordings SET durationMs = :durationMs, totalFrames = :totalFrames, updatedAt = :updatedAt WHERE recordingId = :recordingId AND state = 'RECORDING'")
    suspend fun updateCaptureProgress(recordingId: String, durationMs: Long, totalFrames: Long, updatedAt: Long): Int

    @Query("UPDATE recordings SET localPath = :localPath, endedAt = :endedAt, durationMs = :durationMs, totalFrames = :totalFrames, state = 'RECORDED', updatedAt = :endedAt, errorMessage = :warning WHERE recordingId = :recordingId AND state = 'RECORDING'")
    suspend fun finalizeCapture(recordingId: String, localPath: String, endedAt: Long, durationMs: Long, totalFrames: Long, warning: String? = null): Int

    @Query("UPDATE recordings SET state = 'FAILED', processingRunId = NULL, updatedAt = :updatedAt, errorMessage = :message WHERE recordingId = :recordingId")
    suspend fun fail(recordingId: String, updatedAt: Long, message: String): Int

    @Query("UPDATE recordings SET state = 'PROCESSING', processingRunId = :runId, updatedAt = :updatedAt, errorMessage = NULL WHERE recordingId = :recordingId AND state IN ('RECORDED','FAILED') AND processingRunId IS NULL")
    suspend fun claimProcessing(recordingId: String, runId: String, updatedAt: Long): Int

    @Query("UPDATE recordings SET state = 'FAILED', processingRunId = NULL, updatedAt = :updatedAt, errorMessage = :message WHERE state = 'PROCESSING'")
    suspend fun recoverInterruptedProcessing(updatedAt: Long, message: String): Int

    @Query("UPDATE recordings SET activeWindowStartFrame = :startFrame, activeWindowEndFrame = :endFrame, vadConfigSnapshot = COALESCE(vadConfigSnapshot, :vadConfig), updatedAt = :updatedAt WHERE recordingId = :recordingId AND processingRunId = :runId")
    suspend fun setActiveWindow(recordingId: String, runId: String, startFrame: Long, endFrame: Long, vadConfig: String, updatedAt: Long): Int

    @Query("UPDATE recordings SET processedFrames = :processedFrames, activeWindowStartFrame = NULL, activeWindowEndFrame = NULL, updatedAt = :updatedAt WHERE recordingId = :recordingId AND processingRunId = :runId")
    suspend fun advanceWindow(recordingId: String, runId: String, processedFrames: Long, updatedAt: Long): Int

    @Query("UPDATE recordings SET state = 'COMPLETED', processingRunId = NULL, processedFrames = totalFrames, activeWindowStartFrame = NULL, activeWindowEndFrame = NULL, updatedAt = :updatedAt, errorMessage = NULL WHERE recordingId = :recordingId AND processingRunId = :runId")
    suspend fun complete(recordingId: String, runId: String, updatedAt: Long): Int

    @Query("SELECT * FROM recording_chunks WHERE recordingId = :recordingId AND windowStartFrame = :windowStart ORDER BY startFrame, id")
    suspend fun chunksForWindow(recordingId: String, windowStart: Long): List<RecordingChunkEntity>

    @Query("UPDATE recording_chunks SET state = :state, errorMessage = :error WHERE chunkId = :chunkId")
    suspend fun updateChunkState(chunkId: String, state: String, error: String? = null): Int

    @Query("DELETE FROM recordings WHERE recordId = :recordId")
    suspend fun deleteForSession(recordId: Long): Int
}
