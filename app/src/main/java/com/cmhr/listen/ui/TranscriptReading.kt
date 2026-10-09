package com.cmhr.listen.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cmhr.listen.data.course.TranscriptEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Consecutive transcript segments shown as one paragraph under a single time label. */
internal data class TranscriptGroup(val startTime: Long, val endTime: Long, val segments: List<TranscriptEntity>)

/**
 * Splits chronologically ordered segments into paragraphs: a new paragraph starts after a pause
 * longer than [pauseMs] or once a paragraph spans [maxSpanMs]. Display only; segments, their
 * order and selection identity are untouched.
 */
internal fun groupTranscript(
    ordered: List<TranscriptEntity>,
    pauseMs: Long = PARAGRAPH_PAUSE_MS,
    maxSpanMs: Long = PARAGRAPH_MAX_SPAN_MS
): List<TranscriptGroup> {
    val groups = mutableListOf<TranscriptGroup>()
    var current = mutableListOf<TranscriptEntity>()
    fun flush() {
        if (current.isNotEmpty()) groups += TranscriptGroup(current.first().startTime, current.maxOf { it.endTime }, current)
        current = mutableListOf()
    }
    for (segment in ordered) {
        val previous = current.lastOrNull()
        val breaks = previous != null && (
            segment.startTime - previous.endTime > pauseMs ||
                segment.endTime - current.first().startTime > maxSpanMs
            )
        if (breaks) flush()
        current += segment
    }
    flush()
    return groups
}

internal const val PARAGRAPH_PAUSE_MS = 30_000L
internal const val PARAGRAPH_MAX_SPAN_MS = 3 * 60_000L

internal enum class LinePosition { ONLY, FIRST, MIDDLE, LAST }

internal fun linePosition(index: Int, size: Int): LinePosition = when {
    size == 1 -> LinePosition.ONLY
    index == 0 -> LinePosition.FIRST
    index == size - 1 -> LinePosition.LAST
    else -> LinePosition.MIDDLE
}

@Composable
internal fun TranscriptGroupHeader(group: TranscriptGroup) {
    val minutes = ((group.endTime - group.startTime) / 60_000).toInt()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(group.startTime)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        if (minutes >= 1) Text(
            " · 约 $minutes 分钟",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One segment rendered as part of its paragraph: shapes join so a group reads as one card, while
 * each line stays its own list item for long-press / drag selection.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TranscriptLine(
    segment: TranscriptEntity,
    position: LinePosition,
    selected: Boolean,
    selectionMode: Boolean,
    dragSelectionEnabled: Boolean,
    modifier: Modifier = Modifier,
    restoreOriginal: () -> Unit = {},
    toggle: () -> Unit
) {
    var showCorrection by remember(segment.id) { mutableStateOf(false) }
    if (showCorrection && segment.correctedText != null) {
        AlertDialog(
            onDismissRequest = { showCorrection = false },
            title = { Text("AI 纠错对照") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("原始 ASR", style = MaterialTheme.typography.labelLarge)
                    Text(segment.text)
                    Text("当前纠正文", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(segment.correctedText)
                }
            },
            confirmButton = { TextButton(onClick = { restoreOriginal(); showCorrection = false }) { Text("恢复原文") } },
            dismissButton = { TextButton(onClick = { showCorrection = false }) { Text("关闭") } }
        )
    }
    val shape = position.shape()
    // Selected lines get a tinted band (adjacent selections merge into one), and in selection mode
    // every line shows a check mark, so state is readable without heavy borders.
    val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
    else MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .testTag("segment-${segment.id}")
            .semantics {
                this.selected = selected
                onLongClick("选择片段") { toggle(); true }
                if (selectionMode) onClick(if (selected) "取消选择" else "选择") { toggle(); true }
            }
            .then(
                if (dragSelectionEnabled) Modifier.selectionAwareTap(selectionMode) { if (selectionMode) toggle() }
                else Modifier.combinedClickable(onClick = { if (selectionMode) toggle() }, onLongClick = toggle)
            )
            .padding(
                start = if (selectionMode) 8.dp else 16.dp,
                end = 16.dp,
                top = if (position == LinePosition.FIRST || position == LinePosition.ONLY) 14.dp else 4.dp,
                bottom = if (position == LinePosition.LAST || position == LinePosition.ONLY) 14.dp else 4.dp
            ),
        verticalAlignment = Alignment.Top
    ) {
        if (selectionMode) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp).size(20.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(segment.effectiveText, style = MaterialTheme.typography.bodyLarge)
            if (segment.correctedText != null) {
                TextButton(onClick = { showCorrection = true }, enabled = !selectionMode, modifier = Modifier.align(Alignment.End)) {
                    Text("AI 已纠错")
                }
            }
        }
    }
}

/** Shown under the text while realtime transcription still has audio on its way. */
@Composable
internal fun RecognizingTail(pendingCount: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).testTag("recognizing-tail"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            if (pendingCount > 0) "还有 $pendingCount 段正在识别，通常十几秒后出现" else "正在识别刚才的内容…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun LinePosition.shape(): Shape {
    val r = 16.dp
    return when (this) {
        LinePosition.ONLY -> RoundedCornerShape(r)
        LinePosition.FIRST -> RoundedCornerShape(topStart = r, topEnd = r)
        LinePosition.MIDDLE -> RoundedCornerShape(0.dp)
        LinePosition.LAST -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
    }
}
