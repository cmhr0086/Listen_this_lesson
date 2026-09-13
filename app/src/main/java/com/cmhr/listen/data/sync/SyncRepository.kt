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
    suspend fun synchronize(baseUrl: String): SyncSummary {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        require(normalizedUrl.startsWith("https://")) {
            "云同步服务器地址必须以 https:// 开头。"
        }
        val apiToken = stateStore.readSyncApiToken()?.trim()
        require(!apiToken.isNullOrEmpty()) { "请先配置云同步 Token。" }
        val deviceId = stateStore.getOrCreateDeviceId()
        val lastSyncAt = stateStore.readLastSyncAt()
        val pendingSessions = database.recordDao().pendingSessions()
        val pendingSegments = database.transcriptDao().pendingSegments()
        val request = SyncRequest(
            deviceId = deviceId,
            lastSyncAt = lastSyncAt,
            sessions = pendingSessions.map { row -> row.session.toPayload(row.courseName) },
            segments = pendingSegments.map(SegmentEntity::toPayload)
        )

        val response = remote.sync(normalizedUrl, apiToken, request)
        require(response.serverTime >= lastSyncAt) { "云同步服务返回了倒退的同步游标。" }

        database.withTransaction {
            mergeRemoteSessions(response.sessions)
            mergeRemoteSegments(response.segments)
            acknowledgeSessions(request.sessions, response.sessionAcks)
            acknowledgeSegments(request.segments, response.segmentAcks)
        }
        // Persist the cursor last. If this write fails, the old cursor causes a
        // harmless idempotent replay rather than an incremental-window gap.
        stateStore.updateLastSyncAt(response.serverTime)

        return SyncSummary(
            uploadedSessions = response.sessionAcks.count { it.matches },
            uploadedSegments = response.segmentAcks.count { it.matches },
            receivedSessions = response.sessions.size,
            receivedSegments = response.segments.size,
            serverTime = response.serverTime
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

    private suspend fun acknowledgeSessions(uploaded: List<SyncSessionPayload>, acks: List<SyncAck>) {
        val versions = uploaded.associate { it.sessionId to it.updatedAt }
        acks.filter(SyncAck::matches).forEach { ack ->
            val uploadedVersion = versions[ack.id] ?: return@forEach
            if (ack.updatedAt == uploadedVersion) {
                database.recordDao().markSyncedIfUnchanged(ack.id, uploadedVersion)
            }
        }
    }

    private suspend fun acknowledgeSegments(uploaded: List<SyncSegmentPayload>, acks: List<SyncAck>) {
        val versions = uploaded.associate { it.segmentId to it.updatedAt }
        acks.filter(SyncAck::matches).forEach { ack ->
            val uploadedVersion = versions[ack.id] ?: return@forEach
            if (ack.updatedAt == uploadedVersion) {
                database.transcriptDao().markSyncedIfUnchanged(ack.id, uploadedVersion)
            }
        }
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
