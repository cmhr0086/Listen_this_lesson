package com.cmhr.listen.recording

import android.annotation.SuppressLint
import android.content.Context
import androidx.room.withTransaction
import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.VadConfig
import com.cmhr.listen.audio.VadSegmenter
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.recording.RecordingChunkEntity
import com.cmhr.listen.data.recording.RecordingChunkState
import com.cmhr.listen.data.recording.RecordingRepository
import com.cmhr.listen.data.recording.RecordingState
import com.cmhr.listen.data.settings.VadConfigRepository
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.stt.AsrPromptPolicy
import com.cmhr.listen.data.stt.AsrLifecycleState
import com.cmhr.listen.data.stt.AsrQueueRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class OfflineRecognitionState(
    val activeRecordingId: String? = null,
    val recordId: Long? = null,
    val processedFrames: Long = 0,
    val totalFrames: Long = 0,
    val error: String? = null
) { val isProcessing: Boolean get() = activeRecordingId != null }

@SuppressLint("StaticFieldLeak") // Only the application context is retained by the process runtime.
class OfflineRecognitionRuntime private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = ListenDatabase.get(context)
    private val dao = database.recordingDao()
    private val recordings = RecordingRepository(context, database)
    private val courses = CourseRepository(database)
    private val asr = AsrQueueRuntime.get(context)
    private val vadSettings = VadConfigRepository(context)
    private var job: Job? = null
    private val _state = MutableStateFlow(OfflineRecognitionState())
    val state: StateFlow<OfflineRecognitionState> = _state.asStateFlow()

    fun start(recordingId: String) {
        val operationOwner = "offline:$recordingId"
        if (job?.isActive == true || !RecordingOperationGuard.tryAcquire(operationOwner)) {
            _state.value = _state.value.copy(error = "当前已有录音或识别任务正在运行。")
            return
        }
        job = scope.launch {
            val runId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            if (dao.claimProcessing(recordingId, runId, now) == 0) {
                _state.value = _state.value.copy(error = "该录音已在处理或当前状态不可识别。")
                RecordingOperationGuard.release(operationOwner)
                return@launch
            }
            var recording = requireNotNull(dao.recording(recordingId))
            _state.value = OfflineRecognitionState(recordingId, recording.recordId, recording.processedFrames, recording.totalFrames)
            RecognitionForegroundService.start(context, recording.recordId, recordingId)
            try {
                asr.awaitPendingPersistence()
                val frozenConfig = recording.vadConfigSnapshot?.let(::decodeVad) ?: vadSettings.config.first().validated()
                while (recording.processedFrames < recording.totalFrames) {
                    ensureSessionExists(recording.recordId)
                    val windowStart = recording.activeWindowStartFrame ?: recording.processedFrames
                    val windowEnd = recording.activeWindowEndFrame ?: OfflineWindowPolicy.coreEnd(windowStart, recording.totalFrames)
                    var chunks = dao.chunksForWindow(recordingId, windowStart)
                    if (chunks.isEmpty()) {
                        val planned = planWindow(recording, windowStart, windowEnd, frozenConfig)
                        database.withTransaction {
                            check(dao.setActiveWindow(recordingId, runId, windowStart, windowEnd, encodeVad(frozenConfig), System.currentTimeMillis()) > 0)
                            dao.insertChunks(planned)
                        }
                        chunks = dao.chunksForWindow(recordingId, windowStart)
                    }
                    for (chunk in chunks) processChunk(recording, chunk)
                    check(dao.advanceWindow(recordingId, runId, windowEnd, System.currentTimeMillis()) > 0)
                    recording = requireNotNull(dao.recording(recordingId))
                    _state.value = _state.value.copy(processedFrames = recording.processedFrames)
                }
                dao.complete(recordingId, runId, System.currentTimeMillis())
                _state.value = OfflineRecognitionState()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { dao.fail(recordingId, System.currentTimeMillis(), "识别已停止，可稍后继续。") }
                throw cancelled
            } catch (error: Exception) {
                dao.fail(recordingId, System.currentTimeMillis(), error.message ?: "补识别失败，可稍后继续。")
                _state.value = _state.value.copy(activeRecordingId = null, error = error.message ?: "补识别失败，可稍后继续。")
            } finally {
                RecognitionForegroundService.stop(context)
                RecordingOperationGuard.release(operationOwner)
                job = null
            }
        }
    }

    fun stop() { job?.cancel() }
    fun isProcessingRecord(recordId: Long): Boolean = _state.value.recordId == recordId && _state.value.isProcessing

    private suspend fun processChunk(recording: com.cmhr.listen.data.recording.RecordingEntity, chunk: RecordingChunkEntity) {
        ensureSessionExists(recording.recordId)
        var diagnostic = asr.diagnostic(chunk.chunkId)
        when (diagnostic?.lifecycleState) {
            AsrLifecycleState.COMPLETED, AsrLifecycleState.DROPPED -> {
                dao.updateChunkState(chunk.chunkId, RecordingChunkState.COMPLETED.name); return
            }
            AsrLifecycleState.SUBMISSION_UNKNOWN -> {
                dao.updateChunkState(chunk.chunkId, RecordingChunkState.SUBMISSION_UNKNOWN.name, diagnostic.safeErrorMessage)
                error(diagnostic.safeErrorMessage ?: "存在无法确认是否提交成功的片段，请在 ASR 诊断中确认。")
            }
            AsrLifecycleState.FAILED -> {
                if (!asr.retryFailed(chunk.chunkId)) error(diagnostic.safeErrorMessage ?: "片段识别失败。")
            }
            null -> {
                val pcm = readPcm(recording, chunk.startFrame, chunk.endFrame)
                val startTime = OfflineWindowPolicy.timestamp(recording.startedAt, chunk.startFrame)
                val endTime = OfflineWindowPolicy.timestamp(recording.startedAt, chunk.endFrame)
                check(asr.persistAndEnqueuePreallocated(chunk.chunkId, recording.recordId, chunk.sequenceNumber, startTime, endTime, pcm, chunk.contextSnapshot)) {
                    "无法持久化待识别片段。"
                }
                dao.updateChunkState(chunk.chunkId, RecordingChunkState.QUEUED.name)
            }
            else -> Unit
        }
        while (true) {
            ensureSessionExists(recording.recordId)
            diagnostic = asr.diagnostic(chunk.chunkId)
            when (diagnostic?.lifecycleState) {
                AsrLifecycleState.COMPLETED, AsrLifecycleState.DROPPED -> {
                    dao.updateChunkState(chunk.chunkId, RecordingChunkState.COMPLETED.name); return
                }
                AsrLifecycleState.FAILED -> {
                    dao.updateChunkState(chunk.chunkId, RecordingChunkState.FAILED.name, diagnostic.safeErrorMessage)
                    error(diagnostic.safeErrorMessage ?: "片段识别失败。")
                }
                AsrLifecycleState.SUBMISSION_UNKNOWN -> {
                    dao.updateChunkState(chunk.chunkId, RecordingChunkState.SUBMISSION_UNKNOWN.name, diagnostic.safeErrorMessage)
                    error(diagnostic.safeErrorMessage ?: "无法确认片段是否提交成功。")
                }
                else -> delay(500)
            }
        }
    }

    private suspend fun planWindow(
        recording: com.cmhr.listen.data.recording.RecordingEntity,
        coreStart: Long,
        coreEnd: Long,
        config: VadConfig
    ): List<RecordingChunkEntity> {
        val analysisRange = OfflineWindowPolicy.analysisRange(coreStart, coreEnd, recording.totalFrames, config)
        val analysisStart = analysisRange.first
        val analysisEnd = analysisRange.last + 1
        val captured = mutableListOf<com.cmhr.listen.audio.CapturedPcmSegment>()
        VadSegmenter(context.assets) { config }.use { vad ->
            RandomAccessFile(File(recordings.directory, recording.localPath), "r").use { input ->
                input.seek(com.cmhr.listen.audio.StreamingWavRecorder.HEADER_BYTES + analysisStart * PcmRecorder.BYTES_PER_SAMPLE)
                var remaining = analysisEnd - analysisStart
                while (remaining > 0) {
                    val wantedFrames = minOf(PcmRecorder.CHUNK_SAMPLES.toLong(), remaining).toInt()
                    val raw = ByteArray(wantedFrames * PcmRecorder.BYTES_PER_SAMPLE)
                    input.readFully(raw)
                    val feed = if (wantedFrames == PcmRecorder.CHUNK_SAMPLES) raw else raw.copyOf(PcmRecorder.CHUNK_BYTES)
                    captured += vad.acceptPcm(feed).completedSegments
                    remaining -= wantedFrames
                }
                if (analysisEnd == recording.totalFrames) captured += vad.finish().completedSegments
            }
        }
        val baseSequence = maxOf(asr.nextSequenceNumber(recording.recordId), database.transcriptDao().nextSequenceNumber(recording.recordId))
        val record = courses.record(recording.recordId).first()
        val course = record?.let { courses.course(it.courseId).first() }
        val appSettings = AppSettingsRepository(context).settings.first()
        return captured.filter { OfflineWindowPolicy.owns(analysisStart + it.ownershipSample, coreStart, coreEnd) }.mapIndexed { index, segment ->
            val start = (analysisStart + segment.pcmSlice.startSample).coerceIn(0, recording.totalFrames)
            val end = (analysisStart + segment.pcmSlice.endSample).coerceIn(start, recording.totalFrames)
            val durationMs = (end - start) * 1_000 / PcmRecorder.SAMPLE_RATE_HZ
            val prompt = AsrPromptPolicy.decide(
                globalMode = appSettings.globalAsrPromptMode,
                courseOverride = course?.asrPromptModeOverride,
                coursePrompt = course?.asrPrompt.orEmpty(),
                quality = segment.quality.copy(audioDurationMs = durationMs),
                config = appSettings.asrPromptAutoConfig
            ).prompt
            RecordingChunkEntity(
                recordingId = recording.recordingId,
                windowStartFrame = coreStart,
                windowEndFrame = coreEnd,
                startFrame = start,
                endFrame = end,
                sequenceNumber = baseSequence + index,
                contextSnapshot = prompt
            )
        }.filter { it.endFrame > it.startFrame }
    }

    private fun readPcm(recording: com.cmhr.listen.data.recording.RecordingEntity, start: Long, end: Long): ByteArray {
        val size = ((end - start) * PcmRecorder.BYTES_PER_SAMPLE).toInt()
        return ByteArray(size).also { bytes ->
            RandomAccessFile(File(recordings.directory, recording.localPath), "r").use { input ->
                input.seek(com.cmhr.listen.audio.StreamingWavRecorder.HEADER_BYTES + start * PcmRecorder.BYTES_PER_SAMPLE)
                input.readFully(bytes)
            }
        }
    }

    private suspend fun ensureSessionExists(recordId: Long) {
        check(courses.record(recordId).first() != null) { "课堂记录已删除，补识别已停止。" }
    }

    companion object {
        private fun encodeVad(v: VadConfig) = listOf(v.threshold, v.startConfirmMs, v.endSilenceMs, v.preRollMs, v.postRollMs, v.minSegmentMs, v.softLimitMs, v.hardLimitMs, v.overlapMs).joinToString(",")
        private fun decodeVad(value: String): VadConfig = value.split(',').let { p -> VadConfig(p[0].toFloat(), p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong(), p[7].toLong(), p[8].toLong()).validated() }
        @Volatile private var instance: OfflineRecognitionRuntime? = null
        fun get(context: Context): OfflineRecognitionRuntime = instance ?: synchronized(this) {
            instance ?: OfflineRecognitionRuntime(context.applicationContext).also { instance = it }
        }
    }
}
