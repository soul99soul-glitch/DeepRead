package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.AssistantRegex
import app.amber.core.model.MessageNode
import app.amber.feature.ui.components.message.MessageRenderCache
import app.amber.feature.ui.components.richtext.cachedMarkdownParseResult
import app.amber.feature.ui.components.richtext.prepareMarkdownContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Parse nearby Markdown before the item enters the viewport so history-cold
 * does not show the transparent pending placeholder for multiple frames.
 *
 * [ChatVirtualItemCache] already queues preparation, but FIFO can leave the
 * visible window waiting. This pass walks the current visible range plus
 * [leadItems] and warms [app.amber.feature.ui.components.richtext.MarkdownParseCache]
 * on Default with the same display-text cache key as
 * [app.amber.feature.ui.components.message.ChatMessageMessagePartsBlock].
 *
 * Dedup is [MarkdownPrefetchKey] (node id + content hash), not message
 * index: loading earlier history shifts indexes, and regenerate changes
 * content under the same node. [remember] is keyed by [sessionId] so a
 * conversation switch does not keep the previous set.
 *
 * The actively generating message (the last node while [ChatTimelinePlan.timelineLoading]
 * is true) is excluded and never added to [requested]. Streaming already parses it on
 * its own throttle, and per-token prefixes must not be fully parsed or evict history
 * from the cache. Its settled body is prefetched once loading ends.
 */
@Composable
internal fun TimelineMarkdownPrefetch(
    listState: LazyListState,
    plan: ChatTimelinePlan,
    messageNodes: List<MessageNode>,
    regexes: List<AssistantRegex>,
    sessionId: Uuid,
    leadItems: Int = 8,
) {
    val requested = remember(sessionId) { HashSet<MarkdownPrefetchKey>() }
    LaunchedEffect(listState, plan, messageNodes, regexes, sessionId) {
        val excludedNodeId = generatingPrefetchExclusion(plan, messageNodes)
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index
            val last = info.visibleItemsInfo.lastOrNull()?.index
            if (first == null || last == null) -1 to -1 else first to last
        }
            .distinctUntilChanged()
            .collect { (firstVisible, lastVisible) ->
                if (messageNodes.isEmpty() || firstVisible < 0) return@collect
                val lastIndex = plan.entries.lastIndex
                val from = (firstVisible - leadItems).coerceAtLeast(0)
                val to = (lastVisible + leadItems).coerceAtMost(lastIndex)
                if (to < from) return@collect
                val bodies = collectMarkdownPrefetchBodies(
                    plan = plan,
                    messageNodes = messageNodes,
                    regexes = regexes,
                    lazyFrom = from,
                    lazyTo = to,
                    requested = requested,
                    excludedNodeId = excludedNodeId,
                )
                if (bodies.isEmpty()) return@collect
                withContext(Dispatchers.Default) {
                    bodies.forEach { body ->
                        if (cachedMarkdownParseResult(body) == null) {
                            prepareMarkdownContent(body)
                        }
                    }
                }
            }
    }
}

internal data class MarkdownPrefetchKey(val nodeId: Uuid, val contentHash: Int)

/** The generating tail (mirrors `loadingNodeId` in [rememberChatTimelinePlan]); null when idle. */
internal fun generatingPrefetchExclusion(
    plan: ChatTimelinePlan,
    messageNodes: List<MessageNode>,
): Uuid? = messageNodes.lastOrNull()?.id?.takeIf { plan.timelineLoading }

internal fun collectMarkdownPrefetchBodies(
    plan: ChatTimelinePlan,
    messageNodes: List<MessageNode>,
    regexes: List<AssistantRegex>,
    lazyFrom: Int,
    lazyTo: Int,
    requested: MutableSet<MarkdownPrefetchKey>,
    excludedNodeId: Uuid?,
): List<String> {
    if (lazyTo < lazyFrom) return emptyList()
    return buildList {
        for (lazyIndex in lazyFrom..lazyTo) {
            val messageIndex = plan.messageIndexAt(lazyIndex) ?: continue
            val node = messageNodes.getOrNull(messageIndex) ?: continue
            if (node.id == excludedNodeId) continue
            for (body in node.prefetchMarkdownBodies(regexes)) {
                val key = MarkdownPrefetchKey(node.id, body.hashCode())
                if (!requested.add(key)) continue
                add(body)
            }
        }
    }
}

internal fun collectMarkdownPrefetchBodies(
    messageNodes: List<MessageNode>,
    regexes: List<AssistantRegex>,
    messageIndexRange: IntRange,
    requested: MutableSet<MarkdownPrefetchKey>,
    excludedNodeId: Uuid?,
): List<String> {
    return buildList {
        for (index in messageIndexRange) {
            val node = messageNodes.getOrNull(index) ?: continue
            if (node.id == excludedNodeId) continue
            for (body in node.prefetchMarkdownBodies(regexes)) {
                val key = MarkdownPrefetchKey(node.id, body.hashCode())
                if (!requested.add(key)) continue
                add(body)
            }
        }
    }
}

internal fun MessageNode.prefetchMarkdownBodies(
    regexes: List<AssistantRegex> = emptyList(),
): List<String> {
    val message = messages.getOrNull(selectIndex) ?: return emptyList()
    return message.parts.mapNotNull { part ->
        val text = (part as? UIMessagePart.Text)?.text ?: return@mapNotNull null
        MessageRenderCache.displayMarkdownText(
            text = text,
            regexes = regexes,
            role = message.role,
        ).takeIf { it.isNotBlank() }
    }
}
