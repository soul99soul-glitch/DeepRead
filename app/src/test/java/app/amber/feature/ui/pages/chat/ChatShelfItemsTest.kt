package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatShelfItemsTest {
    @Test
    fun `collects current-branch artifacts newest first without duplicates`() {
        val first = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Document(url = "file:///a/spec.pdf", fileName = "spec.pdf")),
                ),
            ),
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = "file:///img/cat.png")),
        )
        val second = MessageNode(
            messages = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old variant"), UIMessagePart.Image("file:///img/hidden.png"))),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool, UIMessagePart.Image(url = "file:///img/cat.png")),
                    annotations = listOf(UIMessageAnnotation.UrlCitation(title = "Docs", url = "https://example.com")),
                ),
            ),
            selectIndex = 1,
        )
        val items = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, second)).collectShelfItems()
        assertEquals(
            listOf(ShelfItemKind.IMAGE, ShelfItemKind.WEB, ShelfItemKind.FILE),
            items.map { it.kind },
        )
        assertEquals(listOf("cat.png", "Docs", "spec.pdf"), items.map { it.label })
        assertEquals(second.id, items[0].nodeId)
    }

    @Test
    fun `inline data images get no label and duplicates keep the newest message`() {
        val older = MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image("file:///x/a.png?v=1")))))
        val newer = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Image("data:image/png;base64,AAAA"), UIMessagePart.Image("file:///x/a.png?v=1")),
                ),
            ),
        )
        val items = Conversation(assistantId = Uuid.random(), messageNodes = listOf(older, newer)).collectShelfItems()
        assertEquals(listOf("", "a.png"), items.map { it.label })
        assertEquals(listOf(newer.id, newer.id), items.map { it.nodeId })
        assertEquals(newer.messages[0].id.toString(), items[0].messageId)
    }

    @Test
    fun `collects openable show-widget HTML cards from assistant text and respects setting toggle`() {
        val widgetFence = """
            这里是架构说明：
            ```show-widget
            {"title":"系统架构图","widget_code":"<svg viewBox=\"0 0 200 100\" xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"180\" height=\"80\"/></svg>"}
            ```
        """.trimIndent()
        val node = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(widgetFence)),
                ),
            ),
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val enabledItems = conversation.collectShelfItems()
        assertEquals(listOf(ShelfItemKind.HTML), enabledItems.map { it.kind })
        assertEquals(listOf("系统架构图"), enabledItems.map { it.label })
        assertEquals(node.id, enabledItems[0].nodeId)

        val disabledItems = conversation.collectShelfItems(
            generativeUiSetting = app.amber.core.settings.GenerativeUiSetting(enabled = false),
        )
        assertEquals(emptyList<ShelfItem>(), disabledItems)
    }
    @Test
    fun `collects video parts as VIDEO and markdown images from text`() {
        val video = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Video(url = "file:///v/clip.mp4")),
                ),
            ),
        )
        val assistant = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text(
                            "宋太祖两张：\n![画像](https://example.com/a.jpg)\n![b](https://example.com/b.png)",
                        ),
                    ),
                ),
            ),
        )
        val items = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(video, assistant),
        ).collectShelfItems()

        assertEquals(
            listOf(ShelfItemKind.IMAGE, ShelfItemKind.IMAGE, ShelfItemKind.VIDEO),
            items.map { it.kind },
        )
        // 有 alt 时用 alt 当标签，无 alt 才回落 URL 末段。
        assertEquals(listOf("画像", "b", "clip.mp4"), items.map { it.label })
        assertEquals("https://example.com/a.jpg", items[0].target)
        assertEquals("file:///v/clip.mp4", items[2].target)
    }

    @Test
    fun `markdown images inside fenced code are not shelf artifacts`() {
        val node = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text(
                            "示例：\n```markdown\n![x](https://example.com/not-a-real-asset.png)\n```",
                        ),
                    ),
                ),
            ),
        )
        val items = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node)).collectShelfItems()
        assertEquals(emptyList<ShelfItemKind>(), items.map { it.kind })
    }
}
