package com.cmhr.listen.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

class StreamingWavRecorder(private val file: File) : Closeable {
    private val output: RandomAccessFile
    var totalFrames: Long = 0; private set

    init {
        file.parentFile?.mkdirs()
        output = RandomAccessFile(file, "rw")
        output.setLength(0)
        output.write(ByteArray(HEADER_BYTES.toInt()))
    }

    fun append(pcm: ByteArray) {
        require(pcm.size % PcmRecorder.BYTES_PER_SAMPLE == 0) { "PCM 数据必须包含完整采样。" }
        output.write(pcm)
        totalFrames += pcm.size / PcmRecorder.BYTES_PER_SAMPLE
    }

    fun sync() = output.fd.sync()

    fun finish(): File {
        writeHeader(output, totalFrames * PcmRecorder.BYTES_PER_SAMPLE)
        output.fd.sync()
        output.close()
        return file
    }

    override fun close() = runCatching { output.close() }.let { Unit }

    companion object {
        const val HEADER_BYTES = 44L
        fun framesIn(file: File): Long = ((file.length() - HEADER_BYTES).coerceAtLeast(0) / PcmRecorder.BYTES_PER_SAMPLE)
        fun repair(file: File): Boolean {
            val dataBytes = (file.length() - HEADER_BYTES).coerceAtLeast(0) / PcmRecorder.BYTES_PER_SAMPLE * PcmRecorder.BYTES_PER_SAMPLE
            if (dataBytes <= 0) return false
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(HEADER_BYTES + dataBytes)
                writeHeader(raf, dataBytes)
                raf.fd.sync()
            }
            return true
        }

        private fun writeHeader(raf: RandomAccessFile, dataBytes: Long) {
            require(dataBytes <= 0xffff_ffffL - 36L) { "WAV 文件超过 4 GiB 限制。" }
            raf.seek(0)
            raf.writeBytes("RIFF"); raf.writeLeInt((36L + dataBytes).toInt()); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); raf.writeLeInt(16); raf.writeLeShort(1); raf.writeLeShort(PcmRecorder.CHANNEL_COUNT)
            raf.writeLeInt(PcmRecorder.SAMPLE_RATE_HZ)
            raf.writeLeInt(PcmRecorder.SAMPLE_RATE_HZ * PcmRecorder.CHANNEL_COUNT * PcmRecorder.BYTES_PER_SAMPLE)
            raf.writeLeShort(PcmRecorder.CHANNEL_COUNT * PcmRecorder.BYTES_PER_SAMPLE)
            raf.writeLeShort(PcmRecorder.BITS_PER_SAMPLE)
            raf.writeBytes("data"); raf.writeLeInt(dataBytes.toInt())
        }

        private fun RandomAccessFile.writeLeInt(value: Int) {
            write(value); write(value ushr 8); write(value ushr 16); write(value ushr 24)
        }
        private fun RandomAccessFile.writeLeShort(value: Int) { write(value); write(value ushr 8) }
    }
}
