package com.cmhr.listen.data.course

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import com.cmhr.listen.data.ai.AiAttachmentStore

class CourseRepository(
    private val database: ListenDatabase,
    private val attachmentStore: AiAttachmentStore? = null
) {
    val courses: Flow<List<CourseEntity>> = database.courseDao().courses()
    fun course(id: Long) = database.courseDao().course(id)
    fun records(courseId: Long) = database.recordDao().records(courseId)
    fun record(id: Long) = database.recordDao().record(id)
    fun segments(recordId: Long): Flow<List<TranscriptEntity>> = database.transcriptDao().segments(recordId)

    suspend fun createCourse(name: String): Long = database.courseDao().insert(CourseEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
    suspend fun renameCourse(id: Long, name: String) = database.courseDao().rename(id, name.trim())
    suspend fun updateCourseAsrPrompt(id: Long, prompt: String) = database.courseDao().updateAsrPrompt(id, prompt.trim())
    suspend fun updateCourseAsrPromptMode(id: Long, mode: String?) = database.courseDao().updateAsrPromptMode(id, mode)
    suspend fun deleteCourse(id: Long) {
        val now = System.currentTimeMillis()
        val recordIds = database.withTransaction {
            val recordIds = database.recordDao().idsForCourse(id)
            if (recordIds.isNotEmpty()) {
                database.transcriptDao().softDeleteForSessions(recordIds, now)
                database.asrDiagnosticsDao().deleteForRecords(recordIds)
            }
            database.recordDao().softDeleteForCourse(id, now)
            database.courseDao().softDelete(id)
            recordIds
        }
        recordIds.forEach { attachmentStore?.deleteRecord(it) }
    }
    suspend fun createRecord(courseId: Long, name: String): Long = database.recordDao().insert(ClassRecordEntity(courseId = courseId, name = name.trim(), startedAt = System.currentTimeMillis()))
    suspend fun renameRecord(id: Long, name: String) = database.recordDao().rename(id, name.trim(), System.currentTimeMillis())
    suspend fun deleteRecord(id: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.transcriptDao().softDeleteForSession(id, now)
            database.asrDiagnosticsDao().deleteForRecord(id)
            database.recordDao().softDelete(id, now)
        }
        attachmentStore?.deleteRecord(id)
    }
    suspend fun reopenRecord(id: Long) = database.recordDao().reopen(id, System.currentTimeMillis())
    suspend fun finishRecord(id: Long) = database.recordDao().end(id, System.currentTimeMillis())
    suspend fun saveSegment(recordId: Long, start: Long, end: Long, duration: Long, recognitionDuration: Long?, text: String): Long {
        val sessionId = requireNotNull(database.recordDao().sessionId(recordId)) { "Session $recordId does not exist." }
        return database.transcriptDao().insert(
            TranscriptEntity(
                recordId = recordId,
                sessionId = sessionId,
                startTime = start,
                endTime = end,
                audioDurationMs = duration,
                recognitionDurationMs = recognitionDuration,
                text = text
            )
        )
    }
    suspend fun deleteSegments(recordId: Long, ids: List<Long>): Int =
        if (ids.isEmpty()) 0 else database.transcriptDao().softDeleteByIds(recordId, ids, System.currentTimeMillis())
}
