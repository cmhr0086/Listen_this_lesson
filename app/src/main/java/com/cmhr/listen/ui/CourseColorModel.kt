package com.cmhr.listen.ui

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
internal object CourseColors {
    val names = listOf("蓝", "玫红", "橙", "绿", "紫", "青", "棕", "灰蓝")
    val count get() = names.size

    /** A stable default from the course id, unless the user picked one. */
    fun indexFor(courseId: Long, overrides: Map<Long, Int>): Int =
        overrides[courseId]?.takeIf { it in 0 until count } ?: Math.floorMod(courseId - 1, count.toLong()).toInt()
}
