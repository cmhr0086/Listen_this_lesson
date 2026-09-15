package com.cmhr.listen.data.recording

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cmhr.listen.data.course.SessionEntity
import java.util.UUID

enum class RecordingState { RECORDING, RECORDED, PROCESSING, COMPLETED, FAILED }
enum class RecordingChunkState { PLANNED, QUEUED, COMPLETED, FAILED, SUBMISSION_UNKNOWN }

@Entity(
    tableName = "recordings",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordId"), Index("sessionId"), Index(value = ["recordingId"], unique = true)]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String = UUID.randomUUID().toString(),
    val recordId: Long,
    val sessionId: String,
    /** File name relative to filesDir/recordings. */
    val localPath: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    val totalFrames: Long = 0,
    val createdAt: Long = startedAt,
    val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "'RECORDING'") val state: String = RecordingState.RECORDING.name,
    val processedFrames: Long = 0,
    val activeWindowStartFrame: Long? = null,
    val activeWindowEndFrame: Long? = null,
    val vadConfigSnapshot: String? = null,
    val vadAlgorithmVersion: Int = 1,
    val processingRunId: String? = null,
    val errorMessage: String? = null
) {
    val recordingState: RecordingState
        get() = runCatching { RecordingState.valueOf(state) }.getOrDefault(RecordingState.FAILED)
}

@Entity(
    tableName = "recording_chunks",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["recordingId"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("recordingId"),
        Index(value = ["chunkId"], unique = true),
        Index(value = ["recordingId", "windowStartFrame", "startFrame", "endFrame"], unique = true)
    ]
)
data class RecordingChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: String = UUID.randomUUID().toString(),
    val recordingId: String,
    val windowStartFrame: Long,
    val windowEndFrame: Long,
    val startFrame: Long,
    val endFrame: Long,
    val sequenceNumber: Long,
    val contextSnapshot: String? = null,
    @ColumnInfo(defaultValue = "'PLANNED'") val state: String = RecordingChunkState.PLANNED.name,
    val errorMessage: String? = null
)
