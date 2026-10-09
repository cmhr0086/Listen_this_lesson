package com.cmhr.listen.ui

import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.course.SessionSummary
import com.cmhr.listen.data.course.TranscriptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ClassPresentationTest {
    private fun at(month: Int, day: Int, hour: Int = 10, minute: Int = 5) =
        Calendar.getInstance().apply { clear(); set(2026, month - 1, day, hour, minute) }.timeInMillis

    private fun segment(id: Long, start: Long, end: Long = start + 5_000, marked: Boolean = false) =
        TranscriptEntity(id = id, recordId = 1, sessionId = "s", startTime = start, endTime = end, audioDurationMs = end - start, recognitionDurationMs = null, text = "第 $id 句", marked = marked)

    @Test fun classTitleShowsCourseNumberAndTopic() {
        assertEquals("高等数学 · 第 8 节 · 函数的极限", classTitle("高等数学", 8, "函数的极限", "高等数学-10-09"))
        assertEquals("第 8 节", classTitle(null, 8, null, "x"))
        assertEquals("高等数学-10-09", classTitle(null, 0, null, "高等数学-10-09"))
    }

    @Test fun recentGroupsAreTodayThisWeekAndEarlier() {
        val friday = at(10, 9, 20, 0) // a Friday
        assertEquals("今天", recentGroupLabel(at(10, 9), friday))
        assertEquals("本周", recentGroupLabel(at(10, 5), friday)) // Monday of the same week
        assertEquals("更早", recentGroupLabel(at(10, 4), friday)) // the Sunday before
    }

    @Test fun courseWeeksGroupByWeekThenMonth() {
        val friday = at(10, 9, 20, 0)
        assertEquals("本周", weekGroupLabel(at(10, 6), friday))
        assertEquals("上周", weekGroupLabel(at(9, 29), friday))
        assertEquals("9月", weekGroupLabel(at(9, 15), friday))
    }

    @Test fun badgesSkipSharedPrefixesSoSimilarCoursesDiffer() {
        assertEquals("物", courseInitial("大学物理"))
        assertEquals("英", courseInitial("大学英语"))
        assertEquals("高", courseInitial("高等数学"))
        assertEquals("大", courseInitial("大学"))
        assertEquals("课", courseInitial("  "))
    }

    @Test fun courseColorsAreStableAndOverridable() {
        assertEquals(CourseColors.indexFor(3, emptyMap()), CourseColors.indexFor(3, emptyMap()))
        assertNotEquals(CourseColors.indexFor(1, emptyMap()), CourseColors.indexFor(2, emptyMap()))
        assertEquals(5, CourseColors.indexFor(3, mapOf(3L to 5)))
        // An out-of-range override (palette shrank) falls back to the default.
        assertEquals(CourseColors.indexFor(3, emptyMap()), CourseColors.indexFor(3, mapOf(3L to 99)))
    }

    @Test fun paragraphSelectionIsNonePartialOrAll() {
        val group = TranscriptGroup(0, 10_000, listOf(segment(1, 0), segment(2, 5_000)))
        assertEquals(GroupSelection.NONE, groupSelection(group, emptySet()))
        assertEquals(GroupSelection.PARTIAL, groupSelection(group, setOf(1L, 9L)))
        assertEquals(GroupSelection.ALL, groupSelection(group, setOf(1L, 2L)))
    }

    @Test fun askScopesPickRecentWholeOrNothing() {
        val segments = listOf(segment(1, 0), segment(2, 20 * 60_000L), segment(3, 25 * 60_000L))
        assertEquals(setOf(2L, 3L), scopeSegmentIds(AskScope.RECENT, segments))
        assertEquals(setOf(1L, 2L, 3L), scopeSegmentIds(AskScope.WHOLE, segments))
        assertTrue(scopeSegmentIds(AskScope.NONE, segments).isEmpty())
    }

    @Test fun sessionSubtitleLeavesOutTheDayForTodayAndCountsMarks() {
        val now = at(10, 9, 20, 0)
        val session = SessionEntity(id = 1, courseId = 1, name = "高数-10-09", startedAt = at(10, 9, 10, 5), endedAt = at(10, 9, 11, 40), topic = "函数的极限")
        val summary = SessionSummary(session, "高等数学", segmentCount = 40, classNumber = 8, noteCount = 1, markCount = 3)
        assertEquals("函数的极限 · 10:05–11:40 · 笔记 · 3 处重点", sessionSubtitle(summary, includeDay = true, includeTopic = true, now = now))
        val older = summary.copy(session = session.copy(startedAt = at(9, 30), endedAt = null), noteCount = 0, markCount = 0)
        assertEquals("9月30日 10:05 · 40 段文字", sessionSubtitle(older, includeDay = true, now = now))
    }

    @Test fun searchSnippetsCenterOnTheMatch() {
        val text = "一".repeat(100) + "极限" + "二".repeat(100)
        val snippet = snippetAround(text, "极限", radius = 5)
        assertEquals("…一一一一一极限二二二二二…", snippet)
        assertEquals("短句", snippetAround("短句", "没有"))
    }
}
