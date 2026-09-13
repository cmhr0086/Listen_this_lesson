package com.cmhr.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.data.course.ClassRecordEntity
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SyncStatus
import com.cmhr.listen.data.course.TranscriptEntity
import com.cmhr.listen.data.sync.SyncAck
import com.cmhr.listen.data.sync.SyncRemoteDataSource
import com.cmhr.listen.data.sync.SyncRepository
import com.cmhr.listen.data.sync.SyncRequest
import com.cmhr.listen.data.sync.SyncResponse
import com.cmhr.listen.data.sync.SyncSegmentPayload
import com.cmhr.listen.data.sync.SyncSessionPayload
import com.cmhr.listen.data.sync.SyncStateStore
import com.cmhr.listen.data.settings.AppSettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deviceIdIsGeneratedOnceAndPersisted() = runBlocking {
        val repository = AppSettingsRepository(context)
        val first = repository.getOrCreateDeviceId()
        val second = repository.getOrCreateDeviceId()
        UUID.fromString(first)
        assertEquals(first, second)
    }

    @Test
    fun cloudSyncTokenIsStoredWithKeystoreAndNotExposedInSettingsState() = runBlocking {
        val repository = AppSettingsRepository(context)
        repository.clearCloudSyncApiToken()
        try {
            repository.saveCloudSyncServer("https://sync.example.com", "secret-token")

            assertEquals("secret-token", repository.readSyncApiToken())
            assertTrue(repository.settings.first().cloudSync.hasApiToken)
        } finally {
            repository.clearCloudSyncApiToken()
        }
    }

    @Test
    fun pendingLocalRowsUploadAndOnlyUnchangedAcknowledgedVersionsBecomeSynced() = runBlocking {
        withDatabase { database ->
            val (session, segment) = insertPendingGraph(database)
            val state = FakeSyncState(lastSyncAt = 50)
            var captured: SyncRequest? = null
            val remote = SyncRemoteDataSource { _, _, request ->
                captured = request
                database.recordDao().rename(session.id, "网络请求期间的新编辑", 2_000)
                SyncResponse(
                    serverTime = 100,
                    sessions = request.sessions,
                    segments = request.segments,
                    sessionAcks = request.sessions.map { SyncAck(it.sessionId, it.updatedAt, true) },
                    segmentAcks = request.segments.map { SyncAck(it.segmentId, it.updatedAt, true) }
                )
            }

            SyncRepository(database, state, remote).synchronize("https://sync.example.com")

            assertEquals(1, captured?.sessions?.size)
            assertEquals(1, captured?.segments?.size)
            assertEquals(SyncStatus.PENDING.name, database.recordDao().sessionBySessionId(session.sessionId)?.syncStatus)
            assertEquals(SyncStatus.SYNCED.name, database.transcriptDao().segmentBySegmentId(segment.segmentId)?.syncStatus)
            assertEquals(100L, state.lastSyncAt)
        }
    }

    @Test
    fun remoteRowsUseDedicatedSyncedWritePathAndCreateLocalCourse() = runBlocking {
        withDatabase { database ->
            val state = FakeSyncState()
            val remoteSession = remoteSession(updatedAt = 2_000)
            val remoteSegment = remoteSegment(remoteSession.sessionId, updatedAt = 2_100)
            val remote = SyncRemoteDataSource { _, _, _ ->
                SyncResponse(500, listOf(remoteSession), listOf(remoteSegment), emptyList(), emptyList())
            }

            SyncRepository(database, state, remote).synchronize("https://sync.example.com")

            val localSession = database.recordDao().sessionBySessionId(remoteSession.sessionId)
            val localSegment = database.transcriptDao().segmentBySegmentId(remoteSegment.segmentId)
            assertNotNull(localSession)
            assertNotNull(localSegment)
            assertEquals(SyncStatus.SYNCED.name, localSession?.syncStatus)
            assertEquals(SyncStatus.SYNCED.name, localSegment?.syncStatus)
            assertEquals(localSession?.id, localSegment?.recordId)
            assertEquals(500L, state.lastSyncAt)
        }
    }

    @Test
    fun newerRemoteTombstonesHideLocalRowsWithoutCreatingPendingEcho() = runBlocking {
        withDatabase { database ->
            val (session, segment) = insertPendingGraph(database)
            database.recordDao().markSyncedIfUnchanged(session.sessionId, session.updatedAt)
            database.transcriptDao().markSyncedIfUnchanged(segment.segmentId, segment.updatedAt)
            val sessionTombstone = SyncSessionPayload(
                sessionId = session.sessionId,
                courseName = "本地课程",
                name = session.name,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                createdAt = session.createdAt,
                updatedAt = 3_000,
                deleted = true
            )
            val segmentTombstone = SyncSegmentPayload(
                segmentId = segment.segmentId,
                sessionId = session.sessionId,
                startTime = segment.startTime,
                endTime = segment.endTime,
                audioDurationMs = segment.audioDurationMs,
                recognitionDurationMs = segment.recognitionDurationMs,
                text = segment.text,
                createdAt = segment.createdAt,
                updatedAt = 3_100,
                deleted = true
            )
            val remote = SyncRemoteDataSource { _, _, _ ->
                SyncResponse(600, listOf(sessionTombstone), listOf(segmentTombstone), emptyList(), emptyList())
            }

            SyncRepository(database, FakeSyncState(), remote).synchronize("https://sync.example.com")

            val storedSession = database.recordDao().sessionBySessionId(session.sessionId)
            val storedSegment = database.transcriptDao().segmentBySegmentId(segment.segmentId)
            assertTrue(storedSession?.deleted == true)
            assertTrue(storedSegment?.deleted == true)
            assertEquals(SyncStatus.SYNCED.name, storedSession?.syncStatus)
            assertEquals(SyncStatus.SYNCED.name, storedSegment?.syncStatus)
            assertTrue(database.recordDao().records(session.courseId).first().isEmpty())
            assertTrue(database.transcriptDao().segments(session.id).first().isEmpty())
        }
    }

    @Test
    fun networkFailureKeepsPendingRowsAndDoesNotAdvanceCursor() = runBlocking {
        withDatabase { database ->
            val (session, segment) = insertPendingGraph(database)
            val state = FakeSyncState(lastSyncAt = 75)
            val remote = SyncRemoteDataSource { _, _, _ -> error("offline") }

            val error = runCatching {
                SyncRepository(database, state, remote).synchronize("https://sync.example.com")
            }.exceptionOrNull()

            assertNotNull(error)
            assertEquals(SyncStatus.PENDING.name, database.recordDao().sessionBySessionId(session.sessionId)?.syncStatus)
            assertEquals(SyncStatus.PENDING.name, database.transcriptDao().segmentBySegmentId(segment.segmentId)?.syncStatus)
            assertEquals(75L, state.lastSyncAt)
        }
    }

    @Test
    fun cursorPersistenceFailureNeverAdvancesLastSyncAt() = runBlocking {
        withDatabase { database ->
            insertPendingGraph(database)
            val state = FakeSyncState(lastSyncAt = 80, failCursorWrite = true)
            val remote = SyncRemoteDataSource { _, _, request ->
                SyncResponse(
                    120,
                    request.sessions,
                    request.segments,
                    request.sessions.map { SyncAck(it.sessionId, it.updatedAt, true) },
                    request.segments.map { SyncAck(it.segmentId, it.updatedAt, true) }
                )
            }

            val error = runCatching {
                SyncRepository(database, state, remote).synchronize("https://sync.example.com")
            }.exceptionOrNull()

            assertNotNull(error)
            assertEquals(80L, state.lastSyncAt)
        }
    }

    private suspend fun insertPendingGraph(database: ListenDatabase): Pair<ClassRecordEntity, TranscriptEntity> {
        val courseId = database.courseDao().insert(CourseEntity(name = "本地课程", createdAt = 900))
        val localSessionId = database.recordDao().insert(
            ClassRecordEntity(
                courseId = courseId,
                name = "本地课堂",
                startedAt = 1_000,
                createdAt = 1_000,
                updatedAt = 1_000
            )
        )
        val session = requireNotNull(database.recordDao().sessionBySessionId(database.recordDao().sessionId(localSessionId)!!))
        val localSegmentId = database.transcriptDao().insert(
            TranscriptEntity(
                recordId = localSessionId,
                sessionId = session.sessionId,
                startTime = 1_010,
                endTime = 1_100,
                audioDurationMs = 90,
                recognitionDurationMs = 30,
                text = "本地文本"
            )
        )
        val segment = database.transcriptDao().segments(localSessionId).first().single { it.id == localSegmentId }
        return session to segment
    }

    private fun remoteSession(updatedAt: Long) = SyncSessionPayload(
        sessionId = "00000000-0000-4000-8000-000000000010",
        courseName = "远程课程",
        name = "远程课堂",
        startedAt = 1_900,
        createdAt = 1_900,
        updatedAt = updatedAt,
        deleted = false
    )

    private fun remoteSegment(sessionId: String, updatedAt: Long) = SyncSegmentPayload(
        segmentId = "00000000-0000-4000-8000-000000000011",
        sessionId = sessionId,
        startTime = 1_910,
        endTime = 1_990,
        audioDurationMs = 80,
        recognitionDurationMs = 30,
        text = "远程文本",
        createdAt = 1_990,
        updatedAt = updatedAt,
        deleted = false
    )

    private fun inMemoryDatabase() = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private suspend fun withDatabase(block: suspend (ListenDatabase) -> Unit) {
        val database = inMemoryDatabase()
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private class FakeSyncState(
    var lastSyncAt: Long = 0,
    private val failCursorWrite: Boolean = false
) : SyncStateStore {
    override suspend fun getOrCreateDeviceId() = "00000000-0000-4000-8000-000000000099"
    override suspend fun readSyncApiToken() = "test-sync-token"
    override suspend fun readLastSyncAt() = lastSyncAt
    override suspend fun updateLastSyncAt(value: Long) {
        if (failCursorWrite) error("DataStore unavailable")
        lastSyncAt = value
    }
}
