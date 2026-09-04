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

const val MAX_IN_FLIGHT_JOBS = 3
const val MAX_CONCURRENT_HTTP_CALLS = 3

enum class AsrClockBasis {
    ELAPSED_REALTIME,
    LEGACY_WALL_FALLBACK
}

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
        Index(value = ["recordId", "sequenceNumber"], unique = true),
        Index(value = ["jobId"], unique = true)
    ]
)
data class AsrSegmentDiagnosticEntity(
    @PrimaryKey val segmentId: String,
    val recordId: Long,
    val sequenceNumber: Long? = null,
    val clockBasis: String = AsrClockBasis.ELAPSED_REALTIME.name,
    val bootCount: Int? = null,
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

    val diagnosticClockBasis: AsrClockBasis
        get() = runCatching { AsrClockBasis.valueOf(clockBasis) }
            .getOrDefault(AsrClockBasis.LEGACY_WALL_FALLBACK)

    val clientQueueDurationMs: Long?
        get() = diagnosticDuration(queuedLocalElapsedMs, submitStartedElapsedMs, queuedLocalAt, submitStartedAt)

    val serverWaitDurationMs: Long?
        get() = diagnosticDuration(submitCompletedElapsedMs, firstServerCompletedElapsedMs, submitCompletedAt, firstServerCompletedAt)

    val estimatedServerQueueDurationMs: Long?
        get() = diagnosticDuration(
            firstServerQueuedElapsedMs ?: submitCompletedElapsedMs,
            firstServerProcessingElapsedMs,
            firstServerQueuedAt ?: submitCompletedAt,
            firstServerProcessingAt
        )

    val estimatedProcessingDurationMs: Long?
        get() = diagnosticDuration(firstServerProcessingElapsedMs, firstServerCompletedElapsedMs, firstServerProcessingAt, firstServerCompletedAt)

    val totalEndToEndDurationMs: Long?
        get() = diagnosticDuration(captureStartedElapsedMs, finishedElapsedMs, captureStartedAt, finishedAt)

    private fun diagnosticDuration(
        elapsedStart: Long?,
        elapsedEnd: Long?,
        wallStart: Long?,
        wallEnd: Long?
    ): Long? = when (diagnosticClockBasis) {
        AsrClockBasis.ELAPSED_REALTIME -> validDuration(elapsedStart, elapsedEnd)
        AsrClockBasis.LEGACY_WALL_FALLBACK -> validDuration(wallStart, wallEnd)
    }
}

internal fun validDuration(start: Long?, end: Long?): Long? =
    if (start != null && end != null && start > 0L && end >= start) end - start else null

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
    val recognizingCount: Int,
    val queuedLocalCount: Int = 0,
    val submittingCount: Int = 0,
    val serverInFlightCount: Int = 0,
    val pollingCount: Int = 0,
    val submissionUnknownCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val globalInFlightCount: Int = 0,
    val inFlightCapacity: Int = MAX_IN_FLIGHT_JOBS
) {
    val inFlightCount: Int get() = submittingCount + serverInFlightCount
    val isBackpressured: Boolean get() = queuedLocalCount > 0 && globalInFlightCount >= inFlightCapacity
}

@Dao
interface AsrDiagnosticsDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(value: AsrSegmentDiagnosticEntity)

    @Update
    suspend fun updateSegment(value: AsrSegmentDiagnosticEntity): Int

    @Query("SELECT * FROM asr_segment_diagnostics WHERE segmentId = :segmentId")
    suspend fun segment(segmentId: String): AsrSegmentDiagnosticEntity?

    @Query("SELECT * FROM asr_segment_diagnostics ORDER BY captureStartedAt DESC")
    fun observeAll(): Flow<List<AsrSegmentDiagnosticEntity>>

    @Query("SELECT * FROM asr_segment_diagnostics WHERE recordId = :recordId ORDER BY sequenceNumber DESC, captureStartedAt DESC")
    fun observeForRecord(recordId: Long): Flow<List<AsrSegmentDiagnosticEntity>>

    @Query("SELECT * FROM asr_segment_diagnostics WHERE recordId = :recordId ORDER BY sequenceNumber DESC, captureStartedAt DESC LIMIT :limit")
    fun observeRecentForRecord(
        recordId: Long,
        limit: Int = 15
    ): Flow<List<AsrSegmentDiagnosticEntity>>

    @Query("SELECT COUNT(*) FROM asr_segment_diagnostics WHERE recordId = :recordId")
    fun observeCountForRecord(recordId: Long): Flow<Int>

    @Query("SELECT * FROM asr_segment_diagnostics WHERE recordId = :recordId AND state IN (:states) ORDER BY sequenceNumber ASC, audioStartTime ASC")
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
            COUNT(CASE WHEN state IN (:recognizingStates) THEN 1 END) AS recognizingCount,
            COUNT(CASE WHEN jobId IS NULL AND state IN ('QUEUED_LOCAL', 'RETRY_WAIT') THEN 1 END) AS queuedLocalCount,
            COUNT(CASE WHEN state = 'SUBMITTING' THEN 1 END) AS submittingCount,
            COUNT(CASE WHEN jobId IS NOT NULL AND state IN ('QUEUED_SERVER', 'PROCESSING', 'RETRY_WAIT') THEN 1 END) AS serverInFlightCount,
            0 AS pollingCount,
            COUNT(CASE WHEN state = 'SUBMISSION_UNKNOWN' THEN 1 END) AS submissionUnknownCount,
            COUNT(CASE WHEN state = 'COMPLETED' THEN 1 END) AS completedCount,
            COUNT(CASE WHEN state = 'FAILED' THEN 1 END) AS failedCount,
            0 AS globalInFlightCount,
            $MAX_IN_FLIGHT_JOBS AS inFlightCapacity
        FROM asr_segment_diagnostics
        WHERE recordId = :recordId
        """
    )
    fun observeRuntimeSummaryForRecord(
        recordId: Long,
        activeStates: List<String> = ACTIVE_ASR_STATES,
        recognizingStates: List<String> = RECOGNIZING_ASR_STATES
    ): Flow<AsrRuntimeSummary>

    @Query(
        """
        SELECT
            COUNT(CASE WHEN state IN (:activeStates) THEN 1 END) AS activeCount,
            COUNT(CASE WHEN state IN (:recognizingStates) THEN 1 END) AS recognizingCount,
            COUNT(CASE WHEN jobId IS NULL AND state IN ('QUEUED_LOCAL', 'RETRY_WAIT') THEN 1 END) AS queuedLocalCount,
            COUNT(CASE WHEN state = 'SUBMITTING' THEN 1 END) AS submittingCount,
            COUNT(CASE WHEN jobId IS NOT NULL AND state IN ('QUEUED_SERVER', 'PROCESSING', 'RETRY_WAIT') THEN 1 END) AS serverInFlightCount,
            0 AS pollingCount,
            COUNT(CASE WHEN state = 'SUBMISSION_UNKNOWN' THEN 1 END) AS submissionUnknownCount,
            COUNT(CASE WHEN state = 'COMPLETED' THEN 1 END) AS completedCount,
            COUNT(CASE WHEN state = 'FAILED' THEN 1 END) AS failedCount,
            COUNT(CASE WHEN state = 'SUBMITTING' OR (jobId IS NOT NULL AND state IN ('QUEUED_SERVER', 'PROCESSING', 'RETRY_WAIT')) THEN 1 END) AS globalInFlightCount,
            $MAX_IN_FLIGHT_JOBS AS inFlightCapacity
        FROM asr_segment_diagnostics
        """
    )
    fun observeRuntimeSummary(
        activeStates: List<String> = ACTIVE_ASR_STATES,
        recognizingStates: List<String> = RECOGNIZING_ASR_STATES
    ): Flow<AsrRuntimeSummary>

    @Query(
        """
        SELECT * FROM asr_segment_diagnostics
        WHERE jobId IS NULL
          AND state IN ('QUEUED_LOCAL', 'RETRY_WAIT')
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
        ORDER BY queuedLocalAt ASC
        LIMIT 1
        """
    )
    suspend fun nextDueSubmission(now: Long): AsrSegmentDiagnosticEntity?

    @Query(
        """
        UPDATE asr_segment_diagnostics
        SET state = 'SUBMITTING',
            submitAttempts = submitAttempts + 1,
            nextAttemptAt = NULL,
            submitStartedAt = COALESCE(submitStartedAt, :now),
            submitStartedElapsedMs = COALESCE(submitStartedElapsedMs, :nowElapsed),
            failureStage = NULL,
            exceptionClass = NULL,
            safeErrorMessage = NULL
        WHERE segmentId = :segmentId
          AND jobId IS NULL
          AND state IN ('QUEUED_LOCAL', 'RETRY_WAIT')
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
        """
    )
    suspend fun claimSubmission(segmentId: String, now: Long, nowElapsed: Long): Int

    @Query(
        """
        SELECT * FROM asr_segment_diagnostics
        WHERE jobId IS NOT NULL
          AND state IN ('QUEUED_SERVER', 'PROCESSING', 'RETRY_WAIT')
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
        ORDER BY COALESCE(nextAttemptAt, 0) ASC, submitCompletedAt ASC
        LIMIT :limit
        """
    )
    suspend fun duePolls(now: Long, limit: Int): List<AsrSegmentDiagnosticEntity>

    @Query(
        """
        SELECT COUNT(*) FROM asr_segment_diagnostics
        WHERE state = 'SUBMITTING'
           OR (jobId IS NOT NULL AND state IN ('QUEUED_SERVER', 'PROCESSING', 'RETRY_WAIT'))
        """
    )
    suspend fun inFlightCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM asr_segment_diagnostics
        WHERE state IN ('QUEUED_LOCAL', 'SUBMITTING', 'QUEUED_SERVER', 'PROCESSING', 'RETRY_WAIT')
        """
    )
    suspend fun automaticWorkCount(): Int

    @Query(
        """
        SELECT MIN(nextAttemptAt) FROM asr_segment_diagnostics
        WHERE state IN ('QUEUED_LOCAL', 'QUEUED_SERVER', 'PROCESSING', 'RETRY_WAIT')
          AND nextAttemptAt IS NOT NULL
        """
    )
    suspend fun nextAutomaticAttemptAt(): Long?

    @Query("SELECT COALESCE(MAX(sequenceNumber), 0) + 1 FROM asr_segment_diagnostics WHERE recordId = :recordId")
    suspend fun nextSequenceNumber(recordId: Long): Long

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

    @Query("SELECT * FROM asr_segment_diagnostics WHERE state = 'SUBMITTING'")
    suspend fun submittingSegments(): List<AsrSegmentDiagnosticEntity>

    @Query("UPDATE asr_segment_diagnostics SET clockBasis = :legacyClockBasis WHERE state IN (:activeStates) AND clockBasis = 'ELAPSED_REALTIME' AND (bootCount IS NULL OR bootCount != :currentBootCount)")
    suspend fun markTasksFromOtherBootAsLegacyClock(
        currentBootCount: Int,
        legacyClockBasis: String = "LEGACY_WALL_FALLBACK",
        activeStates: List<String> = ACTIVE_ASR_STATES
    )

    @Query("UPDATE asr_segment_diagnostics SET clockBasis = :legacyClockBasis WHERE state IN (:activeStates) AND clockBasis = 'ELAPSED_REALTIME'")
    suspend fun markAllRecoveredActiveTasksAsLegacyClock(
        legacyClockBasis: String = "LEGACY_WALL_FALLBACK",
        activeStates: List<String> = ACTIVE_ASR_STATES
    )

    @Query(
        """
        UPDATE asr_segment_diagnostics
        SET state = CASE WHEN jobId IS NULL THEN :readyState ELSE :serverReadyState END,
            failureStage = NULL,
            exceptionClass = NULL,
            safeErrorMessage = NULL,
            nextAttemptAt = NULL
        WHERE segmentId = :segmentId AND state = :unknownState
        """
    )
    suspend fun confirmRetryUnknown(
        segmentId: String,
        unknownState: String = "SUBMISSION_UNKNOWN",
        readyState: String = "QUEUED_LOCAL",
        serverReadyState: String = "QUEUED_SERVER"
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
