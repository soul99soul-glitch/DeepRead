package app.amber.feature.ui.pages.chat

import androidx.compose.runtime.Immutable
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.generative.GenerativeWidgetParser
import app.amber.core.ai.generative.GenerativeWidgetSanitizeStatus
import app.amber.core.ai.generative.GenerativeWidgetSanitizer
import app.amber.core.ai.generative.GenerativeWidgetSegment
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.model.Conversation
import app.amber.core.settings.GenerativeUiSetting
import kotlin.uuid.Uuid

enum class ShelfItemKind { IMAGE, VIDEO, FILE, MINI_APP, HTML, WEB }

@Immutable
data class ShelfItem(
    val kind: ShelfItemKind,
    val label: String,
    /** Image/video/file URL, web URL, widget key, or mini-app id. */
    val target: String,
    val nodeId: Uuid,
    /** Selected message id of [nodeId] when collected. */
    val messageId: String,
)

/**
 * Artifacts on the current branch, newest first: images and files from messages and
 * tool outputs, mini apps, inline HTML/generative widgets, and cited web pages.
 * Duplicate targets keep the newest.
 */
fun Conversation.collectShelfItems(
    generativeUiSetting: GenerativeUiSetting = GenerativeUiSetting(),
): List<ShelfItem> {
    val seen = HashSet<String>()
    val items = ArrayList<ShelfItem>()
    for (node in messageNodes.asReversed()) {
        val message = node.messages.getOrNull(node.selectIndex) ?: continue
        val messageId = message.id.toString()
        val found = ArrayList<ShelfItem>()
        var textPartIndex = 0
        fun visit(part: UIMessagePart, fromToolOutput: Boolean = false) {
            when (part) {
                is UIMessagePart.Image -> if (part.url.isNotBlank()) {
                    found += ShelfItem(ShelfItemKind.IMAGE, part.url.fileLabel(), part.url, node.id, messageId)
                }
                is UIMessagePart.Video -> if (part.url.isNotBlank()) {
                    // 视频也是本对话产物：之前只收图片/文件，用户上传的视频在架子里根本看不到。
                    // Video 没有 fileName 字段，标签用 URL 末段。
                    found += ShelfItem(
                        ShelfItemKind.VIDEO,
                        part.url.fileLabel(),
                        part.url,
                        node.id,
                        messageId,
                    )
                }
                is UIMessagePart.Document -> if (part.url.isNotBlank()) {
                    found += ShelfItem(
                        ShelfItemKind.FILE,
                        part.fileName.ifBlank { part.url.fileLabel() },
                        part.url,
                        node.id,
                        messageId,
                    )
                }
                is UIMessagePart.MiniApp -> if (part.appId.isNotBlank()) {
                    found += ShelfItem(ShelfItemKind.MINI_APP, part.title, part.appId, node.id, messageId)
                }
                is UIMessagePart.Text -> {
                    // 模型常用 `![说明](url)` 内联展示图片，而不是 Image part；这些图在
                    // timeline 里看得见，产物架也必须收录，否则就是"架子里没有这几张图"。
                    // 工具输出里的图片同样算产物，所以这里不看 fromToolOutput。
                    collectMarkdownImages(part.text).forEach { image ->
                        found += ShelfItem(
                            ShelfItemKind.IMAGE,
                            image.alt.ifBlank { image.url.fileLabel() },
                            image.url,
                            node.id,
                            messageId,
                        )
                    }
                    if (!fromToolOutput && message.role == MessageRole.ASSISTANT && generativeUiSetting.enabled) {
                        val currentPartIdx = textPartIndex++
                        if (GenerativeWidgetParser.containsWidgetFence(part.text) ||
                            GenerativeWidgetParser.containsFullHtmlDeckPayload(part.text)
                        ) {
                            var widgetOrdinal = 0
                            GenerativeWidgetParser.parse(part.text, streaming = false)
                                .filterIsInstance<GenerativeWidgetSegment.Widget>()
                                .forEach { widget ->
                                    val sanitized = GenerativeWidgetSanitizer.sanitize(widget.widgetCode, generativeUiSetting)
                                    val openable = sanitized.status == GenerativeWidgetSanitizeStatus.READY &&
                                        (widget.renderer != GuizangHtmlDeckValidator.RENDERER ||
                                            !widget.specJson.isNullOrBlank())
                                    if (openable) {
                                        val label = widget.title?.takeIf { it.isNotBlank() }
                                            ?: when (widget.renderer) {
                                                GuizangHtmlDeckValidator.RENDERER, "slides" -> "HTML 演示"
                                                "vchart", "chart" -> "图表"
                                                else -> ""
                                            }
                                        val target = "html:$messageId:$currentPartIdx:${widgetOrdinal++}"
                                        found += ShelfItem(ShelfItemKind.HTML, label, target, node.id, messageId)
                                    }
                                }
                        }
                    }
                }
                is UIMessagePart.Tool -> part.output.forEach { visit(it, fromToolOutput = true) }
                else -> Unit
            }
        }
        message.parts.forEach { visit(it, fromToolOutput = false) }
        message.annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>().forEach { citation ->
            if (citation.url.isNotBlank()) {
                found += ShelfItem(ShelfItemKind.WEB, citation.title.ifBlank { citation.url }, citation.url, node.id, messageId)
            }
        }
        // Within one message keep reading order; across messages newest first.
        found.forEach { if (seen.add(it.target)) items += it }
    }
    return items
}

/** markdown 图片；alt 可能为空。 */
internal data class MarkdownImage(val alt: String, val url: String)

/**
 * 解析正文里的 `![alt](url)`。
 *
 * 刻意不引入完整 markdown 解析器：产物架只需要"这段文字里嵌了哪些图"。两条必须
 * 守住的规则——**跳过围栏代码块**（代码示例里的图片语法不是图片），以及**不接收
 * 空 URL**。
 */
internal fun collectMarkdownImages(text: String): List<MarkdownImage> {
    if (text.isEmpty() || !text.contains("![")) return emptyList()
    val images = ArrayList<MarkdownImage>()
    var inFence = false
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trimStart()
        if (line.startsWith("```") || line.startsWith("~~~")) {
            inFence = !inFence
            return@forEach
        }
        if (inFence) return@forEach
        collectMarkdownImagesFromLine(rawLine, images)
    }
    return images
}

private fun collectMarkdownImagesFromLine(line: String, out: MutableList<MarkdownImage>) {
    var index = 0
    while (true) {
        val start = line.indexOf("![", index)
        if (start < 0) return
        val altEnd = line.indexOf("](", start + 2)
        if (altEnd < 0) return
        val urlEnd = line.indexOf(')', altEnd + 2)
        if (urlEnd < 0) return
        val alt = line.substring(start + 2, altEnd)
        val url = line.substring(altEnd + 2, urlEnd).trim().substringBefore(' ')
        if (url.isNotEmpty()) out += MarkdownImage(alt = alt, url = url)
        index = urlEnd + 1
    }
}

/** Last path segment; blank for inline data URLs so the UI falls back to the kind label. */
private fun String.fileLabel(): String =
    if (startsWith("data:")) "" else substringAfterLast('/').substringBefore('?')
