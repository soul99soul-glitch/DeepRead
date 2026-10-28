package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.toMessageNode
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import app.amber.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ChatTimelineEffectsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun queueDrainKeepsHistoricalPositionAndDirectSendReturnsToTail() {
        var conversation by mutableStateOf(Conversation(
            assistantId = Uuid.random(),
            messageNodes = List(12) { UIMessage.user("message $it").toMessageNode() },
        ))
        var pending by mutableStateOf(listOf(
            PendingUserMessage("first", listOf(UIMessagePart.Text("first queued"))),
            PendingUserMessage("second", listOf(UIMessagePart.Text("second queued"))),
        ))
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            // Match the production parameter boundary: a retained lazy key provider
            // must use the same pending snapshot as the plan it was composed with.
            val timelineConversation = conversation
            val timelinePendingMessages = pending
            val plan = rememberChatTimelinePlan(
                timelineConversation, emptyList(), true, false, false, false, timelinePendingMessages.size,
            )
            ChatTimelineScrollEffects(
                timelineConversation, timelinePendingMessages, false, ConversationTimelineLoadState(), plan,
                emptyList(), state, PaddingValues(), Settings(), {}, {},
            )
            LazyColumn(state = state, reverseLayout = true, modifier = Modifier.size(320.dp, 300.dp)) {
                items(
                    count = plan.entries.size,
                    key = {
                        plan.entries[it].lazyItemKey(
                            timelineConversation.id, plan.postSendState, timelinePendingMessages, "compact",
                        )
                    },
                ) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { state.requestScrollToItem(7) }
        compose.waitForIdle()
        val anchor = state.layoutInfo.visibleItemsInfo.first()
        compose.runOnIdle {
            pending = pending.drop(1)
            conversation = conversation.copy(
                messageNodes = conversation.messageNodes + UIMessage.user("drained queued input").toMessageNode(),
            )
        }
        compose.waitForIdle()
        val afterDrain = state.layoutInfo.visibleItemsInfo.first { it.key == anchor.key }
        assertTrue("draining queued input must not return a history reader to index 0", state.firstVisibleItemIndex > 0)
        assertEquals("the visible history key keeps its offset", anchor.offset, afterDrain.offset)

        compose.runOnIdle { pending = emptyList() }
        compose.waitForIdle()
        compose.runOnIdle {
            conversation = conversation.copy(
                messageNodes = conversation.messageNodes + UIMessage.user("direct send").toMessageNode(),
            )
        }
        compose.waitForIdle()
        assertEquals("a direct send explicitly returns the user to the tail", 0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun completedStreamKeepsTailReserveUntilAnotherMessageReplacesIt() {
        var loading by mutableStateOf(true)
        var latestMessageId by mutableStateOf(Uuid.random())
        lateinit var presentation: TimelineTailPresentation
        val conversationId = Uuid.random()
        compose.setContent {
            presentation = rememberTimelineTailPresentation(conversationId, latestMessageId, loading, true)
        }
        compose.waitForIdle()
        assertTrue(presentation.reserveVisible)
        assertTrue(presentation.dotVisible)
        compose.runOnIdle { loading = false }
        compose.waitForIdle()
        assertTrue("the end of streaming preserves the occupied tail height", presentation.reserveVisible)
        assertFalse(presentation.dotVisible)
        compose.runOnIdle { latestMessageId = Uuid.random() }
        compose.waitForIdle()
        assertFalse("a replacement tail releases the previous indicator reserve", presentation.reserveVisible)
    }
}
