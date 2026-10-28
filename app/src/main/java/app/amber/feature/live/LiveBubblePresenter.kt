package app.amber.feature.live

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.amber.core.automation.AmberAccessibilityService
import app.amber.feature.bubble.BubbleWindow
import app.amber.feature.live.bubble.LiveBubbleContent
import app.amber.feature.ui.theme.AmberAgentTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the process-lived Companion window and presentation state, independent of a page. */
internal class LiveBubblePresenter(
    private val ownPackageName: String,
    private val state: StateFlow<LiveModeUiState>,
    private val onFillDraft: () -> LiveFillResult,
    private val onRefresh: () -> Unit,
    private val onStop: () -> Unit,
    private val onSuggestViewed: () -> Unit,
    private val serviceProvider: () -> AmberAccessibilityService? = { AmberAccessibilityService.getActiveService() },
) {
    private val bubble = BubbleWindow()
    private val _bubbleExpanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _bubbleExpanded.asStateFlow()

    @Volatile
    private var lastSeenMillis: Long = 0L

    @Volatile
    private var lastSuggestViewedAt: Long = 0L

    val isShowing: Boolean get() = bubble.isShowing

    fun markSuggestViewed(atMillis: Long) {
        if (atMillis <= lastSuggestViewedAt) return
        lastSuggestViewedAt = atMillis
        onSuggestViewed()
    }

    /** 展开即视为"查看"：新鲜自动结果记指标（P2 终审 #3 口径移到这里，跨重挂仍按结果时间戳去重）。 */
    fun setBubbleExpanded(expanded: Boolean) {
        val s = state.value
        if (expanded && s.card != null && s.lastUpdatedAtMillis > lastSeenMillis) {
            if (s.lastResultAuto) markSuggestViewed(s.lastUpdatedAtMillis)
            lastSeenMillis = s.lastUpdatedAtMillis
        }
        _bubbleExpanded.value = expanded
    }

    fun stop() {
        bubble.hide()
        _bubbleExpanded.value = false
        lastSeenMillis = 0L
    }

    /** 每个 runLoop tick 调一次：根据状态决定气泡显隐。仅主线程。 */
    fun sync(liveSetting: LiveModeSetting, taskBubbleVisible: Boolean) {
        val service = serviceProvider()
        if (service == null ||
            !liveSetting.enabled ||
            !liveSetting.bubbleEnabled ||
            !state.value.active ||
            service.activePackageName() == ownPackageName ||
            // 任务气泡与伴随气泡共用窗口位置，agent 任务运行中优先展示任务
            taskBubbleVisible
        ) {
            bubble.hide()
            return
        }
        bubble.show(service) {
            AmberAgentTheme {
                val uiState by state.collectAsState()
                val isExpanded by expanded.collectAsState()
                LiveBubbleContent(
                    state = uiState,
                    expanded = isExpanded,
                    lastSeenMillis = lastSeenMillis,
                    onExpandedChange = ::setBubbleExpanded,
                    onFillDraft = onFillDraft,
                    onRefresh = onRefresh,
                    onStop = onStop,
                    onDrag = bubble::moveBy,
                    onDragEnd = bubble::snapToEdge,
                    onSizeChanged = bubble::requestReclamp,
                    anchorEndProvider = bubble::isAnchoredEnd,
                )
            }
        }
    }

}
