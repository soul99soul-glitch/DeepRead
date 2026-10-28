package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatListSupportTest {
    @Test
    fun `latest render token is empty for empty conversation`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        )

        assertEquals("0:empty", conversation.latestRenderToken())
    }

    @Test
    fun `latest render token uses current message from last node`() {
        val first = UIMessage.user("first")
        val unselected = UIMessage.assistant("unselected")
        val selected = UIMessage.assistant("selected")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode.of(first),
                MessageNode(
                    messages = listOf(unselected, selected),
                    selectIndex = 1,
                ),
            ),
        )

        val token = conversation.latestRenderToken()

        assertTrue(token.startsWith("${conversation.messageNodes.size}:${selected.id}:${selected.parts.size}:0:"))
        assertTrue(token.contains("text:8:selected:${"selected".hashCode()}"))
    }

    @Test
    fun `latest render token keeps compact text and tool part format`() {
        val textMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("12345678901234567890")),
        )
        val toolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )

        val textToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(textMessage)),
        ).latestRenderToken()
        val toolToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(toolMessage)),
        ).latestRenderToken()

        assertTrue(textToken.contains("text:20:5678901234567890:${"12345678901234567890".hashCode()}"))
        assertTrue(toolToken.contains("tool:call-1:search:2:{}:${"{}".hashCode()}:true:approved:1:"))
    }

    @Test
    fun `latest render token changes when non trailing tool input changes`() {
        val first = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{\"q\":\"a\"}",
                ),
                UIMessagePart.Text("tail"),
            ),
        )
        val second = first.copy(
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{\"q\":\"amber\"}",
                ),
                UIMessagePart.Text("tail"),
            ),
        )

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken.contains("tool:call-1:search:9:"))
        assertTrue(secondToken.contains("tool:call-1:search:13:"))
        assertTrue(firstToken != secondToken)
    }

    @Test
    fun `latest render token changes when same shape text content changes`() {
        val first = UIMessage.assistant("A1234567890123456")
        val second = first.copy(parts = listOf(UIMessagePart.Text("B1234567890123456")))

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken != secondToken)
    }

    @Test
    fun `latest render token changes when same count annotations change`() {
        val first = UIMessage.assistant("answer").copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation(title = "old", url = "https://old.example")),
        )
        val second = first.copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation(title = "new", url = "https://new.example")),
        )

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken != secondToken)
    }
}
