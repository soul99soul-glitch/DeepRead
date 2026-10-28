package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatStreamingHandoverTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pinnedOrInvisibleIdleViewportAllowsTheSwap() {
        assertTrue(
            streamingSettleAllowed(
                0, 0, 24,
                itemVisible = true, readingLineInsideItem = true, scrollInProgress = false,
            ),
        )
        assertTrue(
            streamingSettleAllowed(
                0, 24, 24,
                itemVisible = true, readingLineInsideItem = true, scrollInProgress = false,
            ),
        )
        assertTrue(
            streamingSettleAllowed(
                3, 10, 24,
                itemVisible = false, readingLineInsideItem = false, scrollInProgress = false,
            ),
        )
    }

    @Test fun idleReadingLineInsideTheReplyDefersTheSwap() {
        assertFalse(
            streamingSettleAllowed(
                0, 25, 24,
                itemVisible = true, readingLineInsideItem = true, scrollInProgress = false,
            ),
        )
        assertFalse(
            streamingSettleAllowed(
                1, 300, 24,
                itemVisible = true, readingLineInsideItem = true, scrollInProgress = false,
            ),
        )
    }

    @Test fun idleOlderAnchorAllowsImmediateSwap() {
        assertTrue(
            streamingSettleAllowed(
                0, 25, 24,
                itemVisible = true, readingLineInsideItem = false, scrollInProgress = false,
            ),
        )
        assertTrue(
            streamingSettleAllowed(
                1, 300, 24,
                itemVisible = true, readingLineInsideItem = false, scrollInProgress = false,
            ),
        )
    }

    @Test fun inProgressGestureDefersTheSwap() {
        assertFalse(
            streamingSettleAllowed(
                0, 0, 24,
                itemVisible = true, readingLineInsideItem = false, scrollInProgress = true,
            ),
        )
        assertFalse(
            streamingSettleAllowed(
                1, 300, 24,
                itemVisible = true, readingLineInsideItem = true, scrollInProgress = true,
            ),
        )
        assertFalse(
            streamingSettleAllowed(
                3, 10, 24,
                itemVisible = false, readingLineInsideItem = false, scrollInProgress = true,
            ),
        )
    }

    /**
     * Real reverseLayout LazyColumn: the gate reads the same layout info the
     * timeline does. A visible message's height change moves the content
     * above it unless held; with same-frame hold, idle off-bottom swaps are
     * allowed.
     */
    @Test fun gateAndHoldFollowReverseLayoutGeometry() {
        var messageHeight by mutableIntStateOf(100)
        var held = true
        lateinit var state: LazyListState
        var followPx = 0
        compose.setContent {
            state = rememberLazyListState()
            followPx = with(LocalDensity.current) { 24.dp.roundToPx() }
            LazyColumn(
                Modifier.height(400.dp),
                state = state,
                reverseLayout = true,
                contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp),
            ) {
                items(40, key = { it }) { index ->
                    val height = when (index) {
                        0 -> 40
                        1 -> messageHeight
                        else -> 120
                    }
                    Box(
                        Modifier
                            .then(if (index == 1 && held) Modifier.holdReadingOnTailGrowth(state, 1, followPx) else Modifier)
                            .fillMaxWidth()
                            .height(height.dp),
                    )
                }
            }
        }
        val handover = compose.runOnIdle { TimelineStreamingHandover(state, 1, followPx) }
        compose.runOnIdle { assertTrue("pinned", handover.allowsSettledPresentation()) }

        // Off-bottom with the message fully visible under older content: idle swap is allowed;
        // the hold keeps the content above from moving.
        compose.runOnIdle { runBlocking { state.scrollToItem(0, followPx + 10) } }
        compose.waitForIdle()
        fun olderOffset() = state.layoutInfo.visibleItemsInfo.first { it.index == 2 }.offset
        val before = compose.runOnIdle { assertTrue(handover.allowsSettledPresentation()); olderOffset() }
        // The settled hold restores the content above after a height change...
        compose.runOnIdle { messageHeight = 180 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("held content above moved", before, olderOffset()) }
        // ...while reverseLayout alone moves everything above a resized item.
        held = false
        compose.runOnIdle { messageHeight = 100 }
        compose.waitForIdle()
        val unheldBefore = compose.runOnIdle { olderOffset() }
        compose.runOnIdle { messageHeight = 180 }
        compose.waitForIdle()
        compose.runOnIdle { assertNotEquals(unheldBefore, olderOffset()) }

        // The message spans the viewport (reading line inside it): defer.
        compose.runOnIdle { messageHeight = 2_000 }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { state.scrollToItem(1, 600) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(readingLineInsideItem(state.layoutInfo, 1))
            assertFalse("inside-reply must defer", handover.allowsSettledPresentation())
        }

        // Message entirely below the viewport: nothing visible can move.
        compose.runOnIdle { runBlocking { state.scrollToItem(10, 0) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.layoutInfo.visibleItemsInfo.none { it.index == 1 })
            assertTrue(handover.allowsSettledPresentation())
        }

        // Back to the bottom.
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 0) } }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(handover.allowsSettledPresentation()) }
    }

    @Test
    fun tallReplyOccupyingBottomHalfDoesNotCaptureReadingLineWhenOlderVisibleAtTop() {
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(
                Modifier.height(400.dp),
                state = state,
                reverseLayout = true,
            ) {
                items(12, key = { it }) { index ->
                    val height = when (index) {
                        0 -> 40
                        1 -> 280
                        else -> 120
                    }
                    Box(Modifier.fillMaxWidth().height(height.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val info = state.layoutInfo
            val reply = info.visibleItemsInfo.first { it.key == 1 }
            val older = info.visibleItemsInfo.first { it.key == 2 }
            val midpoint = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val line = viewportReadingLine(info)
            assertTrue(
                "midpoint ($midpoint) is the 4c false-inside case; reply=${reply.offset}/${reply.size}",
                midpoint >= reply.offset && midpoint < reply.offset + reply.size,
            )
            assertTrue(
                "older item must contain the visual-top reading line ($line) older=${older.offset}/${older.size}",
                line >= older.offset && line < older.offset + older.size,
            )
            assertFalse(
                "tall reply must not own the reading line ($line) reply=${reply.offset}/${reply.size}",
                readingLineInsideItem(info, 1),
            )
            assertTrue(readingLineInsideItem(info, 2))
            val handover = TimelineStreamingHandover(state, 1, 24)
            assertTrue("older-anchor must swap immediately", handover.allowsSettledPresentation())
        }
    }
}
