package com.cmhr.listen.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PcmRecorder {
    @SuppressLint("MissingPermission") // Permission is checked by MainActivity before listening starts.
    suspend fun listen(
        onPcmChunk: suspend (ByteArray) -> Unit,
        onReadError: (Int) -> Unit = {}
    ) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) { "设备不支持所需的录音格式。" }

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, CHUNK_BYTES * 4))
            .build()

        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风。" }

        return try {
            audioRecord.startRecording()
            while (true) {
                currentCoroutineContext().ensureActive()
                val chunk = ByteArray(CHUNK_BYTES)
                val read = audioRecord.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) onReadError(read)
                check(read > 0) { "读取麦克风数据失败（错误码：$read）。" }
                if (read == chunk.size) onPcmChunk(chunk) else onPcmChunk(chunk.copyOf(read))
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val CHUNK_SAMPLES = 512
        const val CHUNK_BYTES = CHUNK_SAMPLES * BYTES_PER_SAMPLE
    }
}
