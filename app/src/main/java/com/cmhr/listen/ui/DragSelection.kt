package com.cmhr.listen.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

internal class DragSelectionController<K>(
    private val listState: LazyListState,
    private val edgeSizePx: Float,
    private val autoScrollStepPx: Float,
    private val launch: (suspend () -> Unit) -> Job
) {
    private val itemBounds = mutableMapOf<K, Rect>()
    private var orderedKeys: List<K> = emptyList()
    private var selectedKeys: Set<K> = emptySet()
    private var onSelectionChanged: (Set<K>) -> Unit = {}
    private var viewport: Rect = Rect.Zero
    private var anchor: K? = null
    private var baseSelection: Set<K> = emptySet()
    private var selecting = true
    private var lastPointerY: Float? = null
    private var autoScrollJob: Job? = null
    private var autoScrollDirection = 0

    fun update(keys: List<K>, selected: Set<K>, changed: (Set<K>) -> Unit) {
        orderedKeys = keys
        selectedKeys = selected
        onSelectionChanged = changed
    }

    fun setViewport(value: Rect) {
        viewport = value
    }

    fun register(key: K, value: Rect) {
        itemBounds[key] = value
    }

    fun unregister(key: K) {
        itemBounds.remove(key)
    }

    fun globalY(localY: Float): Float = viewport.top + localY

    fun startAt(globalY: Float) {
        targetAt(globalY)?.let(::start)
    }

    fun start(key: K) {
        anchor = key
        baseSelection = selectedKeys
        selecting = key !in baseSelection
        applyThrough(key)
    }

    fun drag(globalY: Float) {
        lastPointerY = globalY
        applyAt(globalY)
        updateAutoScroll(globalY)
    }

    fun stop() {
        anchor = null
        lastPointerY = null
        autoScrollJob?.cancel()
        autoScrollJob = null
        autoScrollDirection = 0
    }

    private fun applyAt(globalY: Float) {
        val target = targetAt(globalY) ?: return
        applyThrough(target)
    }

    private fun targetAt(globalY: Float): K? = itemBounds.minByOrNull { (_, bounds) ->
            when {
                globalY < bounds.top -> bounds.top - globalY
                globalY > bounds.bottom -> globalY - bounds.bottom
                else -> abs(bounds.center.y - globalY) * 0.01f
            }
        }?.key

    private fun applyThrough(target: K) {
        val currentAnchor = anchor ?: return
        val next = rangeSelection(orderedKeys, baseSelection, currentAnchor, target, selecting)
        if (next != selectedKeys) {
            selectedKeys = next
            onSelectionChanged(next)
        }
    }

    private fun updateAutoScroll(globalY: Float) {
        if (viewport == Rect.Zero) return
        val direction = when {
            globalY < viewport.top + edgeSizePx -> -1
            globalY > viewport.bottom - edgeSizePx -> 1
            else -> 0
        }
        if (direction == 0) {
            autoScrollJob?.cancel()
            autoScrollJob = null
            autoScrollDirection = 0
            return
        }
        if (autoScrollJob?.isActive == true && autoScrollDirection == direction) return
        autoScrollJob?.cancel()
        autoScrollDirection = direction
        autoScrollJob = launch {
            while (currentCoroutineContext().isActive && anchor != null) {
                listState.scrollBy(direction * autoScrollStepPx)
                lastPointerY?.let(::applyAt)
                delay(16)
            }
        }
    }
}

@Composable
internal fun <K> rememberDragSelectionController(
    listState: LazyListState,
    orderedKeys: List<K>,
    selectedKeys: Set<K>,
    onSelectionChanged: (Set<K>) -> Unit
): DragSelectionController<K> {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val controller = remember(listState) {
        DragSelectionController<K>(
            listState = listState,
            edgeSizePx = with(density) { 64.dp.toPx() },
            autoScrollStepPx = with(density) { 10.dp.toPx() },
            launch = { block -> scope.launch { block() } }
        )
    }
    SideEffect { controller.update(orderedKeys, selectedKeys, onSelectionChanged) }
    DisposableEffect(controller) { onDispose(controller::stop) }
    return controller
}

internal fun <K> Modifier.dragSelectionViewport(controller: DragSelectionController<K>): Modifier = composed {
    this
        .onGloballyPositioned { controller.setViewport(it.boundsInRoot()) }
        .pointerInput(controller) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val y = controller.globalY(offset.y)
                    controller.startAt(y)
                    controller.drag(y)
                },
                onDragEnd = controller::stop,
                onDragCancel = controller::stop,
                onDrag = { change, _ ->
                    change.consume()
                    controller.drag(controller.globalY(change.position.y))
                }
            )
        }
}

internal fun <K> Modifier.dragSelectableItem(key: K, controller: DragSelectionController<K>): Modifier = composed {
    DisposableEffect(key, controller) { onDispose { controller.unregister(key) } }
    this
        .onGloballyPositioned { controller.register(key, it.boundsInRoot()) }
}
