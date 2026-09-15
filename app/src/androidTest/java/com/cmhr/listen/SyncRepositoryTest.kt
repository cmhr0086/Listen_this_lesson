package com.cmhr.listen

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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
    fun localEditDuringUploadStaysPendingAndNextRunResumesNaturally() = runBlocking {
        withDatabase { database ->
            val (session, segment) = insertPendingGraph(database)
            val state = FakeSyncState(lastSyncAt = 50)
            val editingRemote = SyncRemoteDataSource { _, _, request ->
                database.recordDao().rename(session.id, "网络请求期间的新编辑", 2_000)
                SyncResponse(
                    serverTime = 100,
                    sessions = request.sessions,
                    segments = request.segments,
                    sessionAcks = request.sessions.map { SyncAck(it.sessionId, it.updatedAt, true) },
                    segmentAcks = request.segments.map { SyncAck(it.segmentId, it.updatedAt, true) }
                )
            }

            val firstError = runCatching {
                SyncRepository(database, state, editingRemote).synchronize("https://sync.example.com")
            }.exceptionOrNull()

            assertNotNull(firstError)
            assertEquals(SyncStatus.PENDING.name, database.recordDao().sessionBySessionId(session.sessionId)?.syncStatus)
            assertEquals(SyncStatus.PENDING.name, database.transcriptDao().segmentBySegmentId(segment.segmentId)?.syncStatus)
            assertEquals(50L, state.lastSyncAt)

            val resumedRemote = SyncRemoteDataSource { _, _, request -> successfulResponse(request) }
            SyncRepository(database, state, resumedRemote).synchronize("https://sync.example.com")

            assertEquals(SyncStatus.SYNCED.name, database.recordDao().sessionBySessionId(session.sessionId)?.syncStatus)
            assertEquals(SyncStatus.SYNCED.name, database.transcriptDao().segmentBySegmentId(segment.segmentId)?.syncStatus)
            assertTrue(state.lastSyncAt > 50)
        }
    }

    @Test
    fun pendingSegmentsAreUploadedInFixedBatchesWithoutOffsetSkipping() = runBlocking {
        withDatabase { database ->
            val (_, segments) = insertPendingSegments(database, 205)
            val state = FakeSyncState(lastSyncAt = 10)
            val batchSizes = mutableListOf<Int>()
            val progress = mutableListOf<com.cmhr.listen.data.sync.SyncProgress>()
            val remote = SyncRemoteDataSource { _, _, request ->
                batchSizes += request.segments.size
                successfulResponse(request)
            }

            SyncRepository(database, state, remote).synchronize("https://sync.example.com", progress::add)

            assertEquals(listOf(100, 100, 5), batchSizes)
            assertEquals(0, database.transcriptDao().pendingSegmentCount())
            assertTrue(segments.all { database.transcriptDao().segmentBySegmentId(it.segmentId)?.syncStatus == SyncStatus.SYNCED.name })
            assertEquals(205, progress.last().completedSegments)
            assertEquals(205, progress.last().totalSegments)
        }
    }

    @Test
    fun pendingSessionsAreUploadedInBatchesOfFifty() = runBlocking {
        withDatabase { database ->
            val courseId = database.courseDao().insert(CourseEntity(name = "Session 批量课程", createdAt = 1))
            repeat(101) { index ->
                database.recordDao().insert(
                    ClassRecordEntity(
                        courseId = courseId,
                        name = "课堂 $index",
                        startedAt = index.toLong() + 1,
                        createdAt = index.toLong() + 1,
                        updatedAt = index.toLong() + 1
                    )
                )
            }
            val batchSizes = mutableListOf<Int>()
            val remote = SyncRemoteDataSource { _, _, request ->
                batchSizes += request.sessions.size
                successfulResponse(request)
            }

            SyncRepository(database, FakeSyncState(), remote).synchronize("https://sync.example.com")

            assertEquals(listOf(50, 50, 1), batchSizes)
            assertEquals(0, database.recordDao().pendingSessionCount())
        }
    }

    @Test
    fun middleBatchFailureKeepsEarlierBatchSyncedAndRetryContinuesRemainingRows() = runBlocking {
        withDatabase { database ->
            insertPendingSegments(database, 250)
            val state = FakeSyncState(lastSyncAt = 20)
            var calls = 0
            val failingRemote = SyncRemoteDataSource { _, _, request ->
                calls += 1
                if (calls == 2) error("second batch failed")
                successfulResponse(request)
            }

            val error = runCatching {
                SyncRepository(database, state, failingRemote).synchronize("https://sync.example.com")
            }.exceptionOrNull()

            assertNotNull(error)
            assertEquals(150, database.transcriptDao().pendingSegmentCount())
            assertEquals(20L, state.lastSyncAt)

            val resumedBatchSizes = mutableListOf<Int>()
            val resumedRemote = SyncRemoteDataSource { _, _, request ->
                resumedBatchSizes += request.segments.size
                successfulResponse(request)
            }
            SyncRepository(database, state, resumedRemote).synchronize("https://sync.example.com")

            assertEquals(listOf(100, 50), resumedBatchSizes)
            assertEquals(0, database.transcriptDao().pendingSegmentCount())
            assertTrue(state.lastSyncAt > 20)
        }
    }

    @Test
    fun onlyMatchingAcksConfirmRowsAsSynced() = runBlocking {
        withDatabase { database ->
            val (_, segments) = insertPendingSegments(database, 101)
            val rejectedId = segments.first().segmentId
            val remote = SyncRemoteDataSource { _, _, request ->
                successfulResponse(request).copy(
                    segmentAcks = request.segments.map {
                        SyncAck(it.segmentId, it.updatedAt, it.segmentId != rejectedId)
                    }
                )
            }

            val error = runCatching {
                SyncRepository(database, FakeSyncState(), remote).synchronize("https://sync.example.com")
            }.exceptionOrNull()

            assertNotNull(error)
            assertEquals(1, database.transcriptDao().pendingSegmentCount())
            assertEquals(SyncStatus.PENDING.name, database.transcriptDao().segmentBySegmentId(rejectedId)?.syncStatus)
            assertTrue(segments.drop(1).all {
                database.transcriptDao().segmentBySegmentId(it.segmentId)?.syncStatus == SyncStatus.SYNCED.name
            })
        }
    }

    @Test
    fun elevenThousandSegmentsQueryReturnsOnlyOneCursorWindowSafePage() = runBlocking {
        withDatabase { database ->
            val courseId = database.courseDao().insert(CourseEntity(name = "大课堂", createdAt = 1))
            val recordId = database.recordDao().insert(
                ClassRecordEntity(courseId = courseId, name = "长录音", startedAt = 1, createdAt = 1, updatedAt = 1)
            )
            val session = requireNotNull(database.recordDao().sessionBySessionId(database.recordDao().sessionId(recordId)!!))
            database.recordDao().markSyncedIfUnchanged(session.sessionId, session.updatedAt)
            val writable = database.openHelper.writableDatabase
            writable.beginTransaction()
            try {
                repeat(11_314) { index ->
                    val values = ContentValues().apply {
                        put("recordId", recordId)
                        put("startTime", index.toLong())
                        put("endTime", index.toLong() + 1)
                        put("audioDurationMs", 1L)
                        put("text", "短文本")
                        put("segmentId", "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
                        put("sessionId", session.sessionId)
                        put("createdAt", index.toLong() + 1)
                        put("updatedAt", index.toLong() + 1)
                        put("deleted", 0)
                        put("syncStatus", SyncStatus.PENDING.name)
                    }
                    writable.insert("transcript_segments", SQLiteDatabase.CONFLICT_ABORT, values)
                }
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }

            assertEquals(11_314, database.transcriptDao().pendingSegmentCount())
            assertEquals(100, database.transcriptDao().pendingSegments(100).size)
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

    private suspend fun insertPendingSegments(
        database: ListenDatabase,
        count: Int
    ): Pair<ClassRecordEntity, List<TranscriptEntity>> {
        val courseId = database.courseDao().insert(CourseEntity(name = "批量课程", createdAt = 900))
        val recordId = database.recordDao().insert(
            ClassRecordEntity(
                courseId = courseId,
                name = "批量课堂",
                startedAt = 1_000,
                createdAt = 1_000,
                updatedAt = 1_000
            )
        )
        val session = requireNotNull(database.recordDao().sessionBySessionId(database.recordDao().sessionId(recordId)!!))
        database.recordDao().markSyncedIfUnchanged(session.sessionId, session.updatedAt)
        repeat(count) { index ->
            database.transcriptDao().insert(
                TranscriptEntity(
                    recordId = recordId,
                    sessionId = session.sessionId,
                    startTime = 1_010L + index,
                    endTime = 1_011L + index,
                    audioDurationMs = 1,
                    recognitionDurationMs = 1,
                    text = "分段 $index",
                    createdAt = 1_011L + index,
                    updatedAt = 1_011L + index
                )
            )
        }
        return session to database.transcriptDao().segments(recordId).first()
    }

    private fun successfulResponse(request: SyncRequest) = SyncResponse(
        serverTime = request.lastSyncAt + 1,
        sessions = request.sessions,
        segments = request.segments,
        sessionAcks = request.sessions.map { SyncAck(it.sessionId, it.updatedAt, true) },
        segmentAcks = request.segments.map { SyncAck(it.segmentId, it.updatedAt, true) }
    )

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
