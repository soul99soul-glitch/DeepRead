package app.amber.feature.ui.components.richtext

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatcher used for background Markdown/HTML parse work.
 *
 * Tests inject a paused dispatcher so the first composition is guaranteed
 * to be the cache-miss path (no race with [Dispatchers.Default]).
 */
internal val LocalMarkdownParseDispatcher = staticCompositionLocalOf<CoroutineDispatcher> {
    Dispatchers.Default
}

/** Test hook: true while the invisible pending placeholder is on screen. */
internal val LocalMarkdownPendingProbe = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

/**
 * Max UTF-16 length parsed on the composition thread on a cache miss.
 *
 * Cacheable content at or below this bound is parsed synchronously so the
 * first settled frame is the formatted tree (no raw `##` / `**` / `<div>`
 * flash). Longer cacheable content uses an invisible equal-measurement
 * placeholder until the background parse is ready. Oversize uncacheable
 * content still uses the visible lossless raw fallback.
 *
 * Bound is the largest measured length class on 25128PNA1C whose every
 * main-thread [MarkdownParseCache.getOrParse] sample is ≤4ms
 * (`round4-diagnosis/parse-threshold.json`). 512 chars already exceeds
 * that budget (median 4.93ms). Snapshot suites inject a paused
 * [LocalMarkdownParseDispatcher], advance it until parse work is done,
 * then waitForIdle before asserting. They do not poll until the dump
 * equals the golden.
 */
internal const val MARKDOWN_SYNC_PARSE_MAX_CHARS = 256

internal fun markdownAllowsSyncParse(content: String): Boolean =
    content.length <= MARKDOWN_SYNC_PARSE_MAX_CHARS && isMarkdownContentCacheable(content)
