package app.amber.agent

import android.os.SystemClock
import android.os.Trace
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.feature.ui.components.richtext.MarkdownParseCache
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Round-4b item C: time the same main-thread [MarkdownParseCache.getOrParse]
 * call that settled [app.amber.feature.ui.components.richtext.MarkdownBlock]
 * makes on a cache miss (composition thread). 1k/2k/4k/8k, five samples each.
 * Writes `parse-threshold-device.json` for `round4-diagnosis/parse-threshold.json`.
 */
class MarkdownSyncParseDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun syncParseSamplesByLength() {
        val lengths = intArrayOf(128, 256, 512, 768, 1_000, 2_000, 4_000, 8_000)
        val repeats = 7
        val samples = JSONArray()
        for (length in lengths) {
            val parseMs = ArrayList<Double>(repeats)
            repeat(repeats) { iter ->
                val content = markdownOfLength(length, seed = "$length-$iter")
                var ns = 0L
                instrumentation.runOnMainSync {
                    Trace.beginSection("AmberMarkdownSyncParse $length")
                    val start = SystemClock.elapsedRealtimeNanos()
                    MarkdownParseCache.getOrParse(content)
                    ns = SystemClock.elapsedRealtimeNanos() - start
                    Trace.endSection()
                }
                parseMs += ns / 1_000_000.0
            }
            samples.put(
                JSONObject()
                    .put("chars", length)
                    .put("parseMs", JSONArray(parseMs))
                    .put("parseMaxMs", parseMs.max())
                    .put("parseMedianMs", parseMs.sorted()[parseMs.size / 2]),
            )
        }
        val json = JSONObject()
            .put("model", android.os.Build.MODEL)
            .put("device", android.os.Build.DEVICE)
            .put("budgetMs", 4.0)
            .put("samples", samples)
        val out = File(context.getExternalFilesDir(null), "parse-threshold-device.json")
        out.writeText(json.toString(2))
        android.util.Log.i("AmberParseThreshold", json.toString())
    }

    private fun markdownOfLength(chars: Int, seed: String): String {
        val unit = "段落 $seed 中文同步解析标定样本，keep the formatter busy with **bold** and `code`.\n\n"
        val builder = StringBuilder(chars + unit.length)
        builder.append("# Parse threshold $seed\n\n")
        while (builder.length < chars) builder.append(unit)
        return builder.substring(0, chars)
    }
}
