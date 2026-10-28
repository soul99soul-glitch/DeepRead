package app.amber.agent

import android.os.Debug
import android.os.SystemClock
import android.os.Trace
import android.view.MotionEvent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.richtext.StreamingSingleTextMarkdown
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.CoroutineScope
import app.amber.feature.ui.pages.chat.holdReadingOnTailGrowth
import app.amber.agent.chatfixture.streamingTailLayout
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** In-memory rendering only. Never opens a repository, settings store, or network provider. */
class ChatRenderingDevicePerfTest {

    @Test(timeout = 120_000) fun repeatableStreamingAndReading() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("amberRenderPerf") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // MIUI can reject an instrumentation process's first background launch.
        // A shell launch of the empty test host makes the process visible without
        // changing background-launch permissions or opening a user conversation.
        val hostCommand = "am start -W -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(hostCommand),
        ).bufferedReader().use { it.readText() }
        Log.i("AmberRenderPerf", "host-shell-started")
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        Log.i("AmberRenderPerf", "scenario-launched")
        lateinit var activity: ComponentActivity
        scenario.onActivity {
            activity = it
            // Request the same 60 Hz mode for both APKs. The host script also
            // scopes/restores a system cap because MIUI may override a window hint.
            val display = it.display
            val mode = display?.supportedModes?.firstOrNull { candidate ->
                candidate.physicalWidth == display.mode.physicalWidth &&
                    candidate.physicalHeight == display.mode.physicalHeight &&
                    kotlin.math.abs(candidate.refreshRate - 60f) < 1f
            }
            it.window.attributes = it.window.attributes.apply {
                preferredRefreshRate = 60f
                if (mode != null) preferredDisplayModeId = mode.modeId
            }
        }
        val durations = Collections.synchronizedList(mutableListOf<Long>())
        val layout = Collections.synchronizedList(mutableListOf<Long>())
        val deadlines = Collections.synchronizedList(mutableListOf<Long>())
        val unknownDelay = Collections.synchronizedList(mutableListOf<Long>())
        val phase = AtomicReference("warmup")
        val phaseFrames = Collections.synchronizedList(mutableListOf<Pair<String, Double>>())
        val worker = HandlerThread("render-frame-metrics").apply { start() }
        val done = CountDownLatch(1)
        val settled = CountDownLatch(1)
        val optimizedLayout = InstrumentationRegistry.getArguments().getString("amberRenderOptimizedLayout") == "true"
        val label = InstrumentationRegistry.getArguments().getString("amberRenderLabel") ?: "unlabelled"
        val started = CountDownLatch(1)
        val state = MutableStateFlow("")
        val streaming = MutableStateFlow(true)
        val profile = InstrumentationRegistry.getArguments().getString("amberRenderProfile") ?: "mixed"
        val paragraph = when (profile) {
            "short" -> "简短回答：流式显示保持顺畅，Unicode 👩‍💻 é 不截断。\n\n"
            "chinese" -> "这是一段用于测量的中文长回答。稳定段落保持可读，滚动阅读时不应被不断增长的尾部带走。Unicode 👩‍💻 é。\n\n"
            "long-unbroken" -> "超长无换行单段测量，Unicode 👩‍💻 é。" + "字".repeat(6_200)
            "history-cold" -> "## 历史回滚\n\n缓存冷启动段落 **重点** 与 `code` 以及公式 \$E=mc^2\$。\n\n"
            else -> "## 测量小节\n\n中文说明 **重点** and [link](https://example.com).\n\n```kotlin\nfun answer(x: Int) = x + 42\n```\n\n| 项目 | 结果 |\n| --- | --- |\n| 文字 | 完整 |\n\n"
        }
        val target = when (profile) {
            "short" -> paragraph.repeat(3)
            "long-unbroken" -> paragraph
            "history-cold" -> paragraph.repeat(2)
            else -> paragraph.repeat(85)
        }
        val fromZero = InstrumentationRegistry.getArguments().getString("amberRenderFromZero") == "true"
        val initial = when {
            profile == "short" || profile == "history-cold" || fromZero -> 0
            else -> target.length - 1600
        }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            durations.add(duration)
            phaseFrames.add(phase.get() to duration / 1_000_000.0)
            layout.add(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
            deadlines.add(metrics.getMetric(FrameMetrics.DEADLINE))
            unknownDelay.add(metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION))
        }
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
        try {
            val runtimeBefore = AtomicReference(Debug.getRuntimeStats())
            state.value = target.take(initial)
            val highlighter = Highlighter(activity)
            scenario.onActivity { host ->
                host.setContent {
                    CompositionLocalProvider(
                        LocalSettings provides Settings(),
                        LocalHighlighter provides highlighter,
                        LocalNavController provides Navigator(mutableListOf()),
                        LocalToaster provides ToasterState(CoroutineScope(Dispatchers.Unconfined)),
                    ) {
                        MaterialTheme {
                            val list = rememberLazyListState()
                            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                                item("tail") { Text("render fixture") }
                                item("stream") {
                                    // Three-argument form is the round3-final-v3 JVM ABI.
                                    val growth = if (optimizedLayout) Modifier.streamingTailLayout(list, 1, 24) else {
                                        Modifier.holdReadingOnTailGrowth(list, 1, 24)
                                            .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium))
                                    }
                                    Column(growth.padding(12.dp)) {
                                        val source by state.collectAsState()
                                        val active by streaming.collectAsState()
                                        StreamingSingleTextMarkdown(source, emptyList(), streaming = active,
                                            onSettled = { settled.countDown() })
                                    }
                                }
                                items(40, key = { "history-$it" }) {
                                    if (profile == "history-cold") {
                                        MarkdownBlock("## 历史条目 $it\n\n" + paragraph + "条目编号 $it。")
                                    } else {
                                        Text("历史回复 $it：" + paragraph.take(100))
                                    }
                                }
                            }
                            LaunchedEffect(Unit) {
                                delay(2500)
                                runtimeBefore.set(Debug.getRuntimeStats())
                                durations.clear(); layout.clear(); deadlines.clear(); unknownDelay.clear(); phaseFrames.clear()
                                phase.set("pinned")
                                Trace.beginSection("Amber fixture pinned"); Trace.endSection()
                                started.countDown()
                                val feed = launch(Dispatchers.Default) {
                                    var end = initial
                                    while (end < target.length) {
                                        end = (end + 12).coerceAtMost(target.length)
                                        state.value = target.take(end)
                                        delay(33)
                                    }
                                    streaming.value = false
                                }
                                if (fromZero && profile != "short") {
                                    // The fixed mixed fixture renders fewer characters than its
                                    // Markdown source. 8192 visible source units conservatively
                                    // places its rendered Text past the 3072 chunk threshold.
                                    // Keep the initial growth on screen before the reading drag.
                                    val threshold = minOf(8192, target.length - 128).coerceAtLeast(0)
                                    while (state.value.length - app.amber.feature.ui.components.richtext.StreamingRenderProbe.displayBacklog < threshold) delay(16)
                                    Log.i("AmberRenderPerf", "from-zero-threshold source=${state.value.length} backlog=${app.amber.feature.ui.components.richtext.StreamingRenderProbe.displayBacklog}")
                                } else delay(750)
                                phase.set("reading-drag-fling")
                                Trace.beginSection("Amber fixture reading drag fling"); Trace.endSection()
                                val view = activity.window.decorView
                                val down = SystemClock.uptimeMillis()
                                val x = view.width * .5f
                                val startY = view.height * .3f
                                fun touch(action: Int, y: Float) {
                                    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also { event ->
                                        activity.dispatchTouchEvent(event)
                                        event.recycle()
                                    }
                                }
                                touch(MotionEvent.ACTION_DOWN, startY)
                                repeat(10) { step ->
                                    delay(16)
                                    touch(MotionEvent.ACTION_MOVE, startY + view.height * .045f * (step + 1))
                                }
                                touch(MotionEvent.ACTION_UP, startY + view.height * .45f)
                                feed.join()
                                kotlinx.coroutines.withTimeout(10_000) {
                                    while (list.isScrollInProgress) delay(16)
                                }
                                // A short answer may leave composition during the drag. Re-enter at
                                // the bottom before waiting for its final visible/parse/fade handover.
                                phase.set("pinned-return")
                                list.scrollToItem(0)
                                kotlinx.coroutines.withTimeout(60_000) {
                                    while (settled.count > 0) delay(16)
                                }
                                Log.i("AmberRenderPerf", "stream-settled")
                                phase.set("history")
                                Trace.beginSection("Amber fixture history"); Trace.endSection()
                                list.scrollToItem(5)
                                repeat(10) {
                                    list.scroll { scrollBy(180f) }
                                    delay(100)
                                }
                                Log.i("AmberRenderPerf", "history-finished")
                                done.countDown()
                            }
                        }
                    }
                }
            }
            Log.i("AmberRenderPerf", "content-installed")
            check(started.await(30, TimeUnit.SECONDS))
            Log.i("AmberRenderPerf", "sample-started")
            check(done.await(90, TimeUnit.SECONDS))
            Log.i("AmberRenderPerf", "sample-finished")
            val runtimeAfter = Debug.getRuntimeStats()
            Log.i("AmberRenderPerf", "runtime-stats-collected")
            val frameMs = synchronized(durations) { durations.map { it / 1_000_000.0 }.sorted() }
            val layoutMs = synchronized(layout) { layout.map { it / 1_000_000.0 }.sorted() }
            fun List<Double>.percentile(p: Double) = getOrElse(((size - 1) * p).toInt()) { 0.0 }
            val refresh = activity.display?.refreshRate ?: 60f
            check(kotlin.math.abs(refresh - 60f) < 1f) { "Fixture refresh request not honored: $refresh" }
            check(frameMs.size >= 10) { "No usable foreground frame sample: ${frameMs.size}" }
            val report = "label=$label profile=$profile chars=${target.length} refresh=$refresh frames=${frameMs.size} " +
                "p50=${frameMs.percentile(.5)} p95=${frameMs.percentile(.95)} p99=${frameMs.percentile(.99)} " +
                "overBudget=${frameMs.count { it > 1000 / refresh }} over32=${frameMs.count { it > 32 }} " +
                "layoutP95=${layoutMs.percentile(.95)} gcBefore=${runtimeBefore.get()} gcAfter=$runtimeAfter"
            Log.i("AmberRenderPerf", report)
            val output = JSONObject().apply {
                put("report", report); put("framesMs", JSONArray(frameMs)); put("layoutMs", JSONArray(layoutMs))
                put("deadlinesMs", JSONArray(synchronized(deadlines) { deadlines.map { it / 1_000_000.0 } }))
                put("unknownDelayMs", JSONArray(synchronized(unknownDelay) { unknownDelay.map { it / 1_000_000.0 } }))
                put("gcBefore", JSONObject(runtimeBefore.get())); put("gcAfter", JSONObject(runtimeAfter))
                val grouped = synchronized(phaseFrames) { phaseFrames.toList() }.groupBy({ it.first }, { it.second })
                put("phaseFramesMs", JSONObject().apply {
                    grouped.forEach { (name, values) -> put(name, JSONArray(values)) }
                })
                put("initialSourceChars", initial); put("fromZero", fromZero)
                put("finalSourceChars", state.value.length); put("settled", settled.count == 0L)
            }
            val resultFile = File(activity.getExternalFilesDir(null), "amber-render-$label-$profile.json")
            resultFile.writeText(output.toString(2))
            Log.i("AmberRenderPerf", "result=${resultFile.absolutePath}")
            val gfx = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo ${activity.packageName} framestats"),
            ).bufferedReader().use { it.readText() }
            File(activity.getExternalFilesDir(null), "amber-render-$label-$profile-gfxinfo.txt").writeText(gfx)
        } finally {
            activity.window.removeOnFrameMetricsAvailableListener(listener)
            worker.quitSafely()
            scenario.close()
        }
    }
}
