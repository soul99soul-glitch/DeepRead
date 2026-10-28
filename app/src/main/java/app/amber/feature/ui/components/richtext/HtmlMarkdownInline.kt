package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.util.fastForEach
import app.amber.feature.ui.components.message.LocalSearchImageUrls
import app.amber.feature.ui.components.message.LocalSearchSources
import app.amber.feature.ui.components.message.LocalStripUnverifiedLinks
import app.amber.feature.ui.components.message.SearchSourcesRegistry
import app.amber.feature.ui.context.LocalSettings
import app.amber.core.utils.openUrl
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ---- Inline group rendering (for list items with mixed inline nodes) ----

/**
 * Renders a list of inline Jsoup nodes as a single Text composable with AnnotatedString.
 * This prevents inline siblings (e.g. <strong>A</strong>和<strong>B</strong>) from being
 * rendered on separate lines.
 */
@Composable
internal fun HtmlInlineGroup(nodes: List<Node>, onClickCitation: (String) -> Unit) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val colorScheme = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val onClickUrl: (String) -> Unit = remember(context) {
        { url -> context.openUrl(url) }
    }
    val searchSources = LocalSearchSources.current
    val stripUnverified = LocalStripUnverifiedLinks.current

    val key = remember(nodes) { nodes.joinToString("") { if (it is Element) it.outerHtml() else it.toString() } }
    val (annotatedString, inlineContents) = remember(
        key,
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
            nodes.fastForEach { node ->
                appendHtmlInlineNode(
                    node = node,
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

    if (annotatedString.isNotEmpty()) {
        Text(text = annotatedString, inlineContent = inlineContents)
    }
}

// ---- Inline-as-Composable rendering (for FlowRow mixed content) ----

/**
 * Renders an individual Jsoup node as a standalone Composable.
 * Used inside FlowRow for paragraphs that mix images, math, and text.
 */
@Composable
internal fun HtmlInlineAsComposable(node: Node, onClickCitation: (String) -> Unit) {
    when (node) {
        is TextNode -> {
            val text = node.text()
            if (text.isNotEmpty()) Text(text = text)
        }

        is Element -> {
            val tag = node.tagName().lowercase()
            when {
                tag == "img" -> {
                    val src = node.attr("src")
                    val alt = node.attr("alt")
                    if (LocalSearchImageUrls.current?.contains(src) == true) return
                    if (src.isNotEmpty()) {
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

                tag == "span" && node.hasClass("math") && node.attr("inline") != "true" -> {
                    HtmlMathBlock(formula = node.text())
                }

                tag == "br" -> {
                    // handled by inline text
                }

                else -> {
                    // Render as an inline text segment
                    val colorScheme = MaterialTheme.colorScheme
                    val textStyle = LocalTextStyle.current
                    val density = LocalDensity.current
                    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
                    val context = LocalContext.current
                    val onClickUrl: (String) -> Unit = remember(context) {
                        { url -> context.openUrl(url) }
                    }
                    val searchSources = LocalSearchSources.current
                    val stripUnverified = LocalStripUnverifiedLinks.current
                    val (annotated, inlineContents) = remember(
                        node.outerHtml(),
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
                            appendHtmlInlineElement(
                                element = node,
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
                        text to contents
                    }
                    Text(text = annotated, inlineContent = inlineContents)
                }
            }
        }
    }
}

// ---- Inline AnnotatedString building ----

internal fun AnnotatedString.Builder.appendHtmlInlineNode(
    node: Node,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
    onClickUrl: (String) -> Unit,
    searchSources: SearchSourcesRegistry?,
    stripUnverified: Boolean,
) {
    when (node) {
        is TextNode -> append(node.text())
        is Element -> appendHtmlInlineElement(
            element = node,
            colorScheme = colorScheme,
            inlineContents = inlineContents,
            density = density,
            style = style,
            enableLatexRendering = enableLatexRendering,
            onClickCitation = onClickCitation,
            onClickUrl = onClickUrl,
            searchSources = searchSources,
            stripUnverified = stripUnverified,
        )
    }
}

private fun AnnotatedString.Builder.appendHtmlInlineElement(
    element: Element,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
    onClickUrl: (String) -> Unit,
    searchSources: SearchSourcesRegistry?,
    stripUnverified: Boolean,
) {
    val cssStyle = element.attr("style").takeIf { it.isNotBlank() }?.let {
        parseInlineSpanStyle(
            style = it,
            density = density,
            baseFontSize = style.fontSize,
        )
    }

    fun recurseChildren(el: Element, inheritedStyle: TextStyle = style) = el.childNodes().fastForEach {
        appendHtmlInlineNode(
            node = it,
            colorScheme = colorScheme,
            inlineContents = inlineContents,
            density = density,
            style = inheritedStyle,
            enableLatexRendering = enableLatexRendering,
            onClickCitation = onClickCitation,
            onClickUrl = onClickUrl,
            searchSources = searchSources,
            stripUnverified = stripUnverified,
        )
    }

    fun appendStyledChildren(spanStyle: SpanStyle) = withStyle(spanStyle) {
        recurseChildren(element, style.merge(spanStyle.asTextStyle()))
    }

    fun appendElementChildren(tagStyle: SpanStyle = SpanStyle()) {
        val elementStyle = tagStyle.merge(cssStyle ?: SpanStyle())
        if (elementStyle == SpanStyle()) {
            recurseChildren(element)
        } else {
            appendStyledChildren(elementStyle)
        }
    }

    when (element.tagName().lowercase()) {
        "b", "strong" -> appendElementChildren(SpanStyle(fontWeight = FontWeight.SemiBold))

        "i", "em" -> appendElementChildren(SpanStyle(fontStyle = FontStyle.Italic))

        "del", "s", "strike" -> appendElementChildren(SpanStyle(textDecoration = TextDecoration.LineThrough))

        "u" -> appendElementChildren(SpanStyle(textDecoration = TextDecoration.Underline))

        "code" -> withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.95.em,
                background = colorScheme.secondaryContainer.copy(alpha = 0.2f),
            ).merge(cssStyle ?: SpanStyle())
        ) {
            append(element.text())
        }

        "a" -> {
            val href = element.attr("href")
            val text = element.text()
            val searchSource = searchSources?.lookup(href)
            when {
                text.startsWith("citation,") -> {
                    // Citation link: [citation,domain](id)
                    val domain = text.substringAfter("citation,")
                    val id = href
                    if (id.length == 6) {
                        appendSearchSourcePill(
                            key = "citation:$id",
                            label = domain,
                            inlineContents = inlineContents,
                            colorScheme = colorScheme,
                            onClick = { onClickCitation(id.trim()) },
                        )
                    } else {
                        append(domain)
                    }
                }

                searchSource != null -> {
                    appendSearchSourcePill(
                        key = "search-source:${searchSource.host}:$href",
                        label = searchSource.name,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        onClick = { onClickUrl(href) },
                    )
                }

                href.isNotEmpty() -> {
                    if (stripUnverified) {
                        // Deep Read strict mode: a link that is neither a citation nor a host
                        // present in the source registry is dropped entirely (text + href).
                        // Readers only ever see pills backed by real prefetched sources.
                    } else {
                        val linkStyle = SpanStyle(
                            color = colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ).merge(cssStyle ?: SpanStyle())
                        withLink(openUrlLinkAnnotation(href, onClickUrl)) {
                            withStyle(linkStyle) {
                                recurseChildren(element, style.merge(linkStyle.asTextStyle()))
                            }
                        }
                    }
                }

                else -> appendElementChildren()
            }
        }

        "span" -> {
            if (element.hasClass("math") && element.attr("inline") == "true") {
                val formula = element.text()
                if (enableLatexRendering) {
                    appendInlineContent(formula, "[Latex]")
                    val (width, height) = with(density) {
                        assumeLatexSize(latex = formula, fontSize = style.fontSize.toPx()).let {
                            it.width().toSp() to it.height().toSp()
                        }
                    }
                    inlineContents.putIfAbsent(
                        formula,
                        InlineTextContent(
                            placeholder = Placeholder(
                                width = width,
                                height = height,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                            children = {
                                MathInline(latex = formula, modifier = Modifier)
                            },
                        ),
                    )
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.95.em)) {
                        append(formula)
                    }
                }
            } else {
                appendElementChildren()
            }
        }

        "font" -> {
            val inlineStyle = buildFontTagStyle(
                element = element,
                density = density,
                baseFontSize = style.fontSize,
            )
            if (inlineStyle != null) {
                appendStyledChildren(inlineStyle)
            } else {
                appendElementChildren()
            }
        }

        "br" -> append("\n")

        else -> appendElementChildren()
    }
}

private fun openUrlLinkAnnotation(url: String, onClickUrl: (String) -> Unit): LinkAnnotation.Url {
    return LinkAnnotation.Url(
        url = url,
        linkInteractionListener = LinkInteractionListener { annotation ->
            val target = (annotation as? LinkAnnotation.Url)?.url ?: url
            onClickUrl(target)
        },
    )
}
