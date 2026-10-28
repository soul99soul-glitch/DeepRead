package app.amber.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.richtext.StreamingSingleTextMarkdown
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.components.richtext.LocalStreamingPresentationHandover
import app.amber.feature.ui.components.richtext.MarkPresented
import app.amber.feature.ui.components.richtext.rememberStreamingPresentationLifetime
import app.amber.feature.ui.pages.chat.TimelineStreamingHandover
import app.amber.feature.ui.pages.chat.holdReadingOnTailGrowth
import app.amber.feature.ui.pages.chat.rememberHandoverReadingHold
import app.amber.agent.chatfixture.streamingTailLayout
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device UI evidence for the streaming chunk renderer:
 *  - native drag selection across a Text chunk seam and the real copy path,
 *  - reading-anchor stability across the 3072 threshold, drain and the
 *    streaming -> settled renderer switch.
 *
 * In-memory only; no repository, settings or provider is opened.
 */
class ChatStreamingChunkUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun log(message: String) { Log.i(TAG, message) }

    private fun launchHost(): ActivityScenario<ComponentActivity> {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/androidx.activity.ComponentActivity",
        )).bufferedReader().use { it.readText() }
        return ActivityScenario.launch(ComponentActivity::class.java)
    }

    private data class ChunkNode(
        val text: String,
        val topLeft: Offset,
        val layout: TextLayoutResult,
    )

    private fun chunkNodes(): List<ChunkNode> {
        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val result = ArrayList<ChunkNode>(nodes.size)
        for (node in nodes) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                ?: continue
            if (text.isEmpty()) continue
            val layouts = ArrayList<TextLayoutResult>(1)
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            runCatching { action.action?.invoke(layouts) }
            val layout = layouts.firstOrNull() ?: continue
            result += ChunkNode(text, node.positionInRoot, layout)
        }
        return result.sortedBy { it.topLeft.y }
    }

    /** Maps per-Text local selection ranges to one offset range in the joined text. */
    private fun globalSelection(chunks: List<ChunkNode>): Pair<Int, Int>? {
        val selectionNodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.TextSelectionRange),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val byText = HashMap<String, androidx.compose.ui.text.TextRange>()
        for (node in selectionNodes) {
            val range = node.config.getOrNull(SemanticsProperties.TextSelectionRange) ?: continue
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            byText[text] = range
            Log.i(TAG, "selection node text=${text.take(20)} range=$range")
        }
        var base = 0
        var start = Int.MAX_VALUE
        var end = -1
        chunks.forEachIndexed { index, chunk ->
            val range = byText[chunk.text]
            if (range != null && range.start != range.end) {
                if (base + range.start < start) start = base + range.start
                if (base + range.end > end) end = base + range.end
            }
            base += chunk.text.length
            // Each seam is exactly one newline, rendered as the boundary.
            if (index < chunks.lastIndex) base += 1
        }
        return if (start <= end && start != Int.MAX_VALUE) start to end else null
    }

    /**
     * Clicks the platform/MIUI selection toolbar's Copy item. The toolbar is
     * an overlay window that is not part of Compose semantics; uiautomator
     * dump sees the full screen, including that window.
     */
    private fun copyViaSystemSelectionToolbar() {
        val label = context.getString(android.R.string.copy)
        val dump = "uiautomator dump /sdcard/amber-selection-toolbar.xml"
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(dump),
            ).bufferedReader().use { it.readText() }
        }
        val xml = runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cat /sdcard/amber-selection-toolbar.xml"),
            ).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        val node = Regex("<node[^>]*text=\"${Regex.escape(label)}\"[^>]*>")
            .findAll(xml).firstOrNull()?.value ?: error("Selection toolbar copy item not found in uiautomator dump")
        val bounds = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")
            .find(node)?.groupValues ?: error("Selection toolbar copy item has no bounds")
        val x = (bounds[1].toFloat() + bounds[3].toFloat()) / 2f
        val y = (bounds[2].toFloat() + bounds[4].toFloat()) / 2f
        Log.i(TAG, "selection-toolbar tap copy at $x,$y")
        val down = SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        SystemClock.sleep(400)
    }

    private fun capture(name: String, json: JSONObject) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "chunk-ui")
            dir.mkdirs()
            File(dir, "$name.json").writeText(json.toString(2))
            compose.onAllNodes(isRoot(), useUnmergedTree = true)
                .fetchSemanticsNodes().indices.joinToString("\n\n") {
                    compose.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString()
                }.let { File(dir, "$name.semantics.txt").writeText(it) }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            try {
                File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            log("evidence=$dir/$name")
        }.onFailure { log("evidence-failed ${it.message}") }
    }

    @Test(timeout = 120_000)
    fun nativeDragSelectionAcrossChunkSeamCopiesExactUnicode() {
        val filler = (0 until 60).joinToString("\n") { "填充行 $it 中文😀é " + "字".repeat(24) }
        val source = buildString {
            append("HEAD_标题\n\n")
            append("SEL_START_中文\n\n")
            append("x".repeat(820))
            append("\n\n")
            append("SEL_END_👩‍💻é **粗体** [链接](https://example.com)\n\n")
            append(filler)
        }
        var failure: Throwable? = null
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(
                        LocalSettings provides Settings(),
                        LocalHighlighter provides Highlighter(activity),
                    ) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().padding(16.dp)) {
                                StreamingSingleTextMarkdown(source, emptyList(), streaming = false)
                            }
                        }
                    }
                }
            }
            try {
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithText("SEL_START_中文", substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                val chunks = chunkNodes()
                check(chunks.size >= 2) { "Expected a chunk seam, saw ${chunks.size} text nodes" }
                log("chunks=${chunks.size} starts=${chunks.map { it.text.length }}")
                chunks.forEachIndexed { index, node ->
                    Log.i(TAG, "chunk[$index] len=${node.text.length} top=${node.topLeft.y} first=${node.text.take(24)}")
                }
                val rendered = chunks.joinToString("\n") { it.text }
                val startToken = "SEL_START_中文"
                val endToken = "SEL_END_👩‍💻é"
                val globalStart = rendered.indexOf(startToken)
                val globalEnd = rendered.indexOf("链接") + "链接".length
                check(globalStart >= 0 && globalEnd > globalStart && globalEnd <= rendered.length) {
                    "Selection tokens missing from rendered text"
                }
                // Locate the chunk and local offset for both endpoints.
                fun locate(global: Int): Pair<ChunkNode, Int> {
                    var base = 0
                    for ((index, node) in chunks.withIndex()) {
                        if (global < base + node.text.length) return node to (global - base)
                        base += node.text.length + if (index < chunks.lastIndex) 1 else 0
                    }
                    error("Offset $global outside rendered chunks")
                }
                val (startNode, startLocal) = locate(globalStart)
                val (endNode, endLocal) = locate(globalEnd - 1)
                assertNotEquals("Selection must cross a Text chunk seam", startNode.text, endNode.text)
                fun point(node: ChunkNode, local: Int): Offset {
                    val box = node.layout.getBoundingBox(local)
                    return Offset(
                        node.topLeft.x + box.center.x,
                        node.topLeft.y + box.center.y,
                    )
                }
                val startPoint = point(startNode, startLocal)
                val endPoint = point(endNode, endLocal)
                Log.i(TAG, "gesture start=$startPoint end=$endPoint seamGlobal=$globalStart..$globalEnd")
                compose.onRoot().performTouchInput {
                    down(startPoint)
                    advanceEventTime(800)
                    val steps = 12
                    repeat(steps) { step ->
                        val t = (step + 1f) / steps
                        moveTo(Offset(
                            startPoint.x + (endPoint.x - startPoint.x) * t,
                            startPoint.y + (endPoint.y - startPoint.y) * t,
                        ), delayMillis = 24)
                    }
                    up()
                }
                compose.waitForIdle()
                SystemClock.sleep(400)
                val range = globalSelection(chunks)
                capture("selection", JSONObject().apply {
                    put("chunks", chunks.size)
                    put("renderedLength", rendered.length)
                    put("globalRange", range?.let { listOf(it.first, it.second) })
                })
                // Compose does not publish TextSelectionRange/SelectionHandleInfo
                // for a plain SelectionContainer through SemanticsProperties;
                // the saved screenshot shows both native handles, and the
                // copied offsets below prove the cross-Text selection.
                val expected = rendered.substring(globalStart, globalEnd)
                check(expected.contains('\n')) { "Selection lost the chunk-seam newline" }
                check(expected.contains("中文")) { "Selection lost Chinese text" }
                check(expected.contains("👩‍💻")) { "Selection lost ZWJ emoji" }
                check(expected.contains("e\u0301")) { "Selection lost combining accent" }
                val seamGlobal = rendered.indexOf('\n', globalStart).let { if (it < 0) globalStart else it }
                assertTrue("Selection must span the chunk seam", globalStart <= seamGlobal && globalEnd > seamGlobal)

                val clipboard = context.getSystemService(ClipboardManager::class.java)
                var priorClip: ClipData? = null
                instrumentation.runOnMainSync { priorClip = clipboard.primaryClip }
                try {
                instrumentation.runOnMainSync { clipboard.clearPrimaryClip() }
                var copied = ""
                fun readClipboard() {
                    instrumentation.runOnMainSync {
                        copied = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context).toString()
                    }
                }
                val copyNodes = compose.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.CopyText),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                log("copyActions=${copyNodes.size}")
                if (copyNodes.isNotEmpty()) {
                    compose.onAllNodes(
                        SemanticsMatcher.keyIsDefined(SemanticsActions.CopyText),
                        useUnmergedTree = true,
                    ).onFirst().performSemanticsAction(SemanticsActions.CopyText)
                } else {
                    // No Compose copy action: try the copy key, then the MIUI
                    // system selection toolbar (outside the Compose tree).
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_COPY)
                    SystemClock.sleep(300)
                    readClipboard()
                    if (copied.isEmpty()) {
                        copyViaSystemSelectionToolbar()
                    }
                }
                compose.waitUntil(5_000) {
                    readClipboard()
                    copied.isNotEmpty()
                }
                assertEquals("Native selection copy must equal the selected rendered text", expected, copied)
                Log.i(TAG, "selection-passed chars=${copied.length} range=$globalStart..$globalEnd sha256=${sha(copied)}")
                } finally {
                    instrumentation.runOnMainSync {
                        val previous = priorClip
                        if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
                    }
                }
            } catch (error: Throwable) {
                failure = error
                capture("selection-failure", JSONObject().apply { put("error", error.toString()) })
            }
        }
        failure?.let { throw it }
    }

    @Test(timeout = 180_000)
    fun readingAnchorStaysStableAcrossThresholdDrainAndSettledSwitch() = verifyReadingAnchor("idle")

    @Test(timeout = 180_000)
    fun readingAnchorSurvivesGenerationEndDuringDrag() = verifyReadingAnchor("drag")

    @Test(timeout = 180_000)
    fun readingAnchorSurvivesGenerationEndDuringFling() = verifyReadingAnchor("fling")

    @Test(timeout = 180_000)
    fun generationEndKeepsPinnedViewportAndRestoresHistoryRendererOffscreen() = verifyReadingAnchor("pinned")

    private fun verifyReadingAnchor(endMode: String) {
        // Match the renderer's real uptime-based pacer with explicit real-time
        // frames. Auto-advancing a virtual clock cannot drain an uptime loop.
        compose.mainClock.autoAdvance = false
        fun pump(milliseconds: Long) {
            val deadline = SystemClock.uptimeMillis() + milliseconds
            while (SystemClock.uptimeMillis() < deadline) {
                compose.mainClock.advanceTimeBy(16)
                SystemClock.sleep(16)
            }
        }
        fun awaitCondition(timeout: Long, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeout
            while (!condition() && SystemClock.uptimeMillis() < deadline) pump(16)
            check(condition()) { "Timed out waiting for rendered anchor output" }
        }
        // Locate one fixed token in the actual rendered Text on every sample.
        // The source offset is not assumed to equal a mapped Markdown offset.
        val paragraph = "中文阅读锚点段落，保持稳定。Unicode 👩‍💻 é。\n\n"
        val token = "ANCHOR_POINT 阅读锚点唯一标记。\n\n"
        val baseText = paragraph.repeat(140) + token + paragraph.repeat(4)
        val target = paragraph.repeat(140) + token + paragraph.repeat(130) + "END_OF_ANCHOR_OUTPUT"
        val initial = baseText.length
        val tokenStart = target.indexOf("ANCHOR_POINT")
        check(initial > 3_072 && target.length > initial + 2_000) {
            "Anchor fixture must start chunked and still grow: initial=$initial target=${target.length}"
        }
        check(tokenStart in 0 until initial) { "Token must be in the initial text" }
        val suffix = MutableStateFlow("")
        val streaming = MutableStateFlow(true)
        val settledLatch = CountDownLatch(1)
        val listRef = AtomicReference<LazyListState>()
        val activityRef = AtomicReference<ComponentActivity>()
        val messageTop = AtomicReference(0f)
        var failure: Throwable? = null
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                activityRef.set(activity)
                val settings = Settings()
                val highlighter = Highlighter(activity)
                activity.setContent {
                    CompositionLocalProvider(
                        LocalSettings provides settings,
                        LocalHighlighter provides highlighter,
                    ) {
                        MaterialTheme {
                            val tail by suffix.collectAsState()
                            val active by streaming.collectAsState()
                            val list = rememberLazyListState()
                            listRef.set(list)
                            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                                item("tail") { Text("render fixture tail") }
                                item("stream") {
                                    HandoverStreamItem(
                                        list = list,
                                        content = baseText + tail,
                                        active = active,
                                        onDrained = { settledLatch.countDown() },
                                        modifier = Modifier.onGloballyPositioned { messageTop.set(it.positionInRoot().y) },
                                    )
                                }
                                items(30, key = { "older-$it" }) { Text("OLDER $it " + paragraph.repeat(3)) }
                            }
                        }
                    }
                }
            }
            try {
                pump(3_000)
                val activity = activityRef.get()
                val view = activity.window.decorView

                fun chunkAnchorY(): Float? {
                    val node = chunkNodes().firstOrNull { "ANCHOR_POINT" in it.text } ?: return null
                    val local = node.text.indexOf("ANCHOR_POINT")
                    return node.topLeft.y + node.layout.getBoundingBox(local).top
                }
                // Real touch drag away from the pinned bottom (release without a fling).
                val x = view.width * 0.5f
                val y0 = view.height * 0.30f
                val drag = view.height * 0.16f
                val downTime = SystemClock.uptimeMillis()
                var gestureDownTime = downTime
                fun send(action: Int, y: Float, at: Long) {
                    if (action == android.view.MotionEvent.ACTION_DOWN) gestureDownTime = at
                    instrumentation.runOnMainSync {
                        val event = android.view.MotionEvent.obtain(gestureDownTime, at, action, x, y, 0)
                        view.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                if (endMode != "pinned") {
                    send(android.view.MotionEvent.ACTION_DOWN, y0, downTime)
                    repeat(12) { step ->
                        pump(20)
                        send(
                            android.view.MotionEvent.ACTION_MOVE,
                            y0 + drag * (step + 1) / 12f,
                            SystemClock.uptimeMillis(),
                        )
                    }
                    send(android.view.MotionEvent.ACTION_UP, y0 + drag, SystemClock.uptimeMillis())
                }
                val scrollDeadline = SystemClock.uptimeMillis() + 10_000
                while (listRef.get().isScrollInProgress && SystemClock.uptimeMillis() < scrollDeadline) {
                    pump(50)
                }
                pump(800)
                val baseline = requireNotNull(chunkAnchorY()) { "Anchor chunk never laid out" }
                Log.i(TAG, "anchor baseline=$baseline index=${listRef.get().firstVisibleItemIndex} offset=${listRef.get().firstVisibleItemScrollOffset}")
                check(baseline >= 0 && baseline < view.height) { "Anchor is not visible before the feed: $baseline" }

                // Grow through several natural-line chunk boundaries.
                val appended = target.substring(initial)
                val finishingDuringGesture = endMode == "drag" || endMode == "fling"
                val idleEnd = if (finishingDuringGesture) (appended.length * 2 / 3).coerceAtLeast(12) else appended.length
                val growthFrames = ArrayList<Float>()
                val gestureGrowthFrames = ArrayList<Float>()
                suspend fun feed(from: Int, to: Int, into: ArrayList<Float>) {
                    var end = from
                    while (end < to) {
                        end = (end + 12).coerceAtMost(to)
                        suffix.value = appended.substring(0, end)
                        compose.mainClock.advanceTimeBy(33)
                        delay(33)
                        chunkAnchorY()?.let { into += it }
                    }
                }
                runBlocking { feed(0, idleEnd, growthFrames) }
                val growthMaxDeviation = (growthFrames + baseline).maxOf { kotlin.math.abs(it - baseline) }
                val growthOver2 = growthFrames.count { kotlin.math.abs(it - baseline) > 2f }
                if (!finishingDuringGesture) {
                    awaitCondition(15_000) { chunkNodes().any { "END_OF_ANCHOR_OUTPUT" in it.text } }
                }
                pump(1_200)
                val during = requireNotNull(chunkAnchorY()) { "Anchor chunk disappeared while generating" }
                Log.i(TAG, "anchor during=$during mode=$endMode index=${listRef.get().firstVisibleItemIndex} offset=${listRef.get().firstVisibleItemScrollOffset}")
                val relativeBefore = during - messageTop.get()
                val scrollOffsetBeforeEnd = listRef.get().firstVisibleItemScrollOffset
                if (finishingDuringGesture) {
                    val gestureDown = SystemClock.uptimeMillis()
                    send(android.view.MotionEvent.ACTION_DOWN, y0, gestureDown)
                    repeat(4) { step ->
                        pump(24)
                        send(android.view.MotionEvent.ACTION_MOVE, y0 + (step + 1) * 28f, SystemClock.uptimeMillis())
                    }
                    check(listRef.get().isScrollInProgress) { "Generation must finish during an actual drag" }
                    runBlocking { feed(idleEnd, appended.length, gestureGrowthFrames) }
                    awaitCondition(15_000) { chunkNodes().any { "END_OF_ANCHOR_OUTPUT" in it.text } }
                }
                val gestureGrowthMax = if (gestureGrowthFrames.isEmpty()) 0f else
                    (gestureGrowthFrames + during).maxOf { kotlin.math.abs(it - during) }
                val gestureGrowthOver2 = gestureGrowthFrames.count { kotlin.math.abs(it - during) > 2f }
                if (endMode != "fling") streaming.value = false
                if (finishingDuringGesture) {
                    if (endMode == "drag") {
                        repeat(5) { step ->
                            pump(16)
                            send(android.view.MotionEvent.ACTION_MOVE, y0 + 112f + (step + 1) * 28f, SystemClock.uptimeMillis())
                        }
                        pump(200)
                        send(android.view.MotionEvent.ACTION_UP, y0 + 252f, SystemClock.uptimeMillis())
                    } else {
                        // Fling: continue the same pointer (DOWN already sent).
                        // Remaining inertia on this fixture is ~19px (~1 frame).
                        // Do not pump frames between UP and the inertia check —
                        // pump(16)/pump(48) ate that window (4d run 8:
                        // scroll=false offset=19123). Confirm isScrollInProgress
                        // on the main thread immediately after UP, with lift
                        // velocity still above min fling; retry one new flick
                        // only if that first UP already settled.
                        val list = listRef.get()
                        val minFling = android.view.ViewConfiguration.get(context)
                            .scaledMinimumFlingVelocity
                            .toFloat()
                        fun snapshot(): Triple<Boolean, Int, Int> {
                            var inProgress = false
                            var index = 0
                            var offset = 0
                            instrumentation.runOnMainSync {
                                inProgress = list.isScrollInProgress
                                index = list.firstVisibleItemIndex
                                offset = list.firstVisibleItemScrollOffset
                            }
                            return Triple(inProgress, index, offset)
                        }
                        fun continueFlick(startY: Float): Triple<Boolean, Int, Int> {
                            var lastY = startY
                            var lastT = SystemClock.uptimeMillis()
                            var prevY = startY
                            var prevT = lastT
                            repeat(6) { step ->
                                compose.mainClock.advanceTimeBy(8)
                                SystemClock.sleep(8)
                                prevY = lastY
                                prevT = lastT
                                lastY = startY + (step + 1) * 48f
                                lastT = SystemClock.uptimeMillis()
                                send(android.view.MotionEvent.ACTION_MOVE, lastY, lastT)
                            }
                            val dt = (lastT - prevT).coerceAtLeast(1L)
                            val vy = kotlin.math.abs((lastY - prevY) * 1000f / dt)
                            val beforeUp = snapshot()
                            send(
                                android.view.MotionEvent.ACTION_UP,
                                lastY,
                                SystemClock.uptimeMillis(),
                            )
                            val after = snapshot()
                            Log.i(
                                TAG,
                                "after-up scroll=${after.first} index=${after.second} offset=${after.third} " +
                                    "from=${beforeUp.second}/${beforeUp.third} vy=$vy minFling=$minFling " +
                                    "beforeUpScroll=${beforeUp.first}",
                            )
                            return Triple(after.first && vy > minFling, after.second, after.third)
                        }
                        fun newFlick(originY: Float): Triple<Boolean, Int, Int> {
                            send(
                                android.view.MotionEvent.ACTION_DOWN,
                                originY,
                                SystemClock.uptimeMillis(),
                            )
                            return continueFlick(originY)
                        }
                        var inertia = continueFlick(y0 + 112f)
                        if (!inertia.first) inertia = newFlick(y0 + 0.15f * view.height)
                        val confirm = snapshot()
                        check(inertia.first && confirm.first) {
                            "Generation must finish during actual inertia " +
                                "scroll=${confirm.first} index=${confirm.second} " +
                                "offset=${confirm.third}"
                        }
                        Log.i(
                            TAG,
                            "inertia-started index=${confirm.second} offset=${confirm.third}",
                        )
                        streaming.value = false
                    }
                }
                val heartbeat = CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    heartbeat.countDown()
                    Log.i(TAG, "main-heartbeat")
                }
                val responded = heartbeat.await(3, TimeUnit.SECONDS)
                Log.i(TAG, "main-heartbeat-responded=$responded activeNow=${streaming.value}")
                val settleDeadline = SystemClock.uptimeMillis() + 45_000
                while (settledLatch.count > 0L && SystemClock.uptimeMillis() < settleDeadline) {
                    // Drive the test frame clock; a headless instrumented window
                    // does not always get vsync-driven recomposition.
                    compose.mainClock.advanceTimeBy(16)
                    pump(32)
                }
                if (settledLatch.count > 0L) {
                    Log.i(TAG, "main-stack=\n" + android.os.Looper.getMainLooper().thread.stackTrace
                        .joinToString("\n") { it.toString() })
                    error("Streaming never settled")
                }
                compose.waitForIdle()
                val motionDeadline = SystemClock.uptimeMillis() + 10_000
                while (listRef.get().isScrollInProgress && SystemClock.uptimeMillis() < motionDeadline) pump(32)
                fun settledNow() = chunkNodes().any { "ANCHOR_POINT" in it.text && it.text.length < 200 }
                val swapDeadline = SystemClock.uptimeMillis() + 2_000
                while (!settledNow() && SystemClock.uptimeMillis() < swapDeadline) pump(32)

                fun stabilizeAnchor(timeout: Long): Float {
                    var last: Float? = null
                    var hits = 0
                    val deadline = SystemClock.uptimeMillis() + timeout
                    while (SystemClock.uptimeMillis() < deadline) {
                        val y = chunkAnchorY()
                        if (y != null) {
                            if (last != null && kotlin.math.abs(y - last) <= 2f) {
                                hits++
                                if (hits >= 3) return y
                            } else {
                                hits = 0
                            }
                            last = y
                        }
                        pump(32)
                    }
                    return requireNotNull(chunkAnchorY()) { "Anchor disappeared while waiting to settle" }
                }
                val afterSwitch = stabilizeAnchor(8_000)
                val relativeAfter = afterSwitch - messageTop.get()
                val stableAgain = stabilizeAnchor(8_000)
                Log.i(TAG, "anchor during=$during settled=$afterSwitch static=$stableAgain")
                val switchDelta = afterSwitch - baseline
                val settledNow = settledNow()
                val json = JSONObject().apply {
                    put("endMode", endMode)
                    put("firstVisibleIndexAfter", listRef.get().firstVisibleItemIndex)
                    put("scrollOffsetBeforeEnd", scrollOffsetBeforeEnd)
                    put("scrollOffsetAfter", listRef.get().firstVisibleItemScrollOffset)
                    put("relativeBefore", relativeBefore)
                    put("relativeAfter", relativeAfter)
                    put("baseline", baseline)
                    put("during", during)
                    put("afterSwitch", afterSwitch)
                    put("switchDelta", switchDelta)
                    put("stableAgain", stableAgain)
                    put("settledAfterDrain", settledNow)
                    put("growthMaxDeviation", growthMaxDeviation)
                    put("growthOver2pxFrames", growthOver2)
                    put("growthSamples", growthFrames.size)
                    put("gestureGrowthMaxDeviation", gestureGrowthMax)
                    put("gestureGrowthOver2pxFrames", gestureGrowthOver2)
                    put("gestureGrowthSamples", gestureGrowthFrames.size)
                    put("coordinateSpace", "unclipped Text positionInRoot + character bounding box")
                    put("targetLength", target.length)
                    put("initialLength", initial)
                    put("viewHeight", view.height)
                }
                // Integer placeable rounding plus LazyList scroll quantization.
                val tolerance = 2f
                if (endMode != "pinned") {
                    assertTrue("Anchor drifted while generating: ${during - baseline}px",
                        kotlin.math.abs(during - baseline) <= tolerance)
                    Log.i(TAG, "growth-frames n=${growthFrames.size} maxDeviation=$growthMaxDeviation over2=$growthOver2 gestureMax=$gestureGrowthMax gestureOver2=$gestureGrowthOver2")
                }
                val handoverDelta = if (finishingDuringGesture) relativeAfter - relativeBefore else afterSwitch - during
                runCatching { capture("anchor-$endMode", json) }
                if (endMode == "pinned") {
                    assertTrue("Settled renderer did not take over after idle (mode=$endMode)", settledNow)
                    assertEquals(0, listRef.get().firstVisibleItemIndex)
                    assertTrue(listRef.get().firstVisibleItemScrollOffset <= 24)
                } else {
                    assertFalse(
                        "Settled renderer took over while the reading anchor was inside the message (mode=$endMode)",
                        settledNow,
                    )
                    assertTrue("Anchor jumped across generation end: ${handoverDelta}px",
                        kotlin.math.abs(handoverDelta) <= tolerance)
                }
                if (finishingDuringGesture) {
                    assertEquals("Finishing must not force the reader back to the bottom", 1, listRef.get().firstVisibleItemIndex)
                    assertTrue(listRef.get().firstVisibleItemScrollOffset >= scrollOffsetBeforeEnd)
                }
                assertTrue("Anchor kept drifting while static: ${stableAgain - afterSwitch}px",
                    kotlin.math.abs(stableAgain - afterSwitch) <= tolerance)
                Log.i(TAG, "switch-displacement=$switchDelta growthMax=$growthMaxDeviation over2=$growthOver2")
                Log.i(TAG, "anchor-passed mode=$endMode tolerance=$tolerance anchor=$baseline during=$during settled=$afterSwitch static=$stableAgain relative=$relativeBefore->$relativeAfter")
                if (endMode != "pinned") {
                    runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { listRef.get().scrollToItem(0) } }
                    awaitCondition(10_000) {
                        chunkNodes().any { "ANCHOR_POINT" in it.text && it.text.length < 200 }
                    }
                    Log.i(TAG, "settled-renderer-stays-after-return-to-bottom mode=$endMode")
                }
            } catch (error: Throwable) {
                failure = error
                runCatching {
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    val dir = File(context.getExternalFilesDir(null), "chunk-ui").apply { mkdirs() }
                    File(dir, "anchor-failure.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        }
        compose.mainClock.autoAdvance = true
        failure?.let { throw it }
    }

    /**
     * The production handover wiring of ChatTimelineItems/MessagePartsBlock:
     * owner-side gate + reading hold, block-side presentation lifetime.
     */
    @androidx.compose.runtime.Composable
    private fun HandoverStreamItem(
        list: LazyListState,
        content: String,
        active: Boolean,
        onDrained: () -> Unit,
        modifier: Modifier = Modifier,
        followBottomPx: Int = 24,
    ) {
        val handover = remember(list) { TimelineStreamingHandover(list, "stream", followBottomPx) }
        val hold = rememberHandoverReadingHold(active || handover.awaiting)
        Box(
            modifier
                .then(
                    if (active || hold) Modifier.streamingTailLayout(
                        list, 1, followBottomPx, animate = active, itemKey = null,
                    )
                    else Modifier
                )
                .padding(12.dp)
        ) {
            CompositionLocalProvider(LocalStreamingPresentationHandover provides handover) {
                val lifetime = rememberStreamingPresentationLifetime(active, content)
                if (active || lifetime.retained) {
                    lifetime.MarkPresented()
                    StreamingSingleTextMarkdown(
                        text = content,
                        regexes = emptyList(),
                        streaming = active,
                        onSettled = { lifetime.onDrained(); onDrained() },
                    )
                } else {
                    MarkdownBlock(content)
                }
            }
        }
    }

    /**
     * Frame-granular check of the off-bottom handover below an older anchor:
     * the reply finishes while visible under the reader. Same-frame hold must
     * keep every layout pass of the older item within 2px while the settled
     * tree takes over immediately (no infinite deferral).
     */
    @Test(timeout = 120_000)
    fun visibleReplyHandoverUnderOlderAnchorHoldsEveryFrame() {
        compose.mainClock.autoAdvance = false
        fun pump(milliseconds: Long) {
            val deadline = SystemClock.uptimeMillis() + milliseconds
            while (SystemClock.uptimeMillis() < deadline) {
                compose.mainClock.advanceTimeBy(16)
                SystemClock.sleep(16)
            }
        }
        val reply = "RICH_INTRO 回复。\n\n| 项目 | 结果 |\n| --- | --- |\n| CELL_ALPHA | CELL_BETA |\n\n" +
            "```kotlin\nfun richFixture() = 42\n```\n\nRICH_END 完成。"
        val streaming = MutableStateFlow(true)
        val drained = CountDownLatch(1)
        val listRef = AtomicReference<LazyListState>()
        val olderTops = java.util.Collections.synchronizedList(ArrayList<Float>())
        val passLog = java.util.Collections.synchronizedList(ArrayList<String>())
        var failure: Throwable? = null
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                val settings = Settings()
                val highlighter = Highlighter(activity)
                activity.setContent {
                    val navigator = remember { app.amber.feature.ui.context.Navigator(mutableListOf()) }
                    CompositionLocalProvider(
                        LocalSettings provides settings,
                        LocalHighlighter provides highlighter,
                        app.amber.feature.ui.context.LocalNavController provides navigator,
                    ) {
                        MaterialTheme {
                            val active by streaming.collectAsState()
                            val list = rememberLazyListState()
                            listRef.set(list)
                            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                                item("tail") { Text("render fixture tail") }
                                item("stream") {
                                    HandoverStreamItem(list, reply, active, onDrained = { drained.countDown() })
                                }
                                items(30, key = { "older-$it" }) { index ->
                                    Text(
                                        "OLDER $index 旧消息阅读段落，保持稳定。".repeat(6),
                                        Modifier.then(
                                            if (index == 0) Modifier.onGloballyPositioned {
                                                olderTops += it.positionInRoot().y
                                                val l = listRef.get()
                                                passLog += "${SystemClock.uptimeMillis()} top=${it.positionInRoot().y} first=${l.firstVisibleItemIndex}/${l.firstVisibleItemScrollOffset} stream=${l.layoutInfo.visibleItemsInfo.firstOrNull { v -> v.key == "stream" }?.let { v -> "${v.offset}+${v.size}" }}"
                                            }
                                            else Modifier
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            try {
                pump(2_000)
                // Off the bottom (beyond the 24px follow buffer), reply top still on screen.
                runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    listRef.get().scrollToItem(1, 60)
                } }
                pump(800)
                val info = listRef.get().layoutInfo
                val stream = info.visibleItemsInfo.first { it.key == "stream" }
                check(stream.offset + stream.size <= info.viewportEndOffset) { "Reply top must be inside the viewport" }
                check(info.visibleItemsInfo.any { it.key == "older-0" }) { "Older anchor item must be visible" }
                val start = olderTops.size
                val baseline = olderTops.last()
                val heightBefore = stream.size
                streaming.value = false
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < deadline && drained.count > 0) pump(32)
                check(drained.count == 0L) { "Streaming presentation never drained" }
                val swapDeadline = SystemClock.uptimeMillis() + 2_000
                while (SystemClock.uptimeMillis() < swapDeadline && chunkNodes().none { it.text == "CELL_ALPHA" }) pump(32)
                val swappedWhileVisible = chunkNodes().any { it.text == "CELL_ALPHA" }
                val frames = synchronized(olderTops) { olderTops.drop(start) }
                val maxDeviation = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
                val passes = synchronized(passLog) { passLog.takeLast(frames.size + 2) }
                passes.forEach { Log.i(TAG, "older-anchor-pass $it") }
                runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { listRef.get().scrollToItem(0) } }
                pump(500)
                val settledAtBottom = chunkNodes().any { it.text == "CELL_ALPHA" }
                val heightAfter = listRef.get().layoutInfo.visibleItemsInfo.first { it.key == "stream" }.size
                val json = JSONObject().apply {
                    put("baseline", baseline); put("layoutPasses", frames.size)
                    put("olderTops", org.json.JSONArray(frames)); put("maxDeviation", maxDeviation)
                    put("swappedWhileVisible", swappedWhileVisible); put("settledAtBottom", settledAtBottom)
                    put("replyHeightStreaming", heightBefore); put("replyHeightSettled", heightAfter)
                    put("passes", org.json.JSONArray(passes))
                }
                runCatching { capture("older-anchor-frames", json) }
                Log.i(TAG, "older-anchor-frames baseline=$baseline passes=${frames.size} maxDeviation=$maxDeviation swapped=$swappedWhileVisible settledAtBottom=$settledAtBottom height=$heightBefore->$heightAfter tops=$frames")
                assertTrue("Older anchor moved in some frame: ${maxDeviation}px", maxDeviation <= 2f)
                assertTrue("Visible off-bottom reply did not swap to settled under the reader", swappedWhileVisible)
                assertTrue("Settled renderer did not remain at the bottom", settledAtBottom)
                // The settled tree really has different geometry (the case being guarded).
                assertTrue("Settled reply height did not differ", kotlin.math.abs(heightAfter - heightBefore) > 2)
            } catch (error: Throwable) {
                Log.i(TAG, "older-anchor-frames failed: $error")
                failure = error
            }
        }
        compose.mainClock.autoAdvance = true
        failure?.let { throw it }
    }

    /**
     * Streaming image label degrades, then the settled tree loads a real local
     * PNG (no network) and a math block. Every older-item layout pass during
     * the swap and the image-height change stays within 2px; formulas must
     * render as [Latex], not as an extra `$` node or raw delimiter paragraph.
     */
    @Test(timeout = 120_000)
    fun visibleReplyImageAndMathHandoverHoldsEveryFrame() {
        compose.mainClock.autoAdvance = false
        fun pump(milliseconds: Long) {
            val deadline = SystemClock.uptimeMillis() + milliseconds
            while (SystemClock.uptimeMillis() < deadline) {
                compose.mainClock.advanceTimeBy(16)
                SystemClock.sleep(16)
            }
        }
        val imageUri = ChatDeviceImageFixtures.writePng(context)
        val reply = "RICH_INTRO 回复。\n\n行内公式 MATH_INLINE_MARK \$E=mc^2\$ 之后。\n\n" +
            "\$\$\n\\sum_{n=1}^{N} n = \\frac{N(N+1)}{2}\n\$\$\n\nMATH_BLOCK_AFTER 块级公式之后。\n\n" +
            "![FIXTURE_IMAGE_ALT]($imageUri)\n\n" +
            "| 项目 | 结果 |\n| --- | --- |\n| CELL_ALPHA | CELL_BETA |\n\nRICH_END 完成。"
        val streaming = MutableStateFlow(true)
        val drained = CountDownLatch(1)
        val listRef = AtomicReference<LazyListState>()
        val olderTops = java.util.Collections.synchronizedList(ArrayList<Float>())
        var failure: Throwable? = null
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                val settings = Settings()
                val highlighter = Highlighter(activity)
                activity.setContent {
                    val navigator = remember { app.amber.feature.ui.context.Navigator(mutableListOf()) }
                    CompositionLocalProvider(
                        LocalSettings provides settings,
                        LocalHighlighter provides highlighter,
                        app.amber.feature.ui.context.LocalNavController provides navigator,
                    ) {
                        MaterialTheme {
                            val active by streaming.collectAsState()
                            val list = rememberLazyListState()
                            listRef.set(list)
                            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                                item("tail") { Text("render fixture tail") }
                                item("stream") {
                                    HandoverStreamItem(list, reply, active, onDrained = { drained.countDown() })
                                }
                                items(30, key = { "older-$it" }) { index ->
                                    Text(
                                        "OLDER $index 旧消息阅读段落，保持稳定。".repeat(6),
                                        Modifier.then(
                                            if (index == 0) Modifier.onGloballyPositioned {
                                                olderTops += it.positionInRoot().y
                                            } else Modifier
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            try {
                pump(2_000)
                runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    listRef.get().scrollToItem(1, 60)
                } }
                pump(800)
                check(listRef.get().layoutInfo.visibleItemsInfo.any { it.key == "older-0" })
                val start = olderTops.size
                val baseline = olderTops.last()
                val streamingHadImageLabel = chunkNodes().any { "FIXTURE_IMAGE_ALT" in it.text }
                val streamingHadRawMath = chunkNodes().any { "\$E=mc^2\$" in it.text || "MATH_INLINE_MARK" in it.text }
                streaming.value = false
                val drainDeadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < drainDeadline && drained.count > 0) pump(32)
                check(drained.count == 0L) { "Streaming presentation never drained" }
                val swapDeadline = SystemClock.uptimeMillis() + 2_000
                while (SystemClock.uptimeMillis() < swapDeadline && chunkNodes().none { it.text == "CELL_ALPHA" }) pump(32)
                val imageDeadline = SystemClock.uptimeMillis() + 8_000
                var imageHeight = 0f
                var imageWidth = 0f
                fun imageLoaded() = imageHeight > 0f && imageWidth / imageHeight > 1.2f
                while (SystemClock.uptimeMillis() < imageDeadline) {
                    val imageNode = ChatDeviceImageFixtures.bounds(compose)
                    imageHeight = imageNode?.height ?: 0f
                    imageWidth = imageNode?.width ?: 0f
                    if (imageLoaded()) break
                    pump(32)
                }
                val frames = synchronized(olderTops) { olderTops.drop(start) }
                val maxDeviation = (frames + baseline).maxOf { kotlin.math.abs(it - baseline) }
                val texts = chunkNodes().map { it.text }
                val latex = texts.any { "[Latex]" in it }
                val rawDollarParagraph = texts.any { it.trim() == "$" || it.trim() == "$$" }
                val json = JSONObject().apply {
                    put("baseline", baseline); put("layoutPasses", frames.size)
                    put("olderTops", org.json.JSONArray(frames)); put("maxDeviation", maxDeviation)
                    put("streamingHadImageLabel", streamingHadImageLabel)
                    put("streamingHadRawMath", streamingHadRawMath)
                    put("swappedCell", texts.any { it == "CELL_ALPHA" })
                    put("latex", latex); put("rawDollarParagraph", rawDollarParagraph)
                    put("imageHeight", imageHeight); put("imageWidth", imageWidth)
                    put("imageUri", imageUri)
                }
                runCatching { capture("image-math-frames", json) }
                Log.i(TAG, "image-math-frames $json")
                assertTrue("Streaming never showed the degraded image label", streamingHadImageLabel)
                assertTrue("Visible off-bottom reply did not swap to settled", texts.any { it == "CELL_ALPHA" })
                assertTrue("Older anchor moved in some frame: ${maxDeviation}px", maxDeviation <= 2f)
                assertTrue("Settled math did not render as [Latex]", latex)
                assertFalse("Settled tree invented a lone \$ node", rawDollarParagraph)
                assertTrue("Local fixture image did not load (size=${imageWidth}x$imageHeight)", imageLoaded())
            } catch (error: Throwable) {
                Log.i(TAG, "image-math-frames failed: $error")
                failure = error
            }
        }
        compose.mainClock.autoAdvance = true
        failure?.let { throw it }
    }

    /**
     * 4c item 3: 1–2 frames after idle hold fires on an off-bottom growing
     * item, a real drag must move by the injected distance and keep
     * isScrollInProgress. requestScrollToItem would cancel the gesture.
     */
    @Test(timeout = 90_000)
    fun idleHoldThenImmediateDragMovesByInjectedDistance() {
        compose.mainClock.autoAdvance = false
        fun pump(milliseconds: Long) {
            val deadline = SystemClock.uptimeMillis() + milliseconds
            while (SystemClock.uptimeMillis() < deadline) {
                compose.mainClock.advanceTimeBy(16)
                SystemClock.sleep(16)
            }
        }
        val listRef = AtomicReference<LazyListState>()
        val olderY = java.util.Collections.synchronizedList(ArrayList<Float>())
        var streamHeight by mutableIntStateOf(180)
        var failure: Throwable? = null
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        val list = rememberLazyListState()
                        listRef.set(list)
                        LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                            item("tail") { Box(Modifier.fillMaxWidth().height(8.dp)) }
                            item("stream") {
                                Box(
                                    Modifier
                                        .streamingTailLayout(list, 1, 24, animate = false, itemKey = "stream")
                                        .fillMaxWidth()
                                        .height(streamHeight.dp),
                                )
                            }
                            items(20, key = { "older-$it" }) { index ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                        .then(
                                            if (index == 0) Modifier.onGloballyPositioned {
                                                olderY += it.positionInRoot().y
                                            } else Modifier,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
            try {
                pump(800)
                runBlocking {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        listRef.get().scrollToItem(1, 80)
                    }
                }
                pump(400)
                check(listRef.get().firstVisibleItemIndex >= 1) {
                    "Need an off-bottom reading anchor before growth"
                }
                streamHeight = 320
                pump(32)
                pump(16)
                check(olderY.isNotEmpty()) { "Older item never laid out" }
                val before = olderY.last()
                val offsetBefore = listRef.get().firstVisibleItemScrollOffset
                var hostView: android.view.View? = null
                scenario.onActivity { hostView = it.window.decorView }
                val view = requireNotNull(hostView) { "Host decor view missing" }
                val x = view.width * 0.5f
                val y0 = view.height * 0.40f
                val slopPx = 48f
                val dragPx = 80f
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, y: Float, at: Long) {
                    instrumentation.runOnMainSync {
                        val event = android.view.MotionEvent.obtain(down, at, action, x, y, 0)
                        view.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                send(android.view.MotionEvent.ACTION_DOWN, y0, down)
                send(android.view.MotionEvent.ACTION_MOVE, y0 + slopPx, SystemClock.uptimeMillis())
                pump(32)
                val afterSlopY = olderY.last()
                val afterSlopOffset = listRef.get().firstVisibleItemScrollOffset
                var sawGesture = listRef.get().isScrollInProgress
                repeat(8) { step ->
                    pump(16)
                    send(
                        android.view.MotionEvent.ACTION_MOVE,
                        y0 + slopPx + dragPx * (step + 1) / 8f,
                        SystemClock.uptimeMillis(),
                    )
                    if (listRef.get().isScrollInProgress) sawGesture = true
                }
                pump(16)
                val duringOffset = listRef.get().firstVisibleItemScrollOffset
                val duringY = olderY.last()
                send(android.view.MotionEvent.ACTION_UP, y0 + slopPx + dragPx, SystemClock.uptimeMillis())
                pump(48)
                val movedY = kotlin.math.abs(duringY - afterSlopY)
                val movedOffset = kotlin.math.abs(duringOffset - afterSlopOffset).toFloat()
                val json = JSONObject()
                    .put("beforeY", before.toDouble())
                    .put("afterSlopY", afterSlopY.toDouble())
                    .put("duringY", duringY.toDouble())
                    .put("movedY", movedY.toDouble())
                    .put("movedOffset", movedOffset.toDouble())
                    .put("dragPx", dragPx.toDouble())
                    .put("offsetBefore", offsetBefore)
                    .put("afterSlopOffset", afterSlopOffset)
                    .put("duringOffset", duringOffset)
                    .put("sawGesture", sawGesture)
                    .put("firstIndex", listRef.get().firstVisibleItemIndex)
                Log.i(TAG, "idle-hold-then-drag $json")
                capture("idle-hold-then-drag", json)
                assertTrue("drag did not take ownership of scroll: $json", sawGesture)
                assertTrue(
                    "injected ${dragPx}px drag after slop moved list offset ${movedOffset}px: $json",
                    kotlin.math.abs(movedOffset - dragPx) <= 2f,
                )
                assertTrue(
                    "injected ${dragPx}px drag after slop moved older item ${movedY}px: $json",
                    kotlin.math.abs(movedY - dragPx) <= 2f,
                )
            } catch (error: Throwable) {
                Log.i(TAG, "idle-hold-then-drag failed: $error")
                failure = error
            }
        }
        compose.mainClock.autoAdvance = true
        failure?.let { throw it }
    }

    private fun sha(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object { private const val TAG = "AmberChunkUi" }
}
