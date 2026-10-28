package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.core.MessageRole
import app.amber.core.settings.Settings
import app.amber.core.model.Conversation
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import app.amber.feature.ui.components.richtext.prewarmMarkdownContent
import app.amber.core.utils.ChatSendTransitionTracker
import kotlin.math.abs
import kotlin.uuid.Uuid
import app.amber.core.model.AssistantRegex

internal data class TimelineHistoryLoadSignal(
    val historyVisible: Boolean,
    val initialized: Boolean,
    val fullyLoaded: Boolean,
    val prefetching: Boolean,
    val loadedNodeCount: Int,
)


internal data class TimelineScrollAnchor(
    val key: Any,
    val offset: Int,
)

internal data class TimelineTailPresentation(
    val reserveVisible: Boolean,
    val dotVisible: Boolean,
    val drainAmortizeGrace: Boolean,
)

@Composable
internal fun rememberTimelineTailPresentation(
    conversationId: Uuid,
    latestMessageId: Uuid?,
    timelineLoading: Boolean,
    showBottomFollowAnimation: Boolean,
): TimelineTailPresentation {
    // reverseLayout 下 TimelineTail（index 0）就在锚定边：生成结束瞬间移除它的
    // 70dp 指示器 reserve 会让整个时间线单帧下坠。保留空间直到下一条消息成为
    // 尾部（与旧实现的 retainedTailIndicator 语义一致），圆点本身照常淡出。
    var retainedTailReserveMessageId by remember(conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(timelineLoading, latestMessageId) {
        val latestId = latestMessageId?.toString()
        when {
            timelineLoading && latestId != null -> retainedTailReserveMessageId = latestId
            latestId != retainedTailReserveMessageId -> retainedTailReserveMessageId = null
        }
    }

    // 生成结束的瞬间 pacing buffer 才开始 drain：文字尾巴还要逐帧生长 ~1s，
    // 动作行也在此刻插入（+48dp）。高度摊铺器若随 timelineLoading 一起撤掉，
    // 这段增长退回单帧阶梯跳（"一行一行往上跳"），按钮插入也成了瞬时重排。
    // true→false 边沿给摊铺器留一个覆盖 drain 的宽限期（首次进会话不布防）。
    var drainAmortizeGrace by remember(conversationId) { mutableStateOf(false) }
    var prevTimelineLoading by remember(conversationId) { mutableStateOf(timelineLoading) }
    LaunchedEffect(timelineLoading) {
        if (prevTimelineLoading && !timelineLoading) {
            drainAmortizeGrace = true
            delay(StreamingDrainAmortizeGraceMs)
            drainAmortizeGrace = false
        } else if (timelineLoading) {
            drainAmortizeGrace = false
        }
        prevTimelineLoading = timelineLoading
    }

    val tailIndicatorReserveVisible = showBottomFollowAnimation &&
        (
            timelineLoading ||
                (retainedTailReserveMessageId != null &&
                    latestMessageId?.toString() == retainedTailReserveMessageId)
            )
    return TimelineTailPresentation(
        reserveVisible = tailIndicatorReserveVisible,
        dotVisible = showBottomFollowAnimation && timelineLoading,
        drainAmortizeGrace = drainAmortizeGrace,
    )
}

@Composable
internal fun ChatTimelineScrollEffects(
    conversation: Conversation,
    pendingUserMessages: List<PendingUserMessage>,
    activeGeneration: Boolean,
    timelineLoadState: ConversationTimelineLoadState,
    chatTimelinePlan: ChatTimelinePlan,
    chatRegexes: List<AssistantRegex>,
    state: LazyListState,
    innerPadding: PaddingValues,
    settings: Settings,
    onLoadOlderTimeline: suspend () -> Unit,
    onRecentScrollChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val activity = LocalContext.current as? app.amber.agent.RouteActivity
    val conversationId = conversation.id.toString()
    val currentConversationState by rememberUpdatedState(conversation)
    val currentPendingUserMessages by rememberUpdatedState(pendingUserMessages)
    val timelineLoadStateState by rememberUpdatedState(timelineLoadState)
    val chatTimelinePlanState by rememberUpdatedState(chatTimelinePlan)
    val loadOlderTimelineState by rememberUpdatedState(onLoadOlderTimeline)
    val onRecentScrollChangedState by rememberUpdatedState(onRecentScrollChanged)
    LaunchedEffect(conversation.id, activeGeneration) {
        if (!activeGeneration) {
            ChatSendTransitionTracker.clear(conversationId)
        }
    }

    // MessageJumper 的"刚滚动过"信号：任何滚动开始即点亮，停止 1.5s 后熄灭。
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            onRecentScrollChangedState(true)
        } else {
            delay(1500)
            onRecentScrollChangedState(false)
        }
    }

    if (settings.displaySetting.enableAutoScroll) {
        // 发送统一回底，即使用户正滚在历史里阅读。监听计数覆盖所有发送路径
        // （输入栏/队列/建议/组件）而不逐个改调用点。`prevPendingCount == 0`
        // 守卫把"用户此刻发送"（此前队列空，直接追加 user 尾节点）与"队列排水
        // 追加"（排水前 pending 非空）区分开——后者不得拽走读历史的用户。
        // 其余结构性插入无需处理：TimelineTail 恒占 lazy index 0，Compose 的
        // key 锚定使插入永远发生在它之上（index ≥ 1），钉底视图原生保持不动。
        LaunchedEffect(state, conversation.id) {
            var prevPendingCount = -1
            var prevNodeCount = -1
            var prevLastNodeId: Uuid? = null
            snapshotFlow {
                val lastNode = currentConversationState.messageNodes.lastOrNull()
                Triple(
                    currentPendingUserMessages.size,
                    currentConversationState.messageNodes.size,
                    lastNode?.id to lastNode?.currentMessage?.role,
                )
            }.collect { (pendingCount, nodeCount, lastIdAndRole) ->
                val (lastNodeId, lastRole) = lastIdAndRole
                val pendingAdded = if (prevPendingCount >= 0) pendingCount - prevPendingCount else 0
                val directSendAppend = prevPendingCount == 0 &&
                    prevNodeCount >= 0 &&
                    nodeCount > prevNodeCount &&
                    lastNodeId != null &&
                    lastNodeId != prevLastNodeId &&
                    lastRole == MessageRole.USER
                prevPendingCount = pendingCount
                prevNodeCount = nodeCount
                prevLastNodeId = lastNodeId
                if (pendingAdded > 0 || directSendAppend) {
                    state.requestScrollToItem(0)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                // reverseLayout: scrollBy(+) moves toward the list end, which is
                // now the visual top (older messages) — so volume-up scrolls up.
                scope.launch {
                    state.scrollBy(if (isVolumeUp) scrollAmount else -scrollAmount)
                }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    LaunchedEffect(conversation.id, state) {
        snapshotFlow {
            val loadState = timelineLoadStateState
            val visibleItems = state.layoutInfo.visibleItemsInfo
            TimelineHistoryLoadSignal(
                historyVisible = visibleItems.any { it.key == HistoryLoadingItemKey },
                initialized = loadState.initialized,
                fullyLoaded = loadState.isFullyLoaded,
                prefetching = loadState.prefetchingOlder,
                loadedNodeCount = loadState.loadedNodeCount,
            )
        }
            .distinctUntilChanged()
            .collect { signal ->
                if (!signal.initialized || signal.fullyLoaded || signal.prefetching || !signal.historyVisible) {
                    return@collect
                }
                val anchor = state.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key != HistoryLoadingItemKey }
                    ?.let { TimelineScrollAnchor(key = it.key, offset = it.offset) }
                loadOlderTimelineState()
                withFrameNanos { }
                anchor?.let { previous ->
                    val current = state.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == previous.key }
                        ?: return@let
                    val delta = current.offset - previous.offset
                    if (abs(delta) > 1) {
                        state.scrollBy(delta.toFloat())
                    }
                }
            }
    }

    LaunchedEffect(conversation.id, chatRegexes) {
        snapshotFlow {
            val latestConversation = currentConversationState
            val latestTimelinePlan = chatTimelinePlanState
            state.markdownPrewarmTexts(
                messageNodes = latestConversation.messageNodes,
                regexes = chatRegexes,
                loadingLastMessage = latestTimelinePlan.timelineLoading,
                timelinePlan = latestTimelinePlan,
            )
        }
            .distinctUntilChanged()
            .collectLatest { contents ->
                withContext(Dispatchers.Default) {
                    contents.distinct().forEach { content ->
                        prewarmMarkdownContent(content)
                    }
                }
            }
    }

}
