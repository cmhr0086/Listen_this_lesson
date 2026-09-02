package com.cmhr.listen.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PendingAiPhoto(
    val absolutePath: String,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/jpeg"
)

class AiPhotoStore(private val context: Context) {
    private val root = File(context.filesDir, "ai_photos")
    private val captureRoot = File(context.cacheDir, "ai_capture")

    fun createCaptureFile(): File {
        captureRoot.mkdirs()
        return File(captureRoot, "capture-${UUID.randomUUID()}.jpg")
    }

    fun captureUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    suspend fun prepareCapture(source: File): PendingAiPhoto = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0) { "没有获取到有效照片。" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取照片。" }
        var sample = 1
        while (bounds.outWidth / sample > 3840 || bounds.outHeight / sample > 3840) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("无法解码照片。")
        val rotation = runCatching {
            when (ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val oriented = if (rotation == 0f) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true
        ).also { if (it !== decoded) decoded.recycle() }
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(oriented.width, oriented.height))
        val width = (oriented.width * scale).toInt().coerceAtLeast(1)
        val height = (oriented.height * scale).toInt().coerceAtLeast(1)
        val normalized = if (width == oriented.width && height == oriented.height) oriented
        else Bitmap.createScaledBitmap(oriented, width, height, true).also { if (it !== oriented) oriented.recycle() }
        val output = File(captureRoot, "prepared-${UUID.randomUUID()}.jpg")
        output.outputStream().buffered().use { stream ->
            require(normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) { "照片压缩失败。" }
        }
        normalized.recycle()
        source.delete()
        PendingAiPhoto(output.absolutePath, width, height)
    }

    suspend fun persist(
        recordId: Long,
        ownerDirectory: String,
        photos: List<PendingAiPhoto>
    ): List<PendingAiPhoto> = withContext(Dispatchers.IO) {
        require(photos.size <= MAX_PHOTOS_PER_REQUEST) { "每次最多添加 $MAX_PHOTOS_PER_REQUEST 张照片。" }
        val directory = File(root, "record_$recordId/$ownerDirectory").apply { mkdirs() }
        photos.map { photo ->
            val source = File(photo.absolutePath)
            require(source.isFile) { "照片文件不存在。" }
            val target = File(directory, "${UUID.randomUUID()}.jpg")
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            photo.copy(absolutePath = target.relativeTo(context.filesDir).invariantSeparatorsPath)
        }
    }

    suspend fun dataUrl(relativePath: String, mimeType: String): String = withContext(Dispatchers.IO) {
        val file = resolve(relativePath)
        require(file.isFile) { "附加照片已丢失。" }
        "data:$mimeType;base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}"
    }

    fun resolve(relativePath: String): File = File(context.filesDir, relativePath)

    suspend fun deleteRelativePaths(paths: Collection<String>) = withContext(Dispatchers.IO) {
        paths.forEach { resolve(it).delete() }
        paths.map { resolve(it).parentFile }.distinct().forEach { directory ->
            if (directory?.listFiles().isNullOrEmpty()) directory?.delete()
        }
    }

    suspend fun deleteRecord(recordId: Long) = withContext(Dispatchers.IO) {
        File(root, "record_$recordId").deleteRecursively()
    }

    fun discard(photo: PendingAiPhoto) {
        val file = File(photo.absolutePath)
        if (file.canonicalPath.startsWith(captureRoot.canonicalPath)) file.delete()
    }

    companion object {
        const val MAX_PHOTOS_PER_REQUEST = 3
        private const val MAX_EDGE = 1920
        private const val JPEG_QUALITY = 85
    }
}
