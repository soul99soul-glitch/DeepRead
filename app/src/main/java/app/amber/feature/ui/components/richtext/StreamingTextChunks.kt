package app.amber.feature.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString

// Main-thread traces show active Text/StaticLayout measurement dominates long
// mixed messages. Keep its natural-line tail small; completed layers remain
// cached, and neither the source nor the pacing cadence changes.
internal const val STREAMING_TEXT_CHUNK_CHARS = 256
internal const val STREAMING_TEXT_CHUNK_MIN_LENGTH = 3_072
private const val STREAMING_TEXT_CHUNK_TAIL_KEEP = 96

/** A newline is represented by the vertical boundary between adjacent Text nodes. */
@Immutable
internal data class StreamingTextChunk(
    val start: Int, val text: AnnotatedString, val lineBreak: AnnotatedString = AnnotatedString(""),
)

/**
 * Split only at existing line boundaries; never at a surrogate or a wrapped line.
 * Retain at least 96 UTF-16 units in the active tail, so the <=48-codepoint ink
 * window cannot dirty a completed chunk. Joining with '\n' exactly recovers text.
 */
internal fun splitStreamingTextChunks(text: AnnotatedString): List<StreamingTextChunk> {
    if (text.length < STREAMING_TEXT_CHUNK_MIN_LENGTH || '\r' in text.text || text.paragraphStyles.isNotEmpty()) return listOf(StreamingTextChunk(0, text))
    return buildList {
        var start = 0
        val stableEnd = text.length - STREAMING_TEXT_CHUNK_TAIL_KEEP
        while (start + STREAMING_TEXT_CHUNK_CHARS < stableEnd) {
            val newline = text.text.indexOf('\n', start + STREAMING_TEXT_CHUNK_CHARS)
            if (newline < 0 || newline >= stableEnd) break
            add(StreamingTextChunk(start, text.subSequence(start, newline), text.subSequence(newline, newline + 1)))
            start = newline + 1
        }
        add(StreamingTextChunk(start, text.subSequence(start, text.length)))
    }
}

/** Worker-side identity reuse, including styles, links and seam metrics. */
internal fun reuseStreamingTextChunks(
    previous: List<StreamingTextChunk>,
    next: List<StreamingTextChunk>,
): List<StreamingTextChunk> = next.mapIndexed { index, chunk ->
    previous.getOrNull(index)?.takeIf { it == chunk } ?: chunk
}

/** Two native lines reproduce the baseline distance across a chunk seam. */
internal fun streamingChunkBridge(
    previous: TextLayoutResult,
    next: TextLayoutResult,
    lineBreak: AnnotatedString,
): AnnotatedString {
    fun line(layout: TextLayoutResult, index: Int): AnnotatedString {
        val text = layout.layoutInput.text
        val start = layout.getLineStart(index)
        var end = layout.getLineEnd(index, visibleEnd = false)
        while (end > start && text[end - 1] == '\n') end--
        if (end > start) return text.subSequence(start, end)
        // A zero-width glyph carries an empty line's inherited font metrics.
        val offset = (start - 1).coerceIn(0, (text.length - 1).coerceAtLeast(0))
        return buildAnnotatedString {
            append('\u200B')
            text.spanStyles.filter { it.start <= offset && it.end > offset }.forEach {
                addStyle(it.item, 0, 1)
            }
        }
    }
    return buildAnnotatedString {
        append(line(previous, previous.lineCount - 1))
        append(lineBreak)
        append(line(next, 0))
    }
}

/** Zero-copy view over the parsed prefix + raw streaming tail. */
private class StreamingTextChars(
    private val base: String,
    private val suffix: String,
) : CharSequence {
    override val length: Int get() = base.length + suffix.length
    override fun get(index: Int): Char = if (index < base.length) base[index] else suffix[index - base.length]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        toString().subSequence(startIndex, endIndex)
    override fun toString(): String = base + suffix
}

/**
 * Incremental planner for the streaming renderer.
 *
 * The parsed [base] is stable for a whole parse tick; only the raw [suffix]
 * grows per frame and the reveal window changes the last few glyphs. Chunk
 * boundaries and every completed chunk's [AnnotatedString] are therefore
 * reused across frames, and only the active tail is rebuilt/styled. A new
 * parse snapshot (identity change) or a non-prefix tail falls back to a full
 * deterministic re-split, never a stale prefix.
 */
internal class StreamingChunkPlanner {
    private var baseRef: AnnotatedString? = null
    private var suffixRef: String = ""
    private var baseHasCr = false
    private val committedStarts = ArrayList<Int>()
    private val committedChunks = ArrayList<StreamingTextChunk>()
    private var tailStart = 0
    private var chunked = false

    fun chunks(
        base: AnnotatedString,
        suffix: String,
        windowChars: Int,
        baseColor: Color,
        preparedBaseChunks: List<StreamingTextChunk>? = null,
    ): List<StreamingTextChunk> {
        val length = base.length + suffix.length
        val baseChanged = baseRef !== base
        if (baseChanged) {
            baseHasCr = base.text.indexOf('\r') >= 0
            resetState()
            baseRef = base
            if (!preparedBaseChunks.isNullOrEmpty() && preparedBaseChunks.size > 1) {
                committedChunks.addAll(preparedBaseChunks.dropLast(1))
                tailStart = preparedBaseChunks.last().start
                chunked = true
            }
        }
        val fallback = length < STREAMING_TEXT_CHUNK_MIN_LENGTH ||
            baseHasCr || suffix.indexOf('\r') >= 0 ||
            base.paragraphStyles.isNotEmpty()
        if (fallback) {
            resetState()
            suffixRef = suffix
            return listOf(
                StreamingTextChunk(
                    start = 0,
                    text = buildStreamingDisplayAnnotated(base, suffix, windowChars, baseColor),
                )
            )
        }
        if (!chunked) {
            resetState()
            chunked = true
        } else if (!suffix.startsWith(suffixRef)) {
            // Correction/regeneration: only the tail moved, so a full re-split
            // is the safe deterministic answer.
            resetState()
            chunked = true
        }
        val view = StreamingTextChars(base.text, suffix)
        val stableEnd = length - STREAMING_TEXT_CHUNK_TAIL_KEEP
        var start = tailStart
        while (start + STREAMING_TEXT_CHUNK_CHARS < stableEnd) {
            val newline = view.indexOf('\n', start + STREAMING_TEXT_CHUNK_CHARS)
            if (newline < 0 || newline >= stableEnd) break
            committedStarts.add(start)
            committedChunks.add(committedChunk(base, suffix, start, newline))
            start = newline + 1
        }
        tailStart = start
        suffixRef = suffix
        val result = ArrayList<StreamingTextChunk>(committedChunks.size + 1)
        result.addAll(committedChunks)
        val basePartEnd = minOf(base.length, length)
        val tailBase = if (tailStart < basePartEnd) {
            base.subSequence(tailStart, basePartEnd)
        } else {
            AnnotatedString("")
        }
        val tailSuffixStart = maxOf(tailStart, base.length)
        val tailSuffix = if (tailSuffixStart < length) {
            suffix.substring(tailSuffixStart - base.length)
        } else {
            ""
        }
        result.add(
            StreamingTextChunk(
                start = tailStart,
                text = buildStreamingDisplayAnnotated(tailBase, tailSuffix, windowChars, baseColor),
            )
        )
        return result
    }

    private fun committedChunk(
        base: AnnotatedString,
        suffix: String,
        start: Int,
        end: Int,
    ): StreamingTextChunk {
        val text = when {
            end <= base.length -> base.subSequence(start, end)
            start >= base.length -> AnnotatedString(suffix.substring(start - base.length, end - base.length))
            else -> buildAnnotatedString {
                append(base.subSequence(start, base.length))
                append(suffix.substring(0, end - base.length))
            }
        }
        val lineBreak = if (end < base.length) {
            base.subSequence(end, end + 1)
        } else {
            AnnotatedString('\n'.toString())
        }
        return StreamingTextChunk(start, text, lineBreak)
    }

    private fun resetState() {
        committedStarts.clear()
        committedChunks.clear()
        tailStart = 0
        chunked = false
        suffixRef = ""
    }
}


/** Cache only the currently displayed chunk layouts; all access is on the UI thread. */
/** Normalized chunk starts, for tests/diagnostics. */
internal fun normalizedChunkStarts(chunks: List<StreamingTextChunk>): List<Int> = chunks.map { it.start }

private class StreamingChunkLayouts {
    val values = HashMap<Int, TextLayoutResult>()
    val bridges = HashMap<StreamingBridgeKey, StreamingBridge>()
}

private data class StreamingBridgeKey(
    val previousStart: Int,
    val nextStart: Int,
    val maxWidth: Int,
)

private class StreamingBridge(
    val previous: TextLayoutResult,
    val next: TextLayoutResult,
    val measured: TextLayoutResult,
)

@Composable
internal fun StreamingChunkedText(
    text: AnnotatedString,
    modifier: Modifier,
    style: TextStyle,
) {
    val chunks = remember(text) { splitStreamingTextChunks(text) }
    StreamingChunkLayout(chunks, modifier, style)
}

/**
 * Streaming entry point that keeps the prepared prefix and the raw tail apart:
 * committed chunks stay identical frame to frame, only the tail is restyled.
 */
@Composable
internal fun StreamingChunkedDisplayText(
    base: AnnotatedString,
    suffix: String,
    windowChars: Int,
    baseColor: Color,
    modifier: Modifier,
    style: TextStyle,
    onChunkPlaced: ((start: Int, topInRoot: Float, layout: TextLayoutResult) -> Unit)? = null,
    preparedBaseChunks: List<StreamingTextChunk>? = null,
) {
    val planner = remember { StreamingChunkPlanner() }
    // Reveal dry-out can recompose many frames with identical inputs; do not
    // re-split or rebuild the tail AnnotatedString for those.
    val chunks = remember(base, suffix, windowChars, baseColor) {
        planner.chunks(base, suffix, windowChars, baseColor, preparedBaseChunks)
    }
    StreamingChunkLayout(chunks, modifier, style, onChunkPlaced)
}

@Composable
private fun StreamingChunkLayout(
    chunks: List<StreamingTextChunk>,
    modifier: Modifier,
    style: TextStyle,
    onChunkPlaced: ((start: Int, topInRoot: Float, layout: TextLayoutResult) -> Unit)? = null,
) {
    if (chunks.size == 1) {
        val layoutHolder = remember { arrayOfNulls<TextLayoutResult>(1) }
        val placed = if (onChunkPlaced != null) {
            Modifier.onGloballyPositioned { coordinates ->
                layoutHolder[0]?.let { onChunkPlaced(chunks[0].start, coordinates.positionInRoot().y, it) }
            }
        } else {
            Modifier
        }
        Text(chunks[0].text, modifier.then(placed), style = style,
            softWrap = true, overflow = TextOverflow.Visible,
            onTextLayout = { layoutHolder[0] = it })
        return
    }
    val layouts = remember { StreamingChunkLayouts() }
    val measurer = rememberTextMeasurer(cacheSize = 128)
    Layout(
        modifier = modifier,
        content = {
            chunks.forEachIndexed { index, chunk ->
                key(chunk.start) {
                    val onLayout = remember(layouts, chunk.start) {
                        { result: TextLayoutResult -> layouts.values[chunk.start] = result; Unit }
                    }
                    // A RenderNode records completed glyphs once; no offscreen
                    // bitmap is allocated. Only the active tail dirties its text.
                    val layer = if (index < chunks.lastIndex) Modifier.graphicsLayer()
                        else Modifier
                    val placed = if (onChunkPlaced != null) {
                        Modifier.onGloballyPositioned { coordinates ->
                            layouts.values[chunk.start]?.let {
                                onChunkPlaced(chunk.start, coordinates.positionInRoot().y, it)
                            }
                        }
                    } else {
                        Modifier
                    }
                    Text(chunk.text, modifier = layer.then(placed), style = style,
                        softWrap = true, overflow = TextOverflow.Visible,
                        onTextLayout = onLayout)
                }
            }
        },
    ) { children, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = children.map { it.measure(childConstraints) }
        val tops = IntArray(placeables.size)
        var top = 0f
        val activeBridges = HashSet<StreamingBridgeKey>(placeables.size)
        for (index in 1..chunks.lastIndex) {
            val previous = checkNotNull(layouts.values[chunks[index - 1].start])
            val current = checkNotNull(layouts.values[chunks[index].start])
            val bridgeKey = StreamingBridgeKey(
                previousStart = chunks[index - 1].start,
                nextStart = chunks[index].start,
                maxWidth = constraints.maxWidth,
            )
            activeBridges.add(bridgeKey)
            // Completed chunks keep the same TextLayoutResult instance. Reuse
            // the seam measurement instead of rebuilding its bridge string and
            // re-measuring it on every frame.
            val cached = layouts.bridges[bridgeKey]
            val bridge = if (cached != null && cached.previous === previous && cached.next === current) {
                cached.measured
            } else {
                measurer.measure(
                    streamingChunkBridge(previous, current, chunks[index - 1].lineBreak),
                    style = style,
                    constraints = Constraints(maxWidth = constraints.maxWidth),
                ).also { layouts.bridges[bridgeKey] = StreamingBridge(previous, current, it) }
            }
            // Native line-height trimming differs at the first/last line of
            // a Text. Reproduce the seam's two-line baseline distance instead
            // of summing child heights (which would compress the message).
            top += previous.getLineBaseline(previous.lineCount - 1) +
                bridge.getLineBaseline(1) - bridge.getLineBaseline(0) - current.getLineBaseline(0)
            tops[index] = kotlin.math.round(top).toInt()
        }
        val height = constraints.constrainHeight(kotlin.math.round(top + placeables.last().height).toInt())
        val width = constraints.constrainWidth(placeables.maxOf { it.width })
        val activeKeys = chunks.mapTo(HashSet()) { it.start }
        layouts.values.keys.retainAll(activeKeys)
        layouts.bridges.keys.retainAll(activeBridges)
        layout(width, height) {
            placeables.forEachIndexed { index, child -> child.placeRelative(0, tops[index]) }
        }
    }
}
