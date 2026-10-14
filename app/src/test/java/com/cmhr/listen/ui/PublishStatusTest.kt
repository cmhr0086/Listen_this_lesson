package com.cmhr.listen.ui

import com.cmhr.listen.data.publish.PublishLessonStatus
import com.cmhr.listen.data.publish.PublishRevisionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishStatusTest {
    private fun revision(number: Int, status: String, note: String? = null) =
        PublishRevisionStatus(number, number, status, submittedAt = 0, reviewNote = note)

    @Test
    fun `status card reads the public state first`() {
        assertEquals("尚未公开", describe(PublishLessonStatus()).second)
        assertEquals(CardTone.BUSY to "等待审核", describe(PublishLessonStatus("L", "active", false, false, listOf(revision(1, "pending")))).let { it.first to it.second })

        val rejected = describe(PublishLessonStatus("L", "active", false, false, listOf(revision(1, "rejected", "标题不完整"))))
        assertEquals("未通过审核", rejected.second)
        assertTrue(rejected.third!!.contains("标题不完整"))

        // A published lesson stays 已公开 while a newer version waits.
        val updating = describe(PublishLessonStatus("L", "active", true, false, listOf(revision(2, "pending"), revision(1, "approved"))))
        assertEquals(CardTone.OK to "已公开", updating.first to updating.second)
        assertTrue(updating.third!!.contains("第 2 版"))

        assertEquals("已下架", describe(PublishLessonStatus("L", "taken_down", false, false, listOf(revision(1, "approved")))).second)
    }
}
