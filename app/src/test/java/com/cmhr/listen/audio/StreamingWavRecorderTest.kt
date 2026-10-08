package com.cmhr.listen.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StreamingWavRecorderTest {
    @Test fun streamsAndFinalizesPcmWithoutBufferingWholeRecording() {
        val directory = Files.createTempDirectory("listen-wav").toFile()
        val file = File(directory, "capture.wav.part")
        val pcm = ByteArray(PcmRecorder.CHUNK_BYTES) { it.toByte() }
        StreamingWavRecorder(file).use { writer ->
            repeat(3) { writer.append(pcm) }
            assertEquals(3L * PcmRecorder.CHUNK_SAMPLES, writer.totalFrames)
            writer.finish()
        }
        assertEquals(44L + pcm.size * 3, file.length())
        assertEquals(3L * PcmRecorder.CHUNK_SAMPLES, StreamingWavRecorder.framesIn(file))
        assertArrayEquals("RIFF".toByteArray(), file.readBytes().copyOfRange(0, 4))
        directory.deleteRecursively()
    }

    @Test fun repairsInterruptedFileAndDropsIncompleteSampleByte() {
        val directory = Files.createTempDirectory("listen-wav-repair").toFile()
        val file = File(directory, "capture.wav.part")
        file.writeBytes(ByteArray(44 + 101))
        assertTrue(StreamingWavRecorder.repair(file))
        assertEquals(44L + 100L, file.length())
        assertEquals(50, StreamingWavRecorder.framesIn(file))
        directory.deleteRecursively()
    }

    @Test fun checkpointLeavesAValidWavEvenIfFinishNeverRuns() {
        val directory = Files.createTempDirectory("listen-wav-checkpoint").toFile()
        val file = File(directory, "capture.wav")
        val pcm = ByteArray(PcmRecorder.CHUNK_BYTES) { 7 }
        val writer = StreamingWavRecorder(file)
        repeat(2) { writer.append(pcm) }
        writer.checkpoint()
        writer.append(pcm) // after the checkpoint: may or may not survive a kill
        // Simulate process death: no finish(), no close().
        val bytes = file.readBytes()
        val headerDataBytes = java.nio.ByteBuffer.wrap(bytes, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(2L * PcmRecorder.CHUNK_SAMPLES, writer.checkpointedFrames)
        assertEquals(2 * pcm.size, headerDataBytes)
        assertArrayEquals("WAVE".toByteArray(), bytes.copyOfRange(8, 12))
        assertTrue(file.length() >= 44L + 2 * pcm.size)
        writer.close()
        directory.deleteRecursively()
    }

    @Test fun appendAfterCheckpointContinuesAtTheEnd() {
        val directory = Files.createTempDirectory("listen-wav-continue").toFile()
        val file = File(directory, "capture.wav")
        val first = ByteArray(PcmRecorder.CHUNK_BYTES) { 1 }
        val second = ByteArray(PcmRecorder.CHUNK_BYTES) { 2 }
        StreamingWavRecorder(file).use { writer ->
            writer.append(first)
            writer.checkpoint()
            writer.append(second)
            writer.finish()
        }
        val bytes = file.readBytes()
        assertEquals(44L + first.size + second.size, file.length())
        assertArrayEquals(first, bytes.copyOfRange(44, 44 + first.size))
        assertArrayEquals(second, bytes.copyOfRange(44 + first.size, bytes.size))
        directory.deleteRecursively()
    }
}
