package app.amber.agent

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.feature.ui.components.richtext.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class ChatStreamingChunkLayoutDeviceTest {
    @Test(timeout = 60_000) fun chunkBoundariesPreserveNativeLineGeometry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity",
        )).bufferedReader().use { it.readText() }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    val measurer = rememberTextMeasurer(cacheSize = 0)
                    val markdownStyle = rememberMarkdownSingleTextStyle()
                    LaunchedEffect(Unit) {
                        try {
                            val body = (0 until 64).joinToString("\n\n") {
                                "## 标题 $it\n\n中文 **重点** 👩‍💻 é and `inline code`.\n\n" +
                                    "```kotlin\nfun step$it() = $it\n```\n\n| a | b |\n| --- | --- |\n| 1 | 2 |"
                            }
                            val parsed = parseMarkdownUncached(body)
                            val annotated = mdNodeToAnnotatedString(parsed.preprocessed, parsed.tree,
                                markdownStyle, imageFallbackLabel = "Image")
                            val chunks = splitStreamingTextChunks(annotated)
                            check(chunks.size > 1)
                            check(chunks.joinToString("\n") { it.text.text } == annotated.text)
                            for (width in listOf(320, 393, 700)) {
                                val style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
                                val full = measurer.measure(annotated, style, constraints = Constraints(maxWidth = width))
                                val parts = chunks.map { measurer.measure(it.text, style, constraints = Constraints(maxWidth = width)) }
                                var top = 0f
                                var globalLine = 0
                                var baselineError = 0f
                                parts.forEachIndexed { index, part ->
                                    if (index > 0) {
                                        val previous = parts[index - 1]
                                        val bridge = measurer.measure(streamingChunkBridge(previous, part, chunks[index - 1].lineBreak),
                                            style, constraints = Constraints(maxWidth = width))
                                        check(bridge.lineCount == 2)
                                        top += previous.getLineBaseline(previous.lineCount - 1) +
                                            bridge.getLineBaseline(1) - bridge.getLineBaseline(0) - part.getLineBaseline(0)
                                    }
                                    repeat(part.lineCount) { line ->
                                        baselineError = maxOf(baselineError, kotlin.math.abs(top + part.getLineBaseline(line) - full.getLineBaseline(globalLine + line)))
                                    }
                                    globalLine += part.lineCount
                                }
                                val height = top + parts.last().size.height
                                val lines = parts.sumOf { it.lineCount }
                                Log.i("AmberChunkLayout", "width=$width fullHeight=${full.size.height} chunkHeight=$height fullLines=${full.lineCount} chunkLines=$lines chunks=${chunks.size} baselineError=$baselineError")
                                check(lines == full.lineCount) { "Chunking changed line count" }
                                check(kotlin.math.abs(height - full.size.height) <= chunks.size && baselineError <= chunks.size) { "Chunking changed line geometry" }
                            }
                        } catch (error: Throwable) { failure = error }
                        finally { done.countDown() }
                    }
                }
            } }
            check(done.await(45, TimeUnit.SECONDS))
            failure?.let { throw it }
        }
    }
}
