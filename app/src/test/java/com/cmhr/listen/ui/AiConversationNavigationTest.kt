package com.cmhr.listen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AiConversationNavigationTest {
    @Test
    fun `listening conversation route binds active record`() {
        assertEquals("ai/new/42", newAiConversationRoute(activeRecordId = 42, isListening = true))
    }

    @Test
    fun `idle conversation route remains general`() {
        assertEquals("ai/new", newAiConversationRoute(activeRecordId = 42, isListening = false))
        assertEquals("ai/new", newAiConversationRoute(activeRecordId = null, isListening = true))
    }
}
