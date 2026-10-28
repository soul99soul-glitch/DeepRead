package app.amber.feature.ui.pages.chat

import android.util.Log
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.amber.agent.BuildConfig
import app.amber.feature.ui.components.richtext.StreamingPresentationHandover
import kotlinx.coroutines.delay

private const val HANDOVER_GATE_TAG = "AmberHandover"

/**
 * Reading line in the same offset space as
 * [androidx.compose.foundation.lazy.LazyListItemInfo.offset] / size.
 *
 * The timeline is a reverseLayout LazyColumn: offsets grow from the visual
 * bottom (newest, [LazyListLayoutInfo.viewportStartOffset]) toward the
 * visual top (older, [LazyListLayoutInfo.viewportEndOffset]). A reader who
 * has scrolled to older content is looking at that top edge, so this is
 * the last pixel inside the viewport — not the midpoint.
 *
 * A tall reply that fills the bottom half contains the midpoint even while
 * the reader is in the older message above it; using the midpoint kept the
 * settle gate deferred (4c `productionVisibleReplySwapsUnderOlderReadingAnchor`).
 */
internal fun viewportReadingLine(layoutInfo: LazyListLayoutInfo): Int =
    layoutInfo.viewportEndOffset - 1

/** True when [viewportReadingLine] falls in the item identified by [itemKey]. */
internal fun readingLineInsideItem(layoutInfo: LazyListLayoutInfo, itemKey: Any): Boolean {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey } ?: return false
    val line = viewportReadingLine(layoutInfo)
    return line >= item.offset && line < item.offset + item.size
}

internal data class StreamingSettleGateSnapshot(
    val viewportReadingLine: Int,
    val itemOffset: Int?,
    val itemSize: Int?,
    val firstVisibleIndex: Int,
    val firstVisibleOffset: Int,
    val scrollInProgress: Boolean,
    val awaiting: Boolean,
    val itemVisible: Boolean,
    val readingLineInsideItem: Boolean,
) {
    override fun toString(): String =
        "line=$viewportReadingLine item=${itemOffset?.let { "$it/${itemSize}" } ?: "none"} " +
            "first=$firstVisibleIndex/$firstVisibleOffset scroll=$scrollInProgress " +
            "awaiting=$awaiting visible=$itemVisible inside=$readingLineInsideItem"
}

internal fun streamingSettleGateSnapshot(
    state: LazyListState,
    itemKey: Any,
    awaiting: Boolean,
): StreamingSettleGateSnapshot {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == itemKey }
    return StreamingSettleGateSnapshot(
        viewportReadingLine = viewportReadingLine(info),
        itemOffset = item?.offset,
        itemSize = item?.size,
        firstVisibleIndex = state.firstVisibleItemIndex,
        firstVisibleOffset = state.firstVisibleItemScrollOffset,
        scrollInProgress = state.isScrollInProgress,
        awaiting = awaiting,
        itemVisible = item != null,
        readingLineInsideItem = readingLineInsideItem(info, itemKey),
    )
}

/**
 * Whether a drained streaming message may swap to the settled renderer now.
 *
 * The timeline is a reverseLayout LazyColumn: the bottom edge of the first
 * visible item is the scroll anchor and lazy offsets grow upward. A height
 * change of any visible message therefore moves everything above it.
 *
 * [holdReadingOnTailGrowth] keeps older-item tops in the same measure pass,
 * so a visible reply may swap immediately when the reader is looking at
 * older content. Swapping while the reader is looking *inside* the reply
 * jumps the character they are reading: the settled block tree does not
 * share the streaming single-Text geometry. That case stays deferred until
 * the viewport is pinned, the reply leaves the viewport, or the item
 * leaves composition.
 *
 * Idle rules:
 * 1. Pinned to the timeline bottom → swap.
 * 2. Reply not in `visibleItemsInfo` → swap.
 * 3. [readingLineInsideItem] (visual top of the reverseLayout viewport) → defer.
 * 4. Otherwise (older-message anchor, reply still visible) → swap.
 * A drag or fling always defers; the next idle poll applies 1–4.
 */
internal fun streamingSettleAllowed(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    followBottomPx: Int,
    itemVisible: Boolean,
    readingLineInsideItem: Boolean,
    scrollInProgress: Boolean,
): Boolean {
    if (scrollInProgress) return false
    if (!itemVisible) return true
    val pinnedToBottom = firstVisibleIndex == 0 && firstVisibleOffset <= followBottomPx
    if (pinnedToBottom) return true
    if (readingLineInsideItem) return false
    return true
}

/**
 * Per-message handover state owned by the timeline. The gate reads
 * [LazyListState.layoutInfo] only when a waiting block polls it through a
 * snapshotFlow, so idle and settled messages add no per-frame work and no
 * recomposition.
 */
@Stable
internal class TimelineStreamingHandover(
    private val state: LazyListState,
    private val itemKey: Any,
    private val followBottomPx: Int,
) : StreamingPresentationHandover {
    private var awaitingCount by mutableIntStateOf(0)

    val awaiting: Boolean get() = awaitingCount > 0

    private var lastDeferLog: String? = null

    override fun allowsSettledPresentation(): Boolean {
        val snapshot = streamingSettleGateSnapshot(state, itemKey, awaiting)
        val allowed = streamingSettleAllowed(
            firstVisibleIndex = snapshot.firstVisibleIndex,
            firstVisibleOffset = snapshot.firstVisibleOffset,
            followBottomPx = followBottomPx,
            // visibleItemsInfo excludes beyond-bounds/prefetched extras.
            itemVisible = snapshot.itemVisible,
            readingLineInsideItem = snapshot.readingLineInsideItem,
            scrollInProgress = snapshot.scrollInProgress,
        )
        if (BuildConfig.DEBUG && snapshot.awaiting && !allowed) {
            val line = snapshot.toString()
            if (line != lastDeferLog) {
                lastDeferLog = line
                Log.i(HANDOVER_GATE_TAG, "defer $line")
            }
        }
        return allowed
    }

    override fun onAwaitingChange(awaiting: Boolean) {
        awaitingCount = (awaitingCount + if (awaiting) 1 else -1).coerceAtLeast(0)
    }
}

/**
 * Keeps the reading hold attached while [active] (generating or awaiting a
 * handover) and for a short grace after it. The latch bridges the frame where
 * loading ends (the final coalesced chunk lands then, before drain grace or
 * the awaiting report exist) and the settled tree's first asynchronous height
 * adjustments; a gap would recreate the hold node and lose that height delta.
 */
@Composable
internal fun rememberHandoverReadingHold(active: Boolean): Boolean {
    var lingering by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active) {
            lingering = true
        } else if (lingering) {
            delay(StreamingDrainAmortizeGraceMs)
            lingering = false
        }
    }
    return active || lingering
}
