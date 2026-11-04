package com.cmhr.listen.ui

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.cmhr.listen.DemoData
import com.cmhr.listen.data.ai.AiAttachmentStore
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SegmentEntity
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.tgui.TranscriptFragment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.telegram.tgui.recyclerview.LinearLayoutManager
import org.telegram.tgui.recyclerview.RecyclerView
import org.telegram.ui.Components.RecyclerListView
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** The three original follow-latest regressions, against native cells and real Room updates. */
@RunWith(AndroidJUnit4::class)
class FollowLatestTest : NativeSettingsFixture() {
    private var courseId = 0L
    private var recordId = 0L
    private var tailId = 0L
    private var tailLines = 3
    private var revision = 0L
    private val changes = AtomicInteger()

    @After fun removeTranscriptFixture() {
        scenario?.close()
        scenario = null
        if (courseId != 0L) runBlocking {
            CourseRepository(ListenDatabase.get(context), AiAttachmentStore(context)).deleteCourse(courseId)
        }
    }

    /** Earlier paragraphs and one stable final segment that becomes taller without changing id. */
    private fun setTranscript(lines: Int) {
        tailLines = lines
        runBlocking {
            DemoData.seedIfEmpty(context)
            val database = ListenDatabase.get(context)
            val now = System.currentTimeMillis()
            courseId = database.courseDao().insert(CourseEntity(name = "示例 · 原文跟随测试", createdAt = now))
            val session = SessionEntity(courseId = courseId, name = "示例 · 逐行增长原文", startedAt = now, endedAt = now + 60_000)
            recordId = database.recordDao().insert(session)
            repeat(5) { index ->
                database.transcriptDao().insert(SegmentEntity(
                    recordId = recordId, sessionId = session.sessionId,
                    startTime = now + index * 1_000, endTime = now + (index + 1) * 1_000,
                    audioDurationMs = 1_000, recognitionDurationMs = null,
                    text = (1..12).joinToString("\n") { "较早的演示段落 ${index + 1}，第 $it 行。" }
                ))
            }
            tailId = database.transcriptDao().insert(SegmentEntity(
                recordId = recordId, sessionId = session.sessionId,
                startTime = now + 5_000, endTime = now + 6_000,
                audioDurationMs = 1_000, recognitionDurationMs = null, text = tailText()
            ))
        }
        launch("transcript/$recordId")
        await { snapshot()?.tailText == tailText() }
        scenario!!.onActivity { activity ->
            nativeList(activity.lastFragment.fragmentView).adapter!!.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { changes.incrementAndGet() }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { changes.incrementAndGet() }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { changes.incrementAndGet() }
            })
        }
    }

    @Test fun scrollToBottomReachesTheEndOfAParagraphTallerThanTheScreen() {
        setTranscript(lines = 80)
        awaitBottom("initial long paragraph")
        val initial = snapshot()!!
        assertTrue("Fixture must contain one paragraph taller than the viewport", initial.tailHeight > initial.viewportHeight)
        dragToOlderText()
        assertTrue("Dragging must leave content below the reading position", snapshot()!!.canScrollForward)
        click("回到最新")
        awaitBottom("explicit resume on oversized paragraph")
        assertTrue(device.hasObject(By.desc("正在跟随最新")))
    }

    @Test fun followingStaysAtTheBottomWhileTheLastParagraphGrows() {
        setTranscript(lines = 3)
        awaitBottom("initial short paragraph")
        repeat(6) { growth ->
            growTail(8)
            await { snapshot()?.tailText == tailText() }
            awaitBottom("growth ${growth + 1}")
            assertTrue(device.hasObject(By.desc("正在跟随最新")))
        }
        val final = snapshot()!!
        assertTrue("Growth must cross the single-cell viewport boundary", final.tailHeight > final.viewportHeight)
    }

    @Test fun draggingUpStopsFollowingUntilTheUserReturns() {
        setTranscript(lines = 35)
        awaitBottom("before reading older text")
        dragToOlderText()
        val anchor = snapshot()!!
        assertTrue(anchor.canScrollForward)
        growTail(12)
        awaitStableLayout()
        val afterGrowth = snapshot()!!
        assertTrue("Reading position was pulled to the latest text", afterGrowth.canScrollForward)
        assertEquals("Room update moved the first visible item", anchor.firstVisible, afterGrowth.firstVisible)
        assertTrue("Room update shifted the reading offset: ${anchor.firstTop} -> ${afterGrowth.firstTop}", abs(anchor.firstTop - afterGrowth.firstTop) <= 2)
        assertTrue("A user drag must suspend following", device.hasObject(By.desc("回到最新")))
        click("回到最新")
        awaitBottom("explicit resume")
        growTail(12)
        await { snapshot()?.tailText == tailText() }
        awaitBottom("growth after resume")
        assertTrue(device.hasObject(By.desc("正在跟随最新")))
    }

    private fun growTail(extraLines: Int) {
        tailLines += extraLines
        val previousChanges = changes.get()
        runBlocking {
            val updated = ListenDatabase.get(context).transcriptDao().applyCorrection(
                recordId, tailId, 0, tailText(), System.currentTimeMillis() + ++revision
            )
            assertEquals("Room must update the same final paragraph", 1, updated)
        }
        await { changes.get() > previousChanges }
        awaitStableLayout()
    }

    private fun tailText() = (1..tailLines).joinToString("\n") { "最新演示段落第 $it 行，持续追加原文。" }

    private fun dragToOlderText() {
        var bounds = IntArray(4)
        scenario!!.onActivity { activity ->
            val list = nativeList(activity.lastFragment.fragmentView)
            val location = IntArray(2)
            list.getLocationOnScreen(location)
            bounds = intArrayOf(location[0], location[1], list.width, list.height)
        }
        val x = bounds[0] + bounds[2] / 2
        assertTrue("Native swipe injection failed", device.swipe(x, bounds[1] + bounds[3] / 4, x, bounds[1] + bounds[3] * 3 / 4, 35))
        text("回到最新")
        awaitStableLayout()
    }

    private fun awaitBottom(stage: String) {
        await {
            val state = snapshot()
            state != null && state.layoutStable && !state.canScrollForward && state.lastVisible == state.itemCount - 1
        }
        val state = snapshot()!!
        assertFalse("$stage: still above the bottom", state.canScrollForward)
        assertTrue("$stage: paragraph end remains below viewport", state.tailBottom <= state.viewportBottom + 2)
    }

    private fun awaitStableLayout() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        await { snapshot()?.layoutStable == true }
    }

    private data class Snapshot(
        val canScrollForward: Boolean, val firstVisible: Int, val firstTop: Int,
        val lastVisible: Int, val itemCount: Int, val tailHeight: Int, val tailBottom: Int,
        val tailText: String?, val viewportHeight: Int, val viewportBottom: Int, val layoutStable: Boolean
    )

    private fun snapshot(): Snapshot? {
        var value: Snapshot? = null
        scenario!!.onActivity { activity ->
            if (activity.lastFragment !is TranscriptFragment) return@onActivity
            val list = nativeList(activity.lastFragment.fragmentView)
            val manager = list.layoutManager as LinearLayoutManager
            val count = list.adapter?.itemCount ?: 0
            val first = manager.findFirstVisibleItemPosition()
            val tail = manager.findViewByPosition(count - 1)
            value = Snapshot(list.canScrollVertically(1), first, manager.findViewByPosition(first)?.top ?: 0,
                manager.findLastVisibleItemPosition(), count, tail?.height ?: 0, tail?.bottom ?: Int.MAX_VALUE,
                tail?.contentDescription?.toString(), list.height - list.paddingTop - list.paddingBottom,
                list.height - list.paddingBottom,
                count > 0 && list.childCount > 0 && list.scrollState == RecyclerView.SCROLL_STATE_IDLE && !list.isComputingLayout && !list.isLayoutRequested)
        }
        return value
    }

    private fun nativeList(root: View): RecyclerListView {
        if (root is RecyclerListView) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is RecyclerListView) return child
        }
        throw AssertionError("TranscriptFragment has no native transcript list")
    }
}
