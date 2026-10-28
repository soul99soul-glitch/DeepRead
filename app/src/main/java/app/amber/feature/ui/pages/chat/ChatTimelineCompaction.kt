package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.CompactSummaryPayloads
import app.amber.core.context.CompactLifecycleStatus
import app.amber.core.context.ConversationCompact
import app.amber.core.model.Conversation

internal fun summaryPreviewOf(compact: ConversationCompact): String? {
    val raw = compact.summary.ifBlank { return null }
    return summaryPreviewOf(raw)
}

internal fun summaryPreviewOf(state: CompactLifecycleState): String? {
    val raw = state.streamingSummary.ifBlank { return null }
    return summaryPreviewOf(raw)
}

private fun summaryPreviewOf(raw: String): String? {
    return CompactSummaryPayloads.timelineSummary(raw)?.compactSummaryPreview()
}

private fun String.compactSummaryPreview(maxLength: Int = 1_000): String {
    val collapsed = COMPACT_SUMMARY_WHITESPACE_RE.replace(this, " ").trim()
    return if (collapsed.length > maxLength) collapsed.take(maxLength) + "…" else collapsed
}

// 2026-05-15 (1.9.7): top-level Regex avoids recompiling the pattern on every
// recomposition of ContextCompactInProgressMarker. The streaming summary flow
// can update at ~30Hz; per-tick Regex compile is a measurable frame-rate hit
// on mid-tier devices.
internal val COMPACT_SUMMARY_WHITESPACE_RE = Regex("\\s+")

internal data class TimelineCompactionPresentation(
    val completedMarkersByTimelineEndIndex: Map<Int, List<ConversationCompact>>,
    val activeCompactTimelineEndIndex: Int?,
    val lifecycleCompletedTimelineEndIndex: Int?,
    val activeCompactStreamingSummary: String,
    val tailTimelineEndIndex: Int,
    val tailCompactItemKey: String,
    val showFloatingCompactMarker: Boolean,
    val visualCompactedTimelineEndIndex: Int?,
)

@Composable
internal fun rememberTimelineCompactionPresentation(
    conversation: Conversation,
    contextCompacts: List<ConversationCompact>,
    compactLifecycleState: CompactLifecycleState,
    isCompacting: Boolean,
    streamingSummary: String,
    state: LazyListState,
): TimelineCompactionPresentation {
    fun timelineAnchorForCompactEvent(eventAt: Long): Int {
        if (conversation.messageNodes.isEmpty()) return -1
        val timeZone = TimeZone.currentSystemDefault()
        val anchor = conversation.messageNodes.indexOfLast { node ->
            node.currentMessage.createdAt.toInstant(timeZone).toEpochMilliseconds() <= eventAt
        }
        return anchor.coerceIn(-1, conversation.messageNodes.lastIndex)
    }

    val completedCompacts = remember(contextCompacts) {
        contextCompacts.filter { compact -> compact.status == "completed" }
    }
    val completedMarkersByTimelineEndIndex = remember(
        contextCompacts,
        conversation.messageNodes,
    ) {
        completedCompacts
            .map { compact -> timelineAnchorForCompactEvent(compact.createdAt) to compact }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }
    val completedCompactIds = remember(contextCompacts) {
        contextCompacts
            .filter { it.status == "completed" }
            .map { it.id }
            .toSet()
    }
    val activeCompactTimelineEndIndex = remember(
        compactLifecycleState,
        isCompacting,
        conversation.messageNodes.size,
    ) {
        conversation.messageNodes.lastIndex
            .takeIf { it >= 0 && (compactLifecycleState.isActive || isCompacting) }
    }
    val lifecycleCompletedTimelineEndIndex = remember(
        compactLifecycleState,
        completedCompactIds,
        conversation.messageNodes.size,
    ) {
        compactLifecycleState
            .takeIf {
                it.status == CompactLifecycleStatus.COMPLETED &&
                    it.completedCompactId != null &&
                    it.completedCompactId !in completedCompactIds
            }
            ?.let { timelineAnchorForCompactEvent(it.anchorAt.takeIf { anchor -> anchor > 0L } ?: it.updatedAt) }
    }
    val activeCompactStreamingSummary = compactLifecycleState.streamingSummary.ifBlank { streamingSummary }
    val tailTimelineEndIndex = conversation.messageNodes.lastIndex
    val tailCompactItemKey = remember(conversation.id, tailTimelineEndIndex) {
        "compact-timeline-tail-${conversation.id}-$tailTimelineEndIndex"
    }
    val compactTailMarkerVisible by remember(state, tailCompactItemKey) {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo.any { item -> item.key == tailCompactItemKey }
        }
    }
    val showFloatingCompactMarker = (compactLifecycleState.isActive || isCompacting) &&
        !compactTailMarkerVisible &&
        !state.isScrollInProgress
    // Timeline index covered by completed compacts. Messages above that visible
    // divider are dimmed so the user can tell they are represented by summary
    // context, even if the runtime privately keeps recent turns for continuity.
    val visualCompactedTimelineEndIndex = remember(
        completedMarkersByTimelineEndIndex,
        lifecycleCompletedTimelineEndIndex,
    ) {
        (completedMarkersByTimelineEndIndex.keys + listOfNotNull(lifecycleCompletedTimelineEndIndex))
            .maxOrNull()
    }

    return TimelineCompactionPresentation(
        completedMarkersByTimelineEndIndex,
        activeCompactTimelineEndIndex,
        lifecycleCompletedTimelineEndIndex,
        activeCompactStreamingSummary,
        tailTimelineEndIndex,
        tailCompactItemKey,
        showFloatingCompactMarker,
        visualCompactedTimelineEndIndex,
    )
}
