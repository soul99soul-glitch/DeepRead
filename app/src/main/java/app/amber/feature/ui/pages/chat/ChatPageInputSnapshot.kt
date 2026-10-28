package app.amber.feature.ui.pages.chat

import app.amber.ai.ui.UIMessage
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.model.toMessageNode
import kotlin.uuid.Uuid

internal class ChatPageInputSnapshot(
    val id: Uuid,
    val assistantId: Uuid,
    val title: String,
    val hasMessages: Boolean,
    val latestMessage: UIMessage?,
) {
    override fun equals(other: Any?): Boolean =
        other is ChatPageInputSnapshot &&
            id == other.id &&
            assistantId == other.assistantId &&
            title == other.title &&
            hasMessages == other.hasMessages &&
            latestMessage?.id == other.latestMessage?.id

    override fun hashCode(): Int =
        (((id.hashCode() * 31 + assistantId.hashCode()) * 31 + title.hashCode()) * 31 +
            hasMessages.hashCode()) * 31 + (latestMessage?.id?.hashCode() ?: 0)

    fun toConversation(): Conversation {
        val inputNodes = when {
            latestMessage != null -> listOf(latestMessage.toMessageNode())
            hasMessages -> listOf(MessageNode(messages = emptyList()))
            else -> emptyList()
        }
        return Conversation(
            id = id,
            assistantId = assistantId,
            title = title,
            messageNodes = inputNodes,
        )
    }
}

internal fun Conversation.toChatPageInputSnapshot() = ChatPageInputSnapshot(
    id = id,
    assistantId = assistantId,
    title = title,
    hasMessages = messageNodes.isNotEmpty(),
    latestMessage = latestCurrentMessage(),
)

private fun Conversation.latestCurrentMessage(): UIMessage? =
    messageNodes.asReversed().firstNotNullOfOrNull { node ->
        node.messages.getOrNull(node.selectIndex)
    }
