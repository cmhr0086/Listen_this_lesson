package com.cmhr.listen.data.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncSessionPayload(
    val sessionId: String,
    val courseName: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean
)

@Serializable
data class SyncSegmentPayload(
    val segmentId: String,
    val sessionId: String,
    val startTime: Long,
    val endTime: Long,
    val audioDurationMs: Long,
    val recognitionDurationMs: Long? = null,
    val text: String,
    val correctedText: String? = null,
    val correctedAt: Long? = null,
    val sourceSegmentId: String? = null,
    val sequenceNumber: Long? = null,
    val asrJobId: String? = null,
    val queueDurationMs: Long? = null,
    val uploadDurationMs: Long? = null,
    val responseWaitDurationMs: Long? = null,
    val totalAsrDurationMs: Long? = null,
    val serverModel: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean
)

@Serializable
data class SyncRequest(
    val deviceId: String,
    val lastSyncAt: Long,
    val sessions: List<SyncSessionPayload>,
    val segments: List<SyncSegmentPayload>
)

@Serializable
data class SyncAck(
    val id: String,
    val updatedAt: Long,
    val matches: Boolean
)

@Serializable
data class SyncResponse(
    val serverTime: Long,
    val sessions: List<SyncSessionPayload>,
    val segments: List<SyncSegmentPayload>,
    val sessionAcks: List<SyncAck>,
    val segmentAcks: List<SyncAck>
)

data class SyncSummary(
    val uploadedSessions: Int,
    val uploadedSegments: Int,
    val receivedSessions: Int,
    val receivedSegments: Int,
    val serverTime: Long
)

interface SyncStateStore {
    suspend fun getOrCreateDeviceId(): String
    suspend fun readSyncApiToken(): String?
    suspend fun readLastSyncAt(): Long
    suspend fun updateLastSyncAt(value: Long)
}

fun interface SyncRemoteDataSource {
    suspend fun sync(baseUrl: String, apiToken: String, request: SyncRequest): SyncResponse
}
