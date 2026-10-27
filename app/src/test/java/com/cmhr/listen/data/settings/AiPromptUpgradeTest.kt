package com.cmhr.listen.data.settings

import com.cmhr.listen.data.ai.DEFAULT_CONVERSATION_PROMPT
import com.cmhr.listen.data.ai.LEGACY_CONVERSATION_PROMPT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiPromptUpgradeTest {
    @Test
    fun savedChatsWithTheOldPromptsGetTheNewOnes() {
        val prompts = AiPromptSettings()
        val followUp = "$LEGACY_CONVERSATION_PROMPT\n\n原始处理任务：$LEGACY_QUICK_ANSWER_PROMPT"

        val upgraded = prompts.upgradeLegacyPrompts(followUp)

        assertEquals("$DEFAULT_CONVERSATION_PROMPT\n\n原始处理任务：$DEFAULT_QUICK_ANSWER_PROMPT", upgraded)
        assertFalse(upgraded.contains("若原文不足以回答，应明确说明"))
    }

    @Test
    fun customPromptsAreLeftAlone() {
        val custom = "只回答原文里有的内容。"
        assertEquals(custom, AiPromptSettings().upgradeLegacyPrompts(custom))
    }

    @Test
    fun upgradeUsesTheReadersOwnCurrentPrompt() {
        val prompts = AiPromptSettings(customConversation = "我的问答提示词")
        assertEquals("我的问答提示词", prompts.upgradeLegacyPrompts(LEGACY_CONVERSATION_PROMPT))
    }
}
