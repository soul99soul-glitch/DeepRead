package app.amber.feature.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Decides when a drained streaming text presentation may be replaced by the
 * settled [MarkdownBlock].
 *
 * The streaming single-Text presentation is a degraded renderer (image labels,
 * monospace tables, raw math, no code cards). It must not outlive the drain,
 * but the settled block tree has different geometry, so the swap can move the
 * character the reader is looking at inside this reply. The timeline that owns
 * LazyListState holds older-item tops in the same measure pass and defers the
 * swap while a drag/fling is in progress or the viewport reading line sits
 * inside this reply.
 */
@Stable
internal interface StreamingPresentationHandover {
    /**
     * Snapshot-readable; true when the settled renderer may replace the
     * retained streaming presentation now without moving visible content.
     */
    fun allowsSettledPresentation(): Boolean

    /** A block holds a finished streaming presentation awaiting the handover. */
    fun onAwaitingChange(awaiting: Boolean)
}

/** Outside an owning timeline there is no reading anchor to protect. */
internal object ImmediateStreamingPresentationHandover : StreamingPresentationHandover {
    override fun allowsSettledPresentation(): Boolean = true
    override fun onAwaitingChange(awaiting: Boolean) = Unit
}

internal val LocalStreamingPresentationHandover =
    staticCompositionLocalOf<StreamingPresentationHandover> { ImmediateStreamingPresentationHandover }

@Stable
internal class StreamingPresentationLifetime(initiallyStreaming: Boolean) {
    /** The streaming presentation has been shown and not yet handed over. */
    var retained by mutableStateOf(initiallyStreaming)
    /** The pacing buffer and reveal finished for the current generation. */
    var drained by mutableStateOf(!initiallyStreaming)
    /** The streaming presentation is actually composed (not a widget/deferred path). */
    var presented by mutableStateOf(false)
    val onDrained: () -> Unit = { drained = true }
}

/** Call inside the composed streaming presentation. */
@Composable
internal fun StreamingPresentationLifetime.MarkPresented() {
    DisposableEffect(this) {
        presented = true
        onDispose { presented = false }
    }
}

/**
 * Lifetime of one text block's streaming presentation.
 *
 * Streaming always uses the single-Text presentation. When generation ends
 * (completion, cancellation, or the block stops being the tail) the buffer
 * drains first; then the settled Markdown parse is warmed off the main thread
 * and the swap waits for [LocalStreamingPresentationHandover] to allow it.
 * A new generation for the same block (regenerate/continue) starts a new
 * lifetime. Returns the lifetime; the caller renders the streaming
 * presentation while `streaming || lifetime.retained`.
 */
@Composable
internal fun rememberStreamingPresentationLifetime(
    streaming: Boolean,
    settledContent: String,
): StreamingPresentationLifetime {
    val lifetime = remember { StreamingPresentationLifetime(streaming) }
    LaunchedEffect(streaming) {
        if (streaming) {
            lifetime.retained = true
            lifetime.drained = false
        }
    }
    if (!streaming && lifetime.retained && !lifetime.presented) {
        // Widget, mini-app or deferred-parse content never showed the streaming
        // presentation; there is nothing to hand over.
        LaunchedEffect(Unit) { lifetime.retained = false }
    } else if (!streaming && lifetime.retained) {
        val handover = LocalStreamingPresentationHandover.current
        DisposableEffect(handover) {
            handover.onAwaitingChange(true)
            onDispose { handover.onAwaitingChange(false) }
        }
        LaunchedEffect(handover, settledContent) {
            snapshotFlow { lifetime.drained }.first { it }
            // The first settled frame should be the formatted tree, not the
            // lossless plain-text fallback MarkdownBlock shows on a cache miss.
            withContext(Dispatchers.Default) { prepareMarkdownContent(settledContent) }
            snapshotFlow { handover.allowsSettledPresentation() }.first { it }
            lifetime.retained = false
        }
    }
    return lifetime
}
