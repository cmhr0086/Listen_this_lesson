package com.cmhr.listen.tgui

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.cmhr.listen.data.publish.PUBLISH_BASE_URL
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The parser is an existing server-branch contract; Android keeps its existing course model. */
class TimetableFragment internal constructor(private val service: TimetableService = TimetableService()) : ListenListFragment("课表 PDF 导入") {
    override val route = "timetable"
    private var preview: TimetablePreview? = null
    private var selected = emptySet<Int>()
    private var busy = false
    private var error: String? = null
    private var notice: String? = null

    override fun onReady() { render() }

    private fun render() {
        submit(buildList {
            add(ListenRow.header("课表 PDF"))
            add(ListenRow.action(if (busy) "处理中…" else "选择 PDF") {
                if (!busy) host.pickDocument("application/pdf", false) { uris ->
                    uris.firstOrNull()?.let(::parse)
                }
            })
            error?.let { detail -> add(ListenRow.action("导入失败", detail) { message(detail) }) }
            notice?.let { detail -> add(ListenRow.action("导入结果", detail) { message(detail) }) }
            preview?.let { value ->
                add(ListenRow.shadow())
                add(ListenRow.header("识别到 ${value.courses.size} 门课程"))
                value.courses.forEachIndexed { index, course ->
                    add(ListenRow.check(course.name, index in selected) {
                        if (!busy) {
                            selected = if (index in selected) selected - index else selected + index
                            render()
                        }
                    })
                    add(ListenRow.action("课表详情", course.teacher.ifBlank { "未填写教师" }) {
                        showDetail(course.name, course.slots.joinToString("\n", transform = ::timetableSlotLabel))
                    })
                }
                if (value.skipped.isNotEmpty()) add(ListenRow.action("未识别内容", "${value.skipped.size} 项") {
                    showDetail("未识别内容", value.skipped.joinToString("\n"))
                })
                add(ListenRow.action("创建所选课程", "${selected.size} 门") {
                    if (!busy && selected.isNotEmpty()) createCourses()
                })
            }
        })
    }

    private fun showDetail(title: String, detail: String) {
        showDialog(org.telegram.ui.ActionBar.AlertDialog.Builder(host).setTitle(title).setMessage(detail)
            .setPositiveButton("关闭", null).create())
    }

    private fun parse(uri: Uri) = perform {
        val bytes = withContext(Dispatchers.IO) {
            host.contentResolver.openInputStream(uri)?.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= TimetableService.MAX_BYTES) { "PDF 不能超过 2 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } ?: error("无法打开 PDF")
        }
        preview = service.parse(bytes)
        selected = preview!!.courses.indices.toSet()
    }

    private fun createCourses() = perform {
        val chosen = preview?.courses?.filterIndexed { index, _ -> index in selected }.orEmpty()
        val names = host.courses.uiState.value.courses.map { it.name.trim() }.toMutableSet()
        var created = 0
        chosen.forEach { course ->
            if (course.name.isNotBlank() && names.add(course.name.trim())) {
                host.courses.createCourse(course.name.trim()).join()
                created++
            }
        }
        notice = "已创建 $created 门课程，已有 ${chosen.size - created} 门"
        selected = emptySet()
    }

    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        render()
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.takeIf(String::isNotBlank) ?: "导入失败，请重试" }
            finally { busy = false; render() }
        }
    }
}

internal class TimetableService(
    private val baseUrl: String = PUBLISH_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build(),
    private val requireHttps: Boolean = true
) {
    suspend fun parse(pdf: ByteArray): TimetablePreview = withContext(Dispatchers.IO) {
        require(pdf.isNotEmpty()) { "文件是空的" }
        require(pdf.size <= MAX_BYTES) { "PDF 不能超过 2 MB" }
        require(pdf.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) { "请选择有效的 PDF" }
        val base = baseUrl.toHttpUrlOrNull()
        require(base != null && (!requireHttps || base.scheme == "https")) { "服务地址无效" }
        val url = base.newBuilder().addPathSegments("api/public/v1/tools/timetable-pdf").build()
        val request = Request.Builder().url(url).post(pdf.toRequestBody("application/pdf".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) throw IOException("当前服务尚未提供课表导入")
            if (!response.isSuccessful) throw IOException(com.cmhr.listen.data.publish.PublishApiClient.errorMessage(response.code, body))
            Json { ignoreUnknownKeys = true }.decodeFromString<TimetablePreview>(body)
        }
    }

    companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}

@Serializable
internal data class TimetablePreview(
    val courses: List<TimetableCourse>,
    val maxWeek: Int = 0,
    val maxPeriod: Int = 0,
    val skipped: List<String> = emptyList()
)

@Serializable
internal data class TimetableCourse(val name: String, val teacher: String = "", val slots: List<TimetableSlot> = emptyList())

@Serializable
internal data class TimetableSlot(
    val day: Int,
    val from: Int,
    val to: Int,
    val weekFrom: Int,
    val weekTo: Int,
    val parity: String = "all",
    val room: String = ""
)

internal fun timetableSlotLabel(slot: TimetableSlot): String {
    val day = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrNull(slot.day - 1) ?: "日期未知"
    val parity = when (slot.parity) { "odd" -> "单周"; "even" -> "双周"; else -> "" }
    return "$day ${slot.from}–${slot.to} 节 · ${slot.weekFrom}–${slot.weekTo} 周$parity" + slot.room.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
}
