package com.cmhr.listen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DragSelectionTest {
    @Test
    fun `selects contiguous range in either drag direction`() {
        val ordered = listOf(5L, 4L, 3L, 2L, 1L)
        assertEquals(setOf(2L, 3L, 4L), rangeSelection(ordered, emptySet(), 4L, 2L, true))
        assertEquals(setOf(2L, 3L, 4L), rangeSelection(ordered, emptySet(), 2L, 4L, true))
    }

    @Test
    fun `drag from selected item removes only anchored range`() {
        val ordered = listOf("a", "b", "c", "d")
        assertEquals(setOf("a", "d"), rangeSelection(ordered, ordered.toSet(), "b", "c", false))
    }
}
