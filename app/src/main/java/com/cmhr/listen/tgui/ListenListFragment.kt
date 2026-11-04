package com.cmhr.listen.tgui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.telegram.messenger.AndroidUtilities
import org.telegram.tgui.R
import org.telegram.tgui.recyclerview.DiffUtil
import org.telegram.tgui.recyclerview.LinearLayoutManager
import org.telegram.tgui.recyclerview.RecyclerView
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Cells.DialogItem
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.ShadowSectionCell
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.TextDetailSettingsCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.TelegramUI

/** Small host model; rendering uses the library's original cells and dimensions. */
data class ListenRow(
    val kind: Int, val title: String = "", val value: String = "", val id: Long = 0,
    val time: String = "", val checked: Boolean = false,
    val onClick: (() -> Unit)? = null, val onLongClick: (() -> Unit)? = null,
    val highlightedWords: List<String> = emptyList()
) {
    companion object {
        fun header(title: String) = ListenRow(0, title)
        fun shadow() = ListenRow(1)
        fun action(title: String, value: String = "", onClick: () -> Unit) = ListenRow(if (value.isEmpty()) 2 else 3, title, value, onClick = onClick)
        fun check(title: String, checked: Boolean, onClick: () -> Unit) = ListenRow(4, title, checked = checked, onClick = onClick)
        fun dialog(id: Long, title: String, value: String, time: String = "", onClick: () -> Unit, onLongClick: (() -> Unit)? = null, highlightedWords: List<String> = emptyList()) =
            ListenRow(5, title, value, id, time, onClick = onClick, onLongClick = onLongClick, highlightedWords = highlightedWords)
    }
    internal fun sameContents(other: ListenRow) = kind == other.kind && title == other.title && value == other.value && time == other.time && checked == other.checked && highlightedWords == other.highlightedWords
}

/** Collect once per fragment; view reconstruction never duplicates observers. */
open class ListenListFragment(private val pageTitle: String) : BaseFragment() {
    protected val host: ListenHostActivity get() = (parentActivity ?: TelegramUI.getHost()) as ListenHostActivity
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected lateinit var list: RecyclerListView
    private var rowAdapter: RowAdapter? = null
    protected var rows: List<ListenRow> = emptyList()
        private set
    open val route: String = ""
    protected open fun onReady() {}
    protected open fun configureMenu() {}
    protected open fun menuClick(id: Int) {}

    override fun onFragmentCreate(): Boolean {
        if (!super.onFragmentCreate()) return false
        onReady()
        return true
    }

    override fun createView(context: Context): View {
        actionBar.setTitle(pageTitle)
        if (route != "home") actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) { if (id == -1) finishFragment() else menuClick(id) }
        })
        configureMenu()
        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        fragmentView = root
        list = RecyclerListView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.itemAnimator = null
        rowAdapter = RowAdapter(context)
        list.adapter = rowAdapter
        list.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position -> rows.getOrNull(position)?.onClick?.invoke() })
        list.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { _, position ->
            rows.getOrNull(position)?.onLongClick?.let { it(); true } ?: false
        })
        root.addView(list, LayoutHelper.createFrame(-1, -1f))
        return root
    }

    protected fun submit(newRows: List<ListenRow>) {
        val previous = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(old: Int, new: Int): Boolean {
                val a = previous[old]; val b = newRows[new]
                return a.kind == b.kind && if (a.kind == 5) a.id == b.id else a.title == b.title
            }
            override fun areContentsTheSame(old: Int, new: Int) = previous[old].sameContents(newRows[new])
        })
        rows = newRows
        rowAdapter?.let { diff.dispatchUpdatesTo(it) }
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        rowAdapter = null
        super.onFragmentDestroy()
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> {
        val descriptions = arrayListOf<ThemeDescription>()
        descriptions += ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault)
        descriptions += ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle)
        descriptions += ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon)
        descriptions += ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector)
        descriptions += ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray)
        val refresh = ThemeDescription.ThemeDescriptionDelegate {
            if (::list.isInitialized) {
                list.setListSelectorColor(Theme.getColor(Theme.key_listSelector))
                // Payload rebinds preserve RecyclerView scroll/selection and update all native cells.
                rowAdapter?.notifyItemRangeChanged(0, rows.size, "theme")
            }
        }
        intArrayOf(Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhiteBlackText,
            Theme.key_windowBackgroundWhiteGrayText2, Theme.key_windowBackgroundWhiteBlueHeader,
            Theme.key_windowBackgroundWhiteValueText, Theme.key_switchTrack, Theme.key_switchTrackChecked,
            Theme.key_chats_name, Theme.key_chats_message, Theme.key_chats_date, Theme.key_chats_unreadCounter,
            Theme.key_windowBackgroundWhiteBlueText4,
            Theme.key_actionBarActionModeDefault, Theme.key_actionBarActionModeDefaultIcon).forEach {
            descriptions += ThemeDescription(null, 0, null, null, null, refresh, it)
        }
        return descriptions
    }

    private inner class RowAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = rows[position].kind
        override fun isEnabled(holder: RecyclerView.ViewHolder) = rows.getOrNull(holder.adapterPosition)?.onClick != null
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell: View = when (viewType) {
                0 -> HeaderCell(context)
                1 -> ShadowSectionCell(context)
                2 -> TextCell(context)
                3 -> TextDetailSettingsCell(context).apply { setMultilineDetail(true) }
                4 -> TextCheckCell(context)
                else -> DialogCell(context)
            }
            cell.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return object : RecyclerView.ViewHolder(cell) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            val view = holder.itemView
            view.isEnabled = row.onClick != null || row.kind < 2
            if (row.kind != 1) view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            view.contentDescription = listOf(row.title, row.value).filter { it.isNotBlank() }.joinToString("，")
            when (view) {
                is HeaderCell -> { view.setText(row.title); view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader)) }
                is TextCell -> { view.setText(row.title, position < rows.lastIndex); view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)) }
                is TextDetailSettingsCell -> {
                    view.setTextAndValue(row.title, row.value, position < rows.lastIndex)
                    view.textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                    view.valueTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
                }
                is TextCheckCell -> { view.setTextAndCheck(row.title, row.checked, position < rows.lastIndex) }
                is DialogCell -> {
                    view.setDialog(DialogItem(row.id, row.title, row.value, row.time).apply {
                        highlightedWords = ArrayList(row.highlightedWords)
                    }, false)
                    view.setChecked(row.checked, false)
                }
            }
        }
    }
}

fun BaseFragment.prompt(title: String, initial: String = "", onSave: (String) -> Unit) {
    val context = parentActivity ?: return
    val input = EditText(context).apply {
        setText(initial); setSelection(text.length); setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        setHintTextColor(Theme.getColor(Theme.key_dialogTextHint)); minLines = 1; maxLines = 8
        setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(8f), AndroidUtilities.dp(24f), AndroidUtilities.dp(8f))
        contentDescription = title
    }
    showDialog(AlertDialog.Builder(context).setTitle(title).setView(input)
        .setPositiveButton("保存") { _, _ -> onSave(input.text.toString()) }.setNegativeButton("取消", null).create())
}

fun BaseFragment.confirm(title: String, body: String = "", onConfirm: () -> Unit) {
    val context = parentActivity ?: return
    val destructive = title.contains("删除")
    var ready = !destructive
    val dialog = AlertDialog.Builder(context).setTitle(title).setMessage(body)
        .setPositiveButton(if (destructive) "删除（2）" else "确定") { _, _ -> if (ready) onConfirm() }
        .setNegativeButton("取消", null).create()
    showDialog(dialog)
    if (destructive) {
        val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        button.isEnabled = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = Runnable { if (dialog.isShowing) { ready = true; button.isEnabled = true; (button as? android.widget.TextView)?.text = "删除" } }
        handler.postDelayed(tick, 2000)
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
    }
}

fun BaseFragment.choices(title: String, labels: List<String>, onChoice: (Int) -> Unit) {
    val context = parentActivity ?: return
    showDialog(AlertDialog.Builder(context).setTitle(title).setItems(labels.toTypedArray()) { _, index -> onChoice(index) }.setNegativeButton("取消", null).create())
}

fun BaseFragment.message(text: String) { parentActivity?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() } }
fun BaseFragment.copyText(text: String) {
    parentActivity?.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Listen", text))
    message("已复制")
}
