package com.cmhr.listen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.ui.theme.ListenTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowLatestTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var listState: LazyListState
    private lateinit var follow: FollowLatestState
    private lateinit var scope: CoroutineScope
    private var lastParagraphLines by mutableIntStateOf(3)

    /** Like the class page: a few cards, then one paragraph item that grows line by line. */
    private fun setTranscript(enabled: Boolean = true) {
        composeRule.setContent {
            ListenTheme {
                listState = rememberLazyListState()
                scope = rememberCoroutineScope()
                follow = rememberFollowLatest(listState, enabled)
                LazyColumn(Modifier.height(400.dp).fillMaxWidth().testTag("transcript"), state = listState) {
                    items(5) { Text("earlier paragraph $it", Modifier.height(120.dp)) }
                    item("last") {
                        Column { repeat(lastParagraphLines) { Text("line $it", Modifier.height(48.dp)) } }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun scrollToBottomReachesTheEndOfAParagraphTallerThanTheScreen() {
        lastParagraphLines = 40 // ~1900dp, far taller than the 400dp viewport
        setTranscript(enabled = false)
        composeRule.runOnIdle { scope.launch { listState.scrollToBottom() } }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse("still above the bottom", listState.canScrollForward) }
    }

    @Test
    fun followingStaysAtTheBottomWhileTheLastParagraphGrows() {
        setTranscript()
        repeat(6) {
            composeRule.runOnIdle { lastParagraphLines += 5 }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertFalse("fell behind after growth ${it + 1}", listState.canScrollForward) }
        }
    }

    @Test
    fun draggingUpStopsFollowingUntilTheUserReturns() {
        lastParagraphLines = 20
        setTranscript()
        composeRule.runOnIdle { assertFalse(listState.canScrollForward) }
        composeRule.onNodeWithTag("transcript").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { lastParagraphLines += 5 }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(follow.following)
            assertTrue("reading position was pulled away", listState.canScrollForward)
        }
        composeRule.runOnIdle { follow.resume(); scope.launch { listState.scrollToBottom() } }
        composeRule.waitForIdle()
        composeRule.runOnIdle { lastParagraphLines += 5 }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(follow.following)
            assertFalse(listState.canScrollForward)
        }
    }
}
