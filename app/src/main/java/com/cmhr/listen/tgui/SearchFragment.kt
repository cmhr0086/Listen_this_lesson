package com.cmhr.listen.tgui

import android.content.Context
import android.view.View
import android.widget.EditText
import com.cmhr.listen.data.course.NoteSearchHit
import com.cmhr.listen.data.course.SegmentSearchHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.telegram.tgui.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Search stays in the local Room repository; the retired marked filter never reaches this UI. */
class SearchFragment : ListenListFragment("搜索") {
    override val route = "search"
    private var query = ""
    private var filter = NativeSearchFilter.ALL
    private var searchJob: Job? = null
    private var searchItem: ActionBarMenuItem? = null
    private var segments = emptyList<SegmentSearchHit>()
    private var notes = emptyList<NoteSearchHit>()
    private var searching = false

    override fun onReady() { render() }

    override fun createView(context: Context): View {
        val view = super.createView(context)
        val menu = actionBar.createMenu().apply { clearItems() }
        actionBar.onSearchFieldVisibilityChanged(false)
        searchItem = menu.addItem(1, R.drawable.ic_ab_search)
            .setIsSearchField(true)
            .setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onTextChanged(editText: EditText) {
                    val newQuery = editText.text.toString()
                    if (query != newQuery) {
                        query = newQuery
                        search()
                    }
                }
                override fun canCollapseSearch(): Boolean = false
            })
        searchItem?.setSearchFieldHint("搜索文字和笔记")
        searchItem?.searchField?.contentDescription = "搜索文字和笔记"
        actionBar.openSearchField(query, false)
        return view
    }

    private fun search() {
        searchJob?.cancel()
        val settled = query.trim()
        segments = emptyList()
        notes = emptyList()
        searching = settled.isNotEmpty()
        render()
        if (settled.isEmpty()) return
        searchJob = scope.launch {
            delay(200)
            val textResults = if (filter != NativeSearchFilter.NOTES) host.courses.searchSegments(settled, false) else flowOf(emptyList())
            val noteResults = if (filter != NativeSearchFilter.TEXT) host.courses.searchNotes(settled) else flowOf(emptyList())
            combine(textResults, noteResults) { text, note -> text to note }.collect { (text, note) ->
                segments = text
                notes = note
                searching = false
                render()
            }
        }
    }

    private fun render() {
        submit(buildList {
            add(ListenRow.action("范围", filter.label) {
                choices("搜索范围", NativeSearchFilter.entries.map { it.label }) { selected ->
                    filter = NativeSearchFilter.entries[selected]
                    search()
                }
            })
            when {
                query.isBlank() -> add(ListenRow.action("输入关键词") {})
                searching -> add(ListenRow.action("搜索中…") {})
                segments.isEmpty() && notes.isEmpty() -> add(ListenRow.action("没有找到结果") {})
                else -> {
                    add(ListenRow.header("${segments.size + notes.size} 条结果"))
                    groupNativeSearchHits(segments, notes).forEach { group ->
                        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(group.startedAt))
                        add(ListenRow.header("${group.courseName} · ${group.topic ?: group.recordName} · $date"))
                        group.segments.forEach { hit ->
                            add(ListenRow.dialog(hit.segmentId,
                                SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(hit.startTime)),
                                nativeSearchSnippet(hit.text, query.trim(), 60),
                                onClick = { openClass(group) }, highlightedWords = listOf(query.trim().lowercase(Locale.ROOT))))
                        }
                        group.notes.forEach { hit ->
                            add(ListenRow.dialog(-hit.resultId, "笔记", nativeSearchSnippet(hit.output, query.trim()),
                                onClick = { openClass(group) }, highlightedWords = listOf(query.trim().lowercase(Locale.ROOT))))
                        }
                    }
                }
            }
        })
    }

    private fun openClass(group: NativeSearchGroup) {
        host.courses.selectRecord(group.courseId, group.recordId)
        host.open("record/${group.recordId}")
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> = super.getThemeDescriptions().apply {
        searchItem?.searchField?.let { field ->
            add(ThemeDescription(field, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_actionBarDefaultSearch))
            add(ThemeDescription(field, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder))
            add(ThemeDescription(field, ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSearch))
        }
    }
}

internal enum class NativeSearchFilter(val label: String) { ALL("全部"), TEXT("文字"), NOTES("笔记") }

internal data class NativeSearchGroup(
    val recordId: Long,
    val courseId: Long,
    val courseName: String,
    val recordName: String,
    val startedAt: Long,
    val topic: String?,
    val segments: List<SegmentSearchHit>,
    val notes: List<NoteSearchHit>
)

internal fun groupNativeSearchHits(segments: List<SegmentSearchHit>, notes: List<NoteSearchHit>): List<NativeSearchGroup> {
    val segmentsByRecord = segments.groupBy { it.recordId }
    val notesByRecord = notes.groupBy { it.recordId }
    return (segmentsByRecord.keys + notesByRecord.keys).map { id ->
        val text = segmentsByRecord[id].orEmpty()
        val note = notesByRecord[id].orEmpty()
        val firstText = text.firstOrNull()
        val firstNote = note.firstOrNull()
        NativeSearchGroup(id, firstText?.courseId ?: firstNote!!.courseId,
            firstText?.courseName ?: firstNote!!.courseName,
            firstText?.recordName ?: firstNote!!.recordName,
            firstText?.recordStartedAt ?: firstNote!!.recordStartedAt,
            firstText?.topic ?: firstNote?.topic, text, note)
    }.sortedByDescending { it.startedAt }
}

internal fun nativeSearchSnippet(text: String, query: String, radius: Int = 40): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    val at = if (query.isBlank()) -1 else flat.indexOf(query, ignoreCase = true)
    if (at < 0) return flat.take(radius * 2)
    val start = (at - radius).coerceAtLeast(0)
    val end = (at + query.length + radius).coerceAtMost(flat.length)
    return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
}
