package com.cmhr.listen.data.stt

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.cmhr.listen.data.course.ClassRecordEntity
import kotlinx.coroutines.flow.Flow

enum class AsrLifecycleState {
    CAPTURING,
    QUEUED_LOCAL,
    SUBMITTING,
    QUEUED_SERVER,
    PROCESSING,
    RETRY_WAIT,
    SUBMISSION_UNKNOWN,
    COMPLETED,
    FAILED,
    DROPPED
}

enum class AsrFailureStage {
    AUDIO_CAPTURE,
    LOCAL_PERSISTENCE,
    CLIENT_QUEUE,
    CONNECT,
    AUDIO_UPLOAD,
    SUBMIT_RESPONSE,
    SERVER_QUEUE,
    MODEL_PROCESSING,
    JOB_POLLING,
    RESULT_PARSE
}

enum class AsrRequestKind { HEALTH, SUBMIT, POLL }

enum class AsrNetworkEventType {
    CALL_START,
    CONNECT_END,
    REQUEST_BODY_START,
    REQUEST_BODY_END,
    RESPONSE_HEADERS_START,
    RESPONSE_BODY_END,
    CALL_FAILED
}

@Entity(
    tableName = "asr_segment_diagnostics",
    foreignKeys = [
        ForeignKey(
            entity = ClassRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("recordId"),
        Index("state"),
        Index(value = ["jobId"], unique = true)
    ]
)
data class AsrSegmentDiagnosticEntity(
    @PrimaryKey val segmentId: String,
    val recordId: Long,
    val jobId: String? = null,
    val state: String = AsrLifecycleState.QUEUED_LOCAL.name,
    val audioStartTime: Long,
    val audioEndTime: Long,
    val audioDurationMs: Long,
    val wavRelativePath: String? = null,
    val language: String = "Chinese",
    val contextSnapshot: String? = null,
    val captureStartedAt: Long,
    val captureFinishedAt: Long,
    val queuedLocalAt: Long,
    val captureStartedElapsedMs: Long? = null,
    val captureFinishedElapsedMs: Long? = null,
    val queuedLocalElapsedMs: Long? = null,
    val submitStartedAt: Long? = null,
    val submitCompletedAt: Long? = null,
    val submitStartedElapsedMs: Long? = null,
    val submitCompletedElapsedMs: Long? = null,
    val firstServerQueuedAt: Long? = null,
    val firstServerProcessingAt: Long? = null,
    val firstServerCompletedAt: Long? = null,
    val firstServerQueuedElapsedMs: Long? = null,
    val firstServerProcessingElapsedMs: Long? = null,
    val firstServerCompletedElapsedMs: Long? = null,
    val finishedAt: Long? = null,
    val finishedElapsedMs: Long? = null,
    val submitAttempts: Int = 0,
    val pollAttempts: Int = 0,
    val nextAttemptAt: Long? = null,
    val lastHttpStatus: Int? = null,
    val serverModel: String? = null,
    val postDurationMs: Long? = null,
    val uploadDurationMs: Long? = null,
    val submitResponseWaitDurationMs: Long? = null,
    val resultResponseDurationMs: Long? = null,
    val failureStage: String? = null,
    val exceptionClass: String? = null,
    val safeErrorMessage: String? = null
) {
    val lifecycleState: AsrLifecycleState
        get() = runCatching { AsrLifecycleState.valueOf(state) }.getOrDefault(AsrLifecycleState.FAILED)

    val clientQueueDurationMs: Long?
        get() = monotonicDuration(queuedLocalElapsedMs, submitStartedElapsedMs)
            ?: submitStartedAt?.let { (it - queuedLocalAt).coerceAtLeast(0) }

    val serverWaitDurationMs: Long?
        get() = monotonicDuration(submitCompletedElapsedMs, firstServerCompletedElapsedMs)
            ?: firstServerCompletedAt?.let { completed ->
            submitCompletedAt?.let { submitted -> (completed - submitted).coerceAtLeast(0) }
        }

    val estimatedServerQueueDurationMs: Long?
        get() = monotonicDuration(
            firstServerQueuedElapsedMs ?: submitCompletedElapsedMs,
            firstServerProcessingElapsedMs
        ) ?: firstServerProcessingAt?.let { processing ->
            (processing - (firstServerQueuedAt ?: submitCompletedAt ?: processing)).coerceAtLeast(0)
        }

    val estimatedProcessingDurationMs: Long?
        get() = monotonicDuration(firstServerProcessingElapsedMs, firstServerCompletedElapsedMs)
            ?: firstServerCompletedAt?.let { completed ->
            firstServerProcessingAt?.let { processing -> (completed - processing).coerceAtLeast(0) }
        }

    val totalEndToEndDurationMs: Long?
        get() = monotonicDuration(captureStartedElapsedMs, finishedElapsedMs)
            ?: finishedAt?.let { (it - captureStartedAt).coerceAtLeast(0) }
}

private fun monotonicDuration(start: Long?, end: Long?): Long? =
    if (start != null && end != null && end >= start) end - start else null

@Entity(
    tableName = "asr_network_events",
    foreignKeys = [
        ForeignKey(
            entity = AsrSegmentDiagnosticEntity::class,
            parentColumns = ["segmentId"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("segmentId")]
)
data class AsrNetworkEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: String,
    val requestKind: String,
    val attempt: Int,
    val eventType: String,
    val timestampMs: Long,
    val elapsedSinceCallStartMs: Long?,
    val exceptionClass: String? = null,
    val appInForeground: Boolean = true
)

data class AsrDiagnosticStateCounts(
    val completedCount: Int,
    val failedCount: Int,
    val droppedCount: Int,
    val discardedFillerCount: Int = 0
)

data class AsrRuntimeSummary(
    val activeCount: Int,
    val recognizingCount: Int
)

@Dao
interface AsrDiagnosticsDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(value: AsrSegmentDiagnosticEntity)

    @Update
    suspend fun updateSegment(value: AsrSegmentDiagnosticEntity)

    @Query("SELECT * FROM asr_segment_diagnostics WHERE segmentId = :segmentId")
    suspend fun segment(segmentId: String): AsrSegmentDiagnosticEntity?

    @Query("SELECT * FROM asr_segment_diagnostics ORDER BY captureStartedAt DESC")
    fun observeAll(): Flow<List<AsrSegmentDiagnosticEntity>>

    @Query("SELECT * FROM asr_segment_diagnostics WHERE recordId = :recordId ORDER BY captureStartedAt DESC")
    fun observeForRecord(recordId: Long): Flow<List<AsrSegmentDiagnosticEntity>>

    @Query("SELECT * FROM asr_segment_diagnostics WHERE recordId = :recordId ORDER BY captureStartedAt DESC LIMIT :limit")
    fun observeRecentForRecord(
        recordId: Long,
        limit: Int = 15
    ): Flow<List<AsrSegmentDiagnosticEntity>>

    @Query("SELECT COUNT(*) FROM asr_segment_diagnostics WHERE recordId = :recordId")
    fun observeCountForRecord(recordId: Long): Flow<Int>

    @Query("SELECT * FROM asr_segment_diagnostics WHERE recordId = :recordId AND state IN (:states) ORDER BY audioStartTime ASC")
    fun observeActiveForRecord(
        recordId: Long,
        states: List<String> = ACTIVE_ASR_STATES
    ): Flow<List<AsrSegmentDiagnosticEntity>>

    @Query(
        """
        SELECT
            COUNT(CASE WHEN state = 'COMPLETED' THEN 1 END) AS completedCount,
            COUNT(CASE WHEN state = 'FAILED' THEN 1 END) AS failedCount,
            COUNT(CASE WHEN state = 'DROPPED' THEN 1 END) AS droppedCount,
            COUNT(CASE WHEN state = 'DROPPED' AND safeErrorMessage LIKE '%' || :fillerMarker || '%' THEN 1 END) AS discardedFillerCount
        FROM asr_segment_diagnostics
        WHERE recordId = :recordId AND captureStartedAt >= :since
        """
    )
    fun observeStateCountsForRecordSince(
        recordId: Long,
        since: Long,
        fillerMarker: String = "语气词"
    ): Flow<AsrDiagnosticStateCounts>

    @Query(
        """
        SELECT
            COUNT(CASE WHEN state IN (:activeStates) THEN 1 END) AS activeCount,
            COUNT(CASE WHEN state IN (:recognizingStates) THEN 1 END) AS recognizingCount
        FROM asr_segment_diagnostics
        """
    )
    fun observeRuntimeSummary(
        activeStates: List<String> = ACTIVE_ASR_STATES,
        recognizingStates: List<String> = RECOGNIZING_ASR_STATES
    ): Flow<AsrRuntimeSummary>

    @Query("SELECT * FROM asr_segment_diagnostics WHERE state IN (:states) ORDER BY audioStartTime ASC LIMIT 1")
    suspend fun oldestRunnable(states: List<String>): AsrSegmentDiagnosticEntity?

    @Query("SELECT MIN(nextAttemptAt) FROM asr_segment_diagnostics WHERE state IN (:states) AND nextAttemptAt IS NOT NULL")
    suspend fun nextAttemptAt(states: List<String>): Long?

    @Query("SELECT COUNT(*) FROM asr_segment_diagnostics WHERE state IN (:states)")
    fun observeQueueCount(states: List<String>): Flow<Int>

    @Query("SELECT COUNT(*) FROM asr_segment_diagnostics WHERE state IN (:states)")
    suspend fun queueCount(states: List<String>): Int

    @Query("UPDATE asr_segment_diagnostics SET state = :unknownState, failureStage = :failureStage, safeErrorMessage = :message WHERE state = :submittingState")
    suspend fun recoverInterruptedSubmissions(
        submittingState: String = "SUBMITTING",
        unknownState: String = "SUBMISSION_UNKNOWN",
        failureStage: String = "SUBMIT_RESPONSE",
        message: String = "提交过程被中断，无法确认服务端是否已接收。"
    )

    @Query("UPDATE asr_segment_diagnostics SET state = :readyState, jobId = NULL, failureStage = NULL, exceptionClass = NULL, safeErrorMessage = NULL, nextAttemptAt = NULL WHERE segmentId = :segmentId AND state = :unknownState")
    suspend fun confirmRetryUnknown(
        segmentId: String,
        unknownState: String = "SUBMISSION_UNKNOWN",
        readyState: String = "QUEUED_LOCAL"
    ): Int

    @Query("SELECT * FROM asr_network_events WHERE segmentId = :segmentId ORDER BY id ASC")
    fun observeEvents(segmentId: String): Flow<List<AsrNetworkEventEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(values: List<AsrNetworkEventEntity>)
}

val ACTIVE_ASR_STATES = listOf(
    AsrLifecycleState.QUEUED_LOCAL.name,
    AsrLifecycleState.SUBMITTING.name,
    AsrLifecycleState.QUEUED_SERVER.name,
    AsrLifecycleState.PROCESSING.name,
    AsrLifecycleState.RETRY_WAIT.name,
    AsrLifecycleState.SUBMISSION_UNKNOWN.name
)

val RUNNABLE_ASR_STATES = listOf(
    AsrLifecycleState.QUEUED_LOCAL.name,
    AsrLifecycleState.QUEUED_SERVER.name,
    AsrLifecycleState.PROCESSING.name,
    AsrLifecycleState.RETRY_WAIT.name
)

val RECOGNIZING_ASR_STATES = listOf(
    AsrLifecycleState.SUBMITTING.name,
    AsrLifecycleState.QUEUED_SERVER.name,
    AsrLifecycleState.PROCESSING.name
)
