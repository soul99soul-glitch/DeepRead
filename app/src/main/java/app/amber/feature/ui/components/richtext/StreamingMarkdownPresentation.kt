package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import java.util.LinkedHashSet

internal data class StreamingMarkdownPresentationSnapshot(
    val semanticContent: String,
    val motionActive: Boolean,
)

private data class PendingMotionCommit(
    val endOffset: Int,
    val firstVisibleAtNanos: Long,
)

internal class StreamingMarkdownPresentationGate(
    initialContent: String = "",
    private val commitDelayNanos: Long = STREAMING_MOTION_COMMIT_DELAY_MS * 1_000_000L,
) {
    private var lastContent: String = initialContent
    private var semanticEnd: Int = initialContent.length
    private var pendingCommits: List<PendingMotionCommit> = emptyList()

    fun update(
        content: String,
        inputActive: Boolean,
        nowNanos: Long,
    ): StreamingMarkdownPresentationSnapshot {
        if (content != lastContent) {
            when {
                content.length < lastContent.length || !content.startsWith(lastContent) -> {
                    pendingCommits = emptyList()
                    semanticEnd = content.length
                }

                content.length > lastContent.length && inputActive -> {
                    pendingCommits = pendingCommits + PendingMotionCommit(
                        endOffset = content.length,
                        firstVisibleAtNanos = nowNanos,
                    )
                }

                else -> {
                    pendingCommits = emptyList()
                    semanticEnd = content.length
                }
            }
            lastContent = content
            semanticEnd = semanticEnd.coerceIn(0, content.length)
        }

        val readyEnd = pendingCommits
            .asSequence()
            .filter { nowNanos - it.firstVisibleAtNanos >= commitDelayNanos }
            .maxOfOrNull { it.endOffset }
        if (readyEnd != null) {
            semanticEnd = maxOf(semanticEnd, readyEnd).coerceAtMost(content.length)
        }
        pendingCommits = pendingCommits.filter { commit ->
            commit.endOffset > semanticEnd && commit.endOffset <= content.length
        }
        if (!inputActive && pendingCommits.isEmpty()) {
            semanticEnd = content.length
        }
        return snapshot(content)
    }

    private fun snapshot(content: String): StreamingMarkdownPresentationSnapshot {
        val safeSemanticEnd = semanticEnd.coerceIn(0, content.length)
        return StreamingMarkdownPresentationSnapshot(
            semanticContent = content.substring(0, safeSemanticEnd),
            motionActive = pendingCommits.isNotEmpty() || safeSemanticEnd < content.length,
        )
    }
}

internal data class StreamingMarkdownMotionKey(
    val type: String,
    val absoluteStartOffset: Int,
)

internal class StreamingMarkdownMotionScope {
    private val animatedKeys = LinkedHashSet<StreamingMarkdownMotionKey>()

    fun claim(key: StreamingMarkdownMotionKey): Boolean {
        val added = animatedKeys.add(key)
        if (added) {
            StreamingRenderProbe.motionClaimCount = animatedKeys.size
        }
        return added
    }

    fun hasSeen(key: StreamingMarkdownMotionKey): Boolean {
        return key in animatedKeys
    }

    fun claimCount(): Int = animatedKeys.size

    fun clear() {
        animatedKeys.clear()
        StreamingRenderProbe.motionClaimCount = 0
    }
}

internal fun streamingMarkdownMotionKey(
    type: MdNodeType,
    sourceOffsetBase: Int,
    nodeStartOffset: Int,
): StreamingMarkdownMotionKey {
    return StreamingMarkdownMotionKey(
        type = type.toString(),
        absoluteStartOffset = sourceOffsetBase + nodeStartOffset,
    )
}

internal fun streamingTableCellMotionKey(
    tableKey: StreamingMarkdownMotionKey,
    rowIndex: Int,
    columnIndex: Int,
    header: Boolean,
): StreamingMarkdownMotionKey {
    val section = if (header) "header" else "row"
    return StreamingMarkdownMotionKey(
        type = "${tableKey.type}:$section:$rowIndex:$columnIndex",
        absoluteStartOffset = tableKey.absoluteStartOffset,
    )
}

/**
 * Codex-style per-character reveal. Each suffix character runs its own
 * fade curve anchored to the moment IT first became visible (see
 * [StreamingCharRevealClock]), instead of a curve shared by the whole parse
 * batch — batch anchoring made late-arriving characters start most of the way
 * through the shared curve and pop in nearly opaque.
 *
 * Absorption side: when a parse tick promotes suffix text into the settled
 * AnnotatedString, the promoted characters keep fading on the same
 * per-character clock (keys carried by the caller), so the tick boundary is
 * invisible — no snap, no catch-up ramp. Both mechanisms live entirely in L4.
 */
internal const val STREAMING_CHAR_REVEAL_FADE_MS = 200
internal const val STREAMING_CHAR_REVEAL_STAGGER_MS = 9
internal const val STREAMING_CHAR_REVEAL_MAX_CASCADE_MS = 140
internal const val STREAMING_CHAR_REVEAL_MAX_PENDING = 160
internal const val STREAMING_CHAR_REVEAL_START_ALPHA = 0f
internal const val STREAMING_CHAR_REVEAL_LIFT_EM = 0.08f
private const val STREAMING_MOTION_COMMIT_DELAY_MS =
    STREAMING_CHAR_REVEAL_FADE_MS + STREAMING_CHAR_REVEAL_MAX_CASCADE_MS + 16
private const val STREAMING_BLOCK_REVEAL_MS = 190
private const val STREAMING_BLOCK_REVEAL_START_ALPHA = 0.86f
internal const val STREAMING_TABLE_CELL_REVEAL_MS = 150
internal const val STREAMING_TABLE_CELL_REVEAL_START_ALPHA = 0.72f
internal const val STREAMING_TABLE_CELL_REVEAL_MAX_CELLS = 48
internal const val STREAMING_LIST_ITEM_REVEAL_MS = 130
internal const val STREAMING_LIST_ITEM_STAGGER_LIMIT = 8
internal const val STREAMING_LIST_ITEM_STAGGER_MS = 8

@Composable
internal fun rememberStreamingMarkdownPresentation(
    content: String,
    inputActive: Boolean,
): StreamingMarkdownPresentationSnapshot {
    val gate = remember { StreamingMarkdownPresentationGate(initialContent = content) }
    var snapshot by remember {
        mutableStateOf(
            StreamingMarkdownPresentationSnapshot(
                semanticContent = content,
                motionActive = false,
            )
        )
    }
    val updatedContent by rememberUpdatedState(content)
    val updatedInputActive by rememberUpdatedState(inputActive)

    LaunchedEffect(content, inputActive) {
        snapshot = gate.update(
            content = content,
            inputActive = inputActive,
            nowNanos = System.nanoTime(),
        )
    }
    LaunchedEffect(inputActive, snapshot.motionActive) {
        if (!inputActive && !snapshot.motionActive) return@LaunchedEffect
        while (true) {
            val next = withFrameNanos { nowNanos ->
                gate.update(
                    content = updatedContent,
                    inputActive = updatedInputActive,
                    nowNanos = nowNanos,
                )
            }
            snapshot = next
            if (!updatedInputActive && !next.motionActive) break
        }
    }

    return snapshot
}

@Composable
internal fun ReportStreamingVisualActive(
    active: Boolean,
    onActiveChange: ((Boolean) -> Unit)?,
) {
    val updatedOnActiveChange by rememberUpdatedState(onActiveChange)
    var reportedActive by remember { mutableStateOf(false) }
    SideEffect {
        if (reportedActive != active) {
            reportedActive = active
            updatedOnActiveChange?.invoke(active)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (reportedActive) {
                updatedOnActiveChange?.invoke(false)
            }
        }
    }
}

@Composable
internal fun StreamingBlockReveal(
    node: MdNode,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    delayMillis: Int = 0,
    durationMillis: Int = STREAMING_BLOCK_REVEAL_MS,
    startAlpha: Float = STREAMING_BLOCK_REVEAL_START_ALPHA,
    content: @Composable (Modifier) -> Unit,
) {
    val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current
    val key = remember(node.type, sourceOffsetBase, node.startOffset) {
        streamingMarkdownMotionKey(
            type = node.type,
            sourceOffsetBase = sourceOffsetBase,
            nodeStartOffset = node.startOffset,
        )
    }
    content(
        modifier.then(
            streamingRevealModifier(
                key = key,
                enabled = enabled,
                delayMillis = delayMillis,
                durationMillis = durationMillis,
                startAlpha = startAlpha,
            )
        )
    )
}

@Composable
internal fun streamingRevealModifier(
    key: StreamingMarkdownMotionKey,
    enabled: Boolean = true,
    delayMillis: Int = 0,
    durationMillis: Int = STREAMING_BLOCK_REVEAL_MS,
    startAlpha: Float = STREAMING_BLOCK_REVEAL_START_ALPHA,
): Modifier {
    val scope = LocalStreamingMarkdownMotionScope.current
    val revealActive = enabled && LocalStreamingTailActive.current != null
    val shouldAnimate = remember(key) {
        mutableStateOf(false)
    }
    LaunchedEffect(scope, revealActive, key) {
        // Once claimed, never un-claim: revealActive drops the moment
        // generation ends, and resetting here snapped a mid-flight block rise
        // to its final position — the message's final block settles right at
        // the end, so its rise was almost always cut. A finished animation is
        // a no-op graphicsLayer, so letting it complete costs nothing.
        if (shouldAnimate.value) return@LaunchedEffect
        val motionScope = scope
        shouldAnimate.value = revealActive && motionScope != null && motionScope.claim(key)
    }
    if (!shouldAnimate.value) return Modifier

    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        anim.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = LinearEasing,
            ),
        )
    }
    return Modifier.graphicsLayer {
        val eased = codexStreamingAlphaProgress(anim.value)
        alpha = startAlpha + (1f - startAlpha) * eased
    }
}

/**
 * Appearance-anchored streaming reveal layer (L4).
 * Instead of mapping per-codepoint alpha back to source offsets through the
 * markdown transforms, it styles only one contiguous range — the live suffix
 * appended after the statically-parsed text. [staticLength] is the boundary
 * between settled text and the revealing suffix. Each suffix character fades
 * and lifts on its own clock (stamped by [clock] the first frame it is seen),
 * so glyphs float in continuously regardless of parse-tick timing.
 *
 * [settledCatchupStart]/[settledCatchupSuffixOffset] carry promotion
 * continuity: the range of settled text grown by the latest parse tick keeps
 * fading on the SAME per-character clock (keys are the source offsets the
 * suffix had before promotion), so a promotion is visually a no-op — no snap
 * to opaque, no brightness ramp.
 *
 * Returns [combined] unchanged when there's nothing to animate.
 */
internal fun applyStreamingCharReveal(
    combined: AnnotatedString,
    staticLength: Int,
    suffixSourceOffset: Int,
    clock: StreamingCharRevealClock,
    nowNanos: Long,
    baseColor: Color,
    settledCatchupStart: Int = staticLength,
    settledCatchupSuffixOffset: Int = -1,
): AnnotatedString {
    if (baseColor == Color.Unspecified) return combined
    if (staticLength < 0 || staticLength > combined.length) return combined
    val hasSuffix = staticLength < combined.length
    val catchupActive = settledCatchupSuffixOffset >= 0 &&
        settledCatchupStart in 0 until staticLength
    if (!hasSuffix && !catchupActive) return combined

    val fadeNanos = STREAMING_CHAR_REVEAL_FADE_MS * 1_000_000L
    if (hasSuffix) {
        clock.stamp(
            suffixText = combined.text.substring(staticLength),
            suffixSourceOffset = suffixSourceOffset,
            nowNanos = nowNanos,
            fadeNanos = fadeNanos,
            staggerNanos = STREAMING_CHAR_REVEAL_STAGGER_MS * 1_000_000L,
            maxCascadeNanos = STREAMING_CHAR_REVEAL_MAX_CASCADE_MS * 1_000_000L,
            maxPending = STREAMING_CHAR_REVEAL_MAX_PENDING,
            pruneBelowOffset = if (catchupActive) {
                minOf(suffixSourceOffset, settledCatchupSuffixOffset)
            } else {
                suffixSourceOffset
            },
        )
    }
    var styledAny = false
    val built = buildAnnotatedString {
        append(combined)
        if (catchupActive) {
            // Promoted characters continue their own fade curves. Characters
            // the clock never saw (transforming promotion, deferred resume)
            // render opaque — progressAt's missing-key contract.
            var offset = settledCatchupStart
            while (offset < staticLength) {
                val codePoint = combined.text.codePointAt(offset)
                val nextOffset = offset + Character.charCount(codePoint)
                val progress = clock.progressAt(
                    absOffset = settledCatchupSuffixOffset + (offset - settledCatchupStart),
                    nowNanos = nowNanos,
                    fadeNanos = fadeNanos,
                )
                if (progress < 1f) {
                    styledAny = true
                    addStyle(
                        style = streamingRevealSpanStyle(
                            baseColor = baseColor,
                            localProgress = progress,
                        ),
                        start = offset,
                        end = nextOffset,
                    )
                }
                offset = nextOffset
            }
        }
        var offset = staticLength
        while (offset < combined.length) {
            val codePoint = combined.text.codePointAt(offset)
            val nextOffset = offset + Character.charCount(codePoint)
            val progress = clock.progressAt(
                absOffset = suffixSourceOffset + (offset - staticLength),
                nowNanos = nowNanos,
                fadeNanos = fadeNanos,
            )
            if (progress < 1f) {
                styledAny = true
                addStyle(
                    style = streamingRevealSpanStyle(
                        baseColor = baseColor,
                        localProgress = progress,
                    ),
                    start = offset,
                    end = nextOffset,
                )
            }
            offset = nextOffset
        }
    }
    return if (styledAny) built else combined
}

internal fun applyStreamingCharRevealPlain(
    text: String,
    suffixSourceOffset: Int,
    clock: StreamingCharRevealClock,
    nowNanos: Long,
    baseColor: Color,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    return applyStreamingCharReveal(
        combined = buildAnnotatedString { append(text) },
        staticLength = 0,
        suffixSourceOffset = suffixSourceOffset,
        clock = clock,
        nowNanos = nowNanos,
        baseColor = baseColor,
    )
}

internal fun streamingRevealSpanStyle(
    baseColor: Color,
    localProgress: Float,
): SpanStyle {
    val alphaProgress = codexStreamingAlphaProgress(localProgress)
    val alpha = STREAMING_CHAR_REVEAL_START_ALPHA +
        (1f - STREAMING_CHAR_REVEAL_START_ALPHA) * alphaProgress
    return SpanStyle(
        color = baseColor.copy(alpha = alpha),
        baselineShift = BaselineShift(-STREAMING_CHAR_REVEAL_LIFT_EM * (1f - alphaProgress)),
    )
}

internal fun codexStreamingAlphaProgress(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}
