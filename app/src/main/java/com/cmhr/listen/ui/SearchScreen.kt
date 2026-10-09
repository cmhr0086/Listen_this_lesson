package com.cmhr.listen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmhr.listen.CourseViewModel
import com.cmhr.listen.data.course.NoteSearchHit
import com.cmhr.listen.data.course.SegmentSearchHit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class SearchFilter(val label: String) { ALL("全部"), TEXT("文字"), NOTES("笔记"), MARKED("只看重点") }

/** One class in the results, with its matching lines and notes. */
internal data class SearchGroup(
    val recordId: Long,
    val courseId: Long,
    val courseName: String,
    val recordName: String,
    val startedAt: Long,
    val topic: String?,
    val segments: List<SegmentSearchHit>,
    val notes: List<NoteSearchHit>
)

internal fun groupSearchHits(segments: List<SegmentSearchHit>, notes: List<NoteSearchHit>): List<SearchGroup> {
    val ids = (segments.map { it.recordId } + notes.map { it.recordId }).distinct()
    return ids.map { id ->
        val s = segments.filter { it.recordId == id }
        val n = notes.filter { it.recordId == id }
        val any = s.firstOrNull()
        val note = n.firstOrNull()
        SearchGroup(
            recordId = id,
            courseId = any?.courseId ?: note!!.courseId,
            courseName = any?.courseName ?: note!!.courseName,
            recordName = any?.recordName ?: note!!.recordName,
            startedAt = any?.recordStartedAt ?: note!!.recordStartedAt,
            topic = any?.topic ?: note?.topic,
            segments = s,
            notes = n
        )
    }.sortedByDescending { it.startedAt }
}

/** A window of [text] around the first match, so long notes still show why they matched. */
internal fun snippetAround(text: String, query: String, radius: Int = 40): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    val at = if (query.isBlank()) -1 else flat.indexOf(query, ignoreCase = true)
    if (at < 0) return flat.take(radius * 2)
    val start = (at - radius).coerceAtLeast(0)
    val end = (at + query.length + radius).coerceAtMost(flat.length)
    return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
}

@Composable
private fun highlight(text: String, query: String, mark: Color): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) { append(text); return@buildAnnotatedString }
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) { append(text.substring(from)); break }
        append(text.substring(from, at))
        withStyle(SpanStyle(background = mark)) { append(text.substring(at, at + query.length)) }
        from = at + query.length
    }
}

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(model: CourseViewModel, open: (courseId: Long, recordId: Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    var settled by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { snapshotFlow { query.trim() }.debounce(200).collect { settled = it } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val wantSegments = filter != SearchFilter.NOTES && (settled.isNotEmpty() || filter == SearchFilter.MARKED)
    val wantNotes = (filter == SearchFilter.ALL || filter == SearchFilter.NOTES) && settled.isNotEmpty()
    val segments by remember(settled, filter) {
        if (wantSegments) model.searchSegments(settled, filter == SearchFilter.MARKED) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val notes by remember(settled, filter) {
        if (wantNotes) model.searchNotes(settled) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val groups = remember(segments, notes) { groupSearchHits(segments, notes) }
    val mark = MaterialTheme.colorScheme.tertiaryContainer
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus).testTag("search-input"),
            placeholder = { Text("搜索课堂文字和笔记") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "清除") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(SearchFilter.entries.size) { index ->
                val option = SearchFilter.entries[index]
                val count = when (option) {
                    SearchFilter.ALL -> if (filter == SearchFilter.ALL) segments.size + notes.size else null
                    SearchFilter.TEXT -> if (filter == SearchFilter.ALL || filter == SearchFilter.TEXT) segments.size else null
                    SearchFilter.NOTES -> if (filter == SearchFilter.ALL || filter == SearchFilter.NOTES) notes.size else null
                    SearchFilter.MARKED -> null
                }?.takeIf { settled.isNotEmpty() }
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label + (count?.let { " · $it" } ?: "")) },
                    modifier = Modifier.testTag("search-filter-${option.name.lowercase()}")
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("search-results"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (groups.isEmpty()) item("search-empty") {
                Text(
                    when {
                        settled.isEmpty() && filter != SearchFilter.MARKED -> "输入关键词，在所有课堂的文字和笔记里查找。"
                        filter == SearchFilter.MARKED && settled.isEmpty() -> "还没有标为重点的内容。上课时点「标记重点」，或在文字里选中后标为重点。"
                        else -> "没有找到「$settled」。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp)
                )
            }
            groups.forEach { group ->
                item("search-head-${group.recordId}") {
                    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CourseBadge(group.courseId, group.courseName, 22.dp, Modifier.padding(end = 8.dp))
                        Text(
                            group.courseName + (group.topic?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            "  " + SimpleDateFormat("M月d日", Locale.CHINA).format(Date(group.startedAt)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item("search-group-${group.recordId}") {
                    ListGroup {
                        var first = true
                        group.segments.forEach { hit ->
                            if (!first) ListDivider(16.dp)
                            first = false
                            SearchHitRow(
                                label = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(hit.startTime)),
                                marked = hit.marked,
                                text = highlight(snippetAround(hit.text, settled, 60), settled, mark),
                                onClick = { open(group.courseId, group.recordId) }
                            )
                        }
                        group.notes.forEach { hit ->
                            if (!first) ListDivider(16.dp)
                            first = false
                            SearchHitRow(
                                label = "笔记",
                                marked = false,
                                text = highlight(snippetAround(hit.output, settled), settled, mark),
                                onClick = { open(group.courseId, group.recordId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(label: String, marked: Boolean, text: AnnotatedString, onClick: () -> Unit) {
    androidx.compose.material3.Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (marked) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(12.dp))
                    Text("重点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
