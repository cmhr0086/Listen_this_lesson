package com.cmhr.listen.ui

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
internal fun <K> rangeSelection(
    orderedKeys: List<K>,
    baseSelection: Set<K>,
    anchor: K,
    target: K,
    selecting: Boolean
): Set<K> {
    val start = orderedKeys.indexOf(anchor)
    val end = orderedKeys.indexOf(target)
    if (start < 0 || end < 0) return baseSelection
    val range = orderedKeys.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
    return if (selecting) baseSelection + range else baseSelection - range
}
