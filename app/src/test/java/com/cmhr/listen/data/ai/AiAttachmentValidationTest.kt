package com.cmhr.listen.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAttachmentValidationTest {
    @Test
    fun `supported image and text types accept mime or extension`() {
        assertTrue(AiAttachmentStore.isSupportedImage("照片.bin", "image/jpeg"))
        assertTrue(AiAttachmentStore.isSupportedImage("照片.webp", "application/octet-stream"))
        assertTrue(AiAttachmentStore.isSupportedText("笔记.md", "application/octet-stream"))
        assertTrue(AiAttachmentStore.isSupportedText("数据.bin", "application/json"))
        assertFalse(AiAttachmentStore.isSupportedText("文档.pdf", "application/pdf"))
    }

    @Test
    fun `text attachment requires valid UTF-8 and one MiB size limit`() {
        assertEquals("课堂笔记", AiAttachmentStore.decodeUtf8("课堂笔记".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            AiAttachmentStore.decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28))
        }
        AiAttachmentStore.validateTextFileSize(AiAttachmentStore.MAX_TEXT_FILE_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            AiAttachmentStore.validateTextFileSize(AiAttachmentStore.MAX_TEXT_FILE_BYTES + 1)
        }
    }

    @Test
    fun `combined text attachments enforce code point limit`() {
        AiAttachmentStore.validateTextCodePointTotal(listOf(10_000, 10_000))
        assertThrows(IllegalArgumentException::class.java) {
            AiAttachmentStore.validateTextCodePointTotal(listOf(10_000, 10_001))
        }
    }
}
