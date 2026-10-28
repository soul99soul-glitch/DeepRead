package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation

internal val ActionOptionLineRegex = Regex(
    """^\s*(?:[-*+•·]|[0-9]{1,2}[.)、]|[（(]?[一二三四五六七八九十]{1,3}[）).、])\s+(.+?)\s*$"""
)
internal val ActionOptionCuePhrases = listOf(
    "你想让我",
    "你想让",
    "你要我",
    "要不要我",
    "你想要",
    "我可以帮你",
    "我可以继续",
    "你可以选择",
    "请选择",
    "哪一种",
    "哪种",
    "哪一个",
    "做哪",
    "选项",
    "choose",
    "option",
    "which one",
    "do next",
    "next step",
    "would you like",
)

internal fun Conversation.actionSuggestionTexts(): List<String> {
    val lastMessage = messageNodes.lastOrNull()?.currentMessage
    if (lastMessage?.role != MessageRole.ASSISTANT) return emptyList()

    val explicitOptions = lastMessage.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .extractAssistantActionOptions()

    return (explicitOptions + chatSuggestions)
        .mapNotNull { it.normalizedActionSuggestionOrNull() }
        .distinctBy { it.compactSuggestionKey() }
        .take(10)
}

private fun String.extractAssistantActionOptions(): List<String> {
    val lines = lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    val cueIndex = lines.indexOfLast { it.isActionOptionCueLine() }
    if (cueIndex < 0) return emptyList()

    val options = mutableListOf<String>()
    for (line in lines.drop(cueIndex + 1).take(12)) {
        val option = ActionOptionLineRegex.matchEntire(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.normalizedActionSuggestionOrNull()
        if (option == null) {
            if (options.isNotEmpty()) break
            continue
        }
        options += option
        if (options.size >= 8) break
    }
    return options
}

private fun String.isActionOptionCueLine(): Boolean {
    if (ActionOptionCuePhrases.any { phrase -> contains(phrase, ignoreCase = true) }) {
        return true
    }
    val asksForNextAction = contains("接下来") || contains("下一步")
    val hasChoiceSignal = contains("?") ||
        contains("？") ||
        contains("哪") ||
        contains("选") ||
        contains("想") ||
        contains("要")
    return asksForNextAction && hasChoiceSignal
}

private fun String.normalizedActionSuggestionOrNull(): String? {
    val rawText = trim()
    val text = (ActionOptionLineRegex.matchEntire(rawText)?.groupValues?.getOrNull(1) ?: rawText)
        .removePrefix("[ ]")
        .removePrefix("[x]")
        .removePrefix("[X]")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()
        .trim('：', ':', '。', '，', ',', '；', ';')
        .replace(Regex("""\s+"""), " ")
        .takeIf { it.length in 2..80 }
        ?: return null
    if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
        return null
    }
    return text
}

private fun String.compactSuggestionKey(): String {
    return replace(Regex("""\s+"""), "").lowercase()
}
