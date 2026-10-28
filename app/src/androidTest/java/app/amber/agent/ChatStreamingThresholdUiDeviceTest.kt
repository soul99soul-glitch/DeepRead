package app.amber.agent

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.feature.ui.components.richtext.StreamingChunkedDisplayText
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Device UI evidence for the chunk threshold and fallback scope:
 * crossing 3072 keeps the first line's baseline and line geometry; 320 dp +
 * 1.3x font scale behaves the same; a long unbroken line stays one Text.
 */
class ChatStreamingThresholdUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private data class Sample(
        val chunkCount: Int,
        val firstBaseline: Float?,
        val firstTop: Float?,
        val firstText: String,
        val firstLineCount: Int,
    )

    private fun sample(
        layouts: ConcurrentHashMap<Int, TextLayoutResult>,
        tops: ConcurrentHashMap<Int, Float>,
    ): Sample {
        val first = layouts[0]
        return Sample(
            chunkCount = layouts.size,
            firstBaseline = first?.getLineBaseline(0),
            firstTop = tops[0],
            firstText = first?.layoutInput?.text?.text.orEmpty(),
            firstLineCount = first?.lineCount ?: 0,
        )
    }

    @Test(timeout = 120_000)
    fun thresholdCrossingKeepsFirstLineGeometryAtNarrowWidthAndLargeFont() {
        val line = "这一行是阈值几何验证，中文与 Unicode 👩‍💻 é 保持完整。\n"
        val prefix = line.repeat(60)
        val crossing = prefix + line.repeat(30)
        val longer = prefix + line.repeat(90)
        check(prefix.length < 3_072 && crossing.length > 3_072) {
            "Threshold fixture wrong: prefix=${prefix.length} crossing=${crossing.length}"
        }
        val state = MutableStateFlow(prefix)
        val fontScale = MutableStateFlow(1.3f)
        val layouts = ConcurrentHashMap<Int, TextLayoutResult>()
        val tops = ConcurrentHashMap<Int, Float>()
        val records = ArrayList<JSONObject>()
        val scenario = launchHost()
        scenario.use {
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        val text by state.collectAsState()
                        val scale by fontScale.collectAsState()
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density, scale),
                        ) {
                            Box(Modifier.width(320.dp)) {
                                StreamingChunkedDisplayText(
                                    base = AnnotatedString(text),
                                    suffix = "",
                                    windowChars = 0,
                                    baseColor = Color.Unspecified,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = LocalTextStyle.current,
                                    onChunkPlaced = { start, top, layout ->
                                        layouts[start] = layout
                                        tops[start] = top
                                    },
                                )
                            }
                        }
                    }
                }
            }
            fun awaitSample(expectedChunks: Int, label: String): Sample {
                val deadline = SystemClock.uptimeMillis() + 10_000
                var last = sample(layouts, tops)
                while (SystemClock.uptimeMillis() < deadline) {
                    compose.waitForIdle()
                    SystemClock.sleep(120)
                    last = sample(layouts, tops)
                    if (last.chunkCount >= expectedChunks && last.firstBaseline != null) break
                }
                check(last.firstBaseline != null) { "No layout for $label" }
                return last
            }
            for (scale in listOf(1.0f, 1.3f, 0.85f)) {
                fontScale.value = scale
                layouts.clear(); tops.clear()
                state.value = prefix
                SystemClock.sleep(300)
                val belowSample = awaitSample(1, "below-$scale")
                assertEquals("Below threshold must be one Text at $scale", 1, belowSample.chunkCount)

                layouts.clear(); tops.clear()
                state.value = crossing
                SystemClock.sleep(300)
                val crossSample = awaitSample(2, "cross-$scale")
                assertTrue("Crossing must split at $scale", crossSample.chunkCount >= 2)
                assertNotNull(crossSample.firstBaseline)
                assertEquals(
                    "First-line baseline must not move at the split ($scale)",
                    belowSample.firstBaseline!!,
                    crossSample.firstBaseline!!,
                    0.5f,
                )
                assertTrue(
                    "First chunk must keep the same leading text ($scale)",
                    belowSample.firstText.startsWith(crossSample.firstText),
                )
                assertTrue("First chunk must keep its lines ($scale)", crossSample.firstLineCount > 0)

                layouts.clear(); tops.clear()
                state.value = longer
                SystemClock.sleep(300)
                val longerSample = awaitSample(2, "longer-$scale")
                assertEquals(
                    "First-line baseline must survive later boundaries ($scale)",
                    belowSample.firstBaseline!!,
                    longerSample.firstBaseline!!,
                    0.5f,
                )
                records += JSONObject().apply {
                    put("fontScale", scale)
                    put("chunksBelow", belowSample.chunkCount)
                    put("chunksCross", crossSample.chunkCount)
                    put("chunksLonger", longerSample.chunkCount)
                    put("baselineBelow", belowSample.firstBaseline)
                    put("baselineCross", crossSample.firstBaseline)
                    put("baselineLonger", longerSample.firstBaseline)
                    put("prefixChars", prefix.length)
                    put("crossingChars", crossing.length)
                    put("longerChars", longer.length)
                }
                Log.i(TAG, "threshold scale=$scale chunks=${crossSample.chunkCount}/${longerSample.chunkCount} " +
                    "baseline=${belowSample.firstBaseline}->${crossSample.firstBaseline}->${longerSample.firstBaseline}")
            }

            // Long unbroken text: deterministic single-Text fallback at the
            // narrowest width and largest font scale.
            layouts.clear(); tops.clear()
            fontScale.value = 1.3f
            val unbroken = "字".repeat(5_000)
            state.value = unbroken
            SystemClock.sleep(400)
            val unbrokenSample = awaitSample(1, "unbroken")
            assertEquals("Long unbroken text must stay one Text", 1, unbrokenSample.chunkCount)
            assertTrue(unbrokenSample.firstLineCount > 0)
            records += JSONObject().apply {
                put("fontScale", 1.3)
                put("unbrokenChars", unbroken.length)
                put("unbrokenChunks", unbrokenSample.chunkCount)
                put("unbrokenLines", unbrokenSample.firstLineCount)
                put("unbrokenText", unbrokenSample.firstText.length)
            }
            Log.i(TAG, "unbroken chunks=${unbrokenSample.chunkCount} lines=${unbrokenSample.firstLineCount}")
            runCatching {
                val dir = File(context.getExternalFilesDir(null), "chunk-ui").apply { mkdirs() }
                File(dir, "threshold.json").writeText(org.json.JSONArray(records).toString(2))
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(dir, "threshold.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                Log.i(TAG, "evidence=${dir.absolutePath}/threshold.json")
            }
        }
    }

    private fun launchHost(): ActivityScenario<ComponentActivity> {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/androidx.activity.ComponentActivity",
        )).bufferedReader().use { it.readText() }
        return ActivityScenario.launch(ComponentActivity::class.java)
    }

    companion object { private const val TAG = "AmberChunkThreshold" }
}
