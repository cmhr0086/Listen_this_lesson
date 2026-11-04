package com.cmhr.listen.tgui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.cmhr.listen.AiContentItem
import com.cmhr.listen.AiContentKey
import com.cmhr.listen.AiContentKind
import com.cmhr.listen.AiContentSelectionScope
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.data.ai.*
import com.cmhr.listen.ui.normalizeAiMarkdown
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.telegram.messenger.AndroidUtilities
import org.telegram.tgui.R as TR
import org.telegram.tgui.recyclerview.RecyclerView
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.TextBubbleCell
import org.telegram.ui.Cells.TextBubbleMessage
import org.telegram.ui.ChatFragmentBase
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.MessageContextMenu
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.TelegramUI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun BaseFragment.aiHost(): ListenHostActivity =
    (parentActivity as? ListenHostActivity) ?: (TelegramUI.getHost() as ListenHostActivity)

private fun aiTime(value: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))
private fun aiStatus(value: String) = when (value) {
    AiRequestStatus.PENDING.name -> "生成中"
    AiRequestStatus.ERROR.name -> "生成失败"
    else -> "已完成"
}

/** Uses the existing timeline and selection APIs, including mixed notes/conversation export. */
open class AiListFragment(private val recordId: Long? = null) : ListenListFragment(if (recordId == null) "AI" else "AI 记录") {
    override val route get() = recordId?.let { "record/$it/ai-results" } ?: "ai"
    private var items = emptyList<AiContentItem>()
    private var filter = 0
    private var selected = emptySet<AiContentKey>()
    private var selecting = false

    override fun createView(context: Context): View = super.createView(context).also {
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) { if (selecting) host.ai.clearContentSelection() else finishFragment() }
                else menuClick(id)
            }
        })
        updateSelectionBar()
    }

    override fun configureMenu() {
        actionBar.createMenu().clearItems()
        actionBar.createMenu().addItem(11, TR.drawable.msg_select).contentDescription = "选择 AI 内容"
        val mode = actionBar.createActionMode()
        mode.clearItems()
        mode.addItem(12, "全选")
        mode.addItem(13, "导出")
        mode.addItem(14, TR.drawable.msg_delete).contentDescription = "删除所选"
        mode.addItem(15, "取消")
        updateSelectionBar()
    }

    override fun menuClick(id: Int) {
        when (id) {
            11 -> host.ai.beginContentSelection(recordId)
            12 -> host.ai.replaceContentSelection(recordId, visibleItems().mapTo(linkedSetOf()) { it.key })
            13 -> exportSelection()
            14 -> confirm("删除所选 AI 内容？") { host.ai.deleteSelectedContents(recordId) }
            15 -> host.ai.clearContentSelection()
        }
    }

    private fun visibleItems() = items.filter { filter == 0 || (filter == 1 && it.recordId != null) || (filter == 2 && it.recordId == null) }
    private fun updateSelectionBar() {
        if (fragmentView == null) return
        if (selecting) actionBar.showActionMode() else actionBar.hideActionMode()
    }

    override fun onReady() {
        scope.launch {
            (recordId?.let(host.ai::contents) ?: host.ai.globalContents()).collect { items = it; render() }
        }
        scope.launch {
            host.ai.uiState.collect {
                selecting = it.contentSelectionScope == AiContentSelectionScope(recordId)
                selected = if (selecting) it.selectedContentKeys else emptySet()
                render()
            }
        }
    }

    private fun render() {
        val visible = visibleItems()
        updateSelectionBar()
        submit(buildList {
            add(ListenRow.action("新对话") {
                val active = host.stt.uiState.value
                presentFragment(AiChatFragment(recordId = recordId ?: active.activeRecordId?.takeIf { active.isListening } ?: active.pausedClass?.recordId))
            })
            if (recordId == null) add(ListenRow.action("筛选", listOf("全部", "课堂", "自由提问")[filter]) {
                choices("筛选", listOf("全部", "课堂", "自由提问")) { filter = it; render() }
            })
            if (selecting) {
                add(ListenRow.header("已选择 ${selected.size} 项"))
            }
            if (visible.isEmpty()) add(ListenRow.header("暂无 AI 记录"))
            var previousDay = ""
            visible.forEach { item ->
                val date = java.time.Instant.ofEpochMilli(item.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val label = when (val days = java.time.temporal.ChronoUnit.DAYS.between(date, java.time.LocalDate.now())) {
                    0L -> "今天"; 1L -> "昨天"; in 2L..6L -> "${days}天前"; else -> date.toString()
                }
                if (label != previousDay) { add(ListenRow.header(label)); previousDay = label }
                val open = {
                    if (selecting) host.ai.toggleContentSelection(recordId, item.key)
                    else when (item.key.kind) {
                        AiContentKind.CONVERSATION -> presentFragment(AiChatFragment(item.key.id, item.recordId))
                        AiContentKind.RESULT -> item.recordId?.let { presentFragment(AiResultFragment(it, item.key.id)) }
                    }
                    Unit
                }
                add(ListenRow.dialog(
                    id = item.key.id * 2 + if (item.key.kind == AiContentKind.RESULT) 1 else 0,
                    title = item.title,
                    value = listOfNotNull(item.courseName.takeIf { it.isNotBlank() },
                        if (item.key.kind == AiContentKind.RESULT) aiStatus(item.status) else null,
                        item.preview.takeIf { it.isNotBlank() }).joinToString(" · "),
                    time = aiTime(item.updatedAt), onClick = open,
                    onLongClick = { host.ai.toggleContentSelection(recordId, item.key) }
                ).copy(checked = item.key in selected))
            }
        })
    }

    private fun exportSelection() {
        val ready: (String?) -> Unit = { text ->
            if (text != null) host.createDocument("AI记录.txt", "text/plain") { uri ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        host.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                            ?: error("无法写入文件")
                    } }
                    message(if (result.isSuccess) "已导出" else "导出失败")
                }
            }
        }
        if (recordId == null) host.ai.buildSelectedGlobalContentsTxt(ready)
        else scope.launch {
            val summary = host.courses.recordSummary(recordId).first()
            host.ai.buildSelectedContentsTxt(recordId, summary?.courseName ?: "课程", summary?.session?.name ?: "课堂", ready)
        }
    }

    override fun onBackPressed(): Boolean {
        if (selecting) { host.ai.clearContentSelection(); return false }
        return super.onBackPressed()
    }

    override fun onFragmentDestroy() {
        if (host.ai.uiState.value.contentSelectionScope == AiContentSelectionScope(recordId)) host.ai.clearContentSelection()
        super.onFragmentDestroy()
    }
}

class AiResultsFragment(recordId: Long) : AiListFragment(recordId)
class AiResultFragment(recordId: Long, resultId: Long, ask: Boolean = false) :
    AiChatFragment(recordId = recordId, resultId = resultId, notesAsContext = ask)

/** The database owns history; this fragment only renders it and submits through AiViewModel. */
open class AiChatFragment(
    conversationId: Long? = null,
    private val recordId: Long? = null,
    private val resultId: Long? = null,
    private val notesAsContext: Boolean = false
) : ChatFragmentBase() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val host get() = aiHost()
    private var activeConversationId = conversationId
    private var messages = emptyList<AiMessageEntity>()
    private var result: AiResultEntity? = null
    private var conversation: AiConversationEntity? = null
    private var conversationJob: Job? = null
    private var followLatest = true
    private var contextLimit = AiViewModel.DEFAULT_CLASSROOM_CONTEXT_CODE_POINTS
    private var pending = emptyList<PendingAiAttachment>()
    private var adapter: BubbleAdapter? = null
    private var lastError: String? = null
    private var destroyed = false
    private var menu: MessageContextMenu? = null
    private val attachmentJobs = mutableMapOf<Long, Job>()
    private val attachmentsByMessage = mutableMapOf<Long, List<AiAttachmentEntity>>()
    private var resultAttachments = emptyList<AiAttachmentEntity>()
    val savedRoute: String get() = when {
        resultId != null -> "record/$recordId/ai-result/$resultId" + if (notesAsContext) "/ask" else ""
        activeConversationId != null -> "ai-conversation/$activeConversationId"
        recordId != null -> "ai/new/$recordId"
        else -> "ai/new"
    }

    override fun onFragmentCreate(): Boolean {
        if (!super.onFragmentCreate()) return false
        activeConversationId?.let(::observeConversation)
        resultId?.let { id ->
            scope.launch { host.ai.result(id).collect { result = it; renderMessages(); updateComposer() } }
            scope.launch { host.ai.resultAttachments(id).collect { resultAttachments = it; renderMessages() } }
            scope.launch { host.ai.conversationForResult(id).collect { it?.id?.let(::observeConversation) } }
        }
        scope.launch {
            host.ai.uiState.collect { state ->
                updateComposer()
                if (state.error != null && state.error != lastError) message(state.error)
                lastError = state.error
                // Stream phase changes only invalidate the pending response, never the whole list.
                adapter?.refreshPending()
            }
        }
        return true
    }

    private fun observeConversation(id: Long) {
        if (activeConversationId == id && conversationJob != null) return
        activeConversationId = id
        conversationJob?.cancel()
        conversationJob = scope.launch {
            launch { host.ai.conversation(id).collect { conversation = it; updateTitle() } }
            launch { host.ai.messages(id).collect { next ->
                messages = next
                next.forEach { value -> if (attachmentJobs[value.id] == null) {
                    attachmentJobs[value.id] = scope.launch {
                        host.ai.messageAttachments(value.id).collect { attachments ->
                            attachmentsByMessage[value.id] = attachments
                            renderMessages()
                        }
                    }
                } }
                val removed = attachmentJobs.keys - next.mapTo(hashSetOf()) { it.id }
                removed.forEach { attachmentJobs.remove(it)?.cancel(); attachmentsByMessage.remove(it) }
                renderMessages()
            } }
        }
    }

    override fun createMessageAdapter(context: Context): RecyclerView.Adapter<*> = BubbleAdapter(context).also { adapter = it; it.replace(rows()) }

    override fun createInputDelegate() = object : ChatActivityEnterView.ChatActivityEnterViewDelegate {
        override fun onMessageSend(message: CharSequence?, notify: Boolean, scheduleDate: Int) {
            val question = message?.toString()?.trim().orEmpty()
            if (question.isNotBlank()) send(question)
        }
        override fun didPressAttachButton() { attachmentMenu() }
    }

    override fun configureChat(context: Context) {
        actionBar.setBackButtonImage(TR.drawable.ic_ab_back)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) { if (id == -1) finishFragment() else if (id == 1) moreMenu() else if (id == 2) jumpToLatest() }
        })
        actionBar.createMenu().clearItems()
        actionBar.createMenu().addItem(2, TR.drawable.msg_arrowright).apply { rotation = 90f; contentDescription = "回到最新" }
        actionBar.createMenu().addItem(1, TR.drawable.ic_ab_other).contentDescription = "更多操作"
        enterView.setRecordButtonVisible(false)
        enterView.setAllowStickersAndGifs(false, false, false)
        enterView.setSendPreviewEnabled(false)
        enterView.sendButton.setOnLongClickListener { true }
        enterView.setChatPermissions(true, false, false, false)
        enterView.editField?.contentDescription = "输入消息"
        enterView.editField?.tag = "ai-composer"
        listView.tag = "ai-message-list"
        listView.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, position ->
            val row = adapter?.rowAt(position)
            if (view is TextBubbleCell && row != null) { showRowMenu(view, row); true } else false
        })
        // No change animations: rebinding a stream token must not cross-fade the old answer.
        listView.itemAnimator = null
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING || dy < 0) followLatest = !recyclerView.canScrollVertically(1)
            }
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(1)) followLatest = true
            }
        })
        updateTitle()
        updateComposer()
        if (followLatest) listView.post { jumpToLatest() }
    }

    private fun updateTitle() {
        actionBar?.setTitle(if (resultId != null) if (notesAsContext) "笔记追问" else "AI 结果" else conversation?.title ?: if (recordId != null) "课堂新对话" else "新对话")
        actionBar?.setSubtitle(if (pending.isNotEmpty()) "已添加 ${pending.size} 个附件" else if (host.ai.uiState.value.isBusy) "正在生成" else null)
    }

    private fun updateComposer() {
        enterView?.setOverrideHint(if (host.ai.uiState.value.isBusy) "正在生成…" else if (recordId != null) "问问这节课" else "输入消息")
        enterView?.visibility = if (resultId != null && result?.status != AiRequestStatus.SUCCESS.name) View.GONE else View.VISIBLE
        updateTitle()
    }

    private fun send(question: String) {
        if (host.ai.uiState.value.isBusy) {
            enterView?.post { enterView?.setFieldText(question) }
            message("已有 AI 请求正在处理")
            return
        }
        val sent = pending
        pending = emptyList()
        followLatest = true
        when {
            resultId != null -> host.ai.sendResultFollowUp(resultId, question, sent)
            activeConversationId != null -> host.ai.sendMessage(requireNotNull(activeConversationId), question, sent)
            recordId != null -> {
                val state = host.ai.uiState.value
                if (state.selectionRecordId == recordId && state.selectedSegmentIds.isNotEmpty()) scope.launch {
                    host.ai.createConversation(recordId, question, host.ai.recordTranscripts(recordId).first(), sent, ::observeConversation)
                }
                else host.ai.createClassroomConversation(recordId, contextLimit, question = question, attachments = sent, onCreated = ::observeConversation)
            }
            else -> host.ai.createGeneralConversation(question, sent, ::observeConversation)
        }
        updateTitle()
    }

    private fun attachmentMenu() {
        choices("附件", listOf("图片", "拍照", "文本文件") + pending.map { "移除 ${it.displayName}" }) { index ->
            when (index) {
                0 -> host.pickDocument("image/*", true) { uris -> uris.forEach { prepare(it, AiAttachmentKind.IMAGE) } }
                1 -> runCatching { host.ai.createCameraCaptureTarget() }.onSuccess { target ->
                    host.capturePhoto(target.uri) { success ->
                        if (success) host.ai.prepareAttachment(target.uri, AiAttachmentKind.IMAGE) { attachment ->
                            host.ai.discardCameraCapture(target)
                            accept(attachment)
                        } else host.ai.discardCameraCapture(target)
                    }
                }.onFailure { message("无法打开相机") }
                2 -> host.pickDocument("*/*", true) { uris -> uris.forEach { prepare(it, AiAttachmentKind.TEXT) } }
                else -> pending.getOrNull(index - 3)?.let { host.ai.discardAttachment(it); pending = pending - it; updateTitle() }
            }
        }
    }

    private fun prepare(uri: Uri, kind: AiAttachmentKind) = host.ai.prepareAttachment(uri, kind, ::accept)
    private fun accept(value: PendingAiAttachment?) {
        if (value == null) return
        if (destroyed) host.ai.discardAttachment(value)
        else if (pending.size >= AiAttachmentStore.MAX_ATTACHMENTS_PER_REQUEST) {
            host.ai.discardAttachment(value); message("每次最多添加 3 个附件")
        } else if ((pending + value).sumOf { it.textCodePoints.toLong() } > AiAttachmentStore.MAX_TEXT_CODE_POINTS) {
            host.ai.discardAttachment(value); message("文本附件合计最多 20000 个字符")
        } else { pending = pending + value; updateTitle(); message("已添加 ${value.displayName}") }
    }

    private fun moreMenu() {
        val labels = mutableListOf("课堂原文上下文", "提示词", "请求诊断")
        if (recordId != null && activeConversationId == null && resultId == null) labels += "课堂上下文字数"
        if (resultId != null) { labels += "查看完整结果"; labels += "重新生成"; labels += "结果附件"; if (result?.correctionPayload != null) labels += "查看并应用纠正" }
        choices("更多操作", labels) { index ->
            when (labels[index]) {
                "课堂原文上下文" -> {
                    val saved = result?.sourceTextSnapshot ?: conversation?.sourceTextSnapshot
                    if (saved != null) presentFragment(AiMarkdownFragment("课堂原文上下文", saved, false))
                    else if (recordId != null) scope.launch {
                        val context = AiViewModel.selectRecentClassroomContext(host.ai.recordTranscripts(recordId).first(), contextLimit)
                        presentFragment(AiMarkdownFragment("课堂原文上下文", context.snapshot, false))
                    } else message("无课堂上下文")
                }
                "提示词" -> presentFragment(AiMarkdownFragment("提示词", result?.requestPrompt ?: conversation?.systemPrompt ?: "首次发送时读取当前提示词", false))
                "请求诊断" -> host.ai.uiState.value.lastDiagnostics?.let { d ->
                    presentFragment(AiMarkdownFragment("请求诊断", "模型：${d.model}\n耗时：${d.durationMs ?: "未知"} 毫秒\n结束原因：${d.finishReason ?: "未知"}\n输入令牌：${d.promptTokens ?: "未知"}\n输出令牌：${d.completionTokens ?: "未知"}\n原文字符：${d.sourceCodePoints}\n图片：${d.imageCount}\n${d.error.orEmpty()}", false))
                } ?: message("暂无请求诊断")
                "课堂上下文字数" -> prompt("课堂上下文字数（1000～20000）", contextLimit.toString()) { value ->
                    val parsed = value.toIntOrNull()
                    if (parsed != null && parsed in AiViewModel.MIN_CLASSROOM_CONTEXT_CODE_POINTS..AiViewModel.MAX_SOURCE_CODE_POINTS) contextLimit = parsed else message("请输入 1000～20000")
                }
                "查看完整结果" -> result?.let { presentFragment(AiMarkdownFragment("AI 结果", it.output.orEmpty())) }
                "重新生成" -> resultId?.let { id -> confirm("重新生成？") { host.ai.retryResult(id) } }
                "结果附件" -> resultId?.let { id -> scope.launch { showAttachments(host.ai.resultAttachments(id).first()) } }
                "查看并应用纠正" -> resultId?.let { presentFragment(AiCorrectionFragment(it)) }
            }
        }
    }

    private data class Row(val id: Long, val content: String, val reasoning: String, val outgoing: Boolean, val time: Long, val status: String, val error: String?, val messageId: Long? = null, val attachments: List<AiAttachmentEntity> = emptyList())
    private fun rows(): List<Row> = buildList {
        result?.let { add(Row(-it.id, it.output.orEmpty(), it.reasoningContent, false, it.createdAt, it.status, it.errorMessage, attachments = resultAttachments)) }
        messages.forEach { add(Row(it.id, it.content, it.reasoningContent, it.role == "user", it.createdAt, it.status, it.errorMessage, it.id, attachmentsByMessage[it.id].orEmpty())) }
    }

    private fun renderMessages() {
        adapter?.replace(rows())
        if (followLatest) listView?.post { if (followLatest) jumpToLatest() }
    }

    private fun jumpToLatest() {
        followLatest = true
        val last = (adapter?.itemCount ?: 0) - 1
        if (last >= 0) layoutManager?.scrollToPosition(last)
    }

    private fun showRowMenu(cell: TextBubbleCell, row: Row) {
        menu?.dismiss()
        menu = MessageContextMenu(cell.context, resourceProvider)
            .addItem(TR.drawable.msg_copy, "复制") { copyAiText(cell.context, row.content); message("已复制") }
            .addItem(TR.drawable.filled_open_message, "查看全文") { presentFragment(AiMarkdownFragment(if (row.outgoing) "提问" else "AI 回答", row.content)) }
        if (row.reasoning.isNotBlank()) menu?.addItem(TR.drawable.msg_info, "思考过程") { presentFragment(AiMarkdownFragment("思考过程", row.reasoning)) }
        row.messageId?.let { id ->
            menu?.addItem(TR.drawable.msg_input_attach2, "附件") { scope.launch { showAttachments(host.ai.messageAttachments(id).first()) } }
            messages.firstOrNull { it.id == id }?.contextPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                menu?.addItem(TR.drawable.msg_info, "本轮上下文") { presentFragment(AiMarkdownFragment("本轮上下文", prompt, false)) }
            }
        }
        if (!row.outgoing && row.status != AiRequestStatus.PENDING.name) {
            if (row.messageId == null && resultId != null) menu?.addItem(TR.drawable.msg_retry, "重新生成") { host.ai.retryResult(resultId) }
            else menu?.addItem(TR.drawable.msg_retry, "重新提问") {
                val position = messages.indexOfFirst { it.id == row.messageId }
                messages.take(position.coerceAtLeast(0)).lastOrNull { it.role == "user" }?.let { enterView?.setFieldText(it.content) }
            }
        }
        menu?.show(cell, cell.width / 2f, cell.height.coerceAtMost(AndroidUtilities.dp(180f)) / 2f)
    }

    private fun showAttachments(attachments: List<AiAttachmentEntity>) {
        if (attachments.isEmpty()) { message("无附件"); return }
        choices("附件", attachments.map { it.displayName }) { index ->
            val attachment = attachments[index]
            presentFragment(AiAttachmentFragment(attachment))
        }
    }

    private inner class BubbleAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        private var values = emptyList<Row>()
        private val renderer = aiMarkwon(context)
        init { setHasStableIds(true) }
        override fun getItemCount() = values.size
        override fun getItemId(position: Int) = values[position].id
        fun rowAt(position: Int): Row? = values.getOrNull(position)
        override fun isEnabled(holder: RecyclerView.ViewHolder) = true
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = RecyclerListView.Holder(TextBubbleCell(context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDelegate(object : TextBubbleCell.Delegate {
                override fun onBubbleLongPressed(cell: TextBubbleCell, message: TextBubbleMessage) { values.firstOrNull { it.id == message.id }?.let { showRowMenu(cell, it) } }
                override fun onInlineButtonPressed(cell: TextBubbleCell, message: TextBubbleMessage, button: TextBubbleMessage.InlineButton) {
                    values.firstOrNull { it.id == message.id }?.let { row ->
                        if (button.id == "attachments") showAttachments(row.attachments)
                        else presentFragment(AiMarkdownFragment(if (row.outgoing) "提问" else "AI 回答", row.content))
                    }
                }
                override fun onLinkPressed(cell: TextBubbleCell, message: TextBubbleMessage, url: String) {
                    if (url.startsWith("https://") || url.startsWith("http://")) choices("链接", listOf("打开链接", "复制链接")) { choice ->
                        if (choice == 0) runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { this@AiChatFragment.message("无法打开链接") }
                        else copyAiText(context, url)
                    }
                }
            })
        })
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = values[position]
            val phase = if (row.messageId == null) host.ai.uiState.value.resultStreamPhases[resultId] else host.ai.uiState.value.messageStreamPhases[row.messageId]
            val status = when {
                row.status == AiRequestStatus.ERROR.name -> "\n\n生成失败：${row.error ?: "请重试"}"
                row.status == AiRequestStatus.PENDING.name -> "\n\n${if (phase == AiStreamPhase.THINKING) "正在思考…" else "正在生成…"}"
                else -> ""
            }
            val isTable = Regex("(?m)^\\s*\\|?.*\\|.*\\n\\s*\\|?\\s*:?-{3,}").containsMatchIn(row.content)
            val collapsed = (notesAsContext && row.messageId == null) || isTable || row.content.length > 5000
            val preview = if (collapsed) row.content.take(if (notesAsContext && row.messageId == null) 180 else 1200) + "\n\n查看全文" else row.content
            val text = if (isTable || row.outgoing) preview + status else renderer.toMarkdown(normalizeAiMarkdown(preview + status))
            val bubble = TextBubbleMessage(row.id, text, row.outgoing, aiTime(row.time), if (row.outgoing) TextBubbleMessage.STATE_SENT else TextBubbleMessage.STATE_NONE)
            bubble.inlineKeyboard = buildList {
                if (!row.outgoing && row.content.isNotBlank()) add(listOf(TextBubbleMessage.InlineButton("full", "查看全文", TextBubbleMessage.InlineButton.Type.CALLBACK, false)))
                if (row.attachments.isNotEmpty()) add(listOf(TextBubbleMessage.InlineButton("attachments", "附件（${row.attachments.size}）", TextBubbleMessage.InlineButton.Type.CALLBACK, false)))
            }
            (holder.itemView as TextBubbleCell).apply {
                setMessage(bubble, position > 0 && values[position - 1].outgoing == row.outgoing, position < values.lastIndex && values[position + 1].outgoing == row.outgoing)
                contentDescription = (if (row.outgoing) "提问：" else "回答：") + preview + status
                tag = "ai-bubble-${row.id}"
            }
        }
        fun replace(next: List<Row>) {
            val old = values
            values = next
            // Streaming changes retain IDs/order. Only changed cells get rebound; no full refresh.
            val common = minOf(old.size, next.size)
            if ((0 until common).any { old[it].id != next[it].id }) { notifyDataSetChanged(); return }
            for (index in 0 until common) if (old[index] != next[index]) notifyItemChanged(index, "stream")
            if (next.size > old.size) notifyItemRangeInserted(old.size, next.size - old.size)
            if (next.size < old.size) notifyItemRangeRemoved(next.size, old.size - next.size)
        }
        fun refreshPending() { values.forEachIndexed { index, row -> if (row.status == AiRequestStatus.PENDING.name) notifyItemChanged(index, "phase") } }
    }

    override fun onFragmentDestroy() {
        destroyed = true
        menu?.dismiss()
        pending.forEach(host.ai::discardAttachment)
        pending = emptyList()
        scope.cancel()
        super.onFragmentDestroy()
    }
}

/** Four existing note directions, existing attachment preparation, and existing generation APIs. */
fun openNoteGeneration(fragment: BaseFragment, recordId: Long) {
    fragment.presentFragment(AiNoteGenerationFragment(recordId))
}

class AiNoteGenerationFragment(private val recordId: Long) : ListenListFragment("生成笔记") {
    override val route get() = "ai/notes/new/$recordId"
    private var style = NoteStyle.DEFAULT
    private var pending = emptyList<PendingAiAttachment>()
    private var busy = false
    private var destroyed = false
    override fun onReady() {
        scope.launch { style = host.ai.lastNoteStyle.first(); render() }
        scope.launch { host.ai.uiState.collect { busy = it.isBusy; render() } }
    }
    private fun render(): Unit = submit(buildList {
        add(ListenRow.header("整理方向"))
        NoteStyle.entries.forEach { value -> add(ListenRow.check(value.label, value == style) { style = value; render() }) }
        add(ListenRow.action("添加图片") { host.pickDocument("image/*", true) { uris -> uris.forEach { prepare(it, AiAttachmentKind.IMAGE) } } })
        add(ListenRow.action("拍照") { runCatching { host.ai.createCameraCaptureTarget() }.onSuccess { target ->
            host.capturePhoto(target.uri) { success ->
                if (success) host.ai.prepareAttachment(target.uri, AiAttachmentKind.IMAGE) { attachment ->
                    host.ai.discardCameraCapture(target); accept(attachment)
                } else host.ai.discardCameraCapture(target)
            }
        }.onFailure { message("无法打开相机") } })
        add(ListenRow.action("添加文本文件") { host.pickDocument("*/*", true) { uris -> uris.forEach { prepare(it, AiAttachmentKind.TEXT) } } })
        pending.forEach { attachment -> add(ListenRow.action(attachment.displayName, "移除") { host.ai.discardAttachment(attachment); pending = pending - attachment; render() }) }
        host.ai.uiState.value.error?.let { add(ListenRow.header(it)) }
        add(ListenRow.action(if (busy) "正在生成" else "生成笔记") {
            if (!busy) scope.launch {
                val segments = host.ai.recordTranscripts(recordId).first()
                val attachments = pending
                pending = emptyList()
                host.ai.runFullRecordAction(recordId, AiActionType.ORGANIZE_NOTES, segments, attachments, style) { resultId ->
                    presentFragment(AiResultFragment(recordId, resultId), true)
                }
                render()
            }
        })
    })
    private fun prepare(uri: Uri, kind: AiAttachmentKind) = host.ai.prepareAttachment(uri, kind, ::accept)
    private fun accept(attachment: PendingAiAttachment?) {
        attachment ?: return
        if (destroyed) host.ai.discardAttachment(attachment)
        else if (pending.size >= AiAttachmentStore.MAX_ATTACHMENTS_PER_REQUEST) { host.ai.discardAttachment(attachment); message("每次最多添加 3 个附件") }
        else if ((pending + attachment).sumOf { it.textCodePoints.toLong() } > AiAttachmentStore.MAX_TEXT_CODE_POINTS) { host.ai.discardAttachment(attachment); message("文本附件合计最多 20000 个字符") }
        else { pending = pending + attachment; render() }
    }
    override fun onFragmentDestroy() { destroyed = true; pending.forEach(host.ai::discardAttachment); super.onFragmentDestroy() }
}

private class AiCorrectionFragment(private val resultId: Long) : ListenListFragment("逐片段纠错") {
    override fun onReady() {
        scope.launch {
            combine(host.ai.result(resultId), host.ai.resultSourceSegments(resultId)) { result, source -> result to source }.collect { (result, source) ->
                val payload = result?.correctionPayload?.let { runCatching { CorrectionPayloadCodec.decode(it, source) }.getOrNull() }
                submit(buildList {
                    payload?.segments?.forEach { correction ->
                        val original = source.firstOrNull { it.id == correction.segmentId }
                        add(ListenRow.header("片段 ${correction.segmentId}"))
                        add(ListenRow.action("原文", original?.text.orEmpty()) { presentFragment(AiMarkdownFragment("原文", original?.text.orEmpty(), false)) })
                        add(ListenRow.action("纠正文", correction.correctedText) { presentFragment(AiMarkdownFragment("纠正文", correction.correctedText, false)) })
                        correction.changes.forEach { add(ListenRow.header(it)) }
                    }
                    val applied = source.isNotEmpty() && source.all { it.correctionResultId == resultId }
                    add(ListenRow.action(if (applied) "已应用纠正" else "应用全部纠正") {
                        if (!applied && payload != null) confirm("应用全部纠正？") { host.ai.applyCorrections(resultId) { if (it) message("已应用纠正") } }
                    })
                })
            }
        }
    }
}

private fun copyAiText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AI 内容", text))
}

internal fun aiMarkwon(context: Context): Markwon = Markwon.builder(context)
    .usePlugin(TablePlugin.create(TableTheme.buildWithDefaults(context)
        .tableBorderColor(Theme.getColor(Theme.key_divider))
        .tableHeaderRowBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        .tableCellPadding(AndroidUtilities.dp(8f)).build()))
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(TaskListPlugin.create(context))
    .usePlugin(HtmlPlugin.create())
    .usePlugin(SoftBreakAddsNewLinePlugin.create())
    .build()

/** Full text is never truncated. Tables/code can also be inspected as original Markdown. */
class AiMarkdownFragment(private val title: String, private val source: String, private val markdown: Boolean = true) : BaseFragment() {
    private var textView: TextView? = null
    private var scroll: ScrollView? = null
    private var raw = !markdown
    override fun createView(context: Context): View {
        actionBar.setTitle(title)
        actionBar.setBackButtonImage(TR.drawable.ic_ab_back)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) { -1 -> finishFragment(); 1 -> { copyAiText(context, source); message("已复制") }; 2 -> { raw = !raw; bindText() } }
            }
        })
        actionBar.createMenu().clearItems()
        actionBar.createMenu().addItem(1, TR.drawable.msg_copy).contentDescription = "复制全文"
        if (markdown) actionBar.createMenu().addItem(2, TR.drawable.msg_edit).contentDescription = "切换原文"
        val view = TextView(context).apply {
            textSize = 17f
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(24f))
            setTextIsSelectable(true)
            movementMethod = TableAwareMovementMethod.create()
            tag = "ai-markdown-full"
            setLineSpacing(AndroidUtilities.dp(4f).toFloat(), 1f)
        }
        textView = view
        scroll = ScrollView(context).apply { isFillViewport = true; addView(view) }
        fragmentView = scroll
        bindText()
        return requireNotNull(scroll)
    }
    private fun bindText() {
        val view = textView ?: return
        scroll?.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        view.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText))
        if (raw) view.text = source else aiMarkwon(view.context).setMarkdown(view, normalizeAiMarkdown(source))
    }
    override fun getThemeDescriptions() = arrayListOf(
        ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault),
        ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle),
        ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon),
        ThemeDescription(scroll, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite),
        ThemeDescription(textView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, { bindText() }, Theme.key_windowBackgroundWhiteBlackText)
    )
}

private class AiAttachmentFragment(private val attachment: AiAttachmentEntity) : BaseFragment() {
    private var root: ScrollView? = null
    override fun createView(context: Context): View {
        actionBar.setTitle(attachment.displayName)
        actionBar.setBackButtonImage(TR.drawable.ic_ab_back)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) { if (id == -1) finishFragment() }
        })
        val file = aiHost().ai.attachmentFile(attachment)
        val content = if (attachment.kind == AiAttachmentKind.IMAGE.name) ImageView(context).apply {
            adjustViewBounds = true
            contentDescription = attachment.displayName
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        } else TextView(context).apply {
            textSize = 16f
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
            setTextIsSelectable(true)
            text = runCatching { file.readText() }.getOrDefault("无法读取附件")
        }
        root = ScrollView(context).apply { setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite)); addView(content) }
        fragmentView = root
        return requireNotNull(root)
    }
    override fun getThemeDescriptions() = arrayListOf(
        ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault),
        ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle),
        ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon),
        ThemeDescription(root, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite),
        ThemeDescription(root?.getChildAt(0), ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText)
    )
}
