package com.cmhr.listen.data.stt

object TranscriptTextFilter {
    private val fillerCharacters = setOf('嗯', '啊', '哦', '噢', '呃', '额', '唔', '哎', '唉', '诶', '欸', '哼')

    fun isFillerOnly(text: String): Boolean {
        val normalized = text.filterNot { character ->
            character.isWhitespace() || Character.getType(character).let { type ->
                type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                    type == Character.DASH_PUNCTUATION.toInt() ||
                    type == Character.START_PUNCTUATION.toInt() ||
                    type == Character.END_PUNCTUATION.toInt() ||
                    type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                    type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                    type == Character.OTHER_PUNCTUATION.toInt() ||
                    type == Character.MATH_SYMBOL.toInt() ||
                    type == Character.MODIFIER_SYMBOL.toInt() ||
                    type == Character.OTHER_SYMBOL.toInt()
            }
        }
        return normalized.isNotEmpty() && normalized.first() in fillerCharacters && normalized.all { it == normalized.first() }
    }
}
