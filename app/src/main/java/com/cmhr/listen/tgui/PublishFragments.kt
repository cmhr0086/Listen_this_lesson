package com.cmhr.listen.tgui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.cmhr.listen.ClassPublishState
import com.cmhr.listen.CoursePublishUiState
import com.cmhr.listen.PublishClassRow
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.publish.PublishApiClient
import com.cmhr.listen.data.publish.PUBLISH_BASE_URL
import com.cmhr.listen.data.publish.termOf
import com.cmhr.listen.data.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Publishing is a top-level destination; opening it only reads locally cached status. */
class PublishHubFragment : ListenListFragment("公开发布") {
    override val route = "publish"
    override fun onReady() {
        scope.launch {
            combine(host.courses.uiState, host.settings.uiState) { courses, settings ->
                buildList {
                    add(ListenRow.header("公开课程"))
                    if (!settings.publish.hasToken) {
                        add(ListenRow.action("绑定设备", "未绑定") { host.open("settings/device") })
                    }
                    if (courses.courseSummaries.isEmpty()) add(ListenRow.action("暂无课程") {})
                    courses.courseSummaries.forEach { summary ->
                        val publish = settings.publish.courses[summary.course.id]
                        val status = publish?.remoteStatus?.entries?.maxByOrNull { it.key }?.value
                        add(ListenRow.dialog(
                            summary.course.id,
                            summary.course.name,
                            "${summary.recordCount} 节课 · ${status?.let(::publicationStatusLabel) ?: "未公开"}",
                            onClick = { host.open("publish/${summary.course.id}") }
                        ))
                    }
                }
            }.collect(::submit)
        }
    }
}

class CoursePublishFragment(private val courseId: Long) : ListenListFragment("课程发布") {
    override val route = "publish/$courseId"
    override fun onReady() {
        scope.launch {
            host.publish.uiState.collect { state ->
                if (state.courseId == courseId) render(state)
            }
        }
        host.publish.load(courseId)
    }

    private fun render(state: CoursePublishUiState) {
        val latest = state.remote.firstOrNull()
        submit(buildList {
            add(ListenRow.header(state.localName.ifBlank { "课程发布" }))
            add(ListenRow.action(
                when {
                    !state.loaded -> "加载中…"
                    state.syncing -> "正在上传…"
                    !state.hasToken -> "尚未绑定设备"
                    latest == null -> "未公开"
                    else -> publicationStatusLabel(latest.status)
                },
                state.progress?.let { "${it.first} / ${it.second} 节" }.orEmpty()
            ) {})
            if (!state.hasToken) add(ListenRow.action("绑定设备") { host.open("settings/device") })
            if (state.remote.isNotEmpty()) {
                add(ListenRow.action(if (state.refreshing) "刷新中…" else "刷新状态") {
                    if (!state.refreshing) host.publish.refreshRemote()
                })
                state.remote.forEach { remote ->
                    add(ListenRow.action(remote.term, publicationStatusLabel(remote.status)) {})
                    remote.reviewNote?.takeIf(String::isNotBlank)?.let { note ->
                        add(ListenRow.action("审核意见", note) { message(note) })
                    }
                    if (remote.status == "rejected") add(ListenRow.action("重新申请", remote.term) {
                        host.publish.requestReview(remote.courseId)
                    })
                }
            }
            state.notice?.let { add(ListenRow.action("发布结果", it) { message(it) }) }
            state.error?.let { add(ListenRow.action("上传失败", it) { message(it) }) }
            state.remoteError?.let { add(ListenRow.action("刷新失败", it) { message(it) }) }
            add(ListenRow.shadow())
            add(ListenRow.header("公开信息"))
            add(ListenRow.action("学校", state.school.ifBlank { "未填写" }) {
                prompt("学校", state.school, host.publish::setSchool)
            })
            add(ListenRow.action("课程名", state.publicName.ifBlank { "未填写" }) {
                prompt("课程名", state.publicName, host.publish::setPublicName)
            })
            add(ListenRow.action("教师", state.course.teacherName.ifBlank { "未填写" }) {
                prompt("教师", state.course.teacherName, host.publish::setTeacher)
            })
            add(ListenRow.action("学期", state.classes.firstOrNull()?.term ?: "暂无课堂") {})
            add(ListenRow.shadow())
            add(ListenRow.action(
                when {
                    state.syncing -> "正在上传…"
                    state.toSync.isEmpty() -> if (state.classes.any { it.state == ClassPublishState.SYNCED }) "已全部同步" else "暂无可上传课堂"
                    latest == null -> "申请公开并上传 ${state.toSync.size} 节"
                    else -> "同步 ${state.toSync.size} 节"
                },
                if (state.missing.isNotEmpty()) "待填写：${state.missing.joinToString("、")}" else ""
            ) { if (state.canSync) host.publish.sync() })
            if (state.course.lastSyncAt > 0) add(ListenRow.action("上次同步", publicationDate(state.course.lastSyncAt)) {})
            add(ListenRow.shadow())
            add(ListenRow.header("课堂（${state.classes.size}）"))
            if (state.loaded && state.classes.isEmpty()) add(ListenRow.action("暂无课堂") {})
            state.classes.forEach { row ->
                val label = classPublicationLabel(row, state.remote.firstOrNull { it.term == row.term }?.status)
                add(ListenRow.action(row.title, "${publicationDate(row.startedAt)} · $label") {
                    message("${row.title}\n$label\n${row.segmentCount} 段原文" + if (row.hasNotes) " · 有笔记" else "")
                })
                if (row.state != ClassPublishState.LIVE && row.state != ClassPublishState.NO_TEXT) {
                    add(ListenRow.check("公开：${row.title}", row.state != ClassPublishState.EXCLUDED) {
                        val exclude = row.state != ClassPublishState.EXCLUDED
                        if (exclude && row.remote?.status == "active" && !row.remote.withdrawRequested) {
                            confirm("申请撤回这节课？", "管理员处理后生效") { host.publish.setExcluded(row, true) }
                        } else host.publish.setExcluded(row, exclude)
                    })
                }
            }
        })
    }
}

internal fun publicationStatusLabel(status: String): String = when (status) {
    "pending" -> "等待审核"
    "approved" -> "已公开"
    "rejected" -> "已打回"
    "taken_down" -> "已下架"
    else -> "状态未知"
}

internal fun classPublicationLabel(row: PublishClassRow, courseStatus: String?): String = when (row.state) {
    ClassPublishState.LIVE -> "上课中"
    ClassPublishState.NO_TEXT -> "没有文字"
    ClassPublishState.NEW -> "未上传"
    ClassPublishState.CHANGED -> "有改动"
    ClassPublishState.EXCLUDED -> when {
        row.remote?.status == "active" && row.remote.withdrawRequested -> "撤回中"
        row.remote?.status == "active" -> "仍公开"
        else -> "不公开"
    }
    ClassPublishState.SYNCED -> when {
        row.remote?.status == "taken_down" -> "已下架"
        row.remote?.withdrawRequested == true -> "撤回中"
        courseStatus == "approved" -> "已公开"
        else -> "已上传"
    }
}

private fun publicationDate(at: Long): String = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))

/** Course and lesson materials share the existing server contract and remain administrator-only. */
class MaterialsFragment internal constructor(
    private val courseId: Long,
    private val recordId: Long? = null,
    private val service: MaterialService = MaterialService(),
    private val publishClient: PublishApiClient = PublishApiClient(),
    private val publishBaseUrl: String = PUBLISH_BASE_URL
) :
    ListenListFragment(if (recordId == null) "课程笔记与资料" else "课堂笔记与资料") {
    override val route = "materials/$courseId" + (recordId?.let { "/$it" } ?: "")
    private var term: String? = null
    private var remoteCourse: String? = null
    private var remoteLesson: String? = null
    private var recordSession: String? = null
    private var materials = emptyList<RemoteMaterial>()
    private var busy = false
    private var loaded = false
    private var error: String? = null

    override fun onReady() {
        scope.launch {
            if (recordId != null) {
                val record = ListenDatabase.get(host).recordDao().record(recordId).first()
                recordSession = record?.sessionId
                term = record?.startedAt?.let(::termOf)
            }
            host.settings.uiState.collect { settings ->
                val known = settings.publish.courses[courseId]?.remoteCourses.orEmpty()
                if (term == null) term = known.keys.maxOrNull()
                val next = known[term]
                if (next != remoteCourse) {
                    remoteCourse = next
                    remoteLesson = null
                    materials = emptyList()
                    loaded = false
                }
                renderMaterials()
            }
        }
        renderMaterials()
    }

    private fun renderMaterials() {
        val settings = host.settings.uiState.value
        val known = settings.publish.courses[courseId]?.remoteCourses.orEmpty()
        submit(buildList {
            add(ListenRow.header(if (recordId == null) "整门课程" else "单节课堂"))
            if (recordId == null && known.size > 1) add(ListenRow.action("学期", term.orEmpty()) {
                val terms = known.keys.sortedDescending()
                choices("学期", terms) { index ->
                    term = terms[index]
                    remoteCourse = known[term]
                    materials = emptyList()
                    remoteLesson = null
                    loaded = false
                    renderMaterials()
                }
            })
            if (!settings.publish.hasToken) {
                add(ListenRow.action("绑定设备", "需要管理员账号") { host.open("settings/device") })
            } else if (remoteCourse == null) {
                add(ListenRow.action("课程尚未上传") {})
            } else {
                add(ListenRow.action(if (busy) "处理中…" else "刷新资料") { if (!busy) refreshMaterials() })
                add(ListenRow.action("上传笔记") { if (!busy) pickMaterial("note") })
                add(ListenRow.action("上传资料") { if (!busy) pickMaterial("material") })
            }
            error?.let { detail -> add(ListenRow.action("操作失败", detail) { message(detail) }) }
            add(ListenRow.shadow())
            add(ListenRow.header("笔记与资料"))
            if (materials.isEmpty()) add(ListenRow.action(if (loaded) "暂无资料" else "尚未加载") {})
            materials.forEachIndexed { index, material ->
                val scope = material.lessonTitle ?: if (material.lessonId == null) "整门课程" else "单节课堂"
                val category = if (material.category == "note") "笔记" else "资料"
                add(ListenRow.dialog(index.toLong() + 1, material.title,
                    "$category · $scope · ${material.ext.uppercase(Locale.ROOT)} · ${materialSize(material.size)}",
                    onClick = { if (!busy) openMaterial(material) },
                    onLongClick = {
                        if (!busy) choices(material.title, listOf("打开", "删除")) { selected ->
                            if (selected == 0) openMaterial(material)
                            else confirm("删除资料？", material.title) {
                                perform {
                                    service.delete(credential(), material.materialId)
                                    reloadMaterials()
                                }
                            }
                        }
                    }))
            }
        })
    }

    private suspend fun credential(): String = AppSettingsRepository(host).readPublishCredential()
        ?.takeIf(String::isNotBlank) ?: error("请先绑定设备")

    private suspend fun resolveLesson(token: String): String? {
        if (recordId == null) return null
        remoteLesson?.let { return it }
        val course = remoteCourse ?: error("课程尚未上传")
        val session = recordSession ?: error("课堂不存在")
        val lesson = publishClient.courseLessons(publishBaseUrl, token, course).firstOrNull { it.sessionId == session }
            ?: error("这节课堂尚未上传")
        remoteLesson = lesson.lessonId
        return lesson.lessonId
    }

    private suspend fun reloadMaterials() {
        val token = credential()
        val lesson = resolveLesson(token)
        val course = remoteCourse ?: error("课程尚未上传")
        materials = service.list(token, course).filter { recordId == null || it.lessonId == lesson }
        loaded = true
    }

    private fun refreshMaterials() = perform { reloadMaterials() }

    private fun pickMaterial(category: String) {
        host.pickDocument("*/*", false) { uris ->
            val uri = uris.firstOrNull() ?: return@pickDocument
            perform {
                val (name, bytes) = readMaterial(uri)
                val token = credential()
                val lesson = resolveLesson(token)
                val course = remoteCourse ?: error("课程尚未上传")
                service.upload(token, course, lesson, name, category, bytes)
                reloadMaterials()
                message("上传成功")
            }
        }
    }

    private suspend fun readMaterial(uri: Uri): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val resolver = host.contentResolver
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) name = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) require(cursor.getLong(sizeColumn) <= MaterialService.MAX_BYTES) { "文件不能超过 20 MB" }
            }
        }
        val fileName = name?.substringAfterLast('/')?.substringAfterLast('\\') ?: error("无法读取文件名")
        require(fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in MaterialService.SUPPORTED_EXTENSIONS) { "不支持这种文件格式" }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MaterialService.MAX_BYTES) { "文件不能超过 20 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } ?: error("无法打开文件")
        require(bytes.isNotEmpty()) { "文件是空的" }
        fileName to bytes
    }

    private fun openMaterial(material: RemoteMaterial) = perform {
        val file = service.download(credential(), material, File(host.cacheDir, "materials"))
        val uri = FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(material.ext.lowercase(Locale.ROOT)) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { host.startActivity(Intent.createChooser(intent, "打开资料")) }
        catch (_: android.content.ActivityNotFoundException) { message("未安装可打开此文件的应用") }
    }

    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        renderMaterials()
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.takeIf(String::isNotBlank) ?: "操作失败，请重试" }
            finally {
                busy = false
                renderMaterials()
            }
        }
    }
}

private fun materialSize(size: Long): String = if (size >= 1024 * 1024) String.format(Locale.CHINA, "%.1f MB", size / 1024.0 / 1024)
else "${(size + 1023) / 1024} KB"
