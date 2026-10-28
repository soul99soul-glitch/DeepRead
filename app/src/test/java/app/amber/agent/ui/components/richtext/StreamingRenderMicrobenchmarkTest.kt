package app.amber.feature.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in CPU diagnostic, not a frame-rate assertion; run on the same warmed JVM. */
class StreamingRenderMicrobenchmarkTest {
    @Test fun stableBlocksAndSaturatedBacklog() {
        assumeTrue(System.getenv("AMBER_RENDER_MICROBENCH") == "1")
        val style = MarkdownSingleTextStyle(16.sp, List(6) { 12.sp }, Color.Blue,
            Color.Blue, Color.Gray, Color.Gray, null, 12.sp, Color.Gray, Color.Gray)
        val body = (0 until 100).joinToString("\n\n") {
            "## Section $it\n\n中文说明 **重点 $it** and `code_$it` 测量稳定历史段落。\n\n" +
                "```kotlin\nfun step$it(value: Int) = value + $it\n```"
        }
        val parses = (0 until 20).map { parseMarkdownUncached(body + "\n\n尾部更新" + "字".repeat(it)) }
        fun map(cached: Boolean) {
            val cache = if (cached) StreamingTextBlockCache() else null
            for (parsed in parses) {
                mdNodeToAnnotatedString(parsed.preprocessed, parsed.tree, style,
                    imageFallbackLabel = "Image", blockCache = cache)
            }
        }
        repeat(3) { map(false); map(true) }
        fun sample(name: String, operation: () -> Unit) {
            val times = List(9) {
                val start = System.nanoTime()
                operation()
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
            println("streamingMicrobench name=$name medianMs=${times[4]} p95Ms=${times[8]} samples=$times")
        }
        sample("map-all-20-snapshots") { map(false) }
        sample("map-stable-cache-20-snapshots") { map(true) }
        val backlog = "中文 👩‍💻 é latin-word ".repeat(2500)
        repeat(100) { countStreamingReleaseUnits(backlog, 0, backlog.length) }
        sample("count-all-100-frames") { repeat(100) { countStreamingReleaseUnits(backlog, 0, backlog.length) } }
        sample("count-bounded-100-frames") { repeat(100) { countStreamingReleaseUnits(backlog, 0, backlog.length, STREAMING_PACE_SATURATED_UNITS) } }
    }
}
