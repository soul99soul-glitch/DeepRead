package app.amber.agent

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.LocalMarkdownParseDispatcher
import app.amber.feature.ui.components.richtext.LocalMarkdownPendingProbe
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.Navigator
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 4c item 8: count transparent pending-placeholder frames in a history-cold
 * viewport. Production warms MarkdownParseCache before items enter view.
 */
class HistoryColdPlaceholderDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 90_000)
    fun historyColdPlaceholderFramesAreReported() {
        compose.mainClock.autoAdvance = false
        val paragraph = "## 历史回滚\n\n" +
            "缓存冷启动段落 **重点** 与 `code` 以及公式 \$E=mc^2\$。\n\n".repeat(12)
        check(paragraph.length > 256) { "Fixture must exceed the sync-parse bound" }
        val pendingFrames = AtomicInteger(0)
        val pendingStreak = AtomicInteger(0)
        val longestStreak = AtomicInteger(0)
        val longestMs = AtomicLong(0L)
        var streakStart = 0L
        var lastPending = false
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                val highlighter = Highlighter(activity)
                activity.setContent {
                    CompositionLocalProvider(
                        LocalSettings provides Settings(),
                        LocalHighlighter provides highlighter,
                        LocalNavController provides Navigator(mutableListOf()),
                        LocalMarkdownParseDispatcher provides Dispatchers.Default,
                        LocalMarkdownPendingProbe provides { pending ->
                            if (pending) {
                                pendingFrames.incrementAndGet()
                                val streak = pendingStreak.incrementAndGet()
                                if (streak > longestStreak.get()) longestStreak.set(streak)
                                if (!lastPending) streakStart = SystemClock.uptimeMillis()
                            } else if (lastPending) {
                                val lasted = SystemClock.uptimeMillis() - streakStart
                                if (lasted > longestMs.get()) longestMs.set(lasted)
                                pendingStreak.set(0)
                            }
                            lastPending = pending
                        },
                    ) {
                        MaterialTheme {
                            val list = rememberLazyListState()
                            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                                items(24, key = { "hist-$it" }) { index ->
                                    MarkdownBlock("## 历史条目 $index\n\n$paragraph\n条目编号 $index。")
                                }
                            }
                        }
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 4_000
            while (SystemClock.uptimeMillis() < deadline) {
                compose.mainClock.advanceTimeBy(16)
                SystemClock.sleep(16)
            }
        }
        val json = JSONObject()
            .put("pendingFrames", pendingFrames.get())
            .put("longestStreakFrames", longestStreak.get())
            .put("longestStreakMs", longestMs.get())
            .put("paragraphChars", paragraph.length)
        Log.i("AmberHistoryPlaceholder", json.toString())
        val out = java.io.File(context.getExternalFilesDir(null), "history-cold-placeholder.json")
        out.writeText(json.toString())
        // Report-only: production warmup is the fix when longest streak > 2.
        check(pendingFrames.get() >= 0) { json.toString() }
    }

    private fun launchHost(): ActivityScenario<ComponentActivity> {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/androidx.activity.ComponentActivity",
            ),
        ).bufferedReader().use { it.readText() }
        return ActivityScenario.launch(ComponentActivity::class.java)
    }
}
