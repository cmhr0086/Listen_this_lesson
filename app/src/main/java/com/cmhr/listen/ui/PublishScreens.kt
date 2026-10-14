package com.cmhr.listen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cmhr.listen.ClassPublishState
import com.cmhr.listen.CoursePublishUiState
import com.cmhr.listen.PublishClassRow
import com.cmhr.listen.PublishViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CourseField { SCHOOL, NAME, TEACHER }

/** The headline of the course publish page: one state, one sentence, at most one action. */
internal data class PublishHeadline(val tone: CardTone, val title: String, val detail: String, val action: HeadlineAction? = null)
internal enum class HeadlineAction { SETTINGS, REQUEST_REVIEW }

internal fun publishHeadline(state: CoursePublishUiState): PublishHeadline {
    val latest = state.remote.firstOrNull()
    val uploaded = state.classes.count { it.remote != null }
    val waiting = state.toSync.size
    val pendingSync = if (waiting > 0) "，还有 $waiting 节待同步" else ""
    return when {
        !state.hasToken -> PublishHeadline(CardTone.NEUTRAL, "还不能发布", "需要管理员开通的上传 Token。", HeadlineAction.SETTINGS)
        latest == null -> PublishHeadline(
            CardTone.NEUTRAL, "还没公开",
            "完善下面的课程信息，点「申请公开」。管理员批准后，同学们就能在阅读端看到这门课。"
        )
        latest.status == "pending" -> PublishHeadline(
            CardTone.NEUTRAL, "等待审核", "已上传 $uploaded 节$pendingSync。管理员批准后，这门课会对同学们公开。"
        )
        latest.status == "approved" -> PublishHeadline(CardTone.OK, "已公开", "$uploaded 节课公开中$pendingSync。")
        latest.status == "rejected" -> PublishHeadline(
            CardTone.PROBLEM, "未通过审核",
            (latest.reviewNote?.let { "管理员：$it。" } ?: "") + "修改后可以重新申请。", HeadlineAction.REQUEST_REVIEW
        )
        else -> PublishHeadline(CardTone.PROBLEM, "已被管理员下架", "读者现在看不到这门课。如有疑问请联系管理员。")
    }
}

/** A class's state in one pill: what readers can see of it, or why it is not there. */
internal fun classPill(row: PublishClassRow, courseStatus: String?): Pair<String, StatusTone> = when (row.state) {
    ClassPublishState.LIVE -> "上课中" to StatusTone.ACTIVE
    ClassPublishState.NO_TEXT -> "没有文字" to StatusTone.DONE
    ClassPublishState.EXCLUDED -> when {
        row.remote?.status == "active" && row.remote.withdrawRequested -> "撤回中" to StatusTone.READY
        row.remote?.status == "active" -> "仍公开" to StatusTone.PROBLEM
        else -> "不公开" to StatusTone.DONE
    }
    ClassPublishState.NEW -> "未上传" to StatusTone.READY
    ClassPublishState.CHANGED -> "有改动" to StatusTone.READY
    ClassPublishState.SYNCED -> when {
        row.remote?.status == "taken_down" -> "已下架" to StatusTone.PROBLEM
        row.remote?.withdrawRequested == true -> "撤回中" to StatusTone.READY
        courseStatus == "approved" -> "已公开" to StatusTone.DONE
        else -> "已上传" to StatusTone.DONE
    }
}

private fun dateLabel(at: Long) = SimpleDateFormat("M月d日 E", Locale.CHINA).format(Date(at))

/**
 * Publishing works per course: fill the course in once, then one button uploads every class that
 * is new or changed. The admin reviews the course; after that its classes go live as they sync.
 */
@Composable
fun CoursePublishScreen(courseId: Long, model: PublishViewModel, openSettings: () -> Unit) {
    val state by model.uiState.collectAsState()
    var editing by rememberSaveable { mutableStateOf<CourseField?>(null) }
    LaunchedEffect(courseId) { model.load(courseId) }
    val headline = publishHeadline(state)
    val latestStatus = state.remote.firstOrNull()?.status

    SettingsPage {
        item("status") {
            val icon: ImageVector = when (headline.tone) {
                CardTone.OK -> Icons.Outlined.Check
                CardTone.PROBLEM -> Icons.Outlined.ErrorOutline
                else -> if (state.remote.isEmpty()) Icons.Outlined.CloudOff else Icons.Outlined.HourglassTop
            }
            StatusCard(
                if (state.syncing) CardTone.BUSY else headline.tone, icon,
                if (state.syncing) "正在上传…" else headline.title,
                state.progress?.let { (done, total) -> "已上传 $done / $total 节" } ?: headline.detail,
                action = when (headline.action) {
                    HeadlineAction.SETTINGS -> { { OutlinedButton(onClick = openSettings) { Text("去设置") } } }
                    HeadlineAction.REQUEST_REVIEW -> state.remote.firstOrNull()?.let { remote ->
                        { OutlinedButton(onClick = { model.requestReview(remote.courseId) }, modifier = Modifier.testTag("publish-request-review")) { Text("重新申请") } }
                    }
                    null -> if (state.remote.isNotEmpty()) { { TextButton(onClick = model::refreshRemote, enabled = !state.refreshing) { Text(if (state.refreshing) "刷新中" else "刷新") } } } else null
                }
            ) {
                state.notice?.let { Note(it, Modifier.testTag("publish-notice")) }
                state.error?.let { Note(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("publish-error")) }
                state.remoteError?.let { Note("读不到最新审核状态：$it", color = MaterialTheme.colorScheme.error) }
                state.remote.drop(1).forEach { older ->
                    Note("${older.term}：${courseStatusLabel(older.status)}")
                }
            }
        }

        item("info-heading") { SectionHeader("课程信息 · 公开显示", Modifier.padding(top = 12.dp)) }
        item("info") {
            ListGroup {
                ValueRow("学校", state.school.ifBlank { "未填写" }, { editing = CourseField.SCHOOL }, Modifier.testTag("publish-school"), subtitle = "所有课程共用")
                ListDivider(16.dp)
                ValueRow("课程名", state.publicName.ifBlank { "未填写" }, { editing = CourseField.NAME }, Modifier.testTag("publish-name"))
                ListDivider(16.dp)
                ValueRow("教师", state.course.teacherName.ifBlank { "未填写" }, { editing = CourseField.TEACHER }, Modifier.testTag("publish-teacher"))
                ListDivider(16.dp)
                ListRow(
                    "学期", subtitle = "按每节课的上课日期自动归入",
                    trailing = {
                        Text(
                            state.classes.firstOrNull()?.term ?: "—",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                )
            }
        }
        if (state.remote.isNotEmpty()) item("info-note") { Note("改动课程名或教师后，这门课会作为新课程重新审核，审核通过前读者看不到。") }

        item("sync") {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = model::sync,
                    enabled = state.canSync,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("publish-sync")
                ) {
                    Icon(if (state.remote.isEmpty()) Icons.Outlined.Public else Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        when {
                            state.syncing -> "正在上传…"
                            state.toSync.isEmpty() -> if (state.classes.any { it.state == ClassPublishState.SYNCED }) "已全部同步" else "没有可上传的课堂"
                            state.remote.isEmpty() -> "申请公开并上传 ${state.toSync.size} 节"
                            else -> "同步 ${state.toSync.size} 节到公开"
                        },
                        Modifier.padding(start = 8.dp)
                    )
                }
                Note(
                    when {
                        state.missing.isNotEmpty() -> "还需要填写：${state.missing.joinToString("、")}。"
                        state.course.lastSyncAt > 0 -> "上次同步 ${checkTime(state.course.lastSyncAt)}。只上传课堂文字、重点和最新的整节课笔记；录音和 AI 对话不会上传。"
                        else -> "只上传课堂文字、重点和最新的整节课笔记；录音和 AI 对话不会上传。"
                    }
                )
            }
        }

        item("classes-heading") { SectionHeader("课堂（${state.classes.size}）", Modifier.padding(top = 12.dp)) }
        item("classes") {
            if (state.classes.isEmpty()) Note("这门课还没有课堂。")
            else ListGroup {
                state.classes.forEachIndexed { index, row ->
                    if (index > 0) ListDivider(16.dp)
                    val (pill, tone) = classPill(row, state.remote.firstOrNull { it.term == row.term }?.status)
                    val canToggle = row.state != ClassPublishState.LIVE && row.state != ClassPublishState.NO_TEXT
                    ListRow(
                        title = row.title,
                        subtitle = buildString {
                            append(dateLabel(row.startedAt))
                            if (row.segmentCount > 0) append(" · ${row.segmentCount} 段")
                            if (row.hasNotes) append(" · 有笔记")
                        },
                        status = { StatusPill(pill, tone) },
                        trailing = if (canToggle) {
                            {
                                Switch(
                                    checked = row.state != ClassPublishState.EXCLUDED,
                                    onCheckedChange = { model.setExcluded(row, !it) },
                                    modifier = Modifier.testTag("publish-class-${row.recordId}")
                                )
                            }
                        } else null
                    )
                }
            }
        }
        if (state.classes.any { it.state == ClassPublishState.EXCLUDED }) item("classes-note") {
            Note("关掉开关的课堂不会上传；已经公开的会申请撤回，管理员处理后读者就看不到了。")
        }
    }

    when (editing) {
        CourseField.SCHOOL -> TextEditSheet(
            title = "学校", description = "所有公开的课程都归在这所学校下。", label = "学校",
            initial = state.school, secret = false, confirmLabel = "确定", placeholder = "例如 西安建筑科技大学",
            keyboardType = KeyboardType.Text, onConfirm = model::setSchool, dismiss = { editing = null }
        )
        CourseField.NAME -> TextEditSheet(
            title = "课程名", description = "同学们看到的课程名称，可以和本机的不同。", label = "课程名",
            initial = state.publicName, secret = false, confirmLabel = "确定", placeholder = state.localName,
            keyboardType = KeyboardType.Text, onConfirm = model::setPublicName, dismiss = { editing = null }
        )
        CourseField.TEACHER -> TextEditSheet(
            title = "教师", description = "同名课程按教师区分，教师姓名会公开显示。", label = "教师",
            initial = state.course.teacherName, secret = false, confirmLabel = "确定", placeholder = "例如 张老师",
            keyboardType = KeyboardType.Text, onConfirm = model::setTeacher, dismiss = { editing = null }
        )
        null -> Unit
    }
}

internal fun courseStatusLabel(status: String) = when (status) {
    "pending" -> "等待审核"
    "approved" -> "已公开"
    "rejected" -> "未通过审核"
    "taken_down" -> "已下架"
    else -> status
}

/** One line for the course page row, from what this device last knew. */
internal fun coursePublishSummary(hasToken: Boolean, status: Map<String, String>, teacher: String): Pair<String, Pair<String, StatusTone>?> {
    val latest = status.entries.maxByOrNull { it.key }?.value
    return when {
        latest != null -> "同学们在阅读端看到的内容" to (courseStatusLabel(latest) to when (latest) {
            "approved" -> StatusTone.DONE
            "pending" -> StatusTone.READY
            else -> StatusTone.PROBLEM
        })
        !hasToken -> "需要管理员开通的上传 Token" to null
        teacher.isBlank() -> "完善课程信息后，公开给同学们阅读" to null
        else -> "已完善，可以申请公开" to null
    }
}
