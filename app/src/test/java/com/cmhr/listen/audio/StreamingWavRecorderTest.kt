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
}
