package com.cmhr.listen.data.ai

import com.cmhr.listen.AiViewModel
import com.cmhr.listen.data.settings.AiPromptSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteStyleTest {
    @Test
    fun `notes prompt joins shared rules and the style, and the style reads back`() {
        val prompts = AiPromptSettings()
        NoteStyle.entries.forEach { style ->
            val prompt = AiViewModel.buildActionPrompt(AiActionType.ORGANIZE_NOTES, prompts, noteStyle = style)
            assertTrue(prompt.startsWith(prompts.organizeNotes))
            assertTrue(prompt.endsWith(style.prompt(prompts)))
            assertEquals(style, NoteStyle.fromPrompt(prompt))
        }
    }

    @Test
    fun `notes made before styles have no style`() {
        assertNull(NoteStyle.fromPrompt("你是课堂笔记整理助手。只依据原文，按主题、关键要点和专业术语整理成中文笔记；保留重要限定条件，不得虚构。"))
    }
}
