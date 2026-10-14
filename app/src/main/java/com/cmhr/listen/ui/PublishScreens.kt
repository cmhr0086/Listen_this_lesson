package com.cmhr.listen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cmhr.listen.PublishRemoteState
import com.cmhr.listen.PublishUiState
import com.cmhr.listen.PublishViewModel
import com.cmhr.listen.data.publish.PublishCourseInfo
import com.cmhr.listen.data.publish.PublishLessonStatus

private enum class PublishField(val label: String, val description: String, val placeholder: String) {
    SCHOOL("学校", "读者按学校、学期、课程名和教师区分课程。", "例如 某某大学"),
    TERM("学期", "同一门课不同学期会分开显示。", "例如 2026-2027 第一学期"),
    COURSE("课程名", "公开显示的课程名称，可以和本机的课程名不同。", "例如 高等数学"),
    TEACHER("教师", "同名课程按教师区分。教师姓名会公开显示。", "例如 张老师"),
    TITLE("课堂标题", "读者在课程里看到的这节课的名字。", "例如 第 3 节 极限");

    fun read(state: PublishUiState): String = when (this) {
        SCHOOL -> state.course.schoolName
        TERM -> state.course.term
        COURSE -> state.course.courseName
        TEACHER -> state.course.teacherName
        TITLE -> state.title
    }
}

private fun PublishCourseInfo.with(field: PublishField, value: String) = when (field) {
    PublishField.SCHOOL -> copy(schoolName = value)
    PublishField.TERM -> copy(term = value)
    PublishField.COURSE -> copy(courseName = value)
    PublishField.TEACHER -> copy(teacherName = value)
    PublishField.TITLE -> this
}

/**
 * One class, sent for public reading: what readers will see it filed under, which notes go along,
 * and where its review stands. Nothing here changes the class on this device.
 */
@Composable
fun PublishLessonScreen(recordId: Long, model: PublishViewModel, openSettings: () -> Unit) {
    val state by model.uiState.collectAsState()
    var editing by rememberSaveable { mutableStateOf<PublishField?>(null) }
    var withdrawing by remember { mutableStateOf(false) }
    LaunchedEffect(recordId) { model.load(recordId) }

    SettingsPage {
        item("status") { PublishStatusCard(state, openSettings, model::refreshStatus) }
        item("course-heading") { SectionHeader("公开显示为", Modifier.padding(top = 12.dp)) }
        item("course") {
            ListGroup {
                PublishField.entries.forEachIndexed { index, field ->
                    if (index > 0) ListDivider(16.dp)
                    ValueRow(field.label, field.read(state).ifBlank { "未填写" }, { editing = field }, Modifier.testTag("publish-field-${field.name.lowercase()}"))
                    if (field == PublishField.TERM && state.termSuggestions.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.termSuggestions.forEach { term ->
                                FilterChip(
                                    selected = state.course.term == term,
                                    onClick = { model.setCourse(state.course.copy(term = term)) },
                                    label = { Text(term) }
                                )
                            }
                        }
                    }
                }
            }
        }
        item("notes-heading") { SectionHeader("一起公开的笔记", Modifier.padding(top = 12.dp)) }
        item("notes") {
            if (state.notes.isEmpty()) {
                Note("这节课还没有整理好的笔记，只会公开课堂文字。")
            } else ListGroup {
                state.notes.forEachIndexed { index, note ->
                    if (index > 0) ListDivider(56.dp)
                    val checked = note.resultId in state.selectedNotes
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(checked, role = Role.Checkbox) { model.toggleNote(note.resultId) }
                            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                            .testTag("publish-note-${note.resultId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${note.title} · ${checkTime(note.createdAt)}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                note.content.lineSequence().map { it.trimStart('#', ' ', '-', '*') }.firstOrNull { it.isNotBlank() }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        item("content-heading") { SectionHeader("课堂文字", Modifier.padding(top = 12.dp)) }
        item("content") {
            Note(
                buildString {
                    append(if (state.segmentCount == 0) "这节课还没有文字" else "${state.segmentCount} 段文字")
                    if (state.markedCount > 0) append("，其中 ${state.markedCount} 段重点")
                    if (state.correctedCount > 0) append("，${state.correctedCount} 段使用纠正后的文字")
                    append("。录音、识别诊断和 AI 对话都不会上传。")
                }
            )
        }
        item("submit") {
            val published = (state.remote as? PublishRemoteState.Loaded)?.status?.published == true
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { Note(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("publish-error")) }
                Button(
                    onClick = model::submit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("publish-submit")
                ) {
                    Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        when {
                            state.submitting -> "正在提交…"
                            published -> "提交新版本审核"
                            else -> "提交审核"
                        },
                        Modifier.padding(start = 8.dp)
                    )
                }
                Note(
                    if (!state.course.complete) "先把学校、学期、课程名和教师填完整。"
                    else if (published) "新版本通过审核前，读者看到的仍是现在公开的版本。"
                    else "提交的是此刻内容的副本；之后在本机修改不会影响它，需要时再提交新版本。"
                )
            }
        }
        val status = (state.remote as? PublishRemoteState.Loaded)?.status
        if (status?.published == true && !status.withdrawRequested) item("withdraw") {
            DangerRow("申请撤回", "管理员处理后，读者将看不到这节课", { withdrawing = true }, Modifier.testTag("publish-withdraw"))
        }
    }

    editing?.let { field ->
        TextEditSheet(
            title = field.label, description = field.description, label = field.label,
            initial = field.read(state), secret = false, confirmLabel = "确定", placeholder = field.placeholder,
            keyboardType = KeyboardType.Text,
            onConfirm = { value ->
                if (field == PublishField.TITLE) model.setTitle(value) else model.setCourse(state.course.with(field, value))
            },
            dismiss = { editing = null }
        )
    }
    if (withdrawing) WithdrawDialog(dismiss = { withdrawing = false }, confirm = model::requestWithdraw)
}

@Composable
private fun PublishStatusCard(state: PublishUiState, openSettings: () -> Unit, retry: () -> Unit) {
    val notice = state.notice
    when (val remote = state.remote) {
        PublishRemoteState.NotConfigured -> StatusCard(
            CardTone.NEUTRAL, Icons.Outlined.CloudOff, "还没设置", "需要先填写上传 Token。",
            action = { OutlinedButton(onClick = openSettings) { Text("去设置") } }
        )
        PublishRemoteState.Loading -> StatusCard(CardTone.BUSY, Icons.Outlined.Public, "正在读取审核状态…", null)
        is PublishRemoteState.Failed -> StatusCard(
            CardTone.PROBLEM, Icons.Outlined.ErrorOutline, "读不到审核状态", remote.message,
            action = { OutlinedButton(onClick = retry) { Text("重试") } }
        )
        is PublishRemoteState.Loaded -> {
            val (tone, title, detail) = describe(remote.status)
            StatusCard(
                tone, if (tone == CardTone.OK) Icons.Outlined.Check else if (tone == CardTone.PROBLEM) Icons.Outlined.ErrorOutline else Icons.Outlined.HourglassTop,
                title, listOfNotNull(notice, detail).joinToString("\n").ifEmpty { null },
                action = { TextButton(onClick = retry) { Text("刷新") } }
            )
        }
    }
}

/** The card for a class: the public state first, then what is waiting behind it. */
internal fun describe(status: PublishLessonStatus): Triple<CardTone, String, String?> {
    val latest = status.revisions.firstOrNull()
    val pending = status.revisions.firstOrNull { it.status == "pending" }
    val waiting = pending?.let { "第 ${it.revision} 版已提交 ${checkTime(it.submittedAt)}，等待审核" }
    val rejectedNote = latest?.takeIf { it.status == "rejected" }?.let { r ->
        "第 ${r.revision} 版被驳回" + (r.reviewNote?.let { "：$it" } ?: "")
    }
    return when {
        latest == null -> Triple(CardTone.NEUTRAL, "尚未公开", "提交后，管理员审核通过才会出现在阅读端。")
        status.lessonStatus == "taken_down" -> Triple(CardTone.PROBLEM, "已下架", "读者已看不到这节课。" + (waiting?.let { "\n$it" } ?: ""))
        status.published -> Triple(
            CardTone.OK, "已公开",
            listOfNotNull(
                if (status.withdrawRequested) "已申请撤回，等待管理员处理" else null,
                waiting, rejectedNote
            ).joinToString("\n").ifEmpty { "读者看到的是最新通过审核的版本。" }
        )
        pending != null -> Triple(CardTone.BUSY, "等待审核", waiting)
        else -> Triple(CardTone.PROBLEM, "未通过审核", rejectedNote ?: "可以修改后重新提交。")
    }
}

@Composable
private fun WithdrawDialog(dismiss: () -> Unit, confirm: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("申请撤回这节课？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("管理员处理后，读者将看不到这节课。已经导出过的文件无法收回。")
                OutlinedTextField(reason, { reason = it }, label = { Text("原因") }, modifier = Modifier.fillMaxWidth().testTag("publish-withdraw-reason"))
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm(reason); dismiss() }, enabled = reason.isNotBlank()) {
                Text("申请撤回", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}
