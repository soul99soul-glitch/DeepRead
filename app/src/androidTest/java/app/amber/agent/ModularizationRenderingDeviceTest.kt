package app.amber.agent

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.agent.ui.components.richtext.nativebridge.MarkdownParserNative
import app.amber.feature.ui.components.message.AgentToolCallCapsule
import app.amber.feature.ui.components.message.AgentToolKind
import app.amber.feature.ui.components.message.AgentToolStatus
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.richtext.MarkdownNew
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownPreprocessNative
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.pages.chat.toChatTheme
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.dokar.sonner.rememberToasterState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** Production renderer components in a measured local viewport, without a chat or provider. */
@RunWith(AndroidJUnit4::class)
class ModularizationRenderingDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun markdownComponentsAt411dpKeepStaticAndStreamingBlocks() = exerciseRenderers(411, 1f)

    @Test
    fun markdownComponentsAt320dpLargeFontKeepStaticAndStreamingBlocks() = exerciseRenderers(320, 1.5f)

    private fun exerciseRenderers(widthDp: Int, fontScale: Float) {
        check(context.applicationContext is AmberAgentApp) { "Use the real UI smoke runner" }
        val settingsStore: SettingsAggregator = getKoin().get()
        val settings = runBlocking { withTimeout(10_000) { settingsStore.settingsFlow.first { !it.init } } }
        val highlighter: Highlighter = getKoin().get()
        check(MarkdownNativeSwitch.isHtmlEnabled() && MarkdownParserNative.available) {
            "Native HTML must be enabled and available in this actual app; the fixture does not change the switch"
        }
        var content by mutableStateOf(STATIC_MARKDOWN)
        var streaming by mutableStateOf(false)
        var htmlRenderer by mutableStateOf(false)
        val citations = mutableListOf<String>()
        val capsuleClicks = AtomicInteger()
        val visibleFrames = AtomicInteger()
        val visualActive = AtomicBoolean()
        lateinit var scrollState: ScrollState
        lateinit var scrollScope: CoroutineScope
        val originalClock = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = true
        try {
            compose.setContent {
                RendererTheme(widthDp, fontScale) {
                    val navigator = remember { Navigator(mutableListOf()) }
                    val toaster = rememberToasterState()
                    scrollState = rememberScrollState()
                    scrollScope = rememberCoroutineScope()
                    CompositionLocalProvider(
                        LocalSettings provides settings,
                        LocalNavController provides navigator,
                        LocalHighlighter provides highlighter,
                        LocalToaster provides toaster,
                    ) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AgentToolCallCapsule(
                                title = TOOL_TITLE, toolName = "file_read", icon = Lucide.FileText,
                                kind = AgentToolKind.FILE, status = AgentToolStatus.SUCCEEDED,
                                loading = false, onClick = { capsuleClicks.incrementAndGet() }, approvalActions = null,
                            )
                            if (htmlRenderer) {
                                MarkdownNew(content, Modifier.fillMaxWidth(), onClickCitation = citations::add)
                            } else {
                                MarkdownBlock(
                                    content, Modifier.fillMaxWidth(), streaming = streaming,
                                    onClickCitation = citations::add,
                                    onStreamingVisibleFrame = { visibleFrames.incrementAndGet() },
                                    onStreamingVisualActiveChange = visualActive::set,
                                )
                            }
                        }
                    }
                }
            }
            awaitText("STATIC_END")
            verifyMarkers(STATIC_MARKERS)
            reveal("TABLE_READY")
            assertTextFits("TABLE_READY")
            capture("01-markdown-table-list", widthDp, fontScale, "MarkdownBlock", streaming, content, visibleFrames.get(), visualActive.get())
            reveal("<b>CODE_GUARD</b>")
            capture("02-markdown-code-protection", widthDp, fontScale, "MarkdownBlock", streaming, content, visibleFrames.get(), visualActive.get())
            assertTildeCodeLiteral()
            reveal("HTML_BOLD")
            assertAstInlineHtmlLiteral()
            capture("03-markdown-inline-html-literal-citation", widthDp, fontScale, "MarkdownBlock-AST", streaming, content, visibleFrames.get(), visualActive.get())
            reveal(CITATION_LABEL).performClick()
            assertEquals(listOf("123456"), citations)
            reveal(TOOL_TITLE).performClick()
            assertEquals("The actual capsule must return its own callback only", 1, capsuleClicks.get())
            assertTextFits(TOOL_TITLE)

            val activeViewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode()
            val viewportSizeBeforeStream = activeViewport.size.toString()
            val viewportBoundsBeforeStream = activeViewport.boundsInWindow.toString()
            compose.mainClock.autoAdvance = false
            val framesBeforeStream = visibleFrames.get()
            compose.runOnUiThread {
                streaming = true
                content = STATIC_MARKDOWN + "\n\nSTREAM_PHASE_A"
            }
            // Active rendering intentionally has frame work pending. Semantic queries and
            // runOnIdle enter Espresso's Choreographer fence even with autoAdvance=false.
            awaitCondition("First appended batch must produce real streaming visual frames") {
                visualActive.get() && visibleFrames.get() > framesBeforeStream
            }
            val scrollAction = compose.runOnUiThread {
                scrollScope.async(start = CoroutineStart.UNDISPATCHED) {
                    val target = scrollState.maxValue
                    scrollState.scrollTo(target)
                    val snapshot = Triple(target, scrollState.value, scrollState.maxValue)
                    assertEquals("Actual scroll must reach this rendered frame's target", snapshot.first, snapshot.second)
                    assertEquals("Actual scroll must reach this rendered frame's tail", snapshot.third, snapshot.second)
                    snapshot
                }
            }
            // Wait for this non-animated action, without advancing the growing stream's frame.
            val scrollSnapshot = runBlocking { withTimeout(5_000) { scrollAction.await() } }
            captureActiveStream(widthDp, fontScale, content, framesBeforeStream, visibleFrames.get(),
                visualActive.get(), scrollSnapshot, viewportSizeBeforeStream, viewportBoundsBeforeStream)
            val framesBeforeSecondBatch = visibleFrames.get()
            compose.runOnUiThread {
                content += "\n\n- STREAM_NEW_LIST\n\n| Later | Value |\n| --- | --- |\n| STREAM_TABLE | retained |\n\nSTREAM_TAIL_COMPLETE"
            }
            awaitCondition("Second appended batch must produce additional real visual frames") {
                visibleFrames.get() > framesBeforeSecondBatch
            }
            compose.runOnUiThread { streaming = false }
            awaitCondition("Streaming visual owner must settle after final content") { !visualActive.get() }
            compose.mainClock.autoAdvance = true
            verifyMarkers(STATIC_MARKERS + listOf("STREAM_PHASE_A", "STREAM_NEW_LIST", "STREAM_TABLE", "STREAM_TAIL_COMPLETE"))
            reveal("STREAM_TABLE")
            assertTextFits("STREAM_TABLE")
            capture("05-stream-final-table-tail", widthDp, fontScale, "MarkdownBlock", streaming, content, visibleFrames.get(), visualActive.get())
            reveal("LIST_ALPHA")
            capture("06-stream-final-original-blocks", widthDp, fontScale, "MarkdownBlock", streaming, content, visibleFrames.get(), visualActive.get())

            compose.runOnIdle { content = HTML_BLOCK_MARKDOWN }
            awaitText("BLOCK_HTML_PARSED")
            verifyMarkers(listOf("BLOCK_HTML_PARSED", "HTML_BLOCK_AFTER"))
            assertTrue("A real HTML block must dispatch to the existing HTML renderer",
                compose.onAllNodesWithText("<strong>BLOCK_HTML_PARSED</strong>", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty())
            reveal("BLOCK_HTML_PARSED")
            assertTextFits("BLOCK_HTML_PARSED")
            capture("06a-markdown-block-html-dispatch", widthDp, fontScale, "MarkdownBlock-HTML-block", false, content, visibleFrames.get(), visualActive.get())

            compose.runOnIdle {
                htmlRenderer = true
                content = STATIC_MARKDOWN
                citations.clear()
            }
            awaitText("STATIC_END")
            verifyMarkers(STATIC_MARKERS)
            reveal("TABLE_READY")
            assertTextFits("TABLE_READY")
            capture("07-markdown-new-table-list", widthDp, fontScale, "MarkdownNew", false, content, visibleFrames.get(), visualActive.get())
            reveal("<b>CODE_GUARD</b>")
            capture("08-markdown-new-code-protection", widthDp, fontScale, "MarkdownNew", false, content, visibleFrames.get(), visualActive.get())
            assertTildeCodeLiteral()
            reveal("HTML_BOLD")
            assertNoRawHtmlTag()
            capture("09-markdown-new-html-citation", widthDp, fontScale, "MarkdownNew", false, content, visibleFrames.get(), visualActive.get())
            reveal(CITATION_LABEL).performClick()
            assertEquals("Real MarkdownNew citation callback must preserve the id", listOf("123456"), citations)
            write("result-${widthDp}dp-font${(fontScale * 100).toInt()}.json", JSONObject().apply {
                put("componentFixture", true)
                put("wholeChatVerified", false)
                put("providerVerified", false)
                put("citationCallback", citations.single())
                put("capsuleCallbackCount", capsuleClicks.get())
                put("streamVisibleFrameCallbacks", visibleFrames.get())
                put("allExpectedFinalMarkersPresent", true)
            }.toString(2))
        } catch (failure: Throwable) {
            runCatching {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                try { File(directory(), "failure-${widthDp}dp-font${(fontScale * 100).toInt()}.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { bitmap.recycle() }
            }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            compose.mainClock.autoAdvance = originalClock
        }
    }

    @Composable
    private fun RendererTheme(widthDp: Int, fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val tokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalAmberTokens provides tokens,
            LocalChatTheme provides tokens.toChatTheme(),
        ) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(tokens.bg), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(widthDp.dp).fillMaxHeight().testTag(VIEWPORT)) { content() }
                }
            }
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000) {
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
                condition()
            }
        } catch (failure: Throwable) { throw AssertionError(message, failure) }
    }

    private fun awaitText(marker: String) = awaitCondition("Actual renderer must retain $marker") {
        compose.onAllNodesWithText(marker, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun verifyMarkers(markers: List<String>) = markers.forEach { marker ->
        awaitText(marker)
        assertEquals("Renderer must preserve exactly one occurrence of $marker", 1,
            compose.onAllNodesWithText(marker, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun reveal(marker: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val node = compose.onAllNodesWithText(marker, substring = true, useUnmergedTree = true).onFirst()
        val nearestScroll = generateSequence(node.fetchSemanticsNode().parent) { it.parent }
            .firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
        if (nearestScroll != null && nearestScroll.config.contains(SemanticsProperties.HorizontalScrollAxisRange) &&
            !nearestScroll.config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
            // performScrollTo chooses only the closest scroll ancestor. Bring the actual
            // table/code horizontal container into the outer vertical viewport first.
            compose.onNode(SemanticsMatcher("Actual horizontal renderer container ${nearestScroll.id}") {
                it.id == nearestScroll.id
            }, useUnmergedTree = true).performScrollTo()
        }
        node.performScrollTo()
        return node.assertIsDisplayed()
    }

    private fun assertNoRawHtmlTag() {
        assertTrue("HTML strong tag must be interpreted by the actual renderer",
            compose.onAllNodesWithText("<strong>HTML_BOLD</strong>", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    private fun assertAstInlineHtmlLiteral() {
        // HEAD corpus 21 pins inline HTML as source text on the AST path; only HtmlBlock dispatches.
        val literal = "<strong>HTML_BOLD</strong> and <em>HTML_EMPHASIS</em>."
        assertEquals("AST inline HTML must preserve the complete literal exactly once", 1,
            compose.onAllNodesWithText(literal, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun assertTildeCodeLiteral() {
        val literal = "www.example.com ${'$'}x${'$'} TILDE_CODE_GUARD"
        assertEquals("Native preprocessing must not linkify URLs or rewrite math inside a tilde fence", 1,
            compose.onAllNodesWithText(literal, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun assertTextFits(marker: String) {
        val node = compose.onAllNodesWithText(marker, substring = true, useUnmergedTree = true).onFirst().assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        instrumentation.runOnMainSync { check(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true) }
        val layout = layouts.single()
        val details = buildString {
            appendLine("marker=$marker size=${node.size} lines=${layout.lineCount}")
            repeat(layout.lineCount) { appendLine("line=$it left=${layout.getLineLeft(it)} right=${layout.getLineRight(it)} bottom=${layout.getLineBottom(it)}") }
        }
        write("layout-${marker.hashCode()}-${layout.layoutInput.density.fontScale}.txt", details)
        assertFalse(details, layout.multiParagraph.didExceedMaxLines)
        repeat(layout.lineCount) { line ->
            assertFalse(details, layout.isLineEllipsized(line))
            assertTrue(details, layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= node.size.width + 1f &&
                layout.getLineBottom(line) <= node.size.height + 1f)
        }
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInWindow
        val box = node.boundsInWindow
        assertTrue("Visible content must stay inside the component viewport: $marker $box / $viewport",
            box.left >= viewport.left - 1 && box.top >= viewport.top - 1 && box.right <= viewport.right + 1 && box.bottom <= viewport.bottom + 1)
    }

    private fun capture(name: String, widthDp: Int, fontScale: Float, renderer: String, streaming: Boolean,
        source: String, visibleFrames: Int, visualActive: Boolean) {
        compose.waitForIdle()
        val suffix = "${widthDp}dp-font${(fontScale * 100).toInt()}"
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory(), "$name-$suffix.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        write("$name-$suffix.semantics.txt", roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode()
        write("$name-$suffix.layout.json", JSONObject().apply {
            put("renderer", renderer)
            put("localComponentOnly", true)
            put("requestedWidthDp", widthDp)
            put("requestedFontScale", fontScale)
            put("viewportSizePx", viewport.size.toString())
            put("viewportBoundsInWindow", viewport.boundsInWindow.toString())
            put("deviceDensityDpi", context.resources.displayMetrics.densityDpi)
            put("nativeHtmlEnabled", MarkdownNativeSwitch.isHtmlEnabled())
            put("nativeAstEnabled", MarkdownNativeSwitch.isAstEnabled())
            put("nativeParserAvailable", MarkdownParserNative.available)
            put("nativePreprocessAvailable", MarkdownPreprocessNative.available)
            put("streamingInput", streaming)
            put("sourceLength", source.length)
            put("visibleFrameCallbacks", visibleFrames)
            put("streamVisualActive", visualActive)
        }.toString(2))
    }

    private fun captureActiveStream(widthDp: Int, fontScale: Float, source: String, framesBefore: Int,
        framesAfter: Int, visualActive: Boolean, scrollSnapshot: Triple<Int, Int, Int>, viewportSize: String, viewportBounds: String) {
        val suffix = "${widthDp}dp-font${(fontScale * 100).toInt()}"
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory(), "04-active-stream-tail-$suffix.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
        write("04-active-stream-tail-$suffix.layout.json", JSONObject().apply {
            put("renderer", "MarkdownBlock")
            put("localComponentOnly", true)
            put("streamingInput", true)
            put("streamVisualActive", visualActive)
            put("visibleFramesBeforeAppend", framesBefore)
            put("visibleFramesAfterAppend", framesAfter)
            put("scrollTargetAtRenderedFrame", scrollSnapshot.first)
            put("actualScrollValue", scrollSnapshot.second)
            put("actualScrollMax", scrollSnapshot.third)
            put("viewportSizePxBeforeStream", viewportSize)
            put("viewportBoundsInWindowBeforeStream", viewportBounds)
            put("source", source)
            put("semanticsCollectedDuringActive", false)
        }.toString(2))
    }

    private fun directory() = checkNotNull(context.getExternalFilesDir("modularization-phase-3-rendering")).also { check(it.exists() || it.mkdirs()) }
    private fun write(name: String, value: String) = File(directory(), name).writeText(value)

    companion object {
        private const val VIEWPORT = "p3-rendering-viewport"
        private const val TOOL_TITLE = "Read local file"
        private const val CITATION_LABEL = "LOCAL_CITATION"
        private val HTML_BLOCK_MARKDOWN = """
            <div>
            <strong>BLOCK_HTML_PARSED</strong>
            </div>

            HTML_BLOCK_AFTER
        """.trimIndent()
        private val STATIC_MARKERS = listOf("LIST_ALPHA", "LIST_BETA", "TABLE_READY", "TABLE_DONE", "<b>CODE_GUARD</b>", "NOT_BOLD", "TILDE_CODE_GUARD", "HTML_BOLD", "HTML_EMPHASIS", "STATIC_END")
        private val STATIC_MARKDOWN = """
            ## Renderer contract

            - LIST_ALPHA stays in the list.
            - LIST_BETA follows the first item.

            | State | Value |
            | --- | --- |
            | TABLE_READY | one |
            | TABLE_DONE | two |

            ```kotlin
            val tag = "<b>CODE_GUARD</b>"
            val literal = "**NOT_BOLD**"
            ```

            ~~~text
            www.example.com ${'$'}x${'$'} TILDE_CODE_GUARD
            ~~~

            <strong>HTML_BOLD</strong> and <em>HTML_EMPHASIS</em>.

            [citation,LOCAL_CITATION](123456)

            STATIC_END
        """.trimIndent()
    }
}
