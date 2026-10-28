package app.amber.feature.ui.components.richtext

import android.os.SystemClock
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.AssistantRegex
import app.amber.core.utils.openUrl
import app.amber.agent.R
import app.amber.feature.ui.components.message.MessageRenderCache
import app.amber.feature.ui.context.LocalChatFontScale
import app.amber.feature.ui.context.LocalSettings
import app.amber.highlight.HighlightToken
import app.amber.highlight.LocalHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

/** Internal composition signal; generation ownership remains upstream. */
internal val LocalStreamingTextVisualActivity = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** Parse throttle window — same cadence as MARKDOWN_STREAMING_PARSE_THROTTLE_MS. */
internal const val STREAMING_SINGLE_TEXT_PARSE_THROTTLE_MS = 120L

/** Token-cache ceiling per streaming message — beyond it new blocks stay flat. */
internal const val STREAMING_CODE_HIGHLIGHT_MAX_BLOCKS = 32

/**
 * Streaming-phase markdown renderer. Stable blocks reuse mapped spans on the
 * background worker. Short messages use one Text; long multiline messages keep
 * completed Text chunks stable and lay out only the growing tail. Markdown styles apply live
 * ([mdNodeToAnnotatedString]) and the writing head fades in via the
 * distance-from-end reveal window ([applyStreamingWindowReveal]): newest chars
 * near-transparent, chars one window back fully opaque — no clock, no index
 * keys, so parse-tick mutations cannot open holes.
 *
 * Pipeline (same sources as the settled path):
 * 1. [MessageRenderCache.visualRegexText] — the assistant visual regex
 *    transform, applied exactly once (the settled path applies it before
 *    MarkdownBlock; this component receives the RAW part text and applies it
 *    itself).
 * 2. [rememberStreamingDisplayText] — the word-quantized pacing buffer, same
 *    source the settled MarkdownBlock uses (the pacer lives upstream of the
 *    render layer and is untouched).
 * 3. Throttled background parse (only the final input enters MarkdownParseCache) (trailing-edge ~120ms,
 *    mirroring MARKDOWN_STREAMING_PARSE_THROTTLE_MS) so token bursts do not
 *    re-parse every frame.
 * 4. [mdNodeToAnnotatedString] — MdNode tree → AnnotatedString.
 *
 * After drain completion the caller hands over to the settled MarkdownBlock
 * through [rememberStreamingPresentationLifetime]; the owning timeline decides
 * when that swap cannot move what the reader sees.
 */
@Composable
fun StreamingSingleTextMarkdown(
    text: String,
    regexes: List<AssistantRegex>,
    modifier: Modifier = Modifier,
    streaming: Boolean = true,
    onSettled: (() -> Unit)? = null,
) {
    val visualActivity = LocalStreamingTextVisualActivity.current
    DisposableEffect(visualActivity) {
        visualActivity(true)
        onDispose { visualActivity(false) }
    }
    val renderSource = remember(text, regexes) {
        MessageRenderCache.visualRegexText(
            text = text,
            regexes = regexes,
            scope = AssistantAffectScope.ASSISTANT,
        )
    }
    // Pacing buffer — same release cadence as the settled MarkdownBlock path.
    // `streaming` follows the message loading flag: when generation ends the
    // buffer enters drain mode and keeps writing the buffered tail at the
    // streaming pace; [onSettled] fires once the tail is fully out so the
    // caller can observe completion without dumping the buffered backlog.
    val visible = rememberStreamingDisplayText(
        content = renderSource,
        streaming = streaming,
    )
    val singleTextStyle = rememberMarkdownSingleTextStyle()
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val headingFontScale = LocalChatFontScale.current
    val context = LocalContext.current
    val imageFallbackLabel = stringResource(R.string.html_asset_slides_fallback_image)
    val onClickUrl = remember(context) { { url: String -> context.openUrl(url) } }
    val highlighter = LocalHighlighter.current
    var snapshot by remember { mutableStateOf(StreamingTextSnapshot("", AnnotatedString(""))) }
    val updatedVisible by rememberUpdatedState(visible)
    val updatedStreaming by rememberUpdatedState(streaming)
    val updatedSource by rememberUpdatedState(renderSource)
    LaunchedEffect(singleTextStyle, enableLatexRendering, headingFontScale, imageFallbackLabel, onClickUrl, highlighter) {
        val blockCache = StreamingTextBlockCache()
        val codeHighlights = mutableMapOf<String, List<HighlightToken>>()
        var lastParseMs = 0L
        var previousChunks = emptyList<StreamingTextChunk>()
        snapshotFlow { updatedVisible }
            .distinctUntilChanged()
            .transform { _ ->
                val elapsed = SystemClock.uptimeMillis() - lastParseMs
                if (elapsed < STREAMING_SINGLE_TEXT_PARSE_THROTTLE_MS) {
                    delay(STREAMING_SINGLE_TEXT_PARSE_THROTTLE_MS - elapsed)
                }
                lastParseMs = SystemClock.uptimeMillis()
                // Take the freshest frame after waiting, rather than a stale
                // value captured before the throttle window.
                emit(updatedVisible)
            }
            .collectLatest { latest ->
                try {
                    val finalInput = !updatedStreaming && latest == updatedSource
                    val parsed = withContext(Dispatchers.Default) {
                        // Transient prefixes must not evict settled history.
                        if (finalInput) parseMarkdownContent(latest) else parseMarkdownUncached(latest)
                    }
                    suspend fun mapped(): StreamingTextSnapshot = withContext(Dispatchers.Default) {
                        val annotated = mdNodeToAnnotatedString(
                            source = parsed.preprocessed,
                            root = parsed.tree,
                            style = singleTextStyle,
                            onClickUrl = onClickUrl,
                            codeHighlights = codeHighlights,
                            enableLatexRendering = enableLatexRendering,
                            headingFontScale = headingFontScale,
                            imageFallbackLabel = imageFallbackLabel,
                            blockCache = blockCache,
                        )
                        // Span slicing scans the full annotation list. Prepare
                        // chunk boundaries off the UI thread with the parse,
                        // and preserve identical committed objects across ticks.
                        val chunks = reuseStreamingTextChunks(previousChunks, splitStreamingTextChunks(annotated))
                        previousChunks = chunks
                        StreamingTextSnapshot(latest, annotated, chunks)
                    }
                    snapshot = mapped()
                    var highlighted = false
                    if (codeHighlights.size < STREAMING_CODE_HIGHLIGHT_MAX_BLOCKS) {
                        val blocks = withContext(Dispatchers.Default) {
                            collectStreamingClosedCodeBlocks(parsed.tree, parsed.preprocessed)
                        }
                        for (block in blocks) {
                            if (codeHighlights.containsKey(block.key)) continue
                            if (codeHighlights.size >= STREAMING_CODE_HIGHLIGHT_MAX_BLOCKS) break
                            codeHighlights[block.key] = try {
                                highlighter.highlight(block.body, block.lang)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                emptyList()
                            }
                            highlighted = true
                        }
                    }
                    if (highlighted) snapshot = mapped()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    exception.printStackTrace()
                }
            }
    }
    val parsedInput = snapshot.input
    val prefixMatches = visible.startsWith(parsedInput)
    // A correction/regeneration displays its current text immediately while
    // the worker prepares styles. Never keep an unrelated old snapshot.
    val annotated = if (prefixMatches) snapshot.annotated else AnnotatedString("")
    val rawSuffix = if (prefixMatches) visible.substring(parsedInput.length) else visible

    // Ink-dry reveal, window form (审美第5条"墨随字速干"): a char's opacity is
    // a function of its DISTANCE FROM THE END of the final string — newest chars
    // near-transparent, chars `windowChars` back fully opaque. No clock
    // and no index keys: the window re-anchors at the end on every append, so
    // structural mutations (marker closure, gap-line insertion) that broke the
    // index-keyed clock (2026-08-16 blank-band forensics) cannot create holes.
    // Each char's alpha rises as newer text streams past it — the fade speed
    // tracks the generation rate with zero per-char state; after ~0.35s of
    // append silence the window dries out linearly to zero so the tail always
    // settles fully opaque ("收笔必是完整淡入"). A bounded frame loop drives
    // only that dry-out; the rebuild keys on the quantized window width, not
    // the frame clock, so steady streaming does not rebuild the string per
    // frame.
    val baseColorForReveal = LocalContentColor.current
    val releaseRate = remember { ReleaseRateTracker() }
    var lastAppendMs by remember { mutableLongStateOf(0L) }
    var settleNowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(visible.length) {
        val nowMs = SystemClock.uptimeMillis()
        releaseRate.record(nowMs, visible.length)
        lastAppendMs = nowMs
        while (SystemClock.uptimeMillis() - lastAppendMs < STREAMING_WINDOW_SETTLE_DRIVE_MS) {
            withFrameNanos { }
            settleNowMs = SystemClock.uptimeMillis()
        }
    }
    val idleMs = (settleNowMs - lastAppendMs).coerceAtLeast(0L)
    val windowChars = revealWindowChars(
        ratePerSecond = releaseRate.ratePerSecond(SystemClock.uptimeMillis()),
        idleMs = idleMs,
    )
    val revealActive = streaming || visible != renderSource || windowChars > 0

    if (onSettled != null) {
        val updatedOnSettled by rememberUpdatedState(onSettled)
        // Settled = buffer drained AND the dry-out finished AND the final
        // parse tick landed. The caller swaps renderers only after this and
        // only when its owner reports the swap cannot move what the reader sees.
        val settled = !streaming && visible == renderSource && windowChars == 0 &&
            visible == parsedInput
        LaunchedEffect(settled) {
            if (settled) updatedOnSettled()
        }
    }

    SelectionContainer {
        StreamingChunkedDisplayText(
            base = annotated,
            preparedBaseChunks = snapshot.chunks.takeIf { prefixMatches },
            suffix = rawSuffix,
            windowChars = if (revealActive) windowChars else 0,
            baseColor = baseColorForReveal,
            modifier = modifier.padding(start = 4.dp),
            style = LocalTextStyle.current,
        )
    }
}

private data class StreamingTextSnapshot(
    val input: String,
    val annotated: AnnotatedString,
    val chunks: List<StreamingTextChunk>? = null,
)

/** 最近 ~1.5s 的释放速率（字符/秒），驱动淡入窗口宽度。 */
internal class ReleaseRateTracker {
    private val samples = ArrayDeque<Pair<Long, Int>>()

    fun record(nowMs: Long, length: Int) {
        samples.addLast(nowMs to length)
        while (samples.isNotEmpty() && nowMs - samples.first().first > 1_500) {
            samples.removeFirst()
        }
    }

    fun ratePerSecond(nowMs: Long): Float {
        val newest = samples.lastOrNull() ?: return 0f
        val anchor = samples.firstOrNull { nowMs - it.first <= 1_500 } ?: return 0f
        val dt = (newest.first - anchor.first).coerceAtLeast(1L)
        return (newest.second - anchor.second).toFloat() / dt * 1000f
    }
}

/**
 * 淡入窗口：速率×0.15s，钳 4..48，按 4 字取整（速率逐帧抖动不触发整串重建）。
 * 上界 48 是故意的：窗口只需罩住"未解析原样尾巴"（≤ 速率×120ms 节拍，
 * cap 360 字/s 时 ≈43 字），再大就把淡入从"逐词"拉成"整段幽灵渐变"。
 * 静默 [STREAMING_WINDOW_DRY_DELAY_MS] 后线性收干，[STREAMING_WINDOW_DRY_MS]
 * 内必然归零——收干有完成时刻，交换渲染器前最后一个词必定完整淡入。
 * 纯函数，可单测。
 */
internal fun revealWindowChars(ratePerSecond: Float, idleMs: Long): Int {
    val base = (ratePerSecond * STREAMING_WINDOW_REVEAL_SECONDS).coerceIn(4f, 48f)
    val dry = if (idleMs <= STREAMING_WINDOW_DRY_DELAY_MS) {
        1f
    } else {
        (1f - (idleMs - STREAMING_WINDOW_DRY_DELAY_MS) / STREAMING_WINDOW_DRY_MS)
            .coerceAtLeast(0f)
    }
    val raw = base * dry
    if (raw < 1f) return 0
    return (raw / STREAMING_WINDOW_QUANTUM).toInt() * STREAMING_WINDOW_QUANTUM
}

internal fun applyStreamingWindowReveal(
    annotated: AnnotatedString,
    windowChars: Int,
    baseColor: Color,
): AnnotatedString {
    return buildStreamingDisplayAnnotated(annotated, "", windowChars, baseColor)
}

internal fun buildStreamingDisplayAnnotated(
    annotated: AnnotatedString,
    suffix: String,
    windowChars: Int,
    baseColor: Color,
): AnnotatedString {
    if (suffix.isEmpty() && (windowChars <= 0 || annotated.isEmpty())) return annotated
    val length = annotated.length + suffix.length
    fun charAt(index: Int): Char =
        if (index < annotated.length) annotated[index] else suffix[index - annotated.length]
    // Locate the tiny reveal window across the two sources without first
    // concatenating the whole message just to inspect its last <=48 codepoints.
    var start = length
    var seen = 0
    while (start > 0 && seen < windowChars) {
        start--
        if (charAt(start).isLowSurrogate() && start > 0 && charAt(start - 1).isHighSurrogate()) start--
        seen++
    }
    return buildAnnotatedString {
        append(annotated)
        append(suffix)
        var offset = start
        while (offset < length) {
            val pair = charAt(offset).isHighSurrogate() && offset + 1 < length &&
                charAt(offset + 1).isLowSurrogate()
            val nextOffset = offset + if (pair) 2 else 1
            val distanceFromEnd = length - offset
            val progress = if (windowChars <= 1) 1f else {
                (distanceFromEnd.toFloat() / windowChars).coerceIn(0f, 1f)
            }
            if (progress < 1f) {
                addStyle(streamingRevealSpanStyle(baseColor, progress), offset, nextOffset)
            }
            offset = nextOffset
        }
    }
}

// 窗口时长贴着"未解析原样尾巴"的尺寸走：尾巴 ≤ 速率 × 解析节拍（120ms），
// 0.15s 给出 1.25x 余量——窗口永远罩住尾巴（原始 ** 记号不露白），又不放大成
// 整段幽灵渐变（0.3s × 360caps 曾钉满 120 字 ≈ 4 行，2026-08-16 录屏取证）。
private const val STREAMING_WINDOW_REVEAL_SECONDS = 0.15f

/** 静默宽限：上游停顿这么久而窗口保持全开（打字途中不提前收干）。 */
private const val STREAMING_WINDOW_DRY_DELAY_MS = 350f

/** 宽限过后窗口线性归零的时长——收干完成时刻有上界（≈ 350+550ms）。 */
private const val STREAMING_WINDOW_DRY_MS = 550f

/** 窗口宽度量化步长：低于一步的变化不值得整串重建。 */
private const val STREAMING_WINDOW_QUANTUM = 4

/** 收干驱动帧循环的上界（必须 > 宽限+收干时长）。 */
private const val STREAMING_WINDOW_SETTLE_DRIVE_MS = 1_400L
