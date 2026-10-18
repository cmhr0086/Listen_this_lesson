package com.cmhr.listen.ui

private val BULLET_WITHOUT_SPACE = Regex("^(\\s*)-(?=[^\\s\\-])")
private val ORDERED_WITHOUT_SPACE = Regex("^(\\s*\\d{1,3})\\.(?=[^\\d\\s.])")

/**
 * Repairs list markers in AI text saved before the streaming client kept whitespace-only chunks:
 * `-要点` and `1.要点` lost the space Markdown needs to see a list. Code fences are left alone.
 */
internal fun normalizeAiMarkdown(markdown: String): String {
    var inFence = false
    return markdown.lineSequence().joinToString("\n") { line ->
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            line
        } else if (inFence) line
        else line
            .replace(BULLET_WITHOUT_SPACE, "$1- ")
            .replace(ORDERED_WITHOUT_SPACE, "$1. ")
    }
}
