package app.amber.feature.ui.pages.chat

import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.model.AssistantRegex
import app.amber.feature.ui.components.message.ChatMessageVirtualItem
import app.amber.feature.ui.components.message.buildChatMessageVirtualItems
import kotlin.uuid.Uuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import app.amber.feature.ui.components.richtext.MarkdownParseResult
import app.amber.feature.ui.components.richtext.MarkdownPreparationOutcome
import app.amber.feature.ui.components.richtext.cachedMarkdownParseResult
import app.amber.feature.ui.components.richtext.isMarkdownContentCacheable
import app.amber.feature.ui.components.richtext.parseMarkdownContent
import app.amber.feature.ui.components.richtext.prepareMarkdownContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHAT_TIMELINE_PLAN_CACHE_MAX_ENTRIES = 2048
private const val CHAT_TIMELINE_PLAN_CACHE_MAX_MARKDOWN_CHARS = 1_200_000

private data class ChatTimelinePlanInputs(
    val assistant: List<AssistantRegex>,
    val showAssistantBubble: Boolean,
    val timelineLoading: Boolean,
    val hasHistoryLoadingItem: Boolean,
    val pendingMessageCount: Int,
    val postSendState: PostSendTimelineState,
    val protectedStreamingNodeIds: Set<Uuid>,
    val preparedRevision: Int,
)

/** entry 若新增依赖相邻节点或其它 composition 状态，必须同步加入 [ChatTimelinePlanInputs]。 */
internal class ChatTimelinePlanReuseCache {
    private var previousNodes: List<MessageNode>? = null
    private var previousInputs: ChatTimelinePlanInputs? = null
    private var previousPlan: ChatTimelinePlan? = null
    private var reusablePrefix: ChatTimelinePrefix? = null

    fun build(
        conversation: Conversation,
        assistant: List<AssistantRegex>?,
        showAssistantBubble: Boolean,
        timelineLoading: Boolean,
        hasHistoryLoadingItem: Boolean,
        pendingMessageCount: Int,
        postSendState: PostSendTimelineState,
        virtualItemCache: ChatVirtualItemCache,
        protectedStreamingNodeIds: Set<Uuid> = emptySet(),
    ): ChatTimelinePlan {
        val nodes = conversation.messageNodes
        val inputs = ChatTimelinePlanInputs(
            assistant = assistant.orEmpty().toList(),
            showAssistantBubble = showAssistantBubble,
            timelineLoading = timelineLoading,
            hasHistoryLoadingItem = hasHistoryLoadingItem,
            pendingMessageCount = pendingMessageCount,
            postSendState = postSendState,
            protectedStreamingNodeIds = protectedStreamingNodeIds.toSet(),
            preparedRevision = virtualItemCache.preparedRevision,
        )
        val priorNodes = previousNodes
        val priorPlan = previousPlan
        val prefixStart = if (
            priorNodes != null && priorPlan != null && previousInputs == inputs &&
            nodes.size == priorNodes.size && nodes.isNotEmpty() &&
            (0 until nodes.lastIndex).all { index -> nodes[index] === priorNodes[index] }
        ) {
            if (nodes.size == 1) {
                if (hasHistoryLoadingItem) priorPlan.entries.lastIndex else priorPlan.entries.size
            } else {
                priorPlan.lazyIndexForMessage(nodes.lastIndex - 1)
            }
        } else {
            null
        }
        val prefix = if (prefixStart != null && priorPlan != null) {
            reusablePrefix ?: priorPlan.historyPrefix(prefixStart)
        } else null
        val plan = buildChatTimelinePlan(
            conversation = conversation,
            assistant = assistant,
            showAssistantBubble = showAssistantBubble,
            timelineLoading = timelineLoading,
            hasHistoryLoadingItem = hasHistoryLoadingItem,
            pendingMessageCount = pendingMessageCount,
            postSendState = postSendState,
            virtualItemCache = virtualItemCache,
            protectedStreamingNodeIds = protectedStreamingNodeIds,
            reusedPrefix = prefix,
        )
        reusablePrefix = prefix
        previousNodes = nodes
        previousInputs = inputs
        previousPlan = plan
        return plan
    }
}

/**
 * Bounded hand-off between the timeline plan and the Markdown parse cache.
 * Implementations must report a terminal [MarkdownPreparationOutcome.Uncacheable]
 * instead of pretending a result will eventually become retrievable.
 */
internal interface MarkdownPreparationGateway {
    fun isCacheable(content: String): Boolean
    fun cached(content: String): MarkdownParseResult?
    fun prepare(content: String): MarkdownPreparationOutcome

    object Default : MarkdownPreparationGateway {
        override fun isCacheable(content: String): Boolean = isMarkdownContentCacheable(content)
        override fun cached(content: String): MarkdownParseResult? = cachedMarkdownParseResult(content)
        override fun prepare(content: String): MarkdownPreparationOutcome = prepareMarkdownContent(content)
    }
}

private const val CHAT_MARKDOWN_MAX_PREPARATION_ATTEMPTS = 3
private const val CHAT_MARKDOWN_MAX_PREPARATION_PER_PASS = 6
private const val CHAT_MARKDOWN_MAX_ACTIVE_PREPARATIONS = 32
private const val CHAT_MARKDOWN_MAX_PENDING_CHARS = 1_200_000

/** Char-count bound only: never retains the prepared text itself. */
private data class MarkdownPreparationKey(
    val length: Int,
    val hash: Int,
    val headHash: Int,
    val tailHash: Int,
)

private enum class PreparationState { PENDING, AVAILABLE, TERMINAL }

private class MarkdownPreparationTask(var content: String?) {
    var attempts = 0
    var state = PreparationState.PENDING
    var owners = 0
}

private data class VirtualCacheEntry(
    val items: List<ChatMessageVirtualItem>?,
    val preparations: Set<MarkdownPreparationKey>,
)

internal class ChatVirtualItemCache(
    private val maxEntries: Int = CHAT_TIMELINE_PLAN_CACHE_MAX_ENTRIES,
    private val maxMarkdownChars: Int = CHAT_TIMELINE_PLAN_CACHE_MAX_MARKDOWN_CHARS,
    private val parseMissingMarkdownSynchronously: Boolean = true,
    private val markdownPreparation: MarkdownPreparationGateway = MarkdownPreparationGateway.Default,
    private val maxPreparationPerPass: Int = CHAT_MARKDOWN_MAX_PREPARATION_PER_PASS,
) {
    private val entries = object : LinkedHashMap<ChatVirtualItemCacheKey, VirtualCacheEntry>(
        maxEntries.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ChatVirtualItemCacheKey, VirtualCacheEntry>,
        ): Boolean = false
    }
    private var totalMarkdownChars: Int = 0
    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    // Metadata lives as long as the cache entries owning it. Terminal tasks
    // retain only a fingerprint; active text is separately bounded by count
    // AND characters. Admission failure is a cached deterministic raw fallback.
    private val preparations = LinkedHashMap<MarkdownPreparationKey, MarkdownPreparationTask>()
    private var pendingChars = 0
    var preparationRequests by mutableIntStateOf(0)
        private set
    var preparedRevision by mutableIntStateOf(0)
        private set

    internal val pendingMarkdownCount: Int
        get() = preparations.values.count { it.state == PreparationState.PENDING }
    internal val retainedPreparationChars: Int get() = pendingChars
    internal val preparationTaskCount: Int get() = preparations.size

    suspend fun preparePendingMarkdown() {
        val batch = preparations.entries.filter { it.value.state == PreparationState.PENDING }
            .take(maxPreparationPerPass.coerceAtLeast(1)).map { it.key to it.value }
        if (batch.isEmpty()) return
        val results = withContext(Dispatchers.Default) {
            batch.map { (key, task) ->
                val content = checkNotNull(task.content)
                val outcome = when {
                    markdownPreparation.cached(content) != null -> MarkdownPreparationOutcome.PREPARED
                    !markdownPreparation.isCacheable(content) -> MarkdownPreparationOutcome.UNCACHEABLE
                    task.attempts >= CHAT_MARKDOWN_MAX_PREPARATION_ATTEMPTS -> MarkdownPreparationOutcome.FAILED
                    else -> {
                        task.attempts++
                        try {
                            markdownPreparation.prepare(content)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            // Cancellation is not a failed parse. Keep the task
                            // queued with its previous budget and propagate it.
                            task.attempts--
                            throw cancelled
                        } catch (_: Exception) {
                            MarkdownPreparationOutcome.FAILED
                        }
                    }
                }
                Triple(key, task, outcome)
            }
        }
        var needsRebuild = false
        for ((key, task, outcome) in results) {
            // An entry may have been evicted while the worker was running.
            if (preparations[key] !== task) continue
            when (outcome) {
                MarkdownPreparationOutcome.PREPARED -> {
                    task.state = PreparationState.AVAILABLE
                    needsRebuild = true
                }
                MarkdownPreparationOutcome.UNCACHEABLE -> finishPreparation(task)
                MarkdownPreparationOutcome.FAILED -> {
                    if (task.attempts >= CHAT_MARKDOWN_MAX_PREPARATION_ATTEMPTS) finishPreparation(task)
                    // Otherwise keep the SAME task queued. Recomposition and
                    // other requests cannot evict/reset its attempt counter.
                }
            }
        }
        if (needsRebuild) preparedRevision++
        if (pendingMarkdownCount > 0) preparationRequests++
    }

    private fun finishPreparation(task: MarkdownPreparationTask) {
        pendingChars -= task.content?.length ?: 0
        task.content = null
        task.state = PreparationState.TERMINAL
    }

    private fun requestMarkdownPreparation(content: String, owners: MutableSet<MarkdownPreparationKey>): Boolean {
        if (!markdownPreparation.isCacheable(content)) return false
        val key = preparationKey(content)
        val existing = preparations[key]
        if (existing != null) {
            owners += key
            if (existing.state == PreparationState.TERMINAL) return false
            if (existing.state == PreparationState.AVAILABLE) {
                // Successful preparation can be evicted before consumption.
                // Its original retry budget still belongs to this entry.
                if (existing.attempts >= CHAT_MARKDOWN_MAX_PREPARATION_ATTEMPTS) {
                    finishPreparation(existing)
                    return false
                }
                existing.state = PreparationState.PENDING
                preparationRequests++
            }
            return true
        }
        val active = preparations.values.count { it.state != PreparationState.TERMINAL }
        if (active >= CHAT_MARKDOWN_MAX_ACTIVE_PREPARATIONS ||
            content.length > CHAT_MARKDOWN_MAX_PENDING_CHARS - pendingChars) return false
        preparations[key] = MarkdownPreparationTask(content)
        pendingChars += content.length
        owners += key
        preparationRequests++
        return true
    }

    private fun preparationKey(content: String): MarkdownPreparationKey = MarkdownPreparationKey(
        content.length, content.hashCode(), content.take(32).hashCode(), content.takeLast(32).hashCode(),
    )

    private fun releasePreparations(entry: VirtualCacheEntry) {
        for (key in entry.preparations) {
            val task = preparations[key] ?: continue
            task.owners--
            if (task.owners == 0) {
                pendingChars -= task.content?.length ?: 0
                preparations.remove(key)
            }
        }
    }

    private val visualCounts = mutableStateMapOf<Uuid, Int>()
    var protectionRevision by mutableIntStateOf(0)
        private set

    fun noteVisualActivity(nodeId: Uuid, active: Boolean) {
        val count = (visualCounts[nodeId] ?: 0) + if (active) 1 else -1
        if (count > 0) visualCounts[nodeId] = count else visualCounts.remove(nodeId)
    }

    fun activeVisualNodeIds(): Set<Uuid> = visualCounts.keys.toSet()

    fun releaseCompletedOffscreen(visibleNodeIds: Set<Uuid>, loadingNodeId: Uuid?) {
        val changed = streamingAssistantNodeIds.removeAll { nodeId ->
            nodeId != loadingNodeId && nodeId !in visibleNodeIds && nodeId !in visualCounts
        }
        if (changed) protectionRevision++
    }

    private val streamingAssistantNodeIds = ArrayDeque<Uuid>()

    /**
     * Record only assistant nodes that were actually observed while loading.
     * A finished historical tail never enters this set, so opening an old
     * conversation does not force its Markdown into one expensive item.
     */
    fun observeStreamingAssistant(nodeId: Uuid) {
        if (nodeId in streamingAssistantNodeIds) return
        streamingAssistantNodeIds.addLast(nodeId)
    }

    fun protectedStreamingNodeIds(): Set<Uuid> = streamingAssistantNodeIds.toSet()

    fun getOrBuild(
        node: MessageNode,
        regexes: List<AssistantRegex>,
        showAssistantBubble: Boolean,
        loading: Boolean,
        lastMessage: Boolean,
        forceSingleItem: Boolean = false,
    ): List<ChatMessageVirtualItem>? {
        val key = ChatVirtualItemCacheKey.of(
            node = node,
            regexes = regexes,
            showAssistantBubble = showAssistantBubble,
            loading = loading,
            lastMessage = lastMessage,
            forceSingleItem = forceSingleItem,
        )
        val previous = entries[key]
        if (previous != null && previous.preparations.none {
                preparations[it]?.state == PreparationState.AVAILABLE
            }) {
            hits++
            return previous.items
        }
        misses++
        val owners = HashSet<MarkdownPreparationKey>()
        val value = buildChatMessageVirtualItems(
            node = node,
            assistant = regexes,
            showAssistantBubble = showAssistantBubble,
            loading = loading,
            lastMessage = lastMessage,
            forceSingleItem = forceSingleItem,
            parseMarkdown = { content ->
                if (parseMissingMarkdownSynchronously) parseMarkdownContent(content) else {
                    val cached = markdownPreparation.cached(content)
                    if (cached != null) {
                        cached
                    } else {
                        // Cache the fallback with its preparation ownership.
                        // A successful result invalidates it; terminal/admission
                        // fallback stays cached and cannot re-queue itself.
                        requestMarkdownPreparation(content, owners)
                        null
                    }
                }
            },
        )
        val markdownChars = value.markdownCharCost()
        // Acquire new references before releasing the previous entry: a
        // rebuild must never accidentally reset the live task's retry budget.
        owners.forEach { preparations.getValue(it).owners++ }
        if (previous != null) {
            totalMarkdownChars -= previous.items.markdownCharCost()
            releasePreparations(previous)
            entries.remove(key)
        }
        val entry = VirtualCacheEntry(value, owners)
        if (markdownChars > maxMarkdownChars) {
            releasePreparations(entry)
            return value
        }
        entries[key] = entry
        totalMarkdownChars += markdownChars
        trimToBudget()
        return value
    }

    private fun trimToBudget() {
        while (
            entries.size > maxEntries ||
            totalMarkdownChars > maxMarkdownChars
        ) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            totalMarkdownChars -= eldest.value.items.markdownCharCost()
            releasePreparations(eldest.value)
            iterator.remove()
        }
    }
}

private fun List<ChatMessageVirtualItem>?.markdownCharCost(): Int {
    if (this == null) return 0
    var cost = 0
    val seen = HashSet<String>()
    for (item in this) {
        if (item is ChatMessageVirtualItem.MarkdownChild && seen.add(item.content)) {
            cost += item.content.length
        }
    }
    return cost
}

private data class ChatVirtualItemCacheKey(
    val nodeId: Uuid,
    val messageId: Uuid,
    val messageIdentity: Int,
    val partsIdentity: Int,
    val assistantSignature: Int,
    val showAssistantBubble: Boolean,
    val loading: Boolean,
    val lastMessage: Boolean,
    val forceSingleItem: Boolean,
) {
    companion object {
        fun of(
            node: MessageNode,
            regexes: List<AssistantRegex>,
            showAssistantBubble: Boolean,
            loading: Boolean,
            lastMessage: Boolean,
            forceSingleItem: Boolean,
        ): ChatVirtualItemCacheKey {
            val message = node.currentMessage
            return ChatVirtualItemCacheKey(
                nodeId = node.id,
                messageId = message.id,
                messageIdentity = System.identityHashCode(message),
                partsIdentity = System.identityHashCode(message.parts),
                assistantSignature = regexes.renderSignature(),
                showAssistantBubble = showAssistantBubble,
                loading = loading,
                lastMessage = lastMessage,
                forceSingleItem = forceSingleItem,
            )
        }
    }
}

private fun List<AssistantRegex>.renderSignature(): Int {
    var result = 1
    forEach { regex ->
        result = 31 * result + regex.id.hashCode()
        result = 31 * result + regex.enabled.hashCode()
        result = 31 * result + regex.findRegex.hashCode()
        result = 31 * result + regex.replaceString.hashCode()
        result = 31 * result + regex.affectingScope.hashCode()
        result = 31 * result + regex.visualOnly.hashCode()
    }
    return result
}
