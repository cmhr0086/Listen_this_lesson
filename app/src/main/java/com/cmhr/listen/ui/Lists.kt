package com.cmhr.listen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The 1.3 surface system: a light page background, white (dark: one step lighter) groups for
 * lists and reading, hairline borders instead of shadows. Lists are rows inside one group with
 * inset dividers rather than one card per item.
 */

@Composable
internal fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Fill of list groups and reading areas: white on light, one step above the page on dark. */
@Composable
internal fun groupColor(): Color =
    if (isDarkSurface()) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest

@Composable
internal fun groupBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

internal val GroupShape = RoundedCornerShape(20.dp)

@Composable
internal fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = GroupShape, color = groupColor(), border = groupBorder()) {
        Column(content = content)
    }
}

/** Drop-in for Material's Card in forms and detail pages, so they share the list groups' surface. */
@Composable
internal fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = GroupShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = groupColor()),
        border = groupBorder(),
        content = content
    )
}

/** Divider between rows of a [ListGroup], inset past the leading badge. */
@Composable
internal fun ListDivider(inset: Dp = 64.dp) {
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** One row of a [ListGroup]: badge, title, subtitle, optional status and trailing content. */
@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = { ChevronIcon() },
    titleMaxLines: Int = 1,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        status?.invoke()
        trailing?.invoke()
    }
}

@Composable
internal fun ChevronIcon() {
    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
}

/** Small rounded label for a row's state ("2 段待识别", "已连接"). */
@Composable
internal fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val (fg, bg) = when (tone) {
        StatusTone.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer to MaterialTheme.colorScheme.primaryContainer
        StatusTone.READY -> MaterialTheme.colorScheme.onTertiaryContainer to MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.DONE -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
        StatusTone.PROBLEM -> MaterialTheme.colorScheme.onErrorContainer to MaterialTheme.colorScheme.errorContainer
    }
    Text(
        text,
        modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = fg,
        maxLines = 1
    )
}

/** Course colors: fixed pairs of similar lightness so no course shouts louder than another. */
internal data class CourseTone(val foreground: Color, val background: Color)

internal object CourseColors {
    val names = listOf("蓝", "玫红", "橙", "绿", "紫", "青", "棕", "灰蓝")
    private val light = listOf(
        CourseTone(Color(0xFF2F64A8), Color(0xFFE3ECF7)),
        CourseTone(Color(0xFFA33A4F), Color(0xFFF6E4E8)),
        CourseTone(Color(0xFFA35A12), Color(0xFFF7EADC)),
        CourseTone(Color(0xFF2F7448), Color(0xFFE2F0E6)),
        CourseTone(Color(0xFF6A4FA3), Color(0xFFECE7F6)),
        CourseTone(Color(0xFF00696B), Color(0xFFDDF0F0)),
        CourseTone(Color(0xFF7A5C2E), Color(0xFFF1E9DC)),
        CourseTone(Color(0xFF48607A), Color(0xFFE4EAF0))
    )
    private val dark = listOf(
        CourseTone(Color(0xFFA9C7FF), Color(0xFF1E2C40)),
        CourseTone(Color(0xFFFFB1C1), Color(0xFF3A1F27)),
        CourseTone(Color(0xFFFFB871), Color(0xFF3A2814)),
        CourseTone(Color(0xFF96D5A6), Color(0xFF1A3022)),
        CourseTone(Color(0xFFD0BCFF), Color(0xFF2C2440)),
        CourseTone(Color(0xFF80D4D6), Color(0xFF0F3132)),
        CourseTone(Color(0xFFE2C38F), Color(0xFF33291A)),
        CourseTone(Color(0xFFB5C8DE), Color(0xFF232C36))
    )
    val count get() = light.size

    /** A stable default from the course id, unless the user picked one. */
    fun indexFor(courseId: Long, overrides: Map<Long, Int>): Int =
        overrides[courseId]?.takeIf { it in 0 until count } ?: Math.floorMod(courseId - 1, count.toLong()).toInt()

    fun tone(index: Int, dark: Boolean): CourseTone = (if (dark) this.dark else light)[Math.floorMod(index, count)]
}

/** Course id → color index overrides from settings, provided once at the app root. */
internal val LocalCourseColors = compositionLocalOf { emptyMap<Long, Int>() }

@Composable
internal fun courseTone(courseId: Long): CourseTone =
    CourseColors.tone(CourseColors.indexFor(courseId, LocalCourseColors.current), isDarkSurface())

/** The course's first character on its color: how a course is recognized across the app. */
@Composable
internal fun CourseBadge(courseId: Long, name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val tone = courseTone(courseId)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tone.background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            courseInitial(name),
            color = tone.foreground,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.44f).sp
        )
    }
}

/** "第 8 节 · 函数的极限", falling back to the record's own name when it has no number yet. */
internal fun classTitle(courseName: String?, classNumber: Int, topic: String?, fallback: String): String {
    val number = classNumber.takeIf { it > 0 }?.let { "第 $it 节" }
    return listOfNotNull(courseName, number).joinToString(" · ").ifEmpty { fallback } +
        (topic?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
}

/** "大学物理" → "物", "大学英语" → "英": skip prefixes many course names share so badges differ. */
internal fun courseInitial(name: String): String {
    val trimmed = name.trim()
    val prefix = listOf("大学", "中国", "基础", "现代", "高级").firstOrNull { trimmed.startsWith(it) && trimmed.length > it.length }
    return (if (prefix != null) trimmed.removePrefix(prefix) else trimmed).take(1).ifEmpty { "课" }
}
