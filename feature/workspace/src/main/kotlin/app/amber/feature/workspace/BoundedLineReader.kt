package app.amber.feature.workspace

import java.io.BufferedReader

internal data class BoundedLine(
    val text: String,
    val consumedChars: Int,
    val terminated: Boolean,
)

internal fun BufferedReader.readLineWithinLimit(maxChars: Int): BoundedLine? {
    require(maxChars > 0) { "maxChars must be positive" }
    val text = StringBuilder(minOf(maxChars, DEFAULT_BUFFER_SIZE))
    var consumed = 0
    while (consumed < maxChars) {
        val value = read()
        if (value < 0) {
            return if (consumed == 0) null else BoundedLine(text.toString(), consumed, terminated = true)
        }
        consumed++
        if (value == '\n'.code) {
            return BoundedLine(text.toString().trimEnd('\r'), consumed, terminated = true)
        }
        text.append(value.toChar())
    }
    return BoundedLine(text.toString(), consumed, terminated = false)
}
