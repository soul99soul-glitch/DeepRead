package app.amber.feature.ui.components.richtext

import app.amber.feature.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownPreprocessNative

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
private val BARE_WEB_URL_REGEX = Regex(
    """(?<![\w/@:\[(])((?:[A-Za-z0-9-]+\.)+(?:com|net|org|io|ai|cn|co|dev|app|me|info|xyz|news)(?![A-Za-z0-9-])(?:/[^\s<>()\[\]{}"']*)?)"""
)

// 预处理markdown内容
internal fun preprocessMarkdownContent(content: String): String {
    val displayContent = stripSearchImageFencesForDisplay(content)
    // TD.Rust.1b: native single-pass scan if available + flag on. Falls back
    // to the original Kotlin path on null return / not loaded / flag off.
    // The native flag is gated by NativePathPrefs.markdownHtml because
    // preprocess is logically a sub-step of the HTML pipeline.
    if (MarkdownNativeSwitch.isHtmlEnabled() &&
        MarkdownPreprocessNative.available
    ) {
        val nativeResult = MarkdownPreprocessNative.preprocess(displayContent)
        if (nativeResult != null) return nativeResult
    }

    return mapMarkdownOutsideCode(normalizeMarkdownLatexOutsideCode(displayContent), ::linkifyBareUrlSegment)
}

// Transform each non-code segment independently so LaTeX replacements cannot shift code offsets.
internal fun normalizeMarkdownLatexOutsideCode(content: String): String =
    mapMarkdownOutsideCode(content) { segment ->
        val inline = INLINE_LATEX_REGEX.replace(segment) { "$" + it.groupValues[1] + "$" }
        BLOCK_LATEX_REGEX.replace(inline) { "$$" + it.groupValues[1] + "$$" }
    }

private fun mapMarkdownOutsideCode(content: String, transform: (String) -> String): String {
    val output = StringBuilder(content.length)
    var cursor = 0
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        if (cursor < match.range.first) {
            output.append(transform(content.substring(cursor, match.range.first)))
        }
        output.append(match.value)
        cursor = match.range.last + 1
    }
    if (cursor < content.length) {
        output.append(transform(content.substring(cursor)))
    }
    return output.toString()
}

private fun linkifyBareUrlSegment(segment: String): String {
    // Pre-scan the segment for explicit-link `[label](dest)` spans so the bare-URL
    // scanner never rewrites a domain-looking token that already lives inside an
    // explicit link. Without this, a destination like
    // `[guides](https://developer.android.com/guide)` has `android.com/guide`
    // matched mid-destination and corrupted into a nested link; a label like
    // `[see example.com](...)` has its label domain corrupted the same way.
    // GFM does not re-linkify text inside an explicit link, so we skip the WHOLE
    // `[label](dest)` construct (both the label and the destination).
    val skipRanges = findExplicitLinkRanges(segment)
    return BARE_WEB_URL_REGEX.replace(segment) { match ->
        val raw = match.value
        if (positionInAnyRange(match.range.first, skipRanges)) {
            // Inside an explicit link's label or destination — leave verbatim.
            return@replace raw
        }
        val url = raw.trimEnd('.', ',', ';', ':', '。', '，', '；', '：')
        val trailing = raw.removePrefix(url)
        "[$url](https://$url)$trailing"
    }
}

/**
 * Collects the character ranges of explicit markdown links `[label](dest)` in
 * [segment]. Each returned range spans the WHOLE construct (from the label's `[`
 * to the destination's closing `)`), so the bare-URL linkifier can skip any
 * candidate match whose start falls inside one.
 *
 * Single forward pass. For each `](` boundary it walks back to the matching
 * (bracket-balanced, escape-aware) `[` for the label start, then forward over
 * balanced parens (escape-aware) for the destination end — mirroring the one
 * level of nesting the downstream parser tolerates in a destination
 * (`Kotlin_(programming_language)`, `path\(1\)`). Allocation-light: returns a
 * flat `IntArray` of `[start, end, start, end, …]` pairs, empty when there are
 * no links.
 */
private fun findExplicitLinkRanges(segment: String): IntArray {
    if (segment.length < 4) return EMPTY_INT_ARRAY // need at least "[](" + ")"
    var ranges: IntArray? = null
    var count = 0
    var i = 0
    val n = segment.length
    while (i < n - 1) {
        if (segment[i] == ']' && segment[i + 1] == '(') {
            val labelStart = findLabelStart(segment, i)
            if (labelStart < 0) {
                i++
                continue
            }
            val destEnd = findDestinationEnd(segment, i + 1)
            if (destEnd < 0) {
                i++
                continue
            }
            if (ranges == null) ranges = IntArray(8)
            if (count * 2 + 2 > ranges.size) {
                ranges = ranges.copyOf(ranges.size * 2)
            }
            ranges[count * 2] = labelStart
            ranges[count * 2 + 1] = destEnd
            count++
            i = destEnd + 1
        } else {
            i++
        }
    }
    return when {
        ranges == null -> EMPTY_INT_ARRAY
        count * 2 == ranges.size -> ranges
        else -> ranges.copyOf(count * 2)
    }
}

/**
 * From a `]` at [closeBracketIndex], scans back to the matching unescaped `[`,
 * balancing nested `[]` pairs. Returns the index of the label's opening `[`, or
 * -1 if none.
 */
private fun findLabelStart(segment: String, closeBracketIndex: Int): Int {
    var depth = 0
    var j = closeBracketIndex - 1
    while (j >= 0) {
        val c = segment[j]
        val escaped = j > 0 && segment[j - 1] == '\\'
        if (!escaped) {
            if (c == ']') {
                depth++
            } else if (c == '[') {
                if (depth == 0) return j
                depth--
            }
        }
        j--
    }
    return -1
}

/**
 * From the `(` at [openParenIndex], scans forward to the matching `)`, balancing
 * nested parens and honoring backslash escapes. Returns the index of the closing
 * `)`, or -1 if unbalanced.
 */
private fun findDestinationEnd(segment: String, openParenIndex: Int): Int {
    var depth = 0
    var k = openParenIndex
    val n = segment.length
    while (k < n) {
        val c = segment[k]
        if (c == '\\') {
            k += 2
            continue
        }
        when (c) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return k
            }
        }
        k++
    }
    return -1
}

/** True if [position] lies within any `[start, end]` pair packed in [ranges]. */
private fun positionInAnyRange(position: Int, ranges: IntArray): Boolean {
    var idx = 0
    while (idx < ranges.size) {
        if (position >= ranges[idx] && position <= ranges[idx + 1]) return true
        idx += 2
    }
    return false
}

private val EMPTY_INT_ARRAY = IntArray(0)

/**
 * Existing test seam for preprocessing without composing a full render.
 */
@androidx.annotation.VisibleForTesting
internal fun preProcessForTest(content: String): String = preprocessMarkdownContent(content)
