package com.cmhr.listen.tgui

import com.cmhr.listen.ClassPublishState
import com.cmhr.listen.PublishClassRow
import com.cmhr.listen.data.course.NoteSearchHit
import com.cmhr.listen.data.course.SegmentSearchHit
import com.cmhr.listen.data.publish.PublishCourseLesson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class PublishSearchPortTest {
    @Test fun searchGroupsJoinNotesWithTextAndKeepNewestClassFirst() {
        val text = SegmentSearchHit(1, 4, 2, "课程", "第四节", 100, "主题", 101, "目标文字", true)
        val sameClass = NoteSearchHit(8, 4, 2, "课程", "第四节", 100, "主题", "目标笔记")
        val newer = NoteSearchHit(9, 5, 2, "课程", "第五节", 200, null, "另一个目标")
        val result = groupNativeSearchHits(listOf(text), listOf(sameClass, newer))
        assertEquals(listOf(5L, 4L), result.map { it.recordId })
        assertEquals(listOf(text), result.last().segments)
        assertEquals(listOf(sameClass), result.last().notes)
        assertTrue(result.first().segments.isEmpty())
        assertEquals(listOf("全部", "文字", "笔记"), NativeSearchFilter.entries.map { it.label })
    }

    @Test fun searchSnippetShowsDistantMatchAndCollapsedWhitespace() {
        val result = nativeSearchSnippet("前".repeat(100) + "目标\n  后文" + "尾".repeat(100), "目标", 6)
        assertEquals("…前前前前前前目标 后文尾尾尾…", result)
        assertEquals("Alpha beta", nativeSearchSnippet("Alpha\n beta", "BETA"))
    }

    @Test fun publicClassStateDistinguishesRequestedWithdrawalFromHiddenLocalState() {
        val active = PublishCourseLesson("lesson", "session", "active", 1, 0, false)
        val excluded = PublishClassRow(1, "session", "第一节", 0, "学期", 1, true, ClassPublishState.EXCLUDED, active)
        assertEquals("仍公开", classPublicationLabel(excluded, "approved"))
        assertEquals("撤回中", classPublicationLabel(excluded.copy(remote = active.copy(withdrawRequested = true)), "approved"))
        assertEquals("不公开", classPublicationLabel(excluded.copy(remote = null), "approved"))
        assertEquals("已下架", classPublicationLabel(excluded.copy(state = ClassPublishState.SYNCED, remote = active.copy(status = "taken_down")), "approved"))
    }

    @Test fun materialsUseRawFileBodyBoundAuthAndLessonScope() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(materialJson))
            val service = MaterialService(server.url("/").toString(), requireHttps = false)
            val bytes = "课堂笔记".toByteArray()
            val material = service.upload("mock-device", "course/id", "lesson/id", "笔记.md", "note", bytes)
            assertEquals("material-1", material.materialId)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("Bearer mock-device", request.getHeader("Authorization"))
            assertEquals("/api/admin/v1/courses/course%2Fid/materials", request.requestUrl!!.encodedPath)
            assertEquals("笔记.md", request.requestUrl!!.queryParameter("fileName"))
            assertEquals("note", request.requestUrl!!.queryParameter("category"))
            assertEquals("lesson/id", request.requestUrl!!.queryParameter("lessonId"))
            assertArrayEquals(bytes, request.body.readByteArray())
        } finally { server.shutdown() }
    }

    @Test fun materialListDeleteAndPermissionFailureUseExistingRoutes() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val service = MaterialService(server.url("/").toString(), requireHttps = false)
            server.enqueue(MockResponse().setBody("{\"materials\":[$materialJson]}"))
            assertEquals("note", service.list("mock", "course-1").single().category)
            assertEquals("/api/admin/v1/courses/course-1/materials", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("{\"deleted\":true}"))
            service.delete("mock", "material-1")
            val deletion = server.takeRequest()
            assertEquals("DELETE", deletion.method)
            assertEquals("/api/admin/v1/materials/material-1", deletion.path)
            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"detail\":\"admin required\"}"))
            try { service.list("mock", "course-1"); fail("Expected administrator rejection") }
            catch (failure: IOException) { assertTrue(failure.message!!.contains("管理员")) }
        } finally { server.shutdown() }
    }

    @Test fun downloadsUseSafeGeneratedNameAndRespectSizeLimit() = runBlocking {
        val server = MockWebServer().apply { start() }
        val directory = java.nio.file.Files.createTempDirectory("listen-material-test").toFile()
        try {
            val service = MaterialService(server.url("/").toString(), requireHttps = false)
            server.enqueue(MockResponse().setBody("demo notes"))
            val malicious = RemoteMaterial("../outside", "course", "标题", "../../outside.md", "../bad", 10)
            val file = service.download("mock", malicious, directory)
            assertEquals(directory.canonicalFile, file.parentFile.canonicalFile)
            assertEquals("bin", file.extension)
            assertEquals("demo notes", file.readText())
            assertEquals("/api/admin/v1/materials/..%2Foutside/file", server.takeRequest().path)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service.upload("mock", "course", null, "a.md", "material", ByteArray(MaterialService.MAX_BYTES + 1)) }
            }
            assertEquals(1, server.requestCount)
        } finally {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
            server.shutdown()
        }
    }

    @Test fun timetableUsesExistingRawPdfContractAndKeepsPreviewDetails() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"courses":[{"name":"演示数学","teacher":"演示教师","slots":[{"day":2,"from":3,"to":4,"weekFrom":1,"weekTo":16,"parity":"odd","room":"演示教室"}]}],"maxWeek":16,"maxPeriod":4,"skipped":["未解析行"]}"""))
            val service = TimetableService(server.url("/").toString(), requireHttps = false)
            val bytes = "%PDF-1.7\nDemo".toByteArray()
            val result = service.parse(bytes)
            assertEquals("演示数学", result.courses.single().name)
            assertEquals(listOf("未解析行"), result.skipped)
            assertEquals("周二 3–4 节 · 1–16 周单周 · 演示教室", timetableSlotLabel(result.courses.single().slots.single()))
            val request = server.takeRequest()
            assertEquals("/api/public/v1/tools/timetable-pdf", request.path)
            assertEquals("application/pdf", request.getHeader("Content-Type"))
            assertNull(request.getHeader("Authorization"))
            assertArrayEquals(bytes, request.body.readByteArray())
            server.enqueue(MockResponse().setResponseCode(404))
            try { service.parse(bytes); fail("Expected missing endpoint") }
            catch (failure: IOException) { assertEquals("当前服务尚未提供课表导入", failure.message) }
        } finally { server.shutdown() }
    }

    @Test fun pdfAndMaterialInvalidRequestsNeverLeaveTheDevice() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                TimetableService(server.url("/").toString(), requireHttps = false).parse("not a pdf".toByteArray())
            } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                MaterialService(server.url("/").toString(), requireHttps = false).list("", "course")
            } }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    companion object {
        private const val materialJson = """{"materialId":"material-1","courseId":"course-1","title":"笔记","fileName":"笔记.md","ext":"md","size":12,"category":"note","lessonId":"lesson-1","lessonTitle":"第一节","viewer":"none","position":1}"""
    }
}
