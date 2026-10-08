package com.cmhr.listen.recording

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import com.cmhr.listen.ListeningForegroundService
import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.StreamingWavRecorder
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingRecovery
import com.cmhr.listen.data.recording.RecordingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal object RecordingOperationGuard {
    private val owner = AtomicReference<String?>(null)
    fun tryAcquire(value: String): Boolean = owner.compareAndSet(null, value)
    fun release(value: String) { owner.compareAndSet(value, null) }
    fun isBusy(): Boolean = owner.get() != null
}

enum class CaptureMode {
    REALTIME_ASR,
    RECORD_ONLY;

    val usesAsr: Boolean get() = this == REALTIME_ASR
}

data class CaptureRuntimeState(
    val active: Boolean = false,
    val mode: CaptureMode? = null,
    val recordId: Long? = null,
    val recordingId: String? = null,
    val startedElapsedMs: Long? = null,
    val courseName: String? = null,
    val recordName: String? = null,
    val durationMs: Long = 0,
    val error: String? = null
)

/** Application-owned microphone arbiter. Record-only capture survives UI/navigation recreation. */
@SuppressLint("StaticFieldLeak") // Only the application context is retained by the process runtime.
class ClassroomCaptureRuntime private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val database = ListenDatabase.get(context)
    private val courses = CourseRepository(database)
    private val recordings = RecordingRepository(context, database)
    private val recorder = PcmRecorder()
    private var job: Job? = null
    private val _state = MutableStateFlow(CaptureRuntimeState())
    val state: StateFlow<CaptureRuntimeState> = _state.asStateFlow()

    init { scope.launch { RecordingRecovery.ensure(recordings) } }

    fun tryClaimRealtime(recordId: Long): Boolean {
        if (!RecordingOperationGuard.tryAcquire(REALTIME_OWNER) || !mutex.tryLock()) {
            RecordingOperationGuard.release(REALTIME_OWNER); return false
        }
        _state.value = CaptureRuntimeState(active = true, mode = CaptureMode.REALTIME_ASR, recordId = recordId)
        return true
    }

    fun updateRealtime(courseName: String?, recordName: String?, startedElapsed: Long) {
        if (_state.value.mode == CaptureMode.REALTIME_ASR) {
            _state.value = _state.value.copy(courseName = courseName, recordName = recordName, startedElapsedMs = startedElapsed)
        }
    }

    fun releaseRealtime() {
        if (_state.value.mode != CaptureMode.REALTIME_ASR) return
        _state.value = CaptureRuntimeState()
        if (mutex.isLocked) mutex.unlock()
        RecordingOperationGuard.release(REALTIME_OWNER)
    }

    fun startRecordOnly(recordId: Long) {
        if (!RecordingOperationGuard.tryAcquire(RECORD_ONLY_OWNER) || !mutex.tryLock()) {
            RecordingOperationGuard.release(RECORD_ONLY_OWNER)
            _state.value = _state.value.copy(error = "当前已有录音或识别任务正在运行。")
            return
        }
        job = scope.launch {
            var writer: StreamingWavRecorder? = null
            var row: RecordingEntity? = null
            try {
                RecordingRecovery.ensure(recordings)
                val record = courses.record(recordId).first() ?: error("课堂记录不存在或已被删除。")
                val course = courses.course(record.courseId).first()
                courses.reopenRecord(recordId)
                val created = recordings.create(recordId, record.sessionId).also { row = it }
                val startedElapsed = SystemClock.elapsedRealtime()
                _state.value = CaptureRuntimeState(true, CaptureMode.RECORD_ONLY, recordId, created.recordingId, startedElapsed, course?.name, record.name)
                ListeningForegroundService.startRecordOnly(context, recordId, course?.name, record.name, startedElapsed)
                val activeWriter = StreamingWavRecorder(File(recordings.directory, created.localPath)).also { writer = it }
                var lastCheckpoint = startedElapsed
                recorder.listen(onPcmChunk = { pcm ->
                    activeWriter.append(pcm)
                    val nowElapsed = SystemClock.elapsedRealtime()
                    if (nowElapsed - lastCheckpoint >= CHECKPOINT_MS) {
                        // Header + fsync first, then publish: the saved length shown in the UI is
                        // always audio that survives a process kill.
                        activeWriter.checkpoint()
                        recordings.updateCaptureProgress(created.recordingId, activeWriter.checkpointedFrames)
                        _state.value = _state.value.copy(durationMs = activeWriter.checkpointedFrames * 1_000 / PcmRecorder.SAMPLE_RATE_HZ)
                        lastCheckpoint = nowElapsed
                    }
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "录音失败。")
            } finally {
                withContext(NonCancellable) {
                    val currentRow = row
                    writer?.let { active -> runCatching { active.finish() }.onFailure { active.close() } }
                    if (currentRow != null) {
                        runCatching { recordings.finalizeCapture(currentRow, File(recordings.directory, currentRow.localPath)) }
                    }
                    runCatching { courses.finishRecord(recordId) }
                    ListeningForegroundService.stop(context)
                    _state.value = CaptureRuntimeState(error = _state.value.error)
                    if (mutex.isLocked) mutex.unlock()
                    RecordingOperationGuard.release(RECORD_ONLY_OWNER)
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
    fun isBusy(): Boolean = RecordingOperationGuard.isBusy()

    companion object {
        private const val CHECKPOINT_MS = 2_000L
        private const val REALTIME_OWNER = "realtime_capture"
        private const val RECORD_ONLY_OWNER = "record_only_capture"
        @Volatile private var instance: ClassroomCaptureRuntime? = null
        fun get(context: Context): ClassroomCaptureRuntime = instance ?: synchronized(this) {
            instance ?: ClassroomCaptureRuntime(context.applicationContext).also { instance = it }
        }
    }
}
