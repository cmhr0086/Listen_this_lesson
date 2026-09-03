package com.cmhr.listen.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PendingAiAttachment(
    val absolutePath: String,
    val kind: AiAttachmentKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val textCodePoints: Int = 0,
    val width: Int? = null,
    val height: Int? = null
)

class AiAttachmentStore(private val context: Context) {
    private val root = File(context.filesDir, "ai_attachments")
    private val legacyRoot = File(context.filesDir, "ai_photos")
    private val stagingRoot = File(context.cacheDir, "ai_attachments")

    suspend fun prepare(uri: Uri, expectedKind: AiAttachmentKind): PendingAiAttachment = withContext(Dispatchers.IO) {
        stagingRoot.mkdirs()
        val resolver = context.contentResolver
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                Pair(
                    nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
                    sizeIndex.takeIf { it >= 0 }?.let(cursor::getLong)
                )
            }
        }
        val displayName = metadata?.first?.takeIf { it.isNotBlank() } ?: "附件"
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { mimeFromName(displayName) }
        val source = File(stagingRoot, "source-${UUID.randomUUID()}")
        resolver.openInputStream(uri)?.use { input -> source.outputStream().buffered().use(input::copyTo) }
            ?: error("无法读取所选附件。")
        try {
            when (expectedKind) {
                AiAttachmentKind.IMAGE -> prepareImage(source, displayName, mimeType)
                AiAttachmentKind.TEXT -> prepareText(source, displayName, mimeType)
            }
        } finally {
            source.delete()
        }
    }

    private fun prepareImage(source: File, displayName: String, mimeType: String): PendingAiAttachment {
        require(isSupportedImage(displayName, mimeType)) {
            "仅支持 JPEG、PNG 或 WebP 图片。"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片。" }
        var sample = 1
        while (bounds.outWidth / sample > 3840 || bounds.outHeight / sample > 3840) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("无法解码图片。")
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
        val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(oriented.width, oriented.height))
        val width = (oriented.width * scale).toInt().coerceAtLeast(1)
        val height = (oriented.height * scale).toInt().coerceAtLeast(1)
        val normalized = if (width == oriented.width && height == oriented.height) oriented
        else Bitmap.createScaledBitmap(oriented, width, height, true).also { if (it !== oriented) oriented.recycle() }
        val output = File(stagingRoot, "prepared-${UUID.randomUUID()}.jpg")
        output.outputStream().buffered().use { stream ->
            require(normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) { "图片压缩失败。" }
        }
        normalized.recycle()
        return PendingAiAttachment(
            absolutePath = output.absolutePath,
            kind = AiAttachmentKind.IMAGE,
            displayName = displayName,
            mimeType = "image/jpeg",
            sizeBytes = output.length(),
            width = width,
            height = height
        )
    }

    private fun prepareText(source: File, displayName: String, mimeType: String): PendingAiAttachment {
        require(isSupportedText(displayName, mimeType)) {
            "仅支持 TXT、Markdown、CSV 或 JSON 文本文件。"
        }
        validateTextFileSize(source.length())
        val bytes = source.readBytes()
        val text = decodeUtf8(bytes)
        require(text.codePointCount(0, text.length) <= MAX_TEXT_CODE_POINTS) {
            "所有文本附件合计最多 20,000 个字符。"
        }
        val output = File(stagingRoot, "prepared-${UUID.randomUUID()}.${displayName.substringAfterLast('.', "txt")}")
        source.copyTo(output, overwrite = true)
        return PendingAiAttachment(
            absolutePath = output.absolutePath,
            kind = AiAttachmentKind.TEXT,
            displayName = displayName,
            mimeType = mimeType.ifBlank { "text/plain" },
            sizeBytes = output.length(),
            textCodePoints = text.codePointCount(0, text.length)
        )
    }

    suspend fun persist(ownerDirectory: String, attachments: List<PendingAiAttachment>): List<PendingAiAttachment> =
        withContext(Dispatchers.IO) {
            require(attachments.size <= MAX_ATTACHMENTS_PER_REQUEST) { "每次最多添加 $MAX_ATTACHMENTS_PER_REQUEST 个附件。" }
            validateTextTotal(attachments)
            val directory = File(root, ownerDirectory).apply { mkdirs() }
            attachments.map { attachment ->
                val source = File(attachment.absolutePath)
                require(source.isFile) { "附件文件不存在。" }
                val extension = if (attachment.kind == AiAttachmentKind.IMAGE) "jpg"
                else attachment.displayName.substringAfterLast('.', "txt")
                    .lowercase()
                    .filter(Char::isLetterOrDigit)
                    .take(8)
                    .ifBlank { "txt" }
                val target = File(directory, "${UUID.randomUUID()}.$extension")
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
                attachment.copy(absolutePath = target.relativeTo(context.filesDir).invariantSeparatorsPath, sizeBytes = target.length())
            }
        }

    suspend fun imageDataUrl(attachment: AiAttachmentEntity): String = withContext(Dispatchers.IO) {
        require(attachment.kind == AiAttachmentKind.IMAGE.name) { "附件不是图片。" }
        val file = resolve(attachment.relativePath)
        require(file.isFile) { "附加图片已丢失。" }
        "data:${attachment.mimeType};base64,${android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)}"
    }

    suspend fun textContent(attachment: AiAttachmentEntity): String = withContext(Dispatchers.IO) {
        require(attachment.kind == AiAttachmentKind.TEXT.name) { "附件不是文本。" }
        val file = resolve(attachment.relativePath)
        require(file.isFile && file.length() <= MAX_TEXT_FILE_BYTES) { "文本附件已丢失或过大。" }
        val text = file.readText(Charsets.UTF_8)
        require(text.codePointCount(0, text.length) <= MAX_TEXT_CODE_POINTS) { "文本附件超过 20,000 个字符。" }
        text
    }

    fun validateTextTotal(attachments: List<PendingAiAttachment>) {
        validateTextCodePointTotal(attachments.map { it.textCodePoints })
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
        File(legacyRoot, "record_$recordId").deleteRecursively()
    }

    fun discard(attachment: PendingAiAttachment) {
        val file = File(attachment.absolutePath)
        if (runCatching { file.canonicalPath.startsWith(stagingRoot.canonicalPath) }.getOrDefault(false)) file.delete()
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        else -> "text/plain"
    }

    companion object {
        const val MAX_ATTACHMENTS_PER_REQUEST = 3
        const val MAX_TEXT_FILE_BYTES = 1_048_576L
        const val MAX_TEXT_CODE_POINTS = 20_000L
        private const val MAX_IMAGE_EDGE = 1920
        private const val JPEG_QUALITY = 85
        private val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val SUPPORTED_TEXT_MIME_TYPES = setOf("text/plain", "text/markdown", "text/csv", "application/csv", "application/json")

        internal fun isSupportedImage(name: String, mimeType: String): Boolean =
            mimeType.lowercase() in SUPPORTED_IMAGE_MIME_TYPES ||
                name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")

        internal fun isSupportedText(name: String, mimeType: String): Boolean =
            mimeType.lowercase() in SUPPORTED_TEXT_MIME_TYPES ||
                name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "markdown", "csv", "json")

        internal fun validateTextFileSize(sizeBytes: Long) {
            require(sizeBytes in 0..MAX_TEXT_FILE_BYTES) { "单个文本附件不能超过 1 MiB。" }
        }

        internal fun decodeUtf8(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { throw IllegalArgumentException("文本附件必须使用 UTF-8 编码。", it) }
        }

        internal fun validateTextCodePointTotal(counts: Iterable<Int>) {
            val total = counts.sumOf(Int::toLong)
            require(total <= MAX_TEXT_CODE_POINTS) { "所有文本附件合计最多 20,000 个字符。" }
        }
    }
}
