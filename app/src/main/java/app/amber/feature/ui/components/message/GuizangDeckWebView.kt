package app.amber.feature.ui.components.message

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import app.amber.agent.R
import app.amber.core.ai.generative.GuizangHtmlDeckValidator

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GuizangHtmlDeckWebView(
    specJson: String,
    modifier: Modifier = Modifier,
    generationRevealRequested: Boolean = false,
    onGenerationRevealConsumed: () -> Unit = {},
    onWebViewReady: (WebView?) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deck = remember(specJson) { GuizangHtmlDeckValidator.normalizeSpecJson(specJson) }
    val validated = remember(deck) {
        deck?.let { GuizangHtmlDeckValidator.validateDeck(it) }
            ?: GuizangHtmlDeckValidator.ValidationResult(false, "expected spec.html")
    }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember(specJson) { mutableStateOf(false) }
    var currentSlide by remember { mutableStateOf(0) }
    var slideCount by remember { mutableStateOf(0) }
    var lowPower by remember { mutableStateOf(false) }
    val lowPowerState by rememberUpdatedState(lowPower)

    LaunchedEffect(activeWebView, pageReady, generationRevealRequested) {
        val target = activeWebView ?: return@LaunchedEffect
        if (pageReady && generationRevealRequested) {
            onGenerationRevealConsumed()
            UIGenerationMotion.play(
                context = context,
                webView = target,
                selector = UIGenerationMotion.DEFAULT_FULL_HTML_SELECTOR,
            )
        }
    }

    if (!validated.valid || deck == null) {
        Text(
            text = stringResource(
                R.string.generative_widget_full_html_validation_failed,
                validated.reason.orEmpty(),
            ),
            modifier = modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    fun setLowPowerMode(on: Boolean) {
        lowPower = on
        activeWebView?.evaluateJavascript(
            guizangSetLowPowerModeJs(on),
            null,
        )
    }

    fun runDeckAction(js: String) {
        activeWebView?.evaluateJavascript(js, null)
    }

    LaunchedEffect(activeWebView) {
        while (true) {
            val target = activeWebView ?: break
            val nextCount = evaluateJsAwait(target, "window.__getSlideCount && window.__getSlideCount()")
                ?.toIntOrNull()
                ?: 0
            val nextCurrent = evaluateJsAwait(target, "window.__getCurrentSlide && window.__getCurrentSlide()")
                ?.toIntOrNull()
                ?: 0
            slideCount = nextCount
            currentSlide = nextCurrent.coerceIn(0, (nextCount - 1).coerceAtLeast(0))
            delay(650)
        }
    }

    DisposableEffect(lifecycleOwner, activeWebView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    UIGenerationMotion.cancel(activeWebView)
                    activeWebView?.evaluateJavascript(
                        guizangSetLowPowerModeJs(true),
                        null,
                    )
                    activeWebView?.onPause()
                }

                Lifecycle.Event.ON_RESUME -> {
                    activeWebView?.onResume()
                    activeWebView?.evaluateJavascript(
                        guizangSetLowPowerModeJs(lowPowerState),
                        null,
                    )
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.blockNetworkLoads = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = true

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            GuizangHtmlDeckValidator.runtimeAssetForUrl(url)?.let { asset ->
                                return guizangAssetResponse(asset)
                            }
                            interceptBuiltinFontRequest(request)?.let { return it }

                            val uri = request.url
                            val scheme = uri.scheme?.lowercase()
                            if (scheme == "file" || scheme == "content" || scheme == "intent") {
                                return blockedGuizangResponse()
                            }
                            if (scheme == "http" || scheme == "https") {
                                if (uri.host == "amberagent.local") return blockedGuizangResponse()
                                if (deck.allowRemoteImages && GuizangHtmlDeckValidator.isAllowedRemoteImage(url)) return null
                                if (deck.allowRemoteFonts && GuizangHtmlDeckValidator.isAllowedRemoteFontOrStylesheet(url)) return null
                                return blockedGuizangResponse()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(buildGuizangDeckBootstrapJs()) {
                                view.evaluateJavascript(guizangSetLowPowerModeJs(lowPowerState), null)
                                if (view === activeWebView) pageReady = true
                            }
                        }
                    }
                    val runtimeHtml = GuizangHtmlDeckValidator.prepareRuntimeHtml(deck.html)
                    post {
                        loadDataWithBaseURL(
                            "https://amberagent.local/full-html-deck/",
                            runtimeHtml,
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                    activeWebView = this
                    onWebViewReady(this)
                }
            },
            update = { webView ->
                activeWebView = webView
                onWebViewReady(webView)
            },
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = {
                        runDeckAction("window.__amberDeckPrev && window.__amberDeckPrev();")
                    },
                ) {
                    Icon(
                        Lucide.ArrowLeft,
                        contentDescription = stringResource(R.string.generative_widget_previous_slide),
                    )
                }
                Text(
                    text = if (slideCount > 0) "${currentSlide + 1}/$slideCount" else "0/0",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { setLowPowerMode(!lowPower) }) {
                    Icon(
                        if (lowPower) Lucide.Play else Lucide.Pause,
                        contentDescription = if (lowPower) {
                            stringResource(R.string.generative_widget_resume_animation)
                        } else {
                            stringResource(R.string.generative_widget_low_power)
                        },
                    )
                }
                IconButton(
                    onClick = {
                        runDeckAction("window.__amberDeckNext && window.__amberDeckNext();")
                    },
                ) {
                    Icon(
                        Lucide.ArrowRight,
                        contentDescription = stringResource(R.string.generative_widget_next_slide),
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeWebView?.apply {
                UIGenerationMotion.cancel(this)
                evaluateJavascript(guizangSetLowPowerModeJs(true), null)
                onPause()
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            activeWebView = null
            onWebViewReady(null)
        }
    }
}

private fun guizangSetLowPowerModeJs(on: Boolean): String =
    "window.__setLowPowerMode && window.__setLowPowerMode($on, {persist:false});"

private fun WebView.guizangAssetResponse(
    asset: GuizangHtmlDeckValidator.RuntimeAsset,
): WebResourceResponse? =
    runCatching {
        WebResourceResponse(asset.mimeType, "utf-8", context.assets.open(asset.assetPath)).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
            )
        }
    }.getOrNull()

private fun blockedGuizangResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArray(0).inputStream()).apply {
        responseHeaders = mapOf("Cache-Control" to "no-store")
    }

private fun buildGuizangDeckBootstrapJs(): String = """
(function(){
  if (window.__amberGuizangReady) return true;
  window.__amberGuizangReady = true;

  function installViewportHeightFallback(){
    var style = document.getElementById('amber-full-html-viewport-fallback');
    if (!style) {
      style = document.createElement('style');
      style.id = 'amber-full-html-viewport-fallback';
      document.head.appendChild(style);
    }
    style.textContent = [
      'html,body{height:100%!important;min-height:100%!important;}',
      'canvas.bg,#deck{height:100%!important;min-height:100%!important;}',
      '#deck .slide{height:100%!important;min-height:100%!important;}'
    ].join('\n');
  }

  function slideSelector(){ return '.slide, section[data-slide], article[data-slide], div[data-slide]'; }
  function ensureSlideClass(el){
    if (el && el.classList && !el.classList.contains('slide')) el.classList.add('slide');
    return el;
  }
  function deckEl(){
    var deck = document.getElementById('deck') ||
      document.querySelector('[data-guizang-deck], [data-deck], .deck, .slides, main');
    if (deck && deck.querySelectorAll(slideSelector()).length) {
      if (!deck.id) deck.id = 'deck';
      Array.prototype.forEach.call(deck.querySelectorAll(slideSelector()), ensureSlideClass);
      return deck;
    }
    var slides = Array.prototype.slice.call(document.querySelectorAll(slideSelector()));
    if (slides.length) {
      var wrapper = document.createElement('div');
      wrapper.id = 'deck';
      slides[0].parentNode.insertBefore(wrapper, slides[0]);
      slides.forEach(function(slide){ wrapper.appendChild(ensureSlideClass(slide)); });
      return wrapper;
    }
    return null;
  }
  function navEl(){ return document.getElementById('nav'); }
  function slideList(){
    var deck = deckEl();
    return deck ? Array.prototype.slice.call(deck.querySelectorAll(slideSelector())).map(ensureSlideClass) : [];
  }
  function count(){ return slideList().length; }
  function getIndex(){
    try {
      if (typeof idx !== 'undefined' && Number.isFinite(idx)) return idx;
    } catch(e) {}
    return window.__currentSlideIndex || window.__amberGuizangCurrent || 0;
  }
  function applyTheme(slide){
    if (!slide) return;
    var th = slide.dataset && slide.dataset.theme;
    var isLight = th ? th === 'light' : slide.classList.contains('light');
    var isDark = th ? th === 'dark' : (slide.classList.contains('dark') || slide.classList.contains('accent'));
    document.body.classList.toggle('light-bg', !!isLight);
    document.body.classList.toggle('dark-bg', !!isDark);
  }
  function setIndex(n, animate){
    var slides = slideList();
    var total = slides.length;
    if (!total) return false;
    n = Math.max(0, Math.min(total - 1, Number(n) || 0));
    try { if (typeof lock !== 'undefined') lock = false; } catch(e) {}
    try { if (typeof idx !== 'undefined') idx = n; } catch(e) {}
    window.__currentSlideIndex = n;
    window.__amberGuizangCurrent = n;
    var deck = deckEl();
    if (deck) {
      var previousTransition = deck.style.transition;
      if (!animate) deck.style.transition = 'none';
      deck.style.width = (total * 100) + 'vw';
      deck.style.transform = 'translateX(' + (-n * 100) + 'vw)';
      if (!animate) requestAnimationFrame(function(){ deck.style.transition = previousTransition; });
    }
    var nav = navEl();
    if (nav) {
      Array.prototype.forEach.call(nav.querySelectorAll('.dot'), function(dot, i){
        dot.classList.toggle('active', i === n);
      });
    }
    applyTheme(slides[n]);
    if (window.__playSlide) setTimeout(function(){ window.__playSlide(n); }, animate ? 450 : 30);
    return true;
  }

  window.__getSlideCount = count;
  window.__getCurrentSlide = function(){
    var total = count();
    if (!total) return 0;
    return Math.max(0, Math.min(total - 1, getIndex()));
  };
  window.__jumpToSlideInstant = function(n){ return setIndex(n, false); };
  window.__amberDeckPrev = function(){
    var next = window.__getCurrentSlide() - 1;
    if (typeof go === 'function') {
      try { if (typeof lock !== 'undefined') lock = false; } catch(e) {}
      go(next);
      window.__amberGuizangCurrent = window.__getCurrentSlide();
      return true;
    }
    return setIndex(next, true);
  };
  window.__amberDeckNext = function(){
    if (window.__pipeAdvance && window.__pipeAdvance()) return 'step';
    var next = window.__getCurrentSlide() + 1;
    if (typeof go === 'function') {
      try { if (typeof lock !== 'undefined') lock = false; } catch(e) {}
      go(next);
      window.__amberGuizangCurrent = window.__getCurrentSlide();
      return true;
    }
    return setIndex(next, true);
  };
  window.__beginCapture = function(){
    document.body.classList.add('amber-capture');
    var style = document.getElementById('amber-capture-style');
    if (!style) {
      style = document.createElement('style');
      style.id = 'amber-capture-style';
      style.textContent = '#hint,#nav,#overview{display:none!important} body{overflow:hidden!important}';
      document.head.appendChild(style);
    }
  };
  window.__endCapture = function(){
    document.body.classList.remove('amber-capture');
    var style = document.getElementById('amber-capture-style');
    if (style) style.remove();
  };
  installViewportHeightFallback();
  setIndex(getIndex(), false);
  if (window.lucide && window.lucide.createIcons) window.lucide.createIcons();
  return true;
})();
""".trimIndent()
