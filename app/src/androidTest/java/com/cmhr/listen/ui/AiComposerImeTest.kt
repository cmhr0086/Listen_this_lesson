package com.cmhr.listen.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cmhr.listen.MainActivity
import org.junit.Rule
import org.junit.Test

class AiComposerImeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun attachmentMenuDoesNotFocusInputAndCanBeReopened() {
        composeRule.activity.runOnUiThread {
            val inputMethod = composeRule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethod.hideSoftInputFromWindow(composeRule.activity.window.decorView.windowToken, 0)
            composeRule.activity.window.decorView.clearFocus()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI 会话").performClick()
        composeRule.onNodeWithTag("global-fab").performClick()
        composeRule.onNodeWithText("新对话").assertTextContains("新对话")

        composeRule.onNodeWithTag("composer-input").assertIsNotFocused()
        composeRule.onNodeWithTag("ai-chat-composer-surface").assertExists()
        composeRule.onNodeWithTag("attachment-button").performClick()
        composeRule.onNodeWithTag("composer-input").assertIsNotFocused()
        composeRule.onNodeWithText("选择图片").assertExists()
        composeRule.onNodeWithText("选择文件").assertExists()

        composeRule.onNodeWithTag("attachment-button").performClick()
        composeRule.onNodeWithText("选择图片").assertDoesNotExist()
        composeRule.onNodeWithTag("attachment-button").performClick()
        composeRule.onNodeWithText("选择图片").assertExists()
    }
}
