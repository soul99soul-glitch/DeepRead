package app.amber.feature.ui.components.message

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.core.ai.generative.VChartSpecValidator
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.font.SlidesFontRepository
import org.json.JSONObject
import org.koin.compose.koinInject

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun RichSandboxWebView(
    renderer: String,
    specJson: String,
    setting: GenerativeUiSetting,
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 720,
    streaming: Boolean = false,
    onWebViewReady: (WebView?) -> Unit = {},
) {
    val fontRepository = koinInject<SlidesFontRepository>()
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val normalizedSpecJson = remember(specJson, renderer) {
        if (renderer == "slides") VChartSpecValidator.normalizeSlidesDeckSpecJson(specJson) else specJson
    }
    val validated = remember(normalizedSpecJson, renderer) {
        when (renderer) {
            "vchart" -> VChartSpecValidator.validateChartSpec(normalizedSpecJson.orEmpty())
            "slides" -> VChartSpecValidator.validateSlidesSpec(normalizedSpecJson.orEmpty())
            else -> VChartSpecValidator.ValidationResult(false, "unknown renderer")
        }
    }
    // Start at a reasonable initial height; JS resize will adjust to exact content.
    // Capped to avoid jarring shrink animation when maxHeightDp is very large (tablet fullscreen).
    var heightDp by remember(maxHeightDp) { mutableStateOf(maxHeightDp) }
    // Strip U+2028/U+2029 (line/paragraph separators) — JSON allows them,
    // but evaluateJavascript treats them as line terminators and silently fails.
    val safeJson = normalizedSpecJson.orEmpty().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    val fontCss = remember(safeJson, renderer, setting, fontStates) {
        if (renderer == "slides") fontRepository.buildSlidesFontCss(safeJson, setting) else ""
    }
    val latestFontCss by rememberUpdatedState(fontCss)
    val animatedHeight by animateDpAsState(
        targetValue = heightDp.coerceIn(240, maxHeightDp).dp,
        animationSpec = if (streaming) snap() else tween(durationMillis = 200),
        label = "rich-widget-height",
    )

    if (!validated.valid) {
        Text(
            text = stringResource(
                R.string.generative_widget_chart_validation_failed,
                validated.reason.orEmpty(),
            ),
            modifier = modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    val assetFile = when (renderer) {
        "vchart" -> "generative-libs/vchart-sandbox.html"
        "slides" -> "generative-libs/slides-sandbox.html"
        else -> return
    }
    val renderFunction = when (renderer) {
        "vchart" -> "__renderChart"
        "slides" -> "__renderSlides"
        else -> return
    }

    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(activeWebView, fontCss) {
        activeWebView?.evaluateJavascript(
            "window.__setAmberFontCss && window.__setAmberFontCss(${JSONObject.quote(fontCss)});",
            null,
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (renderer == "slides") Modifier.fillMaxSize() else Modifier.height(animatedHeight))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = renderer != "slides"
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(renderer != "slides")
                    settings.builtInZoomControls = renderer != "slides"
                    settings.displayZoomControls = false
                    settings.useWideViewPort = renderer != "slides"
                    settings.loadWithOverviewMode = renderer != "slides"
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.blockNetworkLoads = renderer != "slides"
                    // Slides need MIXED_CONTENT_ALWAYS_ALLOW so that the file:///android_asset/ page
                    // can trigger https://amberagent.local/ font requests which are intercepted locally
                    // by shouldInterceptRequest. NEVER_ALLOW silently blocks these before interception.
                    settings.mixedContentMode = if (renderer == "slides")
                        WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    else
                        WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun resize(height: Int) {
                                Handler(Looper.getMainLooper()).post {
                                    heightDp = height.coerceIn(240, maxHeightDp)
                                }
                            }
                        },
                        "AmberWidget",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = true

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            if (renderer == "vchart") {
                                interceptVChartRuntimeRequest(request)?.let { return it }
                            }
                            // Serve downloaded CJK fonts from local storage
                            fontRepository.interceptFontRequest(request)?.let { return it }
                            // Serve builtin English fonts from APK assets
                            interceptBuiltinFontRequest(request)?.let { return it }
                            // Block all other network requests
                            val scheme = request?.url?.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") {
                                return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                """
                                window.__setAmberFontCss && window.__setAmberFontCss(${JSONObject.quote(latestFontCss)});
                                window.$renderFunction(${JSONObject.quote(safeJson)});
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                    // Load via loadDataWithBaseURL so the page origin is https://amberagent.local/.
                    // This lets @font-face url("https://amberagent.local/fonts/...") trigger
                    // shouldInterceptRequest (same-origin). loadUrl("file:///") would make
                    // the https font requests cross-origin and silently blocked.
                    // Delay until after one layout pass so viewport height is non-zero.
                    val sandboxHtml = context.assets.open(assetFile).bufferedReader().readText()
                    val localizedSandboxHtml = when (renderer) {
                        "vchart" -> context.localizeVChartSandbox(sandboxHtml)
                        "slides" -> context.localizeSlidesSandbox(sandboxHtml)
                        else -> sandboxHtml
                    }
                    post {
                        loadDataWithBaseURL(
                            "https://amberagent.local/",
                            localizedSandboxHtml,
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                    activeWebView = this
                    onWebViewReady(this)
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            activeWebView?.apply {
                stopLoading()
                removeJavascriptInterface("AmberWidget")
                loadUrl("about:blank")
                destroy()
            }
            activeWebView = null
            onWebViewReady(null)
        }
    }
}
