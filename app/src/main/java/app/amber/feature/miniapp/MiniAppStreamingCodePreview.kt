package app.amber.feature.miniapp

/**
 * Incremental decoder for the `"html"` string field of a streaming MiniApp JSON payload.
 *
 * Aligns with iOS `IOSMiniAppStreamingCodePreviewState` and Harmony `MiniAppStreamingCodePreview`:
 * - Only decodes newly appended characters when streaming grows monotonically; resets if the
 *   prefix changes (e.g. regeneration or replacement).
 * - Unescapes JSON string escapes (`\n`, `\t`, `\r`, `\b`, `\f`, `\"`, `\\`, `\/`, `\uXXXX`).
 * - Caps the displayed tail window to [MINI_APP_STREAM_PREVIEW_LIMIT] characters so the
 *   Compose text layout cost stays O(1) regardless of total HTML size.
 */
const val MINI_APP_STREAM_PREVIEW_LIMIT: Int = 2000

class MiniAppStreamingCodePreview {
    private enum class ScanState {
        SEARCHING_KEY,
        WAITING_VALUE,
        READING_VALUE,
        FINISHED,
    }

    private var raw: String = ""
    private var state: ScanState = ScanState.SEARCHING_KEY
    private var escaping: Boolean = false
    private var unicodeDigits: StringBuilder? = null
    private val decoded = StringBuilder()

    fun update(next: String) {
        if (!next.startsWith(raw)) {
            reset()
        }
        val from = raw.length
        raw = next
        consume(from)
    }

    fun displayText(
        limit: Int = MINI_APP_STREAM_PREVIEW_LIMIT,
        omittedPrefixFormat: (Int) -> String = { count -> "已省略 $count 字" },
    ): String {
        val source = if (state == ScanState.READING_VALUE || state == ScanState.FINISHED) {
            decoded.toString()
        } else {
            raw
        }
        if (source.length <= limit) return source
        val omitted = source.length - limit
        return "${omittedPrefixFormat(omitted)}\n${source.takeLast(limit)}"
    }

    private fun reset() {
        raw = ""
        state = ScanState.SEARCHING_KEY
        escaping = false
        unicodeDigits = null
        decoded.setLength(0)
    }

    private fun consume(from: Int) {
        var i = from
        if (state == ScanState.SEARCHING_KEY) {
            val searchStart = (from - HTML_KEY.length + 1).coerceAtLeast(0)
            val at = raw.indexOf(HTML_KEY, startIndex = searchStart)
            if (at < 0) return
            state = ScanState.WAITING_VALUE
            i = at + HTML_KEY.length
        }
        if (state == ScanState.WAITING_VALUE) {
            while (i < raw.length && raw[i] != ':') i++
            if (i >= raw.length) return
            i++ // skip ':'
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) return
            if (raw[i] != '"') return
            state = ScanState.READING_VALUE
            i++
        }
        if (state != ScanState.READING_VALUE) return

        var runStart = i
        while (i < raw.length) {
            val ch = raw[i]
            val digits = unicodeDigits
            when {
                digits != null -> {
                    digits.append(ch)
                    if (digits.length == 4) {
                        val code = digits.toString().toIntOrNull(16)
                        if (code != null) {
                            decoded.append(code.toChar())
                        }
                        unicodeDigits = null
                    }
                    runStart = i + 1
                }
                escaping -> {
                    escaping = false
                    if (ch == 'u') {
                        unicodeDigits = StringBuilder(4)
                    } else {
                        decoded.append(
                            when (ch) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                '"', '\\', '/' -> ch
                                else -> ch
                            }
                        )
                    }
                    runStart = i + 1
                }
                ch == '\\' -> {
                    if (i > runStart) decoded.append(raw, runStart, i)
                    escaping = true
                    runStart = i + 1
                }
                ch == '"' -> {
                    if (i > runStart) decoded.append(raw, runStart, i)
                    state = ScanState.FINISHED
                    return
                }
            }
            i++
        }
        if (!escaping && unicodeDigits == null && runStart < raw.length) {
            decoded.append(raw, runStart, raw.length)
        }
    }

    companion object {
        private const val HTML_KEY = "\"html\""
    }
}
