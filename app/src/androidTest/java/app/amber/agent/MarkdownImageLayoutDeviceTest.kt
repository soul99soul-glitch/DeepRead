package app.amber.agent

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.LocalMarkdownImageDecodeProbe
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.richtext.ZoomableAsyncImage
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.Navigator
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 4c item 1: Markdown image layout stays inside 120–420dp at the call site,
 * large photos are not decoded at Size.ORIGINAL, and tool thumbnails keep
 * the main 64.dp height (no shared defaultMinSize / aspectRatio).
 */
class MarkdownImageLayoutDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun launchHost(): ActivityScenario<ComponentActivity> {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/androidx.activity.ComponentActivity",
            ),
        ).bufferedReader().use { it.readText() }
        return ActivityScenario.launch(ComponentActivity::class.java)
    }

    private fun pumpUntil(ms: Long, pred: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(16)
            SystemClock.sleep(16)
            if (pred()) return true
        }
        return pred()
    }

    private fun imageBounds() = ChatDeviceImageFixtures.bounds(compose)

    @Test(timeout = 60_000)
    fun portraitOneToThreeMarkdownImageHeightAtMost420dp() {
        compose.mainClock.autoAdvance = false
        val uri = ChatDeviceImageFixtures.writePng(context, width = 240, height = 720, name = "amber-portrait-1x3.png")
        val md = "PORTRAIT_BEFORE\n\n![${ChatDeviceImageFixtures.ALT}]($uri)\n\nPORTRAIT_AFTER\n"
        val density = AtomicReference(1f)
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                val highlighter = Highlighter(activity)
                activity.setContent {
                    density.set(LocalDensity.current.density)
                    CompositionLocalProvider(
                        LocalSettings provides Settings(),
                        LocalHighlighter provides highlighter,
                        LocalNavController provides Navigator(mutableListOf()),
                    ) {
                        MaterialTheme {
                            MarkdownBlock(content = md, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            val loaded = pumpUntil(12_000) {
                val b = imageBounds()
                b != null && b.height > 8f && b.width > 8f
            }
            val bounds = imageBounds()
            val maxPx = 420f * density.get()
            val json = JSONObject()
                .put("loaded", loaded)
                .put("width", bounds?.width)
                .put("height", bounds?.height)
                .put("maxPx", maxPx.toDouble())
            Log.i("AmberImageLayout", "portrait $json")
            assertTrue("portrait 1:3 image did not load: $json", loaded && bounds != null)
            assertTrue(
                "portrait 1:3 height ${bounds!!.height} exceeds 420.dp ($maxPx px): $json",
                bounds.height <= maxPx + 2f,
            )
        }
    }

    @Test(timeout = 90_000)
    fun largePhotoDecodeDoesNotExceedLayoutTimesDensity() {
        compose.mainClock.autoAdvance = false
        val uri = ChatDeviceImageFixtures.writePng(context, width = 4000, height = 3000, name = "amber-large-4000x3000.png")
        val md = "LARGE_BEFORE\n\n![${ChatDeviceImageFixtures.ALT}]($uri)\n\nLARGE_AFTER\n"
        val decodedW = AtomicInteger(0)
        val decodedH = AtomicInteger(0)
        val density = AtomicReference(1f)
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                val highlighter = Highlighter(activity)
                activity.setContent {
                    density.set(LocalDensity.current.density)
                    CompositionLocalProvider(
                        LocalSettings provides Settings(),
                        LocalHighlighter provides highlighter,
                        LocalNavController provides Navigator(mutableListOf()),
                        LocalMarkdownImageDecodeProbe provides { w, h ->
                            decodedW.set(w)
                            decodedH.set(h)
                        },
                    ) {
                        MaterialTheme {
                            MarkdownBlock(content = md, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            val loaded = pumpUntil(20_000) {
                imageBounds()?.let { it.height > 8f && it.width > 8f } == true && decodedW.get() > 0
            }
            val bounds = imageBounds()
            val layoutW = bounds?.width ?: 0f
            val layoutH = bounds?.height ?: 0f
            val capW = layoutW * density.get() + 2f
            val capH = layoutH * density.get() + 2f
            val json = JSONObject()
                .put("loaded", loaded)
                .put("layoutW", layoutW.toDouble())
                .put("layoutH", layoutH.toDouble())
                .put("decodedW", decodedW.get())
                .put("decodedH", decodedH.get())
                .put("capW", capW.toDouble())
                .put("capH", capH.toDouble())
            Log.i("AmberImageLayout", "large $json")
            assertTrue("4000x3000 markdown image did not load: $json", loaded && bounds != null)
            assertTrue(
                "decoded ${decodedW.get()}x${decodedH.get()} exceeds layout*density $capW x $capH: $json",
                decodedW.get() <= capW + 1f && decodedH.get() <= capH + 1f,
            )
        }
    }

    @Test(timeout = 60_000)
    fun toolThumbnailHeightMatchesMain64dp() {
        compose.mainClock.autoAdvance = false
        val uri = ChatDeviceImageFixtures.writePng(context, width = 480, height = 320, name = "amber-thumb-3x2.png")
        val density = AtomicReference(1f)
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    density.set(LocalDensity.current.density)
                    MaterialTheme {
                        ZoomableAsyncImage(
                            model = uri,
                            contentDescription = ChatDeviceImageFixtures.ALT,
                            modifier = Modifier.height(64.dp).wrapContentWidth(),
                        )
                    }
                }
            }
            val loaded = pumpUntil(12_000) {
                imageBounds()?.let { it.height > 8f } == true
            }
            val bounds = imageBounds()
            val expectedH = 64f * density.get()
            val min120 = 120f * density.get()
            val json = JSONObject()
                .put("loaded", loaded)
                .put("width", bounds?.width)
                .put("height", bounds?.height)
                .put("expectedH", expectedH.toDouble())
                .put("min120", min120.toDouble())
            Log.i("AmberImageLayout", "thumb $json")
            assertTrue("tool thumbnail did not load: $json", loaded && bounds != null)
            assertTrue(
                "tool thumbnail height ${bounds!!.height} != 64.dp ($expectedH): $json",
                kotlin.math.abs(bounds.height - expectedH) <= 2f,
            )
            assertTrue(
                "tool thumbnail was stretched to the shared 120.dp min ($min120): $json",
                bounds.height < min120 - 2f,
            )
        }
    }
}
