package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatTailGrowthHoldTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compensatedScrollTargetWalksToNewerItemsWhenShrinkCrossesZero() {
        val sizes = mapOf(0 to 80, 1 to 200, 2 to 120)
        fun sizeOf(index: Int) = sizes[index]
        assertEquals(0 to 30, compensatedScrollTarget(0, 50, -20, ::sizeOf))
        assertEquals(0 to 0, compensatedScrollTarget(0, 50, -80, ::sizeOf))
        assertEquals(1 to 130, compensatedScrollTarget(2, 30, -100, ::sizeOf))
        assertEquals(0 to 0, compensatedScrollTarget(2, 30, -400, ::sizeOf))
        assertEquals(2 to 80, compensatedScrollTarget(2, 30, 50, ::sizeOf))
    }

    @Test
    fun compensatedScrollTargetWalksToOlderItemsWhenGrowthExceedsFirstVisibleSize() {
        fun sizeOf(index: Int) = mapOf(0 to 40, 1 to 8_000, 2 to 120)[index]
        assertEquals(1 to 70, compensatedScrollTarget(0, 10, 100, ::sizeOf))
        assertEquals(3 to 1_850, compensatedScrollTarget(0, 10, 10_000, ::sizeOf))
        assertEquals(
            1 to 9_970,
            compensatedScrollTarget(0, 10, 10_000) { index ->
                mapOf(0 to 40, 1 to 20_000)[index]
            },
        )
    }

    @Test
    fun idleHoldDoesNotReadLayoutInfoWhenHeightIsUnchanged() {
        assertFalse(idleHoldShouldReadLayout(lastHeight = -1, height = 180, pendingIdleDelta = 0))
        assertFalse(idleHoldShouldReadLayout(lastHeight = 180, height = 180, pendingIdleDelta = 0))
        assertTrue(idleHoldShouldReadLayout(lastHeight = 180, height = 240, pendingIdleDelta = 0))
        assertTrue(idleHoldShouldReadLayout(lastHeight = 180, height = 180, pendingIdleDelta = 12))
    }

    @Test
    fun idleHoldDropsSettledTargetAfterTwoStableFrames() {
        fun drop(
            height: Int = 180,
            pending: Int = 0,
            frames: Int = 2,
            visibleIndex: Int = 0,
            visibleOffset: Int = 0,
            requestedIndex: Int = 0,
            requestedOffset: Int = 80,
        ) = idleHoldShouldDropSettledTarget(
            lastHeight = 180,
            height = height,
            pendingIdleDelta = pending,
            stableFrameCount = frames,
            firstVisibleIndex = visibleIndex,
            firstVisibleOffset = visibleOffset,
            requestedIndex = requestedIndex,
            requestedOffset = requestedOffset,
        )
        assertFalse(drop(height = 240))
        assertFalse(drop(pending = 12))
        assertFalse(drop(frames = 1))
        assertFalse(drop(visibleOffset = 80, requestedOffset = 80))
        assertTrue(drop(visibleOffset = 0, requestedOffset = 80))
        assertTrue(drop(visibleIndex = 0, requestedIndex = 1, visibleOffset = 0, requestedOffset = 80))
    }

    @Test
    fun idleHoldAdoptsExternalScrollToSameIndexNotLag() {
        assertFalse(
            idleHoldShouldAdoptExternalScroll(
                requestedIndex = 1,
                requestedOffset = 80,
                firstVisibleIndex = 1,
                firstVisibleOffset = 40,
                lastSeenOffset = 40,
                applyingHold = false,
            ),
        )
        assertTrue(
            idleHoldShouldAdoptExternalScroll(
                requestedIndex = 1,
                requestedOffset = 80,
                firstVisibleIndex = 1,
                firstVisibleOffset = 200,
                lastSeenOffset = 80,
                applyingHold = false,
            ),
        )
        assertFalse(
            idleHoldShouldAdoptExternalScroll(
                requestedIndex = 1,
                requestedOffset = 80,
                firstVisibleIndex = 1,
                firstVisibleOffset = 200,
                lastSeenOffset = -1,
                applyingHold = false,
            ),
        )
        assertFalse(
            idleHoldShouldAdoptExternalScroll(
                requestedIndex = 1,
                requestedOffset = 80,
                firstVisibleIndex = 1,
                firstVisibleOffset = 200,
                lastSeenOffset = 80,
                applyingHold = true,
            ),
        )
    }

    /**
     * 4c device cancelRegenerate: after a long regenerate the test
     * scrollToItem(0) to expose DEVICE_FINAL_COPY_MARKER. Skipping
     * layoutInfo on stable-height frames left requestedIndex at the
     * in-growth offset, and reconcile yanked the tail marker off screen.
     */
    @Test
    fun idleHoldDoesNotYankScrollToNewestAfterHeightSettles() {
        var streamHeight by mutableIntStateOf(180)
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(8.dp)) }
                item(key = 1) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 1, 24, itemKey = 1)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                items(8, key = { it + 2 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(1, 40) } }
        compose.waitForIdle()
        compose.runOnIdle { streamHeight = 720 }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 0) } }
        compose.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
        assertTrue(
            "idle reconcile yanked scrollToItem(0): offset=${state.firstVisibleItemScrollOffset}",
            state.firstVisibleItemScrollOffset <= 2,
        )
    }

    @Test
    fun idleHoldDoesNotYankProgrammaticScrollToItemOnSameIndexWhileGrowing() {
        var streamHeight by mutableIntStateOf(180)
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(8.dp)) }
                item(key = 1) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 1, 24, itemKey = 1)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                items(8, key = { it + 2 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(1, 40) } }
        compose.waitForIdle()
        compose.runOnIdle { streamHeight = 400 }
        compose.waitForIdle()
        val holdOffset = state.firstVisibleItemScrollOffset
        check(state.firstVisibleItemIndex == 1)
        check(holdOffset > 40) { "hold should have compensated growth: offset=$holdOffset" }
        compose.runOnIdle { runBlocking { state.scrollToItem(1, 200) } }
        compose.waitForIdle()
        compose.runOnIdle { streamHeight = 640 }
        compose.waitForIdle()
        val after = state.firstVisibleItemScrollOffset
        assertEquals(1, state.firstVisibleItemIndex)
        assertTrue(
            "scrollToItem(1, 200) must stay at the target (further growth may add to it): after=$after",
            after >= 198,
        )
        assertTrue(
            "scrollToItem(1, 200) must not return to the pre-scroll hold offset $holdOffset: after=$after",
            kotlin.math.abs(after - holdOffset) > 2,
        )
    }

    @Test
    fun userDragOwnsScrollEvenWhenIdleHoldTargetIsOutstanding() {
        assertTrue(userGestureOwnsScroll(scrollInProgress = true, programmaticScroll = false))
        assertFalse(userGestureOwnsScroll(scrollInProgress = true, programmaticScroll = true))
        assertFalse(userGestureOwnsScroll(scrollInProgress = false, programmaticScroll = false))
        assertFalse(userGestureOwnsScroll(scrollInProgress = false, programmaticScroll = true))
    }

    /**
     * Device completion on 392288d7: LazyList still published first=0/0
     * (pinned) while layoutInfo already listed the growing message at
     * index 2. The hold must read the layout min index, not the stale pair.
     */
    @Test
    fun readingAnchorPrefersLayoutMinIndexOverStalePublishedPinnedPair() {
        val vis = listOf(
            FakeItem(index = 2, key = "msg", offset = -105, size = 19_000),
            FakeItem(index = 3, key = "older", offset = 18_895, size = 200),
        )
        val anchor = readingAnchorFromLayout(
            visibleItems = vis,
            viewportStartOffset = 0,
            publishedIndex = 0,
            publishedOffset = 0,
            requestedIndex = -1,
            requestedOffset = -1,
        )
        assertEquals(2 to 105, anchor)
        assertTrue(
            shouldHoldReadingOnTailGrowth(
                firstIndex = anchor.first,
                firstOffset = anchor.second,
                followBottomPx = 66,
                growingItemVisible = true,
            ),
        )
    }

    @Test
    fun readingAnchorUsesLayoutOffsetWhenPublishedOffsetLagsOnSameIndex() {
        val vis = listOf(
            FakeItem(index = 0, key = "tail", offset = -88, size = 200),
            FakeItem(index = 1, key = "msg", offset = 112, size = 800),
            FakeItem(index = 2, key = "older", offset = 912, size = 200),
        )
        val anchor = readingAnchorFromLayout(
            visibleItems = vis,
            viewportStartOffset = 0,
            publishedIndex = 0,
            publishedOffset = 0,
            requestedIndex = -1,
            requestedOffset = -1,
        )
        assertEquals(0 to 88, anchor)
        assertTrue(
            shouldHoldReadingOnTailGrowth(
                firstIndex = anchor.first,
                firstOffset = anchor.second,
                followBottomPx = 72,
                growingItemVisible = true,
            ),
        )
    }

    @Test
    fun readingAnchorKeepsPinnedWhenLayoutFirstIsTheTail() {
        val vis = listOf(
            FakeItem(index = 0, key = "tail", offset = 0, size = 8),
            FakeItem(index = 1, key = "compact", offset = 8, size = 40),
            FakeItem(index = 2, key = "msg", offset = 48, size = 19_000),
        )
        val anchor = readingAnchorFromLayout(
            visibleItems = vis,
            viewportStartOffset = 0,
            publishedIndex = 0,
            publishedOffset = 0,
            requestedIndex = -1,
            requestedOffset = -1,
        )
        assertEquals(0 to 0, anchor)
        assertFalse(
            shouldHoldReadingOnTailGrowth(
                firstIndex = anchor.first,
                firstOffset = anchor.second,
                followBottomPx = 66,
                growingItemVisible = true,
            ),
        )
    }

    @Test
    fun readingAnchorPrefersInFlightRequestWhenThatIndexIsStillVisible() {
        val vis = listOf(
            FakeItem(index = 2, key = "msg", offset = -171, size = 19_000),
        )
        val anchor = readingAnchorFromLayout(
            visibleItems = vis,
            viewportStartOffset = 0,
            publishedIndex = 0,
            publishedOffset = 0,
            requestedIndex = 2,
            requestedOffset = 171,
        )
        assertEquals(2 to 171, anchor)
    }

    /**
     * Device idle/drag/fling fixtures keep a small reverseLayout tail at
     * index 0. After a short drag, that tail is still first-visible while the
     * growing stream is index 1. Compensation must walk onto the stream
     * instead of clamping offset on the tail (round-4b: -12888px).
     */
    @Test
    fun streamGrowthBelowASmallerFirstVisibleItemDoesNotMoveTheReadingItem() {
        var streamHeight by mutableIntStateOf(200)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                item(key = 1) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 1, 0)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                item(key = 2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 3 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 20) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertTrue(state.firstVisibleItemScrollOffset > 0)
        }
        val start = olderTops.size
        val baseline = olderTops.last()
        compose.runOnIdle { streamHeight = 800 }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px across ${frames.size} passes (baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
    }

    /** Issue #21：补偿滚动跨越 item 回收边界时，不能在 OnPositioned 分发中途改动布局树。 */
    @Test
    fun tailResizeAcrossManyItemsWhileReadingAwayFromBottomDoesNotCrash() {
        var tailHeight by mutableIntStateOf(600)
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(300.dp), state = state, reverseLayout = true) {
                items(300, key = { it }) { index ->
                    val height = if (index == 0) tailHeight else 4
                    Box(
                        Modifier
                            .then(if (index == 0) Modifier.holdReadingOnTailGrowth(state, 0, 0) else Modifier)
                            .fillMaxWidth()
                            .height(height.dp),
                    )
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 400) } }

        // 尾消息变矮（如思考块折叠）：上方涌入的 item 会被同帧反向补偿再挤出，超出复用池即被移除。
        repeat(3) {
            compose.runOnIdle { tailHeight -= 160 }
            compose.waitForIdle()
            // 补偿生效：尾 item 露出部分不变，上方已读内容留在原位。
            compose.runOnIdle { assertEquals(240, state.firstVisibleItemScrollOffset) }
            compose.runOnIdle { tailHeight += 160 }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(400, state.firstVisibleItemScrollOffset) }
        }

        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }

    /**
     * Same-frame hold: growing the tail while off-bottom must not move the
     * older item in any layout pass, including the frame the height changes.
     */
    @Test
    fun tailGrowthDoesNotMoveOlderItemInAnyLayoutPass() {
        var tailHeight by mutableIntStateOf(80)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(300.dp), state = state, reverseLayout = true) {
                item(key = 0) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 0, 0)
                            .fillMaxWidth()
                            .height(tailHeight.dp),
                    )
                }
                item(key = 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 2 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 40) } }
        compose.waitForIdle()
        val start = olderTops.size
        val baseline = olderTops.last()
        compose.runOnIdle { tailHeight = 180 }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px across ${frames.size} passes (baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }

    /**
     * One-step jump the size of a settled markdown image appearing (round-4b
     * device: 664px). Nested requestScrollToItem remeasure must not drop it.
     */
    @Test
    fun largeOneStepTailGrowthDoesNotMoveOlderItemInAnyLayoutPass() {
        var tailHeight by mutableIntStateOf(80)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(300.dp), state = state, reverseLayout = true) {
                item(key = 0) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 0, 0)
                            .fillMaxWidth()
                            .height(tailHeight.dp),
                    )
                }
                item(key = 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 2 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 40) } }
        compose.waitForIdle()
        val start = olderTops.size
        val baseline = olderTops.last()
        compose.runOnIdle { tailHeight = 744 }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px across ${frames.size} passes (baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
    }

    /**
     * Two height steps in one off-bottom session: handover (80→180) then an
     * image-load-sized jump (180→280). Every recorded pass of the older item
     * must stay within 2px of the baseline.
     */
    @Test
    fun twoStepTailGrowthDoesNotMoveOlderItemInAnyLayoutPass() {
        var tailHeight by mutableIntStateOf(80)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(300.dp), state = state, reverseLayout = true) {
                item(key = 0) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 0, 0)
                            .fillMaxWidth()
                            .height(tailHeight.dp),
                    )
                }
                item(key = 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 2 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 40) } }
        compose.waitForIdle()
        val start = olderTops.size
        val baseline = olderTops.last()
        compose.runOnIdle { tailHeight = 180 }
        compose.waitForIdle()
        compose.runOnIdle { tailHeight = 280 }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px across ${frames.size} passes (baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
    }

    @Test
    fun manySmallTailGrowthStepsDoNotMoveOlderItem() {
        var tailHeight by mutableIntStateOf(80)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(300.dp), state = state, reverseLayout = true) {
                item(key = 0) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 0, 0)
                            .fillMaxWidth()
                            .height(tailHeight.dp),
                    )
                }
                item(key = 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 2 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 40) } }
        compose.waitForIdle()
        val start = olderTops.size
        val baseline = olderTops.last()
        repeat(40) {
            compose.runOnIdle { tailHeight += 12 }
            compose.waitForIdle()
        }
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px across ${frames.size} passes (baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
    }

    /**
     * Device idle/drag/fling: 12-char appends land as separate measures while
     * the previous requestScrollToItem still has isScrollInProgress set.
     * Hold must keep accumulating on the in-flight target (round-4b fling
     * leftover −144px when measure skipped those frames).
     */
    @Test
    fun manySmallStreamGrowthStepsBelowASmallerFirstVisibleItemDoNotMoveOlderItem() {
        var streamHeight by mutableIntStateOf(200)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                item(key = 1) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 1, 0)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                item(key = 2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 3 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 20) } }
        compose.waitForIdle()
        val start = olderTops.size
        val baseline = olderTops.last()
        repeat(40) {
            compose.runOnIdle { streamHeight += 12 }
            compose.waitForIdle()
        }
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px across ${frames.size} passes (baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
    }

    @Test
    fun burstStreamGrowthBelowASmallerFirstVisibleItemDoesNotMoveOlderItem() {
        var streamHeight by mutableIntStateOf(200)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                item(key = 1) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 1, 0)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                item(key = 2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 3 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 20) } }
        compose.waitForIdle()
        val start = olderTops.size
        val baseline = olderTops.last()
        compose.runOnIdle { streamHeight += 12 * 40 }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px across ${frames.size} passes (baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
    }

    /**
     * Production completion on 392288d7: remaining 19 chars landed in the
     * same frame TimelineTail removed a plan slot, so this node's lazyIndex
     * no longer matched layoutInfo for one measure. Adopting lastHeight
     * without compensating ate 66px (1195→1129). Identify the growing
     * item by key so a stale lazyIndex does not drop lastHeight.
     */
    @Test
    fun streamGrowthWhileHoldLazyIndexMismatchesLayoutDoesNotMoveOlderItem() {
        var streamHeight by mutableIntStateOf(200)
        var holdIndex by mutableIntStateOf(1)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                item(key = 1) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, holdIndex, 0, itemKey = 1)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                item(key = 2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 3 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 20) } }
        compose.waitForIdle()
        val start = olderTops.size
        val baseline = olderTops.last()
        // Drag clears requestedIndex; then the plan index shifts in the
        // same frame as the completion append. Identify the item by key.
        compose.runOnIdle {
            holdIndex = 2
            streamHeight = 360
        }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px after lazyIndex mismatch + growth (baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
    }

    /**
     * After a 336px production drag the published first-visible is the
     * growing message itself (index 2, offset ~105). Remaining completion
     * chars must not move the older item; layoutInfo — not a stale 0/0
     * pair — is what shouldHold reads.
     */
    @Test
    fun streamGrowthWhileFirstVisibleIsTheGrowingItemDoesNotMoveOlderItem() {
        var streamHeight by mutableIntStateOf(300)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(8.dp)) }
                item(key = 1) { Box(Modifier.fillMaxWidth().height(8.dp)) }
                item(key = 2) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 2, 24, itemKey = 2)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                item(key = 3) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 4 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(2, 80) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, state.firstVisibleItemIndex)
            assertTrue(state.firstVisibleItemScrollOffset > 0)
            val vis = state.layoutInfo.visibleItemsInfo
            val layoutFirst = vis.minBy { it.index }
            assertEquals(2, layoutFirst.index)
            val derived = state.layoutInfo.viewportStartOffset - layoutFirst.offset
            assertEquals(state.firstVisibleItemScrollOffset, derived)
            assertTrue("older item must stay composed", olderTops.isNotEmpty())
        }
        val start = olderTops.size
        val baseline = olderTops.last()
        compose.runOnIdle { streamHeight = 460 }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px while first-visible was the growing item " +
                "(baseline=$baseline tops=$frames first=${state.firstVisibleItemIndex}/" +
                "${state.firstVisibleItemScrollOffset})",
            maxDev <= 2f,
        )
    }

    /**
     * 4d older-anchor device: one short drag leaves TimelineTail first-visible
     * at offset 88 (just past the 24.dp pin buffer). Settled-tree height +84px
     * must not move the older token; a stale published 0/0 would look pinned
     * and eat the delta.
     */
    @Test
    fun streamGrowthWhileFirstVisibleIsTheTailJustPastPinBufferDoesNotMoveOlderItem() {
        var streamHeight by mutableIntStateOf(200)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(200.dp)) }
                item(key = 1) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 1, 24, itemKey = 1)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                item(key = 2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 3 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 88) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertTrue(
                "need a just-off-bottom tail offset, was ${state.firstVisibleItemScrollOffset}",
                state.firstVisibleItemScrollOffset >= 80,
            )
        }
        val start = olderTops.size
        val baseline = olderTops.last()
        compose.runOnIdle { streamHeight = 284 }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px after +84px while first-visible was the tail " +
                "(baseline=$baseline tops=$frames first=${state.firstVisibleItemIndex}/" +
                "${state.firstVisibleItemScrollOffset})",
            maxDev <= 2f,
        )
    }

    /**
     * Production keeps streamingTailLayout on the last message after
     * loading/awaiting/grace end, so a later Coil-sized jump still holds.
     */
    @Test
    fun lastMessageHoldCoversImageGrowthAfterLoadingFlagDrops() {
        var streamHeight by mutableIntStateOf(180)
        var loading by mutableStateOf(true)
        val olderTops = mutableListOf<Float>()
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(400.dp), state = state, reverseLayout = true) {
                item(key = 0) { Box(Modifier.fillMaxWidth().height(8.dp)) }
                item(key = 1) {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(state, 1, 24, itemKey = 1)
                            .fillMaxWidth()
                            .height(streamHeight.dp),
                    )
                }
                item(key = 2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onGloballyPositioned { olderTops += it.positionInRoot().y },
                    )
                }
                items(8, key = { it + 3 }) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 40) } }
        compose.waitForIdle()
        val start = olderTops.size
        val baseline = olderTops.last()
        compose.runOnIdle { streamHeight = 280 }
        compose.waitForIdle()
        compose.runOnIdle { loading = false }
        compose.waitForIdle()
        compose.runOnIdle { streamHeight = 744 }
        compose.waitForIdle()
        val frames = olderTops.drop(start)
        val maxDev = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
        assertTrue(
            "older item moved ${maxDev}px after loading ended then image growth " +
                "(loading=$loading baseline=$baseline tops=$frames)",
            maxDev <= 2f,
        )
    }
}

private data class FakeItem(
    override val index: Int,
    override val key: Any,
    override val offset: Int,
    override val size: Int,
    override val contentType: Any? = null,
) : LazyListItemInfo
