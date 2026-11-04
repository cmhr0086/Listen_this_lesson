package com.cmhr.listen.tgui

import com.cmhr.listen.data.publish.PUBLISH_BASE_URL
import com.cmhr.listen.data.publish.PublishApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A UI adapter to the already-existing administrator materials API; no local schema/storage. */
internal class MaterialService(
    private val baseUrl: String = PUBLISH_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build(),
    private val requireHttps: Boolean = true
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(token: String, course: String): List<RemoteMaterial> =
        json.decodeFromString<MaterialList>(call(token, listOf("courses", course, "materials"))).materials

    suspend fun upload(token: String, course: String, lesson: String?, fileName: String, category: String, body: ByteArray): RemoteMaterial {
        require(body.isNotEmpty()) { "文件是空的" }
        require(body.size <= MAX_BYTES) { "文件不能超过 20 MB" }
        require(category in setOf("note", "material")) { "资料类别无效" }
        val query = mutableMapOf("fileName" to fileName, "category" to category)
        lesson?.let { query["lessonId"] = it }
        return json.decodeFromString(call(token, listOf("courses", course, "materials"), query) {
            post(body.toRequestBody("application/octet-stream".toMediaType()))
        })
    }

    suspend fun delete(token: String, material: String) {
        call(token, listOf("materials", material)) { delete() }
    }

    suspend fun download(token: String, material: RemoteMaterial, directory: File): File = withContext(Dispatchers.IO) {
        val request = request(token, listOf("materials", material.materialId, "file")).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(error(response.code, response.body?.string().orEmpty()))
            val body = response.body ?: throw IOException("资料内容为空")
            if (body.contentLength() > MAX_BYTES) throw IOException("文件不能超过 20 MB")
            directory.mkdirs()
            // Both the remote identifier and the display name are untrusted. Use a fresh local name.
            val extension = material.ext.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
            val file = File.createTempFile("资料-", ".$extension", directory)
            try {
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_BYTES) throw IOException("文件不能超过 20 MB")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                file
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    }

    private suspend fun call(
        token: String,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        method: Request.Builder.() -> Request.Builder = { get() }
    ): String = withContext(Dispatchers.IO) {
        client.newCall(request(token, path, query).method().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(error(response.code, text))
            text
        }
    }

    private fun request(token: String, path: List<String>, query: Map<String, String> = emptyMap()): Request.Builder {
        require(token.isNotBlank()) { "请先绑定设备" }
        return Request.Builder().url(endpoint(path, query)).header("Authorization", "Bearer $token")
    }

    internal fun endpoint(path: List<String>, query: Map<String, String> = emptyMap()): HttpUrl {
        val base = baseUrl.toHttpUrlOrNull()
        require(base != null && (!requireHttps || base.scheme == "https")) { "发布服务地址无效" }
        return base.newBuilder().apply {
            listOf("api", "admin", "v1").plus(path).forEach(::addPathSegment)
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
    }

    private fun error(code: Int, body: String): String = when (code) {
        401, 403 -> "此操作需要管理员账号，请在微信绑定管理员设备"
        else -> PublishApiClient.errorMessage(code, body)
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        val SUPPORTED_EXTENSIONS = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "jpg", "jpeg", "png", "txt", "md", "zip", "rar", "7z")
    }
}

@Serializable
internal data class RemoteMaterial(
    val materialId: String,
    val courseId: String,
    val title: String,
    val fileName: String,
    val ext: String,
    val size: Long,
    val category: String = "material",
    val lessonId: String? = null,
    val lessonTitle: String? = null,
    val viewer: String = "none",
    val position: Int = 0
)

@Serializable
private data class MaterialList(val materials: List<RemoteMaterial>)
