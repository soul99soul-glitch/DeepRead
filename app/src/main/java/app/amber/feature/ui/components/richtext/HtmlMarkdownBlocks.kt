package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.dokar.sonner.ToastType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Check
import app.amber.agent.R
import app.amber.feature.ui.components.message.LocalSearchImageUrls
import app.amber.feature.ui.components.message.LocalSearchSources
import app.amber.feature.ui.components.message.LocalStripUnverifiedLinks
import app.amber.feature.ui.components.table.DataTable
import app.amber.feature.ui.context.LocalChatFontScale
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.core.utils.openUrl
import app.amber.core.utils.toDp
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ---- Node dispatching ----

@Composable
private fun HtmlStyledElement(
    element: Element,
    content: @Composable () -> Unit,
) {
    val baseTextStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val elementStyle = remember(element.attr("style"), density, baseTextStyle) {
        element.attr("style").takeIf { it.isNotBlank() }?.let {
            parseBlockTextStyle(
                style = it,
                density = density,
                baseTextStyle = baseTextStyle,
            )
        }
    }

    if (elementStyle != null) {
        ProvideTextStyle(baseTextStyle.merge(elementStyle), content)
    } else {
        content()
    }
}

@Composable
internal fun HtmlBodyNode(node: Node, onClickCitation: (String) -> Unit) {
    when (node) {
        is Element -> HtmlBlockElement(element = node, onClickCitation = onClickCitation)
        is TextNode -> {
            val text = node.text().trim()
            if (text.isNotEmpty()) Text(text = text)
        }
    }
}

@Composable
private fun HtmlBlockElement(
    element: Element,
    onClickCitation: (String) -> Unit,
    listLevel: Int = 0,
) {
    when (element.tagName().lowercase()) {
        "p" -> HtmlParagraph(element = element, onClickCitation = onClickCitation)

        "h1", "h2", "h3", "h4", "h5", "h6" -> HtmlHeading(
            element = element,
            onClickCitation = onClickCitation,
        )

        "ul" -> HtmlList(
            element = element,
            ordered = false,
            onClickCitation = onClickCitation,
            level = listLevel,
        )

        "ol" -> HtmlList(
            element = element,
            ordered = true,
            onClickCitation = onClickCitation,
            level = listLevel,
        )

        "pre" -> HtmlCodeBlock(element = element)

        "blockquote" -> HtmlStyledElement(element = element) {
            HtmlBlockquote(element = element, onClickCitation = onClickCitation)
        }

        "table" -> HtmlStyledElement(element = element) {
            HtmlTable(element = element, onClickCitation = onClickCitation)
        }

        "hr" -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            thickness = 0.5.dp,
        )

        "img" -> {
            val src = element.attr("src")
            val alt = element.attr("alt")
            if (LocalSearchImageUrls.current?.contains(src) == true) return
            if (src.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ZoomableAsyncImage(
                        model = src,
                        contentDescription = alt.takeIf { it.isNotEmpty() },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .widthIn(min = 120.dp, max = 360.dp)
                            .heightIn(min = 120.dp, max = 420.dp),
                    )
                }
            }
        }

        "span" -> {
            // Block-level math span emitted directly into body
            if (element.hasClass("math") && element.attr("inline") != "true") {
                HtmlMathBlock(formula = element.text())
            } else {
                HtmlInlineGroup(nodes = listOf(element), onClickCitation = onClickCitation)
            }
        }

        "details" -> HtmlStyledElement(element = element) {
            HtmlDetails(element = element, onClickCitation = onClickCitation)
        }

        "progress" -> HtmlProgress(element = element)

        "div" -> HtmlStyledElement(element = element) {
            Column(modifier = Modifier.fillMaxWidth()) {
                element.childNodes().fastForEach { HtmlBodyNode(it, onClickCitation) }
            }
        }

        else -> HtmlStyledElement(element = element) {
            // Generic fallback: recurse into children
            element.childNodes().forEach { HtmlBodyNode(it, onClickCitation) }
        }
    }
}

// ---- Block renderers ----

@Composable
private fun HtmlParagraph(element: Element, onClickCitation: (String) -> Unit) {
    val baseTextStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val paragraphStyle = remember(element.attr("style"), density, baseTextStyle) {
        element.attr("style").takeIf { it.isNotBlank() }?.let {
            parseBlockTextStyle(
                style = it,
                density = density,
                baseTextStyle = baseTextStyle,
            )
        }
    }

    if (paragraphStyle != null) {
        ProvideTextStyle(baseTextStyle.merge(paragraphStyle)) {
            HtmlParagraphContent(element = element, onClickCitation = onClickCitation, density = density)
        }
    } else {
        HtmlParagraphContent(element = element, onClickCitation = onClickCitation, density = density)
    }
}

@Composable
private fun HtmlParagraphContent(
    element: Element,
    onClickCitation: (String) -> Unit,
    density: Density,
) {
    val hasImages = element.select("img").isNotEmpty()
    // A span.math with inline != "true" is a block math element
    val hasBlockMath = element.select("span.math").any { it.attr("inline") != "true" }

    if (hasImages || hasBlockMath) {
        // Mixed block content: render children individually in a FlowRow
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            element.childNodes().fastForEach { child ->
                HtmlInlineAsComposable(node = child, onClickCitation = onClickCitation)
            }
        }
        return
    }

    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val hasInlineMath = element.select("span.math").any { it.attr("inline") == "true" }
    val colorScheme = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current
    val context = LocalContext.current
    val onClickUrl: (String) -> Unit = remember(context) {
        { url -> context.openUrl(url) }
    }
    val searchSources = LocalSearchSources.current
    val stripUnverified = LocalStripUnverifiedLinks.current

    val (annotatedString, inlineContents) = remember(
        element.outerHtml(),
        enableLatexRendering,
        colorScheme,
        density,
        textStyle,
        onClickCitation,
        onClickUrl,
        searchSources,
        stripUnverified,
    ) {
        val contents = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            element.childNodes().forEach { child ->
                appendHtmlInlineNode(
                    node = child,
                    colorScheme = colorScheme,
                    inlineContents = contents,
                    density = density,
                    style = textStyle,
                    enableLatexRendering = enableLatexRendering,
                    onClickCitation = onClickCitation,
                    onClickUrl = onClickUrl,
                    searchSources = searchSources,
                    stripUnverified = stripUnverified,
                )
            }
        }
        text to contents
    }

    Text(
        text = annotatedString,
        inlineContent = inlineContents,
        softWrap = true,
        overflow = TextOverflow.Visible,
        modifier = Modifier.fillMaxWidth(),
        style = textStyle.copy(
            lineHeight = if (hasInlineMath && enableLatexRendering)
                TextUnit.Unspecified
            else
                textStyle.lineHeight,
        ),
    )
}

@Composable
private fun HtmlHeading(element: Element, onClickCitation: (String) -> Unit) {
    val level = element.tagName().removePrefix("h").toIntOrNull() ?: 1
    val headingStyle = when (level) {
        1 -> HeaderStyle.H1
        2 -> HeaderStyle.H2
        3 -> HeaderStyle.H3
        4 -> HeaderStyle.H4
        5 -> HeaderStyle.H5
        else -> HeaderStyle.H6
    }
    // 与 Markdown.kt 标题分支一致：绝对字号乘聊天字号比例（非聊天面默认 1f 不变）。
    val fontScale = LocalChatFontScale.current
    val scaledHeading = headingStyle.copy(
        fontSize = headingStyle.fontSize * fontScale,
        lineHeight = headingStyle.lineHeight * fontScale,
    )
    // 垂直 padding 同乘 scale，与块级路径保持同一呼吸感比例。
    val verticalPadding = when (level) {
        1 -> 16.dp; 2 -> 14.dp; 3 -> 12.dp; 4 -> 10.dp; 5 -> 8.dp; else -> 6.dp
    } * fontScale
    ProvideTextStyle(LocalTextStyle.current.merge(scaledHeading)) {
        Box(modifier = Modifier.padding(vertical = verticalPadding)) {
            HtmlParagraph(element = element, onClickCitation = onClickCitation)
        }
    }
}

@Composable
private fun HtmlList(
    element: Element,
    ordered: Boolean,
    onClickCitation: (String) -> Unit,
    level: Int,
) {
    HtmlStyledElement(element = element) {
        Column(modifier = Modifier.padding(start = (level * 8).dp, top = 4.dp, bottom = 4.dp)) {
            val bulletBase = when (level % 3) {
                0 -> "•"; 1 -> "◦"; else -> "▪"
            }
            var orderedIndex = 1
            element.children().fastForEach { item ->
                if (item.tagName().lowercase() == "li") {
                    val bullet = if (ordered) "${orderedIndex++}. " else "$bulletBase "
                    HtmlListItem(
                        item = item,
                        bulletText = bullet,
                        onClickCitation = onClickCitation,
                        level = level,
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlListItem(
    item: Element,
    bulletText: String,
    onClickCitation: (String) -> Unit,
    level: Int,
) {
    val isTaskItem = item.hasClass("task-list-item")
    val checkboxInput = item.selectFirst("input[type=checkbox]")
    val isChecked = checkboxInput?.hasAttr("checked") == true

    HtmlStyledElement(element = item) {
        Column {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                if (isTaskItem && checkboxInput != null) {
                    // Checkbox indicator
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        modifier = Modifier.padding(end = 4.dp, top = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isChecked) {
                                Icon(
                                    imageVector = Lucide.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = bulletText,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.alignByBaseline(),
                    )
                }

                // Item inline content (excluding nested lists and the checkbox input)
                Column(modifier = Modifier.weight(1f)) {
                    val directContentNodes = item.childNodes().filter { node ->
                        !(node is Element &&
                            (node.tagName().lowercase() in listOf("ul", "ol") ||
                                (node.tagName().lowercase() == "input" && node.attr("type") == "checkbox")))
                    }
                    // Group consecutive inline nodes and render as a single paragraph
                    val groups = mutableListOf<MutableList<Node>>()
                    directContentNodes.fastForEach { node ->
                        if (node is Element && node.tagName().lowercase() == "p") {
                            groups.add(mutableListOf(node))
                        } else {
                            val last = groups.lastOrNull()
                            if (last != null && last.none {
                                    it is Element && it.tagName().lowercase() == "p"
                                }) {
                                last.add(node)
                            } else {
                                groups.add(mutableListOf(node))
                            }
                        }
                    }
                    groups.fastForEach { group ->
                        val first = group.firstOrNull()
                        if (first is Element && first.tagName().lowercase() == "p") {
                            HtmlParagraph(element = first, onClickCitation = onClickCitation)
                        } else {
                            HtmlInlineGroup(nodes = group, onClickCitation = onClickCitation)
                        }
                    }
                }
            }

            // Nested lists
            item.children().fastForEach { child ->
                val tag = child.tagName().lowercase()
                if (tag == "ul" || tag == "ol") {
                    HtmlList(
                        element = child,
                        ordered = tag == "ol",
                        onClickCitation = onClickCitation,
                        level = level + 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlCodeBlock(element: Element) {
    val codeElement = element.selectFirst("code")
    val language = codeElement?.classNames()
        ?.find { it.startsWith("language-") }
        ?.removePrefix("language-")
        ?: "plaintext"
    val code = codeElement?.wholeText()?.trimEnd('\n') ?: element.wholeText().trimEnd('\n')

    HighlightCodeBlock(
        code = code,
        language = language,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        completeCodeBlock = true,
    )
}

@Composable
private fun HtmlBlockquote(element: Element, onClickCitation: (String) -> Unit) {
    ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
        // Bug fix: drawWithContent + drawRect 把 bg 画在 content 之上盖住文字, 深色下看不清.
        // 改 drawBehind, bgColor alpha 0.2→0.12 减轻蒙层感.
        val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)
        Column(
            modifier = Modifier
                .drawBehind {
                    drawRect(color = bgColor, size = size)
                    drawRect(color = borderColor, size = Size(10f, size.height))
                }
                .padding(8.dp),
        ) {
            element.childNodes().fastForEach { HtmlBodyNode(it, onClickCitation) }
        }
    }
}

@Composable
internal fun HtmlMathBlock(formula: String) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    if (enableLatexRendering) {
        MathBlock(
            latex = formula,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    } else {
        Text(
            text = formula,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun HtmlTable(element: Element, onClickCitation: (String) -> Unit) {
    val headerElements = element.select("thead tr th")
    val columnCount = headerElements.size.takeIf { it > 0 }
        ?: element.select("tbody tr:first-child td").size
    if (columnCount == 0) return

    val headers = List(columnCount) { col ->
        @Composable {
            if (col < headerElements.size) {
                HtmlStyledElement(element = headerElements[col]) {
                    HtmlInlineGroup(
                        nodes = headerElements[col].childNodes(),
                        onClickCitation = onClickCitation,
                    )
                }
            }
        }
    }

    val bodyRows = element.select("tbody tr")
    val rows = bodyRows.map { tr ->
        val cellElements = tr.select("td")
        List(columnCount) { col ->
            @Composable {
                if (col < cellElements.size) {
                    HtmlStyledElement(element = cellElements[col]) {
                        HtmlInlineGroup(
                            nodes = cellElements[col].childNodes(),
                            onClickCitation = onClickCitation,
                        )
                    }
                }
            }
        }
    }

    val context = LocalContext.current
    val toaster = LocalToaster.current

    Box(modifier = Modifier.fillMaxWidth()) {
        DataTable(
            headers = headers,
            rows = rows,
            modifier = Modifier.padding(vertical = 8.dp),
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
        )
        // 导出 CSV: 序列化表格为 RFC 4180 后写入 cache 并通过 FileProvider 分享
        IconButton(
            onClick = {
                runCatching {
                    shareTableCsv(context, element)
                }.onFailure { error ->
                    error.printStackTrace()
                    toaster.show(
                        context.getString(R.string.table_export_failed),
                        type = ToastType.Error,
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            Icon(
                imageVector = Lucide.Download,
                contentDescription = stringResource(R.string.table_export),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 把 HTML 表格序列化为 RFC 4180 CSV: 含引号/逗号/换行的字段加双引号包裹, 内部引号翻倍,
 * 行以 CRLF 结尾. 表头取 thead 的 th, 数据行取 tbody 的 tr/td (与 HtmlTable 渲染路径一致).
 */
@Composable
private fun HtmlDetails(element: Element, onClickCitation: (String) -> Unit) {
    // Delegate to the existing SimpleHtmlBlock details renderer via a mini-document
    val summaryElement = element.children().find { it.tagName().lowercase() == "summary" }
    val summaryText = summaryElement?.text() ?: "Details"

    var expanded by remember { mutableStateOf(element.hasAttr("open")) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = if (expanded) "▼ " else "▶ ")
            Text(text = summaryText, fontWeight = FontWeight.Medium)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                element.childNodes().fastForEach { child ->
                    if (!(child is Element && child.tagName().lowercase() == "summary")) {
                        HtmlBodyNode(child, onClickCitation)
                    }
                }
            }
        }
    }
}

@Composable
private fun HtmlProgress(element: Element) {
    val value = element.attr("value").toFloatOrNull() ?: 0f
    val max = element.attr("max").toFloatOrNull()?.takeIf { it > 0 } ?: 100f
    val progress = (value / max).coerceIn(0f, 1f)

    val style = element.attr("style")
    val widthValue = parseCssDeclarations(style)["width"] ?: element.attr("width")

    val widthModifier = when {
        widthValue.endsWith("%") -> widthValue.removeSuffix("%").toFloatOrNull()
            ?.let { Modifier.fillMaxWidth(it / 100f) } ?: Modifier.fillMaxWidth()
        widthValue.endsWith("px") -> widthValue.removeSuffix("px").toIntOrNull()
            ?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
        widthValue.isNotEmpty() -> widthValue.toIntOrNull()
            ?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
        else -> Modifier.fillMaxWidth()
    }

    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress },
        modifier = widthModifier.padding(vertical = 4.dp),
    )
}
