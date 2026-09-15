package com.cmhr.listen.data.sync

import androidx.room.withTransaction
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SegmentEntity
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.course.SyncStatus

class SyncRepository(
    private val database: ListenDatabase,
    private val stateStore: SyncStateStore,
    private val remote: SyncRemoteDataSource = SyncApiClient()
) {
    suspend fun synchronize(
        baseUrl: String,
        onProgress: (SyncProgress) -> Unit = {}
    ): SyncSummary {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        require(normalizedUrl.startsWith("https://")) {
            "云同步服务器地址必须以 https:// 开头。"
        }
        val apiToken = stateStore.readSyncApiToken()?.trim()
        require(!apiToken.isNullOrEmpty()) { "请先配置云同步 Token。" }
        val deviceId = stateStore.getOrCreateDeviceId()
        var workingCursor = stateStore.readLastSyncAt()
        var totalSessions = database.recordDao().pendingSessionCount()
        var totalSegments = database.transcriptDao().pendingSegmentCount()
        var completedSessions = 0
        var completedSegments = 0
        var batchNumber = 0
        var sentRequest = false
        var uploadedSessions = 0
        var uploadedSegments = 0
        var receivedSessions = 0
        var receivedSegments = 0

        fun progress() = SyncProgress(
            batchNumber = batchNumber,
            completedSessions = completedSessions,
            totalSessions = totalSessions,
            completedSegments = completedSegments,
            totalSegments = totalSegments
        )
        onProgress(progress())

        while (true) {
            // Always query the current first page. Confirmed rows disappear from
            // PENDING, so OFFSET would skip records after every successful batch.
            val pendingSessions = database.recordDao().pendingSessions(SESSION_BATCH_SIZE)
            val pendingSegments = if (pendingSessions.isEmpty()) {
                database.transcriptDao().pendingSegments(SEGMENT_BATCH_SIZE)
            } else {
                emptyList()
            }
            if (pendingSessions.isEmpty() && pendingSegments.isEmpty() && sentRequest) break

            val request = SyncRequest(
                deviceId = deviceId,
                lastSyncAt = workingCursor,
                sessions = pendingSessions.map { row -> row.session.toPayload(row.courseName) },
                segments = pendingSegments.map(SegmentEntity::toPayload)
            )
            batchNumber += 1
            onProgress(progress())

            val response = remote.sync(normalizedUrl, apiToken, request)
            require(response.serverTime >= workingCursor) { "云同步服务返回了倒退的同步游标。" }

            val batchResult = database.withTransaction {
                mergeRemoteSessions(response.sessions)
                mergeRemoteSegments(response.segments)
                val acknowledgedSessions = acknowledgeSessions(request.sessions, response.sessionAcks)
                val acknowledgedSegments = acknowledgeSegments(request.segments, response.segmentAcks)
                val remainingSessions = if (request.sessions.isEmpty()) emptyList() else {
                    database.recordDao().pendingSessionIds(request.sessions.map(SyncSessionPayload::sessionId))
                }
                val remainingSegments = if (request.segments.isEmpty()) emptyList() else {
                    database.transcriptDao().pendingSegmentIds(request.segments.map(SyncSegmentPayload::segmentId))
                }
                BatchResult(acknowledgedSessions, acknowledgedSegments, remainingSessions.size, remainingSegments.size)
            }

            val processedSessions = request.sessions.size - batchResult.remainingSessions
            val processedSegments = request.segments.size - batchResult.remainingSegments
            if (request.sessions.isNotEmpty() && processedSessions == 0) {
                error("当前 Session 批次未能确认任何记录，请检查时间戳冲突后重试。")
            }
            if (request.segments.isNotEmpty() && processedSegments == 0) {
                error("当前 Segment 批次未能确认任何记录，请检查时间戳冲突后重试。")
            }

            completedSessions += processedSessions
            completedSegments += processedSegments
            uploadedSessions += batchResult.acknowledgedSessions
            uploadedSegments += batchResult.acknowledgedSegments
            receivedSessions += response.sessions.size
            receivedSegments += response.segments.size
            workingCursor = response.serverTime
            sentRequest = true

            val remainingSessionCount = database.recordDao().pendingSessionCount()
            val remainingSegmentCount = database.transcriptDao().pendingSegmentCount()
            totalSessions = maxOf(totalSessions, completedSessions + remainingSessionCount)
            totalSegments = maxOf(totalSegments, completedSegments + remainingSegmentCount)
            onProgress(progress())
        }

        // Persist only after every local batch and every corresponding server
        // delta has succeeded. A failure replays from the old cursor safely.
        stateStore.updateLastSyncAt(workingCursor)

        return SyncSummary(
            uploadedSessions = uploadedSessions,
            uploadedSegments = uploadedSegments,
            receivedSessions = receivedSessions,
            receivedSegments = receivedSegments,
            serverTime = workingCursor
        )
    }

    private suspend fun mergeRemoteSessions(values: List<SyncSessionPayload>) {
        values.forEach { remoteSession ->
            val local = database.recordDao().sessionBySessionId(remoteSession.sessionId)
            if (local == null) {
                if (remoteSession.deleted) return@forEach
                val courseId = ensureCourse(remoteSession.courseName, remoteSession.createdAt)
                database.recordDao().insertRemote(remoteSession.toEntity(courseId))
            } else if (remoteSession.updatedAt > local.updatedAt) {
                val courseId = if (remoteSession.deleted) local.courseId
                else ensureCourse(remoteSession.courseName, remoteSession.createdAt)
                database.recordDao().applyRemote(
                    sessionId = remoteSession.sessionId,
                    courseId = courseId,
                    name = remoteSession.name,
                    startedAt = remoteSession.startedAt,
                    endedAt = remoteSession.endedAt,
                    createdAt = remoteSession.createdAt,
                    updatedAt = remoteSession.updatedAt,
                    deleted = remoteSession.deleted
                )
            }
        }
    }

    private suspend fun mergeRemoteSegments(values: List<SyncSegmentPayload>) {
        values.forEach { remoteSegment ->
            val local = database.transcriptDao().segmentBySegmentId(remoteSegment.segmentId)
            if (local == null && remoteSegment.deleted) return@forEach
            val session = database.recordDao().sessionBySessionId(remoteSegment.sessionId)
            if (session == null) {
                if (remoteSegment.deleted) return@forEach
                error("服务端 Segment ${remoteSegment.segmentId} 缺少对应的 Session。")
            }
            if (local == null) {
                database.transcriptDao().insertRemote(remoteSegment.toEntity(session.id))
            } else if (remoteSegment.updatedAt > local.updatedAt) {
                database.transcriptDao().applyRemote(
                    segmentId = remoteSegment.segmentId,
                    recordId = session.id,
                    sessionId = remoteSegment.sessionId,
                    startTime = remoteSegment.startTime,
                    endTime = remoteSegment.endTime,
                    audioDurationMs = remoteSegment.audioDurationMs,
                    recognitionDurationMs = remoteSegment.recognitionDurationMs,
                    text = remoteSegment.text,
                    correctedText = remoteSegment.correctedText,
                    correctedAt = remoteSegment.correctedAt,
                    sourceSegmentId = remoteSegment.sourceSegmentId,
                    sequenceNumber = remoteSegment.sequenceNumber,
                    asrJobId = remoteSegment.asrJobId,
                    queueDurationMs = remoteSegment.queueDurationMs,
                    uploadDurationMs = remoteSegment.uploadDurationMs,
                    responseWaitDurationMs = remoteSegment.responseWaitDurationMs,
                    totalAsrDurationMs = remoteSegment.totalAsrDurationMs,
                    serverModel = remoteSegment.serverModel,
                    createdAt = remoteSegment.createdAt,
                    updatedAt = remoteSegment.updatedAt,
                    deleted = remoteSegment.deleted
                )
            }
        }
    }

    private suspend fun ensureCourse(name: String, createdAt: Long): Long {
        val normalized = name.trim().ifEmpty { "云同步课程" }
        return database.courseDao().idByName(normalized)
            ?: database.courseDao().insert(CourseEntity(name = normalized, createdAt = createdAt))
    }

    private suspend fun acknowledgeSessions(uploaded: List<SyncSessionPayload>, acks: List<SyncAck>): Int {
        val versions = uploaded.associate { it.sessionId to it.updatedAt }
        return acks.filter(SyncAck::matches).sumOf { ack ->
            val uploadedVersion = versions[ack.id] ?: return@sumOf 0
            if (ack.updatedAt == uploadedVersion) {
                database.recordDao().markSyncedIfUnchanged(ack.id, uploadedVersion)
            } else 0
        }
    }

    private suspend fun acknowledgeSegments(uploaded: List<SyncSegmentPayload>, acks: List<SyncAck>): Int {
        val versions = uploaded.associate { it.segmentId to it.updatedAt }
        return acks.filter(SyncAck::matches).sumOf { ack ->
            val uploadedVersion = versions[ack.id] ?: return@sumOf 0
            if (ack.updatedAt == uploadedVersion) {
                database.transcriptDao().markSyncedIfUnchanged(ack.id, uploadedVersion)
            } else 0
        }
    }

    private data class BatchResult(
        val acknowledgedSessions: Int,
        val acknowledgedSegments: Int,
        val remainingSessions: Int,
        val remainingSegments: Int
    )

    private companion object {
        const val SESSION_BATCH_SIZE = 50
        const val SEGMENT_BATCH_SIZE = 100
    }
}

private fun SessionEntity.toPayload(courseName: String) = SyncSessionPayload(
    sessionId = sessionId,
    courseName = courseName,
    name = name,
    startedAt = startedAt,
    endedAt = endedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted
)

private fun SegmentEntity.toPayload() = SyncSegmentPayload(
    segmentId = segmentId,
    sessionId = sessionId,
    startTime = startTime,
    endTime = endTime,
    audioDurationMs = audioDurationMs,
    recognitionDurationMs = recognitionDurationMs,
    text = text,
    correctedText = correctedText,
    correctedAt = correctedAt,
    sourceSegmentId = sourceSegmentId,
    sequenceNumber = sequenceNumber,
    asrJobId = asrJobId,
    queueDurationMs = queueDurationMs,
    uploadDurationMs = uploadDurationMs,
    responseWaitDurationMs = responseWaitDurationMs,
    totalAsrDurationMs = totalAsrDurationMs,
    serverModel = serverModel,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted
)

private fun SyncSessionPayload.toEntity(courseId: Long) = SessionEntity(
    courseId = courseId,
    name = name,
    startedAt = startedAt,
    endedAt = endedAt,
    sessionId = sessionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
    syncStatus = SyncStatus.SYNCED.name
)

private fun SyncSegmentPayload.toEntity(recordId: Long) = SegmentEntity(
    recordId = recordId,
    startTime = startTime,
    endTime = endTime,
    audioDurationMs = audioDurationMs,
    recognitionDurationMs = recognitionDurationMs,
    text = text,
    correctedText = correctedText,
    correctedAt = correctedAt,
    sourceSegmentId = sourceSegmentId,
    sequenceNumber = sequenceNumber,
    asrJobId = asrJobId,
    queueDurationMs = queueDurationMs,
    uploadDurationMs = uploadDurationMs,
    responseWaitDurationMs = responseWaitDurationMs,
    totalAsrDurationMs = totalAsrDurationMs,
    serverModel = serverModel,
    segmentId = segmentId,
    sessionId = sessionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
    syncStatus = SyncStatus.SYNCED.name
)
