package app.amber.feature.ui.components.message

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime registry for one-shot live generation reveal keys so scrolling
 * a LazyColumn row offscreen and back never replays an entrance animation.
 */
object ChatLiveEntranceRegistry {
    private val revealedKeys = ConcurrentHashMap.newKeySet<String>()

    fun hasRevealed(key: String): Boolean = revealedKeys.contains(key)

    fun claim(key: String): Boolean = revealedKeys.add(key)

    internal fun clearForTesting() {
        revealedKeys.clear()
    }
}

/**
 * Tracks whether a streaming surface actually transitioned from streaming (`isStreaming = true`)
 * to completed (`isStreaming = false && complete = true`). Historical cards mounted with
 * `isStreaming = false` from the start never trigger a reveal.
 */
class UIGenerationRevealState(
    private val key: String? = null,
) {
    var sawStreaming: Boolean = false
        private set
    var consumed: Boolean = false
        private set

    fun update(isStreaming: Boolean, complete: Boolean): Boolean {
        sawStreaming = sawStreaming || isStreaming
        if (!sawStreaming || isStreaming || !complete || consumed) return false
        if (key != null && !ChatLiveEntranceRegistry.claim(key)) {
            consumed = true
            return false
        }
        consumed = true
        return true
    }
}

/**
 * Host-owned Web UI generation reveal integration (bundled transitions.dev runtime).
 * Generated HTML never needs external script or network privileges.
 */
object UIGenerationMotion {
    private const val TAG = "UIGenerationMotion"
    const val DEFAULT_FULL_HTML_SELECTOR = "[data-amber-reveal], #deck .slide:first-child, main, #app"
    const val DEFAULT_MINIAPP_SELECTOR = "[data-amber-reveal], main, #app"
    const val DEFAULT_WIDGET_SELECTOR = "#root"

    @Volatile
    private var cachedInstallScript: String? = null

    fun isReduceMotionEnabled(context: Context): Boolean = try {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    } catch (_: Throwable) {
        false
    }

    internal fun buildInstallScript(
        css: String,
        runtimeJs: String,
        hostJs: String,
    ): String {
        val cssLiteral = JSONObject.quote(css)
        val classicRuntime = runtimeJs.replace("export function ", "function ")
        return """
            (() => {
              if (typeof window.__amberGenerationPlay === 'function') return true;
              const style = document.createElement('style');
              style.textContent = $cssLiteral + '\n.t-gen { overflow: clip; contain: layout paint; }';
              (document.head || document.documentElement).appendChild(style);
              $classicRuntime
              $hostJs
              return true;
            })();
        """.trimIndent()
    }

    fun installScript(context: Context): String? {
        cachedInstallScript?.let { return it }
        return synchronized(this) {
            cachedInstallScript?.let { return@synchronized it }
            runCatching {
                val assets = context.applicationContext.assets
                val css = assets.open("generative-libs/ui-generation.css").bufferedReader().use { it.readText() }
                val runtime = assets.open("generative-libs/ui-generation.js").bufferedReader().use { it.readText() }
                val host = assets.open("generative-libs/amber-ui-generation.js").bufferedReader().use { it.readText() }
                buildInstallScript(css = css, runtimeJs = runtime, hostJs = host).also {
                    cachedInstallScript = it
                }
            }.onFailure { error ->
                Log.w(TAG, "Amber UI generation assets unavailable; skipping decoration", error)
            }.getOrNull()
        }
    }

    fun play(
        context: Context,
        webView: WebView,
        selector: String,
        reduceMotion: Boolean = isReduceMotionEnabled(context),
    ) {
        val script = installScript(context) ?: return
        val selectorLiteral = JSONObject.quote(selector)
        val invokeJs = """
            $script
            window.__amberGenerationPlay && window.__amberGenerationPlay($selectorLiteral, $reduceMotion);
        """.trimIndent()
        webView.evaluateJavascript(invokeJs, null)
    }

    fun cancel(webView: WebView?) {
        webView?.evaluateJavascript(
            "window.__amberGenerationCancel && window.__amberGenerationCancel();",
            null,
        )
    }
}
