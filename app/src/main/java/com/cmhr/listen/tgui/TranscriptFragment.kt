package com.cmhr.listen.tgui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.data.ai.AiActionType
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
import org.telegram.ui.ActionBar.ActionBarMenuItem
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
    private var selecting = false
    private var countItem: ActionBarMenuItem? = null
    private var followLatest = true
    private var draggingSelection = false
    private var deferredSegments: List<TranscriptEntity>? = null
    private lateinit var list: RecyclerListView
    private lateinit var manager: LinearLayoutManager
    private var adapter: TranscriptAdapter? = null
    private lateinit var followButton: TextCell

    override fun onFragmentCreate(): Boolean {
        if (!super.onFragmentCreate()) return false
        scope.launch {
            ListenDatabase.get(host).transcriptDao().segments(recordId).collect { values ->
                if (draggingSelection) { deferredSegments = values; return@collect }
                segments = AiViewModel.orderTranscriptSegments(values)
                selected = selected.intersect(segments.map { it.id }.toSet())
                adapter?.replace(segments)
                if (followLatest && !selecting) scrollLatest()
                updateSelection()
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
                    -1 -> if (selecting) clearSelection() else finishFragment()
                    1 -> host.open("search")
                    2 -> { selecting = true; selected = segments.map { it.id }.toSet(); updateSelection() }
                    3 -> copyText(segments.filter { it.id in selected }.joinToString("\n") { it.effectiveText })
                    4 -> confirm("删除 ${selected.size} 段原文？") { host.courses.deleteSegments(recordId, selected) { clearSelection() } }
                    5 -> selectionAiMenu()
                    8 -> { selecting = true; updateSelection() }
                    9 -> exportText()
                }
            }
        })
        actionBar.createMenu().apply {
            clearItems(); addItem(1, R.drawable.ic_ab_search).contentDescription = "搜索"
            addItem(7, R.drawable.ic_ab_other).apply {
                contentDescription = "更多操作"
                addSubItem(8, "选择片段"); addSubItem(9, "导出 TXT")
            }
        }
        actionBar.createActionMode().apply {
            clearItems(); countItem = addItem(6, "已选 0 段").apply { isEnabled = false }
            addItem(2, "全选"); addItem(3, R.drawable.msg_copy).contentDescription = "复制所选原文"
            addItem(5, "AI").contentDescription = "问 AI"; addItem(4, R.drawable.msg_delete).contentDescription = "删除所选原文"
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
                if (selecting) {
                    val line = this@TranscriptFragment.adapter?.lineAt(position)
                    if (line?.segment != null) toggle(line.segment.id)
                    else if (line != null) { selected = if (selected.containsAll(line.groupIds)) selected - line.groupIds else selected + line.groupIds; updateSelection() }
                }
            })
            setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, position ->
                val segment = this@TranscriptFragment.adapter?.segmentAt(position)
                if (segment != null && selecting) { startDragSelection(position, segment.id); true }
                else if (view is TextBubbleCell && segment != null) { showMenu(view, segment); true } else false
            })
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) post {
                    draggingSelection = false
                    deferredSegments?.let { values ->
                        deferredSegments = null
                        segments = AiViewModel.orderTranscriptSegments(values)
                        selected = selected.intersect(segments.map { it.id }.toSet())
                        this@TranscriptFragment.adapter?.replace(segments)
                        updateSelection()
                    }
                }
                false
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) { followLatest = false; updateFollowButton() }
                    else if (newState == RecyclerView.SCROLL_STATE_IDLE && !selecting && !recyclerView.canScrollVertically(1)) {
                        followLatest = true; updateFollowButton()
                    }
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
    private fun startDragSelection(position: Int, firstId: Long) {
        val initial = selected
        val adding = firstId !in initial
        draggingSelection = true
        toggle(firstId)
        list.startMultiselect(position, false, object : RecyclerListView.onMultiSelectionChanged {
            override fun onSelectionChanged(position: Int, selected: Boolean, x: Float, y: Float) {
                val id = adapter?.segmentAt(position)?.id ?: return
                val desired = if (adding) selected else !selected
                if ((id in this@TranscriptFragment.selected) != desired) toggle(id)
            }
            override fun canSelect(position: Int): Boolean = adapter?.segmentAt(position)?.id?.let { if (adding) it !in initial else it in initial } ?: false
            override fun checkPosition(position: Int, selectionFromTop: Boolean) = position
            override fun limitReached() = false
            override fun getPaddings(paddings: IntArray) { paddings[0] = list.paddingTop; paddings[1] = list.paddingBottom }
            override fun scrollBy(dy: Int) { list.scrollBy(0, dy) }
        })
    }
    private fun clearSelection() { selecting = false; selected = emptySet(); host.ai.clearSelection(); updateSelection() }
    private fun updateSelection() {
        if (actionBar != null) {
            if (!selecting) actionBar.hideActionMode() else actionBar.showActionMode()
            countItem?.setText("已选 ${selected.size} 段")
            countItem?.contentDescription = "已选 ${selected.size} 段"
            listOf(3, 4, 5).forEach { actionBar.actionMode?.getItem(it)?.isEnabled = selected.isNotEmpty() }
        }
        if (selecting) followLatest = false
        adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, "selection")
        updateFollowButton()
    }
    private fun showMenu(cell: TextBubbleCell, segment: TranscriptEntity) {
        if (selecting) { toggle(segment.id); return }
        MessageContextMenu(parentActivity, resourceProvider).apply {
            addItem(R.drawable.msg_copy, "复制") { copyText(segment.effectiveText) }
            addItem(R.drawable.msg_select, "多选") { selecting = true; selected = setOf(segment.id); updateSelection() }
            if (!segment.correctedText.isNullOrBlank()) {
                addItem(R.drawable.msg_info, "查看识别原文") { presentFragment(AiMarkdownFragment("识别原文", segment.text, false)) }
                addItem(R.drawable.msg_retry, "恢复原文") { confirm("恢复识别原文？") { host.ai.restoreOriginal(segment.id) } }
            }
            addItem(R.drawable.msg_delete, "删除", true) { confirm("删除这段原文？") { host.courses.deleteSegments(recordId, setOf(segment.id)) } }
            show(cell, cell.width / 2f, cell.height / 2f)
        }
    }
    override fun onBackPressed(): Boolean {
        if (selecting) { clearSelection(); return false }
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
    private fun selectionAiMenu() {
        if (selected.isEmpty()) return
        choices("AI", listOf("问 AI", "整理笔记", "快速回答", "逐片段纠错")) { index ->
            host.ai.replaceSelection(recordId, selected)
            if (index == 0) host.ai.createConversationDraft(recordId, segments) { id -> host.open("ai-conversation/$id") }
            else {
                val action = when (index) { 1 -> AiActionType.ORGANIZE_NOTES; 2 -> AiActionType.QUICK_ANSWER; else -> AiActionType.CORRECT_ASR }
                host.ai.runFixedAction(recordId, action, segments) { id -> host.open("record/$recordId/ai-result/$id") }
            }
        }
    }
    private fun exportText() {
        val text = segments.joinToString("\n\n") { it.effectiveText }
        if (text.isEmpty()) { message("还没有原文"); return }
        host.createDocument("课堂原文.txt", "text/plain") { uri ->
            scope.launch {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { requireNotNull(host.contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
                }
                message(if (result.isSuccess) "已导出原文" else "导出失败")
            }
        }
    }
    private data class Line(val key: String, val segment: TranscriptEntity? = null, val date: String = "", val groupIds: Set<Long> = emptySet())
    private inner class TranscriptAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        private var lines = emptyList<Line>()
        fun lineAt(position: Int) = lines.getOrNull(position)
        fun segmentAt(position: Int) = lines.getOrNull(position)?.segment
        override fun getItemCount() = lines.size
        override fun getItemViewType(position: Int) = if (lines[position].segment == null) 0 else 1
        override fun isEnabled(holder: RecyclerView.ViewHolder) = selecting || lines.getOrNull(holder.adapterPosition)?.segment != null
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
            if (segment == null) (holder.itemView as ChatActionCell).apply {
                val label = line.date + if (selecting) { if (selected.containsAll(line.groupIds)) " · 取消整段" else " · 选择整段" } else ""
                setMessage(ServiceMessage.custom(line.key, label))
                contentDescription = if (selecting) { if (selected.containsAll(line.groupIds)) "取消整段" else "选择整段" } else label
            }
            else (holder.itemView as TextBubbleCell).apply {
                setMessage(TextBubbleMessage(segment.id, segment.effectiveText, false, SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(segment.startTime)), TextBubbleMessage.STATE_NONE), false, false)
                setCheckBoxVisible(selecting, false); setChecked(segment.id in selected, false); isSelected = segment.id in selected
                contentDescription = segment.effectiveText
            }
        }
        fun replace(values: List<TranscriptEntity>) {
            val format = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
            val next = buildList {
                com.cmhr.listen.ui.groupTranscript(values).forEach { group ->
                    add(Line("group-${group.segments.first().id}", date = format.format(Date(group.startTime)), groupIds = group.segments.map { it.id }.toSet()))
                    group.segments.forEach { segment -> add(Line("segment-${segment.id}", segment)) }
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
