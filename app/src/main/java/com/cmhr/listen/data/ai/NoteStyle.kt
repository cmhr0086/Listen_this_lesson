package com.cmhr.listen.data.ai

import com.cmhr.listen.data.settings.AiPromptSettings

/** What a set of class notes is for; each adds its own instructions to the notes prompt. */
enum class NoteStyle(val label: String, val description: String) {
    DETAILED("详细讲义", "按讲课顺序，保留讲解过程、推导和例题"),
    OUTLINE("复习提纲", "考点、公式、易错点和考试信息，精炼好背"),
    FRAMEWORK("知识框架", "按概念层级梳理，看清一章的脉络"),
    QA_CARDS("问答卡片", "拆成一问一答，方便自测");

    fun prompt(prompts: AiPromptSettings): String = when (this) {
        DETAILED -> prompts.noteStyleDetailed
        OUTLINE -> prompts.noteStyleOutline
        FRAMEWORK -> prompts.noteStyleFramework
        QA_CARDS -> prompts.noteStyleQaCards
    }

    companion object {
        val DEFAULT = DETAILED
        private const val MARKER = "本次整理方向："

        /** The full system prompt: shared notes rules, then this style's instructions. */
        fun buildPrompt(style: NoteStyle, prompts: AiPromptSettings): String =
            "${prompts.organizeNotes.trim()}\n\n$MARKER${style.label}\n${style.prompt(prompts).trim()}"

        /** Style a stored notes prompt was built with; null for notes made before styles existed. */
        fun fromPrompt(requestPrompt: String): NoteStyle? {
            val label = requestPrompt.substringAfter(MARKER, "").lineSequence().firstOrNull()?.trim() ?: return null
            return entries.firstOrNull { it.label == label }
        }

        fun fromName(name: String?): NoteStyle? = entries.firstOrNull { it.name == name }
    }
}
