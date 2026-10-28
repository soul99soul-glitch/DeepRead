package app.amber.feature.ui.components.richtext

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import app.amber.agent.BuildConfig
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType

internal data class StreamingRepairResult(
    val text: String,
    val syntheticSuffixStart: Int,
)

internal data class StreamingLiveSuffix(
    val text: String,
    val sourceOffset: Int,
)

private val FENCE_LINE_REGEX = Regex("""(?m)^[ \t]*```""")
internal val TRAILING_FENCE_TERMINATOR_REGEX = Regex("""[ \t]*```[ \t]*""")
internal val EMPTY_STREAMING_LIVE_SUFFIX = StreamingLiveSuffix("", 0)

private fun String.countNonOverlapping(token: String): Int {
    if (token.isEmpty()) return 0
    var count = 0
    var searchFrom = 0
    while (searchFrom < length) {
        val next = indexOf(token, startIndex = searchFrom)
        if (next < 0) break
        count++
        searchFrom = next + token.length
    }
    return count
}

internal fun repairStreamingMarkdownTail(tail: String): StreamingRepairResult {
    if (tail.isEmpty()) return StreamingRepairResult(tail, tail.length)
    val suffix = StringBuilder()
    val syntheticStart = tail.length

    if (FENCE_LINE_REGEX.findAll(tail).count() % 2 == 1) {
        if (!tail.endsWith('\n')) suffix.append('\n')
        suffix.append("```")
        return StreamingRepairResult(tail + suffix, syntheticStart)
    }

    if (tail.count { it == '`' } % 2 == 1) {
        suffix.append('`')
    }
    if (tail.countNonOverlapping("**") % 2 == 1) {
        suffix.append("**")
    }
    if (tail.countNonOverlapping("$$") % 2 == 1) {
        suffix.append("$$")
    } else if (tail.count { it == '$' } % 2 == 1) {
        suffix.append('$')
    }

    return StreamingRepairResult(tail + suffix, syntheticStart)
}

internal fun streamingLiveSuffixFor(
    renderContent: String,
    activeBaseOffset: Int,
    parsedPreprocessed: String,
    syntheticSuffixStart: Int,
): StreamingLiveSuffix {
    // No `streaming` gate: the suffix is whatever the display text has that
    // the last parse has NOT covered yet, so staleness alone decides when it
    // is empty. An eager `!streaming -> empty` return dropped the tail one
    // frame early at stream end — the final chars were still un-parsed
    // (finalParse lands on a background dispatcher), so they vanished for a
    // beat and popped back in ("最后一个字顿住"). A fresh parse covers the
    // whole active region and this yields EMPTY on its own — including in
    // fully settled state.
    if (activeBaseOffset < 0 || activeBaseOffset > renderContent.length) {
        return EMPTY_STREAMING_LIVE_SUFFIX
    }
    val parsedSourceEnd = if (syntheticSuffixStart == Int.MAX_VALUE) {
        parsedPreprocessed.length
    } else {
        (syntheticSuffixStart - activeBaseOffset).coerceIn(0, parsedPreprocessed.length)
    }
    val liveActiveLength = renderContent.length - activeBaseOffset
    if (
        liveActiveLength <= parsedSourceEnd ||
        !renderContent.regionMatches(
            thisOffset = activeBaseOffset,
            other = parsedPreprocessed,
            otherOffset = 0,
            length = parsedSourceEnd,
            ignoreCase = false,
        )
    ) {
        return EMPTY_STREAMING_LIVE_SUFFIX
    }
    return StreamingLiveSuffix(
        text = renderContent.substring(activeBaseOffset + parsedSourceEnd),
        sourceOffset = activeBaseOffset + parsedSourceEnd,
    )
}

internal enum class LiveSuffixPlacement {
    /** Append to the active paragraph and batch-fade with the AST tail. */
    Inline,

    /** Show as plain text below the settled block; avoids block-boundary reflow. */
    Plain,
}

private val LIVE_SUFFIX_BLOCK_HEADING_REGEX = Regex("""^#{1,6}\s""")
private val LIVE_SUFFIX_BLOCK_LIST_REGEX = Regex("""^[-*+]\s""")
private val LIVE_SUFFIX_BLOCK_ORDERED_LIST_REGEX = Regex("""^\d+\.\s""")
private val LIVE_SUFFIX_BLOCK_QUOTE_REGEX = Regex("""^>\s?""")

internal fun liveSuffixPlacementFor(suffix: String): LiveSuffixPlacement {
    if (suffix.isEmpty()) return LiveSuffixPlacement.Inline
    if (suffix.startsWith("\n\n") || suffix.startsWith("\r\n\r\n")) {
        return LiveSuffixPlacement.Plain
    }
    val trimmedStart = suffix.trimStart()
    if (trimmedStart.isEmpty()) return LiveSuffixPlacement.Inline
    return when {
        LIVE_SUFFIX_BLOCK_HEADING_REGEX.containsMatchIn(trimmedStart) -> LiveSuffixPlacement.Plain
        LIVE_SUFFIX_BLOCK_LIST_REGEX.containsMatchIn(trimmedStart) -> LiveSuffixPlacement.Plain
        LIVE_SUFFIX_BLOCK_ORDERED_LIST_REGEX.containsMatchIn(trimmedStart) -> LiveSuffixPlacement.Plain
        trimmedStart.startsWith("|") -> LiveSuffixPlacement.Plain
        trimmedStart.startsWith("```") -> LiveSuffixPlacement.Plain
        LIVE_SUFFIX_BLOCK_QUOTE_REGEX.containsMatchIn(trimmedStart) -> LiveSuffixPlacement.Plain
        trimmedStart.matches(Regex("""^(-{3,}|\*{3,}|_{3,})\s*$""")) -> LiveSuffixPlacement.Plain
        else -> LiveSuffixPlacement.Inline
    }
}

/**
 * Splits an unparsed live suffix at the first top-level block boundary — a
 * blank line (`\n\n` or `\r\n\r\n`). The first component continues the current
 * block; the second (including the blank line) belongs to subsequent blocks.
 * With no blank line the whole suffix stays with the current block.
 *
 * Pure split — callers apply `<br>` normalization where the parts are rendered.
 */
internal fun splitLiveSuffixAtBlockBoundary(suffix: String): Pair<String, String> {
    val boundary = suffix.indexOf("\n\n").let { unix ->
        val windows = suffix.indexOf("\r\n\r\n")
        when {
            unix == -1 -> windows
            windows == -1 -> unix
            else -> minOf(unix, windows)
        }
    }
    return if (boundary == -1) {
        suffix to ""
    } else {
        suffix.substring(0, boundary) to suffix.substring(boundary)
    }
}

/**
 * Length of the leading newline/CR run. The plain suffix's leading run is the
 * block-boundary blank line; the parsed form renders that gap as the previous
 * paragraph's bottom padding, so the preview strips it (see Paragraph).
 */
internal fun String.countLeadingNewlines(): Int {
    var i = 0
    while (i < length && (this[i] == '\n' || this[i] == '\r')) i++
    return i
}

internal fun streamingLiveSuffixForLastRenderableChild(
    children: List<MdNode>,
    child: MdNode,
    liveSuffix: String,
    liveSuffixSourceOffset: Int,
): StreamingLiveSuffix {
    if (liveSuffix.isEmpty()) return EMPTY_STREAMING_LIVE_SUFFIX
    val target = children.asReversed().firstOrNull { it.isStreamingSuffixRenderableTarget() }
        ?: return EMPTY_STREAMING_LIVE_SUFFIX
    return if (child === target) {
        StreamingLiveSuffix(liveSuffix, liveSuffixSourceOffset)
    } else {
        EMPTY_STREAMING_LIVE_SUFFIX
    }
}

private fun MdNode.isStreamingSuffixRenderableTarget(): Boolean {
    // ATX_1..6 → Heading, CODE_FENCE+CODE_BLOCK → CodeBlock, ATX_CONTENT+PARAGRAPH →
    // Paragraph (mapping doc §2 #2/#3/#8/#9, §4 H1) collapse the JetBrains-side
    // duplicates onto the unified MdNodeType variants below.
    return when (type) {
        MdNodeType.Paragraph,
        MdNodeType.ListUnordered,
        MdNodeType.ListOrdered,
        MdNodeType.ListItem,
        MdNodeType.Blockquote,
        MdNodeType.Heading,
        MdNodeType.Image,
        MdNodeType.CodeBlock,
        MdNodeType.HtmlBlock,
        MdNodeType.Text,
        MdNodeType.HorizontalRule,
        MdNodeType.Table,
        MdNodeType.MathBlock,
        MdNodeType.TaskListMarker -> true

        else -> children.any { it.isStreamingSuffixRenderableTarget() }
    }
}

internal class StreamingMarkdownParseCache {
    private val lock = Any()
    private var stableRawPrefix = ""
    private var stableBlocks = emptyList<MarkdownTopLevelBlockSnapshot>()

    fun reset() = synchronized(lock) {
        stableRawPrefix = ""
        stableBlocks = emptyList()
    }

    private fun parseRepairedTail(
        tail: String,
        activeBaseOffset: Int,
        blocks: List<MarkdownTopLevelBlockSnapshot>,
    ): MarkdownParseResult {
        val repairedTail = repairStreamingMarkdownTail(tail)
        return parsePreprocessedMarkdownUncached(repairedTail.text)
            .copy(
                stableTopLevelBlocks = blocks,
                activeBaseOffset = activeBaseOffset,
                syntheticSuffixStart = activeBaseOffset + repairedTail.syntheticSuffixStart,
            )
    }

    fun finalizeComplete(content: String): MarkdownParseResult =
        traceMarkdown("Amber Markdown parse finalize streaming") {
            val (prefix, blocks) = synchronized(lock) {
                if (stableRawPrefix.isNotEmpty() && content.startsWith(stableRawPrefix)) {
                    stableRawPrefix to stableBlocks
                } else {
                    "" to emptyList()
                }
            }
            val parsed = MarkdownParseCache.getOrParse(content)
            if (
                blocks.isEmpty() ||
                parsed.hasHtmlBlocks ||
                parsed.preprocessed != content
            ) {
                reset()
                return@traceMarkdown parsed
            }

            val tail = content.substring(prefix.length)
            val tailParse = parsePreprocessedMarkdownUncached(tail)
            if (tailParse.hasHtmlBlocks) {
                reset()
                return@traceMarkdown parsed
            }

            val finalizedTailBlocks = tailParse.tree.children.mapIndexed { index, child ->
                val blockContent = tail.substring(child.startOffset, child.endOffset)
                MarkdownTopLevelBlockSnapshot(
                    key = "active:${child.type}:${prefix.length + child.startOffset}:$index",
                    parseResult = MarkdownParseCache.getOrParsePreprocessed(blockContent),
                    preserveParagraphBottomPadding = child.type == MdNodeType.Paragraph &&
                        child.nextSibling() != null &&
                        child.findChildOfTypeRecursive(MdNodeType.Image, MdNodeType.MathBlock) == null,
                )
            }
            reset()
            parsed.copy(
                tree = EmptyMarkdownRootNode(parsed.preprocessed.length),
                stableTopLevelBlocks = blocks + finalizedTailBlocks,
                activeBaseOffset = parsed.preprocessed.length,
                syntheticSuffixStart = Int.MAX_VALUE,
            )
        }

    /**
     * Freeze the current streaming split and return a final parse result whose
     * Compose keys are byte-identical to the last streaming frame.
     *
     * Unlike [parse], this does NOT promote any active-tail block into
     * [stableTopLevelBlocks]. The active children stay with their
     * `"active:type:offset:idx"` keys, which match the final streaming frame,
     * so Compose reuses the warm compositions instead of cold-rebuilding them.
     * No subtree destruction → no height collapse → no viewport jump.
     *
     * Falls back to a plain full parse when [preprocessMarkdownContent] would modify the
     * content (in that case the streaming cache had already reset mid-stream,
     * so [stableTopLevelBlocks] was already empty and the else-branch
     * keying handles stable key matching).
     */
    fun finalParse(content: String): MarkdownParseResult =
        traceMarkdown("Amber Markdown finalize streaming (freeze)") {
            val (prefix, blocks) = synchronized(lock) {
                stableRawPrefix to stableBlocks
            }
            reset()
            // Settled content is complete. Streaming repair (odd `$` / `**`)
            // is a live heuristic and must not invent trailing nodes after
            // freeze. History that never streamed has empty stable blocks
            // and is a plain cached parse — the same path `main` used.
            if (blocks.isEmpty() || blocks.any { it.parseResult.hasHtmlBlocks }) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "AmberChatScroll",
                        "[md.finalize] branch=settled_full prefix=${prefix.length} " +
                            "stableBlocks=${blocks.size} contentLen=${content.length}"
                    )
                }
                return@traceMarkdown MarkdownParseCache.getOrParse(content)
            }
            val activeRaw = content.substring(prefix.length)
            val activePreprocessed = preprocessMarkdownContent(activeRaw)
            if (activePreprocessed != activeRaw) {
                // preProcess modifies the tail (e.g. bare-URL rewrite, LaTeX).
                // A full plain parse follows; with the unified active-key scheme
                // this only re-keys the tail children, not the whole message.
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "AmberChatScroll",
                        "[md.finalize] branch=preprocess_reset prefix=${prefix.length} " +
                            "stableBlocks=${blocks.size} contentLen=${content.length}"
                    )
                }
                return@traceMarkdown MarkdownParseCache.getOrParse(content)
            }
            // Existing stable blocks + full active tail, without promoting
            // any active child to stable → active-tail keys stay "active:…"
            // and without appending synthetic closers.
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "AmberChatScroll",
                    "[md.finalize] branch=freeze prefix=${prefix.length} " +
                        "stableBlocks=${blocks.size} contentLen=${content.length}"
                )
            }
            parsePreprocessedMarkdownUncached(activePreprocessed).copy(
                stableTopLevelBlocks = blocks,
                activeBaseOffset = prefix.length,
                syntheticSuffixStart = Int.MAX_VALUE,
            )
        }

    fun parse(
        content: String,
    ): MarkdownParseResult = traceMarkdown("Amber Markdown parse streaming") {
        val baseline = synchronized(lock) {
            if (stableRawPrefix.isNotEmpty() && content.startsWith(stableRawPrefix)) {
                stableRawPrefix to stableBlocks
            } else {
                stableRawPrefix = ""
                stableBlocks = emptyList()
                "" to emptyList()
            }
        }

        val (prefix, blocks) = baseline
        val activeRaw = content.substring(prefix.length)
        val activePreprocessed = preprocessMarkdownContent(activeRaw)
        if (activePreprocessed != activeRaw) {
            reset()
            return@traceMarkdown parseMarkdownUncached(content)
        }
        val activeParse = parsePreprocessedMarkdownUncached(activePreprocessed)
        if (activeParse.hasHtmlBlocks) {
            reset()
            return@traceMarkdown parseMarkdownUncached(content)
        }

        val activeChildren = activeParse.tree.children
        if (activeChildren.size <= 1) {
            return@traceMarkdown parseRepairedTail(
                tail = activePreprocessed,
                activeBaseOffset = prefix.length,
                blocks = blocks,
            )
        }

        // Structural stabilization: every top-level block except the last
        // (still-growing) one is finalized. No longer gated on reveal
        // progress — the batch fade is a purely visual layer.
        val newlyStableChildren = activeChildren.dropLast(1)

        // Streamdown principle: stable block keys are INDEX-based, not content
        // hashes. Promoted blocks are frozen at promotion time and only ever
        // APPEND, so "type:index" keeps the same Compose instance across every
        // subsequent tick — a hash key would unmount/remount on any late
        // content correction of an already-promoted block.
        val nextStableBlocks = blocks + newlyStableChildren.mapIndexed { stableIndex, child ->
            val blockContent = activePreprocessed.substring(child.startOffset, child.endOffset)
            MarkdownTopLevelBlockSnapshot(
                key = "stable:${child.type}:${blocks.size + stableIndex}",
                parseResult = MarkdownParseCache.getOrParsePreprocessed(blockContent),
                preserveParagraphBottomPadding = child.type == MdNodeType.Paragraph &&
                    child.nextSibling() != null &&
                    child.findChildOfTypeRecursive(MdNodeType.Image, MdNodeType.MathBlock) == null,
            )
        }
        val activeTailStart = newlyStableChildren.last().endOffset
        val nextPrefixEnd = prefix.length + activeTailStart
        synchronized(lock) {
            stableRawPrefix = content.substring(0, nextPrefixEnd)
            stableBlocks = nextStableBlocks
        }
        val tail = activePreprocessed.substring(activeTailStart)
        parseRepairedTail(
            tail = tail,
            activeBaseOffset = nextPrefixEnd,
            blocks = nextStableBlocks,
        )
    }
}
