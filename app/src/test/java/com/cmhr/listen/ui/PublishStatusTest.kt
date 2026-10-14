package com.cmhr.listen.ui

import com.cmhr.listen.ClassPublishState
import com.cmhr.listen.CoursePublishUiState
import com.cmhr.listen.PublishClassRow
import com.cmhr.listen.PublishRemoteCourse
import com.cmhr.listen.data.publish.PublishCourseLesson
import com.cmhr.listen.data.publish.PublishCourseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishStatusTest {
    private fun row(state: ClassPublishState, remote: PublishCourseLesson? = null) =
        PublishClassRow(1, "S", "第 1 节", 0, "2026-2027 第一学期", 3, false, state, remote)

    private fun lesson(status: String = "active", withdraw: Boolean = false) = PublishCourseLesson("L", "S", status, 1, 0, withdraw)

    private val ready = CoursePublishUiState(courseId = 1, loaded = true, localName = "高数", school = "某大学", hasToken = true,
        course = PublishCourseState(teacherName = "张老师"))

    @Test
    fun `headline walks through the course life cycle`() {
        assertEquals(HeadlineAction.SETTINGS, publishHeadline(ready.copy(hasToken = false)).action)
        assertEquals("还没公开", publishHeadline(ready).title)
        val withClasses = ready.copy(classes = listOf(row(ClassPublishState.SYNCED, lesson()), row(ClassPublishState.NEW)))
        val pending = publishHeadline(withClasses.copy(remote = listOf(PublishRemoteCourse("t", "C", "pending"))))
        assertEquals("等待审核", pending.title)
        assertTrue(pending.detail.contains("已上传 1 节") && pending.detail.contains("1 节待同步"))
        assertEquals(CardTone.OK, publishHeadline(withClasses.copy(remote = listOf(PublishRemoteCourse("t", "C", "approved")))).tone)
        val rejected = publishHeadline(withClasses.copy(remote = listOf(PublishRemoteCourse("t", "C", "rejected", "写全名"))))
        assertEquals(HeadlineAction.REQUEST_REVIEW, rejected.action)
        assertTrue(rejected.detail.contains("写全名"))
        assertEquals("已被管理员下架", publishHeadline(withClasses.copy(remote = listOf(PublishRemoteCourse("t", "C", "taken_down")))).title)
    }

    @Test
    fun `missing fields block syncing`() {
        assertEquals(listOf("学校", "教师"), ready.copy(school = "", course = PublishCourseState()).missing)
        assertTrue(ready.copy(classes = listOf(row(ClassPublishState.NEW))).canSync)
        assertEquals(false, ready.copy(classes = listOf(row(ClassPublishState.SYNCED))).canSync)
    }

    @Test
    fun `class pills tell readers view apart from local state`() {
        assertEquals("已公开", classPill(row(ClassPublishState.SYNCED, lesson()), "approved").first)
        assertEquals("已上传", classPill(row(ClassPublishState.SYNCED, lesson()), "pending").first)
        assertEquals("已下架", classPill(row(ClassPublishState.SYNCED, lesson("taken_down")), "approved").first)
        assertEquals("有改动", classPill(row(ClassPublishState.CHANGED, lesson()), "approved").first)
        assertEquals("仍公开", classPill(row(ClassPublishState.EXCLUDED, lesson()), "approved").first)
        assertEquals("撤回中", classPill(row(ClassPublishState.EXCLUDED, lesson(withdraw = true)), "approved").first)
        assertEquals("不公开", classPill(row(ClassPublishState.EXCLUDED), null).first)
    }

    @Test
    fun `course row summary prefers the known review state`() {
        assertEquals("已公开", coursePublishSummary(true, mapOf("2025-2026 第二学期" to "rejected", "2026-2027 第一学期" to "approved"), "x").second?.first)
        assertEquals(null, coursePublishSummary(false, emptyMap(), "").second)
    }
}
