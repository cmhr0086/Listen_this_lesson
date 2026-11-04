package com.cmhr.listen.ui

import com.cmhr.listen.ClassPublishState
import com.cmhr.listen.CoursePublishUiState
import com.cmhr.listen.PublishClassRow

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
/** The headline of the course publish page: one state, one sentence, at most one action. */
internal data class PublishHeadline(val tone: CardTone, val title: String, val detail: String, val action: HeadlineAction? = null)
internal enum class HeadlineAction { SETTINGS, REQUEST_REVIEW }

internal fun publishHeadline(state: CoursePublishUiState): PublishHeadline {
    val latest = state.remote.firstOrNull()
    val uploaded = state.classes.count { it.remote != null }
    val waiting = state.toSync.size
    val pendingSync = if (waiting > 0) "，还有 $waiting 节待同步" else ""
    return when {
        !state.hasToken -> PublishHeadline(CardTone.NEUTRAL, "还不能发布", "先在设置里用微信绑定这台设备，再请管理员开通上传。", HeadlineAction.SETTINGS)
        latest == null -> PublishHeadline(
            CardTone.NEUTRAL, "还没公开",
            "完善下面的课程信息，点「申请公开」。管理员批准后，同学们就能在阅读端看到这门课。"
        )
        latest.status == "pending" -> PublishHeadline(
            CardTone.NEUTRAL, "等待审核", "已上传 $uploaded 节$pendingSync。管理员批准后，这门课会对同学们公开。"
        )
        latest.status == "approved" -> PublishHeadline(CardTone.OK, "已公开", "$uploaded 节课公开中$pendingSync。")
        latest.status == "rejected" -> PublishHeadline(
            CardTone.PROBLEM, "已打回",
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

internal fun courseStatusLabel(status: String) = when (status) {
    "pending" -> "等待审核"
    "approved" -> "已公开"
    "rejected" -> "已打回"
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
        !hasToken -> "绑定设备后，请管理员开通上传" to null
        teacher.isBlank() -> "完善课程信息后，公开给同学们阅读" to null
        else -> "已完善，可以申请公开" to null
    }
}

internal enum class CardTone { OK, PROBLEM, NEUTRAL, BUSY }
