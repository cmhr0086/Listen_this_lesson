package com.cmhr.listen.data.recording

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One recording file to copy out, with the name it gets in the export folder. */
data class RecordingExportItem(val file: File, val fileName: String, val bytes: Long)

/** What to export: every class of a course, or one class. */
sealed interface RecordingExportTarget {
    data class Course(val courseId: Long) : RecordingExportTarget
    data class Class(val recordId: Long) : RecordingExportTarget
}

data class RecordingExportPlan(val folderName: String, val items: List<RecordingExportItem>) {
    val totalBytes: Long get() = items.sumOf { it.bytes }
}

/**
 * Copies class recordings (WAV) into a folder the user picked with the system file picker. Files
 * are named by class number, class name and date, so a whole course sorts in teaching order.
 * Recordings still being captured are left out.
 */
class RecordingExporter(
    private val context: Context,
    private val database: ListenDatabase = ListenDatabase.get(context),
    private val directory: File = File(context.filesDir, RecordingRepository.DIRECTORY)
) {
    suspend fun plan(target: RecordingExportTarget): RecordingExportPlan = withContext(Dispatchers.IO) {
        val classes = when (target) {
            is RecordingExportTarget.Course -> database.recordDao().summariesForCourse(target.courseId).first()
            is RecordingExportTarget.Class -> listOfNotNull(database.recordDao().summary(target.recordId).first())
        }
        val courseName = classes.firstOrNull()?.courseName.orEmpty()
        val items = classes.sortedWith(compareBy({ it.session.startedAt }, { it.session.id })).flatMap { summary ->
            val recordings = database.recordingDao().observeForSession(summary.session.id).first()
                .filter { it.recordingState != RecordingState.RECORDING }
                .sortedBy { it.startedAt }
                .mapNotNull { recording -> File(directory, recording.localPath).takeIf { recording.localPath.isNotBlank() && it.isFile } }
            recordings.mapIndexed { index, file ->
                RecordingExportItem(file, exportFileName(summary, index + 1, recordings.size), file.length())
            }
        }
        RecordingExportPlan(safeName("$courseName 录音").ifBlank { "课堂录音" }, items)
    }

    /**
     * Writes [plan] into a new folder under [treeUri]. [progress] gets the number of files done.
     * Cancelling stops between buffers and removes the half-written file.
     */
    suspend fun export(treeUri: Uri, plan: RecordingExportPlan, progress: (done: Int, copiedBytes: Long) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val folder = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, plan.folderName)
                ?: error("无法在所选位置新建文件夹。")
            var copied = 0L
            plan.items.forEachIndexed { index, item ->
                val target = DocumentsContract.createDocument(resolver, folder, "audio/wav", item.fileName)
                    ?: error("无法新建文件「${item.fileName}」。")
                try {
                    item.file.inputStream().use { input ->
                        (resolver.openOutputStream(target) ?: error("无法写入「${item.fileName}」。")).use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                progress(index, copied)
                            }
                        }
                    }
                } catch (exception: Throwable) {
                    runCatching { DocumentsContract.deleteDocument(resolver, target) }
                    throw exception
                }
                progress(index + 1, copied)
            }
            folder
        }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** "第03节 截面的几何性质 2026-10-19.wav", with " 录音2" when a class was recorded in parts. */
        internal fun exportFileName(summary: SessionSummary, part: Int, parts: Int, zone: ZoneId = ZoneId.systemDefault()): String {
            val number = summary.classNumber.takeIf { it > 0 }?.let { "第%02d节 ".format(it) }.orEmpty()
            val date = DATE.format(Instant.ofEpochMilli(summary.session.startedAt).atZone(zone))
            val partLabel = if (parts > 1) " 录音$part" else ""
            return safeName("$number${summary.session.name} $date$partLabel") + ".wav"
        }

        /** Strips characters that file systems or the SAF provider reject. */
        internal fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
    }
}
