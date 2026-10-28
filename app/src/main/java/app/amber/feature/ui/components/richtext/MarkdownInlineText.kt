package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.util.fastForEach
import app.amber.feature.ui.components.message.LocalSearchSources
import app.amber.feature.ui.components.message.SearchSourcesRegistry
import app.amber.feature.ui.context.LocalSettings
import app.amber.core.utils.openUrl
import app.amber.core.utils.toDp
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import app.amber.feature.ui.components.richtext.tree.textIn

private val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")

@Composable
internal fun Paragraph(
    node: MdNode,
    content: String,
    trim: Boolean = false,
    onClickCitation: (String) -> Unit = {},
    modifier: Modifier,
    // Style for the plain tail suffix (text belonging to the NEXT block, shown
    // as a preview before the parser stabilizes it). Defaults to the current
    // text style, but callers that override LocalTextStyle for their own block
    // (e.g. a heading) must pass the base body style so the preview of the
    // following block isn't rendered with this block's style.
    plainSuffixStyle: TextStyle = LocalTextStyle.current,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    // dumpAst(node, content)
    // Cache the AST-walk result so a per-frame recomposition (triggered by
    // revealClock subscription below) doesn't re-traverse the whole subtree
    // just to decide between FlowRow-of-MarkdownNode (images/block-math
    // present) and the inline AnnotatedString path. Pre-cache mirrors
    // hasInlineMath's pattern further down.
    val hasImageOrBlockMath = remember(node) {
        node.findChildOfTypeRecursive(MdNodeType.Image, MdNodeType.MathBlock) != null
    }
    if (hasImageOrBlockMath) {
        FlowRow(modifier = modifier.fillWidthIf(LocalMarkdownFillWidth.current)) {
            node.children.fastForEach { child ->
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                MarkdownNode(
                    node = child,
                    content = content,
                    onClickCitation = onClickCitation,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
            }
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    // Populated lazily by appendMarkdownNodeContent during static rebuilds
    // via putIfAbsent for INLINE_LINK citation + INLINE_MATH formula
    // keys. Lives as long as this Paragraph composable (remember without
    // keys), so entries from previously rendered inline nodes can persist
    // across content changes — pre-existing behavior, not introduced by
    // the reveal-overlay refactor. Acceptable because content changes
    // during streaming only append new inline nodes; the user doesn't
    // edit/remove inline content mid-stream in this app.
    val inlineContents = remember {
        mutableStateMapOf<String, InlineTextContent>()
    }
    val hasInlineMath = remember(node) {
        node.findChildOfTypeRecursive(MdNodeType.MathInline) != null
    }
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering

    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val onClickUrl: (String) -> Unit = remember(context) {
        { url -> context.openUrl(url) }
    }
    val searchSources = LocalSearchSources.current

    // Streaming-aware text build: static parsed text is opaque; only the
    // live suffix (newest, not-yet-parsed text) fades in as one batch.
    val baseColor = LocalContentColor.current
    val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current

    // Batch reveal (L4 decoupled): only the inline unparsed suffix animates.
    // A suffix that opens a new block renders as plain tail text below to
    // avoid block-boundary reflow.
    val suffixPlacement = liveSuffixPlacementFor(liveSuffix)
    // Even an Inline-classified suffix can spill past a blank line into the
    // next block (e.g. a heading's trailing words followed by the paragraph
    // after it). Keep only the pre-boundary part inline (this block's
    // style); everything past the blank line drops below as plain body text.
    val (blockInlineSuffix, blockPlainSuffix) = splitLiveSuffixAtBlockBoundary(liveSuffix)
    val inlineLiveSuffix: String
    val plainLiveSuffixRaw: String
    val plainSuffixBaseOffset: Int
    if (suffixPlacement == LiveSuffixPlacement.Inline) {
        inlineLiveSuffix = blockInlineSuffix
        plainLiveSuffixRaw = blockPlainSuffix.replace(BREAK_LINE_REGEX, "\n")
        plainSuffixBaseOffset = liveSuffixSourceOffset + blockInlineSuffix.length
    } else {
        inlineLiveSuffix = ""
        plainLiveSuffixRaw = liveSuffix.replace(BREAK_LINE_REGEX, "\n")
        plainSuffixBaseOffset = liveSuffixSourceOffset
    }
    // The plain segment starts with the boundary blank line itself. In the
    // parsed form that gap is NOT literal empty lines — it is this
    // paragraph's fontSize bottom padding (see the FlowRow padding below).
    // Rendering the raw "\n\n" previewed 1-2 full empty lines (lineHeight
    // each), so every block promotion collapsed them into the fontSize
    // padding and visibly hopped the streaming text upward — the
    // "segmented re-layout at every paragraph boundary" complaint
    // (STREAMING_PRESENTATION_PLAYBOOK 坑8: preview the settled form).
    // Strip the leading newline run; the FlowRow below already renders the
    // gap as padding, making promotion pixel-identical.
    val plainBoundaryStripped = plainLiveSuffixRaw.countLeadingNewlines()
    val plainLiveSuffix = plainLiveSuffixRaw.substring(plainBoundaryStripped)
    val plainSuffixSourceOffset = plainSuffixBaseOffset + plainBoundaryStripped

    FlowRow(
        modifier = modifier
            .fillWidthIf(LocalMarkdownFillWidth.current)
            .then(
                // A pending plain suffix means a following block is already
                // streaming below — pre-render this paragraph's bottom gap the
                // way the parser will (fontSize), so the block promotion swap
                // below is pixel-identical instead of trading empty lines for
                // padding (the per-boundary reflow hop).
                if (node.nextSibling() != null || plainLiveSuffix.isNotEmpty()) {
                    Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
                } else {
                    Modifier
                }
            )
    ) {
        // `node` is included as a defensive key — in practice it's stable
        // across content-equal recompositions because MarkdownParseCache
        // returns the same root reference, so this rarely contributes to
        // invalidations. Kept so a future cache refactor that breaks the
        // identity invariant doesn't silently stale the static AnnotatedString.
        val staticAnnotated = remember(
            content,
            enableLatexRendering,
            onClickUrl,
            baseColor,
            node,
            trim,
            colorScheme,
            density,
            textStyle,
            searchSources,
        ) {
            val built = buildAnnotatedString {
                node.children.fastForEach { child ->
                    appendMarkdownNodeContent(
                        node = child,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        onClickCitation = onClickCitation,
                        style = textStyle,
                        density = density,
                        enableLatexRendering = enableLatexRendering,
                        onClickUrl = onClickUrl,
                        baseColor = baseColor,
                        sourceOffsetBase = sourceOffsetBase,
                        searchSources = searchSources,
                    )
                }
            }
            // Parity class [A] fix: trim the whole assembled heading content ONCE at its outer
            // boundaries (only the heading path passes trim = true), instead of trimming each leaf
            // token. This drops `## foo ` → `foo` while preserving interior spaces around
            // punctuation that the JetBrains tree splits into separate TEXT leaves.
            if (trim) built.trimEnds() else built
        }
        val combined = remember(staticAnnotated, inlineLiveSuffix, baseColor) {
            if (inlineLiveSuffix.isEmpty()) {
                staticAnnotated
            } else {
                buildAnnotatedString {
                    append(staticAnnotated)
                    append(inlineLiveSuffix.replace(BREAK_LINE_REGEX, "\n"))
                }
            }
        }
        // Appearance-anchored reveal: every suffix character runs its own
        // fade+lift from the frame it first appears (clock keyed by absolute
        // source offsets, so a parse tick shrinking the suffix does not
        // restart carried-over characters). Gate on the suffix itself, NOT on
        // the streaming-tail marker — the marker drops the instant generation
        // ends, and the final batch must finish fading on its own clock.
        // Separate clocks for the inline and plain segments: each clock prunes
        // by its own source offset, and the plain segment starts at a higher
        // offset than the inline one.
        val streamingTail = inlineLiveSuffix.isNotEmpty() || plainLiveSuffix.isNotEmpty()
        val inlineRevealClock = remember { StreamingCharRevealClock() }
        val plainRevealClock = remember { StreamingCharRevealClock() }
        // Seeded with System.nanoTime, NOT 0: Choreographer frame nanos share
        // the System.nanoTime timebase, and characters stamped before the
        // first frame callback would otherwise read as ages old and skip
        // their fade entirely.
        var revealNowNanos by remember { mutableLongStateOf(System.nanoTime()) }
        LaunchedEffect(streamingTail) {
            if (!streamingTail) return@LaunchedEffect
            while (true) {
                withFrameNanos { revealNowNanos = it }
            }
        }
        // Promotion continuity: when a parse tick promotes suffix text into
        // staticAnnotated, the newest characters are usually still mid-fade.
        // They keep their own per-character clock curves on the settled side
        // (keyed by the source offsets the suffix had before promotion) — no
        // snap to opaque and no catch-up brightness ramp sweeping the writing
        // head at every tick.
        val staticLength = staticAnnotated.length
        var settledCatchupStart by remember { mutableIntStateOf(staticLength) }
        var prevStaticLength by remember { mutableIntStateOf(staticLength) }
        var settledCatchupSuffixOffset by remember { mutableIntStateOf(-1) }
        LaunchedEffect(staticLength) {
            if (staticLength > prevStaticLength && prevStaticLength > 0) {
                settledCatchupStart = prevStaticLength
                // The promoted text is exactly the suffix head that just left:
                // walk its source base back by the promoted length (combined
                // and source lengths match for verbatim prose appends; on a
                // transforming promotion the keys simply miss → opaque).
                settledCatchupSuffixOffset =
                    (liveSuffixSourceOffset - (staticLength - prevStaticLength)).coerceAtLeast(0)
                prevStaticLength = staticLength
            } else {
                prevStaticLength = staticLength
            }
        }
        val annotatedString = remember(
            combined,
            staticLength,
            revealNowNanos,
            baseColor,
        ) {
            applyStreamingCharReveal(
                combined = combined,
                staticLength = staticLength,
                suffixSourceOffset = liveSuffixSourceOffset,
                clock = inlineRevealClock,
                nowNanos = revealNowNanos,
                baseColor = baseColor,
                settledCatchupStart = settledCatchupStart,
                settledCatchupSuffixOffset = settledCatchupSuffixOffset,
            )
        }

        Text(
            text = annotatedString,
            modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
            inlineContent = inlineContents,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = LocalTextStyle.current.copy(
                lineHeight = if (hasInlineMath && enableLatexRendering) TextUnit.Unspecified else LocalTextStyle.current.lineHeight
            )
        )
        if (plainLiveSuffix.isNotEmpty()) {
            val plainAnnotatedString = remember(plainLiveSuffix, revealNowNanos, baseColor) {
                applyStreamingCharRevealPlain(
                    text = plainLiveSuffix,
                    suffixSourceOffset = plainSuffixSourceOffset,
                    clock = plainRevealClock,
                    nowNanos = revealNowNanos,
                    baseColor = baseColor,
                )
            }
            Text(
                text = plainAnnotatedString,
                modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
                softWrap = true,
                overflow = TextOverflow.Visible,
                style = plainSuffixStyle.copy(
                    lineHeight = if (hasInlineMath && enableLatexRendering) {
                        TextUnit.Unspecified
                    } else {
                        plainSuffixStyle.lineHeight
                    },
                ),
            )
        }
    }
}

/**
 * Trims leading/trailing whitespace from an [AnnotatedString] at its OUTER boundaries only,
 * preserving all spans/annotations on the surviving range via [AnnotatedString.subSequence].
 * Interior whitespace is untouched — used by the heading render path to drop `## foo ` → `foo`
 * without collapsing the interior spaces the JetBrains tree splits across multiple TEXT leaves
 * (parity class [A]). Returns the receiver unchanged when there is nothing to trim.
 */
internal fun AnnotatedString.trimEnds(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return if (text.isEmpty()) this else AnnotatedString("")
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return if (start == 0 && end == text.length) this else subSequence(start, end)
}

/**
 * The CommonMark "ASCII punctuation" set — every char a leading backslash may escape
 * (CommonMark §2.4 Backslash escapes). A `\` before any of these is consumed and the char rendered
 * literally; a `\` before anything else (letters, digits, whitespace) is NOT an escape and stays.
 */
private val COMMONMARK_ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".toCharArray().toHashSet()

/**
 * Resolves CommonMark backslash escapes in a TEXT-leaf slice: replaces `\X` with `X` for every X in
 * [COMMONMARK_ESCAPABLE]. A lone/trailing backslash, or a `\` before a non-punctuation char, is kept
 * verbatim — so `\*` → `*`, `\_` → `_`, but `back\slash` and a trailing `\` mid-stream stay literal.
 *
 * Parity class [B]: the JetBrains (JVM) tree keeps the backslash in its Text-leaf source slice
 * (`textIn` is a raw `substring`), so escapes render literally (`\!`); the native (pulldown) tree
 * already slices the escape away (its Text node spans only the punctuation), so applying this rule to
 * the SHARED leaf arm fixes the JVM side and is idempotent on the native side (`!` has no backslash
 * to strip). This is applied ONLY in the generic `node.children.isEmpty()` leaf arm — InlineCode and
 * MathInline are hoisted ABOVE it and slice their own raw source, so code/math escapes stay raw.
 *
 * Fast path: most leaves contain no backslash, so we scan once and return the receiver unchanged
 * unless a real escape is present.
 */
internal fun resolveBackslashEscapes(text: String): String {
    if (text.indexOf('\\') < 0) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && text[i + 1] in COMMONMARK_ESCAPABLE) {
            sb.append(text[i + 1])
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/**
 * Walks a markdown [MdNode] and appends its rendered content into the
 * receiver [AnnotatedString.Builder] — including spans for EMPH /
 * STRONG / STRIKETHROUGH / links / code / inline math.
 *
 */
internal fun AnnotatedString.Builder.appendMarkdownNodeContent(
    node: MdNode,
    content: String,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean = true,
    onClickCitation: (String) -> Unit = {},
    onClickUrl: (String) -> Unit = {},
    baseColor: Color = Color.Unspecified,
    sourceOffsetBase: Int = 0,
    searchSources: SearchSourcesRegistry? = null,
) {
    when {
        // BLOCK_QUOTE marker token (`>`) — mapping doc §4 H9. The token maps to Unknown; the
        // original `type == BLOCK_QUOTE -> {}` no-op skipped rendering the `>` prefix.
        // isBlockquoteMarker checks the real JetBrains type so only the genuine marker is skipped
        // (a `>` autolink delimiter or escaped `\>` is NOT). Must precede the generic leaf arm.
        node.isBlockquoteMarker -> {}

        // Links — INLINE_LINK + AUTOLINK + GFM_AUTOLINK all map to MdNodeType.Link (mapping doc
        // §2 #18/#19/#23, §4 H6/H7). Discriminate by structure (NOT linkHref — an inline link
        // with an angle-bracket destination `[t](<url>)` has no LINK_DESTINATION child and so a
        // null linkHref):
        //  - leaf (no children)  → GFM_AUTOLINK (bare url), original 2485 arm
        //  - isAutolink (<url>)  → AUTOLINK, original 2606 arm (trim LT/GT via contentChildren)
        //  - otherwise           → INLINE_LINK, original 2564 arm (pills / normal link)
        // Placed before the generic leaf arm so the leaf GFM_AUTOLINK is caught here (matching the
        // original order where GFM_AUTOLINK precedes the leaf arm).
        node.type == MdNodeType.Link && node.children.isEmpty() -> {
            val link = node.textIn(content)
            withLink(openUrlLinkAnnotation(link, onClickUrl)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(link)
                }
            }
        }

        // ── Type-dispatch BEFORE shape-dispatch for leaf-eligible inline types ──
        // The generic leaf arm below (`node.children.isEmpty()`) appends the node's RAW source span.
        // For JVM (JetBrains) trees that is harmless because InlineCode/InlineMath carry marker-token
        // children (`` ` `` / `$`), so they never reach the leaf arm and fall through to their own
        // type arms which strip the delimiters / dispatch LaTeX. The native (pulldown) tree emits
        // these as CHILDLESS leaves (tree_builder.rs: Event::Code → InlineCode leaf, Event::InlineMath
        // → MathInline leaf), whose span INCLUDES the delimiters — so the leaf arm would append
        // `` `code` `` with backticks (BUG #2) and `$formula$` as plain text instead of a rendered
        // LaTeX inline. Audit of every type arm gated behind children-shape: only InlineCode and
        // MathInline are childless in the native tree AND have a dedicated arm after the leaf arm;
        // Emphasis/Strong/Strikethrough/Link(inline)/Link(autolink) are inline CONTAINERS that always
        // carry content children (NativeMdTree.kt contentChildren KDoc), so they never hit the leaf
        // arm. Hoisting these two ahead of the leaf arm fixes the CLASS for both tree shapes and is a
        // no-op for JVM (those nodes never matched the leaf arm anyway).
        node.type == MdNodeType.InlineCode -> {
            val code = node.textIn(content).trim('`')
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.95.em,
                    background = colorScheme.secondaryContainer.copy(alpha = 0.2f),
                )
            ) {
                append(code)
            }
        }

        node.type == MdNodeType.MathInline -> {
            if (enableLatexRendering) {
                // formula as id
                val formula = node.textIn(content)
                appendInlineContent(formula, "[Latex]")
                val (width, height) = with(density) {
                    assumeLatexSize(
                        latex = formula, fontSize = style.fontSize.toPx()
                    ).let {
                        it.width().toSp() to it.height().toSp()
                    }
                }
                inlineContents.putIfAbsent(/* key = */ formula,/* value = */ InlineTextContent(
                    placeholder = Placeholder(
                        width = width, height = height, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                    ), children = {
                        MathInline(
                            latex = formula, modifier = Modifier
                        )
                    })
                )
            } else {
                // 禁用 LaTeX 渲染时，以等宽字体显示原始公式
                val formula = node.textIn(content)
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 0.95.em,
                    )
                ) {
                    append(formula)
                }
            }
        }

        node.children.isEmpty() -> {
            // Parity class [A] fix: do NOT trim per leaf token. The JetBrains tree splits a
            // heading's inline text into MULTIPLE TEXT leaves at punctuation (em-dash / `(` / `"` /
            // `:`); trimming each leaf here ate the interior space on both sides of the punctuation
            // (`## GFM Tables — Simple` → `GFM Tables—Simple`). The whole-heading leading/trailing
            // trim that `trim = true` intends is now applied ONCE to the assembled AnnotatedString
            // in [Paragraph] (see [AnnotatedString.trimEnds]), so interior token-boundary spaces
            // survive while outer whitespace is still removed.
            // Parity class [B] fix: resolve CommonMark backslash escapes in the Text-leaf slice
            // (`\*` → `*`, `\!` → `!`, …). The JetBrains tree keeps the backslash in `textIn` (raw
            // substring), the native tree already slices it away — so resolving here in the SHARED
            // leaf arm fixes the JVM side and is idempotent on the native side. InlineCode/MathInline
            // are hoisted ABOVE this arm (slice their own raw source), so code/math escapes stay raw.
            val text = resolveBackslashEscapes(node.textIn(content)).replace(BREAK_LINE_REGEX, "\n")
            append(text)
        }

        node.type == MdNodeType.Emphasis -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                // contentChildren strips the `*`/`_` markers via the real EMPH token type
                // (mapping doc §4 H5 — markers collapse to Unknown so can't be stripped here).
                node.contentChildren.fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        onClickCitation = onClickCitation,
                        onClickUrl = onClickUrl,
                        baseColor = baseColor,
                        sourceOffsetBase = sourceOffsetBase,
                        searchSources = searchSources,
                    )
                }
            }
        }

        node.type == MdNodeType.Strong -> {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                // contentChildren strips the 2+2 `**` markers via the real EMPH token type (§4 H5).
                node.contentChildren.fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        onClickCitation = onClickCitation,
                        onClickUrl = onClickUrl,
                        baseColor = baseColor,
                        sourceOffsetBase = sourceOffsetBase,
                        searchSources = searchSources,
                    )
                }
            }
        }

        node.type == MdNodeType.Strikethrough -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                // contentChildren strips the 2+2 `~~` markers via the real TILDE token type (§4 H5).
                node.contentChildren.fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        onClickCitation = onClickCitation,
                        onClickUrl = onClickUrl,
                        baseColor = baseColor,
                        sourceOffsetBase = sourceOffsetBase,
                        searchSources = searchSources,
                    )
                }
            }
        }

        node.type == MdNodeType.Link && !node.isAutolink -> {
            // INLINE_LINK (everything that is a Link, non-leaf, and not the <url> autolink form;
            // gating on linkHref would wrongly route `[t](<url>)` — which has no LINK_DESTINATION
            // — to the autolink arm). Destination via linkHref (E5); visible label = LINK_TEXT
            // text trimmed of brackets (linkLabel relocates the dig, §4 H6/KU-1).
            val linkDest = node.linkHref ?: ""
            val linkText = node.linkLabel?.trim { it == '[' || it == ']' } ?: linkDest
            val searchSource = searchSources?.lookup(linkDest)
            if (linkText.startsWith("citation,")) {
                // 如果是引用，则特殊处理
                val domain = linkText.substringAfter("citation,")
                val id = linkDest
                if (id.length == 6) {
                    appendSearchSourcePill(
                        key = "citation:$linkDest",
                        label = domain,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        onClick = { onClickCitation(id.trim()) },
                    )
                } else {
                    append(domain)
                }
            } else if (searchSource != null) {
                appendSearchSourcePill(
                    key = "search-source:${searchSource.host}:$linkDest",
                    label = searchSource.name,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    onClick = { onClickUrl(linkDest) },
                )
            } else {
                withLink(openUrlLinkAnnotation(linkDest, onClickUrl)) {
                    withStyle(
                        SpanStyle(
                            color = colorScheme.primary, textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                }
            }
        }

        node.type == MdNodeType.Link -> {
            // AUTOLINK (<url>) — the only remaining Link form here (leaf GFM_AUTOLINK and
            // INLINE_LINK already handled). contentChildren strips the `<`/`>` delimiter tokens
            // (LT/GT, which collapse to Unknown) via the real types (mapping doc §4 H7); the
            // surviving inner-autolink token renders as an italic clickable link.
            node.contentChildren.fastForEach { link ->
                withLink(openUrlLinkAnnotation(link.textIn(content), onClickUrl)) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(link.textIn(content))
                    }
                }
            }
        }

        // InlineCode + MathInline arms hoisted ABOVE the generic leaf arm (see the children-shape
        // audit comment there) — childless native leaves of these types must dispatch by type, not
        // fall through to the raw-span leaf arm.

        // 其他类型继续递归处理
        else -> {
            node.children.fastForEach {
                appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    enableLatexRendering = enableLatexRendering,
                    onClickCitation = onClickCitation,
                    onClickUrl = onClickUrl,
                    baseColor = baseColor,
                    sourceOffsetBase = sourceOffsetBase,
                    searchSources = searchSources,
                )
            }
        }
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
