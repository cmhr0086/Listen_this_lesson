package com.cmhr.listen.tgui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.TranscriptEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.tgui.R
import org.telegram.tgui.recyclerview.DiffUtil
import org.telegram.tgui.recyclerview.LinearLayoutManager
import org.telegram.tgui.recyclerview.RecyclerView
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.Cells.ServiceMessage
import org.telegram.ui.Cells.TextBubbleCell
import org.telegram.ui.Cells.TextBubbleMessage
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.MessageContextMenu
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.TelegramUI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local transcript viewer. Stable segment identities survive live appends and multi-selection. */
class TranscriptFragment(private val recordId: Long) : BaseFragment() {
    val route = "transcript/$recordId"
    private val host get() = (parentActivity ?: TelegramUI.getHost()) as ListenHostActivity
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var segments = emptyList<TranscriptEntity>()
    private var selected = emptySet<Long>()
    private var followLatest = true
    private lateinit var list: RecyclerListView
    private lateinit var manager: LinearLayoutManager
    private var adapter: TranscriptAdapter? = null
    private lateinit var followButton: TextCell

    override fun onFragmentCreate(): Boolean {
        if (!super.onFragmentCreate()) return false
        scope.launch {
            ListenDatabase.get(host).transcriptDao().segments(recordId).collect { values ->
                segments = AiViewModel.orderTranscriptSegments(values)
                selected = selected.intersect(segments.map { it.id }.toSet())
                adapter?.replace(segments)
                if (followLatest && selected.isEmpty()) scrollLatest()
                updateFollowButton()
            }
        }
        return true
    }

    override fun createView(context: Context): View {
        actionBar.setTitle("原文")
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> if (selected.isNotEmpty()) clearSelection() else finishFragment()
                    1 -> host.open("search")
                    2 -> { selected = segments.map { it.id }.toSet(); updateSelection() }
                    3 -> copyText(segments.filter { it.id in selected }.joinToString("\n") { it.effectiveText })
                    4 -> confirm("删除 ${selected.size} 段原文？") { host.courses.deleteSegments(recordId, selected) { clearSelection() } }
                    5 -> { host.ai.replaceSelection(recordId, selected); openNoteGeneration(this@TranscriptFragment, recordId) }
                }
            }
        })
        actionBar.createMenu().apply { clearItems(); addItem(1, R.drawable.ic_ab_search).contentDescription = "搜索" }
        actionBar.createActionMode().apply {
            clearItems(); addItem(2, "全选"); addItem(3, R.drawable.msg_copy).contentDescription = "复制所选原文"
            addItem(5, "整理"); addItem(4, R.drawable.msg_delete).contentDescription = "删除所选原文"
        }
        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        fragmentView = root
        manager = LinearLayoutManager(context)
        list = RecyclerListView(context).apply {
            layoutManager = manager
            itemAnimator = null
            setPadding(0, AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f))
            clipToPadding = false
            setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
                this@TranscriptFragment.adapter?.segmentAt(position)?.let { if (selected.isNotEmpty()) toggle(it.id) }
            })
            setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, position ->
                val segment = this@TranscriptFragment.adapter?.segmentAt(position)
                if (view is TextBubbleCell && segment != null) { showMenu(view, segment); true } else false
            })
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) { followLatest = false; updateFollowButton() }
                }
            })
        }
        adapter = TranscriptAdapter(context).also { list.adapter = it; it.replace(segments) }
        root.addView(list, LayoutHelper.createFrame(-1, -1f, Gravity.TOP, 0f, 0f, 0f, 50f))
        followButton = TextCell(context).apply {
            setOnClickListener { followLatest = true; clearSelection(); scrollLatest(); updateFollowButton() }
        }
        root.addView(followButton, LayoutHelper.createFrame(-1, 50, Gravity.BOTTOM))
        updateSelection()
        updateFollowButton()
        if (followLatest) scrollLatest()
        return root
    }
    private fun scrollLatest() {
        if (::list.isInitialized) list.post {
            if (followLatest && (adapter?.itemCount ?: 0) > 0) {
                val last = adapter!!.itemCount - 1
                list.scrollToPosition(last)
                // A single paragraph may be taller than the viewport; position alone shows its top.
                list.post {
                    if (followLatest) manager.findViewByPosition(last)?.let { child ->
                        list.scrollBy(0, (child.bottom - (list.height - list.paddingBottom)).coerceAtLeast(0))
                    }
                }
            }
        }
    }
    private fun updateFollowButton() {
        if (!::followButton.isInitialized) return
        followButton.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        followButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText))
        followButton.setText(if (segments.isEmpty()) "等待原文" else if (followLatest) "正在跟随最新" else "回到最新", false)
        followButton.contentDescription = if (followLatest) "正在跟随最新" else "回到最新"
    }
    private fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id; updateSelection() }
    private fun clearSelection() { selected = emptySet(); host.ai.clearSelection(); updateSelection() }
    private fun updateSelection() {
        if (actionBar != null) { if (selected.isEmpty()) actionBar.hideActionMode() else actionBar.showActionMode() }
        if (selected.isNotEmpty()) followLatest = false
        adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, "selection")
        updateFollowButton()
    }
    private fun showMenu(cell: TextBubbleCell, segment: TranscriptEntity) {
        if (selected.isNotEmpty()) { toggle(segment.id); return }
        MessageContextMenu(parentActivity, resourceProvider).apply {
            addItem(R.drawable.msg_copy, "复制") { copyText(segment.effectiveText) }
            addItem(R.drawable.msg_select, "多选") { selected = setOf(segment.id); updateSelection() }
            if (!segment.correctedText.isNullOrBlank()) {
                addItem(R.drawable.msg_info, "查看识别原文") { presentFragment(AiMarkdownFragment("识别原文", segment.text, false)) }
                addItem(R.drawable.msg_retry, "恢复原文") { confirm("恢复识别原文？") { host.ai.restoreOriginal(segment.id) } }
            }
            addItem(R.drawable.msg_delete, "删除", true) { confirm("删除这段原文？") { host.courses.deleteSegments(recordId, setOf(segment.id)) } }
            show(cell, cell.width / 2f, cell.height / 2f)
        }
    }
    override fun onBackPressed(): Boolean {
        if (selected.isNotEmpty()) { clearSelection(); return false }
        return super.onBackPressed()
    }
    override fun onFragmentDestroy() { scope.cancel(); super.onFragmentDestroy() }
    override fun getThemeDescriptions(): ArrayList<ThemeDescription> {
        val refresh = ThemeDescription.ThemeDescriptionDelegate {
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, "theme"); updateFollowButton()
        }
        return arrayListOf(
            ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault),
            ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle),
            ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon),
            ThemeDescription(actionBar, ThemeDescription.FLAG_AB_AM_BACKGROUND, null, null, null, null, Theme.key_actionBarActionModeDefault),
            ThemeDescription(actionBar, ThemeDescription.FLAG_AB_AM_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarActionModeDefaultIcon),
            ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray)
        ).apply {
            intArrayOf(Theme.key_chat_inBubble, Theme.key_chat_messageTextIn, Theme.key_chat_inTimeText, Theme.key_chat_serviceText,
                Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhiteBlueText, Theme.key_checkbox, Theme.key_checkboxCheck).forEach {
                add(ThemeDescription(null, 0, null, null, null, refresh, it))
            }
        }
    }
    private data class Line(val key: String, val segment: TranscriptEntity? = null, val date: String = "")
    private inner class TranscriptAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        private var lines = emptyList<Line>()
        fun segmentAt(position: Int) = lines.getOrNull(position)?.segment
        override fun getItemCount() = lines.size
        override fun getItemViewType(position: Int) = if (lines[position].segment == null) 0 else 1
        override fun isEnabled(holder: RecyclerView.ViewHolder) = getItemViewType(holder.adapterPosition.coerceAtLeast(0)) == 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = RecyclerListView.Holder(
            if (viewType == 0) ChatActionCell(context) else TextBubbleCell(context).apply {
                setDelegate(object : TextBubbleCell.Delegate {
                    override fun onBubbleLongPressed(cell: TextBubbleCell, message: TextBubbleMessage) { segments.firstOrNull { it.id == message.id }?.let { showMenu(cell, it) } }
                })
            }
        )
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val line = lines[position]
            val segment = line.segment
            if (segment == null) (holder.itemView as ChatActionCell).setMessage(ServiceMessage.custom(line.key, line.date))
            else (holder.itemView as TextBubbleCell).apply {
                setMessage(TextBubbleMessage(segment.id, segment.effectiveText, false, SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(segment.startTime)), TextBubbleMessage.STATE_NONE), false, false)
                setCheckBoxVisible(selected.isNotEmpty(), false); setChecked(segment.id in selected, false)
                contentDescription = segment.effectiveText
            }
        }
        fun replace(values: List<TranscriptEntity>) {
            val format = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
            val next = buildList {
                var lastDay = ""
                values.forEach { segment ->
                    val date = format.format(Date(segment.startTime))
                    if (date != lastDay) { add(Line("date-${segment.id}", date = date)); lastDay = date }
                    add(Line("segment-${segment.id}", segment))
                }
            }
            val previous = lines
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(a: Int, b: Int) = previous[a].key == next[b].key
                override fun areContentsTheSame(a: Int, b: Int) = previous[a] == next[b]
            })
            lines = next
            diff.dispatchUpdatesTo(this)
        }
    }
}
