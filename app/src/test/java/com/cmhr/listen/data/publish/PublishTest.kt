package com.cmhr.listen.data.publish

import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.course.SegmentEntity
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.settings.decodePublishCourseInfo
import com.cmhr.listen.data.settings.encodePublishCourseInfo
import java.util.Calendar
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PublishTest {
    private val session = SessionEntity(
        id = 7, courseId = 3, name = "第 3 节", startedAt = 1_000, endedAt = 9_000,
        sessionId = "11111111-1111-4111-8111-111111111111", topic = " 极限 "
    )

    private fun segment(text: String, corrected: String? = null, marked: Boolean = false, deleted: Boolean = false, start: Long = 1_000) = SegmentEntity(
        recordId = 7, startTime = start, endTime = start + 500, audioDurationMs = 500, recognitionDurationMs = 10,
        text = text, correctedText = corrected, asrJobId = "job", serverModel = "model",
        sessionId = session.sessionId, deleted = deleted, marked = marked
    )

    private fun result(id: Long, type: AiActionType, output: String?, status: AiRequestStatus = AiRequestStatus.SUCCESS, at: Long = id) = AiResultEntity(
        id = id, recordId = 7, actionType = type.name, requestPrompt = "prompt", sourceTextSnapshot = "src",
        output = output, status = status.name, createdAt = at, finishedAt = at
    )

    @Test
    fun `submission carries only reader facing fields`() {
        val submission = buildSubmission(
            session, " 第 3 节 极限 ", PublishCourseInfo(" 某某大学 ", "2026-2027 第一学期", "高数", "张老师"),
            listOf(segment("原文", corrected = "纠正", marked = true), segment("  "), segment("删掉", deleted = true)),
            listOf(PublishNoteCandidate(42, "课堂笔记", 5, "# 笔记")),
            submissionId = "id"
        )
        assertEquals("第 3 节 极限", submission.session.name)
        assertEquals("极限", submission.session.topic)
        assertEquals("某某大学", submission.course.schoolName)
        assertEquals(listOf(PublishSegment(1_000, 1_500, "原文", "纠正", true)), submission.segments)
        assertEquals("${session.sessionId}:42", submission.notes.single().noteId)
    }

    @Test
    fun `corrections and failed results are never offered as notes`() {
        val candidates = noteCandidates(listOf(
            result(1, AiActionType.ORGANIZE_NOTES, "旧笔记", at = 10),
            result(2, AiActionType.ORGANIZE_NOTES, "新笔记", at = 20),
            result(3, AiActionType.CORRECT_ASR, "纠错"),
            result(4, AiActionType.SUMMARY, null, AiRequestStatus.ERROR),
            result(5, AiActionType.SUMMARY, "总结", at = 30)
        ))
        assertEquals(listOf(5L, 2L, 1L), candidates.map { it.resultId })
        // Only the newest whole-class notes are ticked by default.
        assertEquals(setOf(2L), defaultNoteSelection(candidates))
        assertEquals(emptySet<Long>(), defaultNoteSelection(candidates.filter { it.resultId == 5L }))
    }

    @Test
    fun `generated class names become the class number`() {
        val start = 1_760_000_000_000
        val generated = com.cmhr.listen.data.course.RecordNameGenerator.defaultName("高数", start)
        assertEquals("第 3 节", defaultPublishTitle(generated, "高数", start, 3))
        assertEquals("期中复习", defaultPublishTitle("期中复习", "高数", start, 3))
        assertEquals(generated, defaultPublishTitle(generated, "高数", start, 0))
    }

    private fun at(year: Int, month: Int): Long = Calendar.getInstance().apply { set(year, month - 1, 15, 12, 0) }.timeInMillis

    @Test
    fun `term suggestions follow the academic calendar`() {
        assertEquals(listOf("2026-2027 第一学期", "2025-2026 第二学期", "2026-2027 第二学期"), termSuggestions(at(2026, 10)))
        assertEquals("2026-2027 第一学期", termSuggestions(at(2027, 1)).first())
        assertEquals(listOf("2026-2027 第二学期", "2026-2027 第一学期", "2027-2028 第一学期"), termSuggestions(at(2027, 4)))
    }

    @Test
    fun `course labels round trip and tolerate junk`() {
        val info = mapOf(3L to PublishCourseInfo("某某大学", "2026-2027 第一学期", "高数", "张老师"))
        assertEquals(info, decodePublishCourseInfo(encodePublishCourseInfo(info)))
        assertEquals(emptyMap<Long, PublishCourseInfo>(), decodePublishCourseInfo("not json"))
        assertEquals(emptyMap<Long, PublishCourseInfo>(), decodePublishCourseInfo(null))
    }

    @Test
    fun `submit gzips the body and sends the token`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"lessonId":"L","revisionId":1,"revision":1,"status":"pending"}"""))
        server.start()
        try {
            val submission = buildSubmission(session, "t", PublishCourseInfo("a", "b", "c", "d"), listOf(segment("x")), emptyList())
            val result = PublishApiClient(OkHttpClient(), requireHttps = false).submit(server.url("/").toString(), "lp_token", submission)
            assertEquals("pending", result.status)
            val request = server.takeRequest()
            assertEquals("/api/publish/v1/submissions", request.path)
            assertEquals("Bearer lp_token", request.getHeader("Authorization"))
            assertEquals("gzip", request.getHeader("Content-Encoding"))
            val body = GZIPInputStream(request.body.inputStream()).readBytes().decodeToString()
            assertTrue(body.contains("\"sessionId\":\"${session.sessionId}\""))
            assertFalse(body.contains("asrJobId"))
            assertFalse(body.contains("serverModel"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `status query escapes the session id and parses revisions`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"lessonId":"L","lessonStatus":"active","published":true,"withdrawRequested":false,
               "revisions":[{"revisionId":2,"revision":2,"status":"pending","submittedAt":5,"reviewedAt":null,"reviewNote":null}]}"""
        ))
        server.start()
        try {
            val status = PublishApiClient(OkHttpClient(), requireHttps = false).status(server.url("/").toString(), "t", "a b")
            assertTrue(status.published)
            assertEquals("pending", status.revisions.single().status)
            assertEquals("/api/publish/v1/submissions?sessionId=a%20b", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `server errors become readable messages`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid or missing bearer token."}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"提交过于频繁，请稍后再试"}"""))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":[{"msg":"Value error, must not be blank"}]}"""))
        server.start()
        val client = PublishApiClient(OkHttpClient(), requireHttps = false)
        val url = server.url("/").toString()
        try {
            listOf("上传 Token 无效", "提交过于频繁", "提交的内容不符合要求：Value error, must not be blank").forEach { expected ->
                try {
                    client.me(url, "t")
                    fail("expected an error")
                } catch (error: PublishException) {
                    assertTrue(error.message, error.message!!.startsWith(expected))
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `plain http is refused for real use`() {
        try {
            PublishApiClient.endpoint("http://sync.example.com", "me")
            fail("expected an error")
        } catch (error: PublishException) {
            assertTrue(error.message!!.contains("https"))
        }
        assertEquals("https://sync.example.com/api/publish/v1/me", PublishApiClient.endpoint("https://sync.example.com/", "me").toString())
    }
}
