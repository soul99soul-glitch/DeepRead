package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MessageNode
import app.amber.core.model.AssistantRegex
import app.amber.feature.ui.components.message.MessageRenderCache

internal const val MarkdownPrewarmBeforeItems = 4
internal const val MarkdownPrewarmAfterItems = 8
internal const val MarkdownPrewarmMaxTexts = 32

internal fun LazyListState.markdownPrewarmTexts(
    messageNodes: List<MessageNode>,
    regexes: List<AssistantRegex>,
    loadingLastMessage: Boolean,
    timelinePlan: ChatTimelinePlan,
): List<String> {
    if (messageNodes.isEmpty()) return emptyList()
    val visibleMessageIndexes = layoutInfo.visibleItemsInfo
        .mapNotNull { timelinePlan.messageIndexAt(it.index) }
        .filter { it in messageNodes.indices }
        .distinct()
    if (visibleMessageIndexes.isEmpty()) return emptyList()

    val start = (visibleMessageIndexes.minOrNull()!! - MarkdownPrewarmBeforeItems)
        .coerceAtLeast(0)
    val end = (visibleMessageIndexes.maxOrNull()!! + MarkdownPrewarmAfterItems)
        .coerceAtMost(messageNodes.lastIndex)

    return buildList {
        for (index in start..end) {
            if (loadingLastMessage && index == messageNodes.lastIndex) continue
            addAll(messageNodes[index].markdownPrewarmTexts(regexes))
            if (size >= MarkdownPrewarmMaxTexts) break
        }
    }.take(MarkdownPrewarmMaxTexts)
}

private fun MessageNode.markdownPrewarmTexts(regexes: List<AssistantRegex>): List<String> {
    val message = currentMessage
    return message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .mapNotNull { part ->
            MessageRenderCache.displayMarkdownText(
                text = part.text,
                regexes = regexes,
                role = message.role,
            )
                .takeIf { it.isNotBlank() }
        }
}
