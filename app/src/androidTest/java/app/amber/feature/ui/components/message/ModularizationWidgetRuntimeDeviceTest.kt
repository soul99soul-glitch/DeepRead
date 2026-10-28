package app.amber.feature.ui.components.message

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.R
import app.amber.core.ai.generative.GenerativeWidgetAction
import app.amber.core.ai.generative.GenerativeWidgetSegment
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import com.dokar.sonner.rememberToasterState
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual production rich runtime and its installed WebViewClient. */
@RunWith(AndroidJUnit4::class)
class ModularizationWidgetRuntimeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val activeWebView = AtomicReference<WebView?>()

    @Test
    fun vchartLoadsBundledLibraryAndRendersCanvas() {
        showVchart()
        compose.waitUntil(timeoutMillis = 15_000) {
            val state = readState()
            state.optBoolean("renderReady") &&
                (state.optInt("canvasCount") > 0 || state.optString("error").isNotBlank())
        }
        val state = readState()
        captureRichDiagnostic("01-vchart-library-and-render", state)

        assertTrue("Bundled VChart must load: $state", state.getBoolean("libraryReady"))
        assertTrue("The production chart must create a canvas: $state", state.getInt("canvasCount") > 0)
        assertEquals("The production chart must not show its error panel", "", state.getString("error"))
        assertTrue("The actual canvas backing bitmap must have positive width: $state", state.getInt("canvasBitmapWidth") > 0)
        assertTrue("The actual canvas backing bitmap must have positive height: $state", state.getInt("canvasBitmapHeight") > 0)
        assertTrue("The presented canvas must have positive CSS width: $state", state.getDouble("canvasRectWidth") > 0)
        assertTrue("The presented canvas must have positive CSS height: $state", state.getDouble("canvasRectHeight") > 0)
        assertEquals("Loading must be hidden after the real render", "none", state.getString("loadingDisplay"))
        assertTrue("The actual window copy must contain rendered chart pixels: $state", state.getInt("pixelCopySampledNonWhite") > 0)
    }

    @Test
    fun unknownHttpsRequestRemainsBlockedByProductionPolicy() {
        showVchart()
        compose.waitUntil(timeoutMillis = 15_000) { activeWebView.get() != null }
        val webView = activeWebView.get()!!
        val client = AtomicReference<WebViewClient>()
        instrumentation.runOnMainSync { client.set(webView.webViewClient) }
        val response = client.get().shouldInterceptRequest(
            webView,
            object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse("https://widget-policy.invalid/unknown.js")
                override fun isForMainFrame(): Boolean = false
                override fun isRedirect(): Boolean = false
                override fun hasGesture(): Boolean = false
                override fun getMethod(): String = "GET"
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            },
        )
        assertNotNull("Unknown HTTPS must receive the production blocking response", response)
        assertEquals(0, response!!.data.use { it.readBytes().size })
        capture("02-vchart-unknown-https-blocked", JSONObject().put("unknownHttpsResponseBytes", 0))
    }

    @Test
    fun safeStreamingKeepsItsReceiverAndRespondsToHostLifecycle() {
        val html = mutableStateOf(svg("first"))
        val streaming = mutableStateOf(true)
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    SafeGenerativeWidgetWebView(
                        html = html.value,
                        setting = GenerativeUiSetting(),
                        streaming = streaming.value,
                        widgetKey = html.value.take(120),
                        onWebViewReady = activeWebView::set,
                    )
                }
            }
        }
        awaitSafeMarker("first")
        val original = activeWebView.get()
        for (marker in listOf("second", "third")) {
            compose.runOnIdle { html.value = svg(marker) }
            awaitSafeMarker(marker)
            assertSame("A changing stream key must not replace its WebView", original, activeWebView.get())
        }
        compose.runOnIdle { streaming.value = false }
        awaitSafeMarker("third")
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitUntil(timeoutMillis = 5_000) { readSafeState().optBoolean("paused") }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(timeoutMillis = 5_000) { !readSafeState().optBoolean("paused") }
        val state = readSafeState()
        capture("03-safe-stream-final-and-resume", state)
        assertTrue("The real SVG receiver must report content height", state.getDouble("height") > 0)
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(timeoutMillis = 5_000) { activeWebView.get() == null }
    }

    @Test
    fun guizangDeckUsesItsBootstrapAndRestoresForegroundPowerState() {
        val html = """<html><head><style>html,body{margin:0}#deck{display:flex}.slide{flex:0 0 100vw;display:grid;place-items:center;background:#123456;color:white}</style></head><body><div id="deck"><section class="slide">First page</section><section class="slide">Second page</section></div><script>window.__setLowPowerMode=function(on){window.fixtureLowPower=on;};</script></body></html>"""
        compose.setContent {
            MaterialTheme {
                GuizangHtmlDeckWebView(
                    specJson = JSONObject().put("html", html).put("allowRemoteImages", false).put("allowRemoteFonts", false).toString(),
                    onWebViewReady = activeWebView::set,
                )
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) { readDeckState().optInt("count") == 2 }
        evaluateObject("window.__amberDeckNext(); JSON.stringify({})")
        compose.waitUntil(timeoutMillis = 5_000) { readDeckState().optInt("current") == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitUntil(timeoutMillis = 5_000) { readDeckState().optBoolean("lowPower") }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(timeoutMillis = 5_000) { !readDeckState().optBoolean("lowPower") }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("2/2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("2/2").assertIsDisplayed()
        val state = readDeckState()
        capture("04-guizang-second-page-and-resume", state)
        assertEquals(1, state.getInt("current"))
    }

    @Test
    fun slidesRuntimeRendersAndChangesPagesWithoutAnInteractiveChartSetting() {
        compose.setContent {
            MaterialTheme {
                RichSandboxWebView(
                    renderer = "slides",
                    specJson = """{"slides":[{"title":"First slide","content":["A"]},{"title":"Second slide","content":["B"]}]}""",
                    setting = GenerativeUiSetting(enableInteractiveCharts = false),
                    maxHeightDp = 640,
                    onWebViewReady = activeWebView::set,
                )
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) { readDeckState().optInt("count") == 2 }
        evaluateObject("window.__jumpToSlideInstant(1); JSON.stringify({})")
        compose.waitUntil(timeoutMillis = 5_000) { readDeckState().optInt("current") == 1 }
        assertEquals("2 / 2", readDeckState().getString("indicator"))
        captureRichDiagnostic("05-slides-second-page", readDeckState())
    }

    @Test
    fun realCardStreamsExpandsAndReturnsWithItsInlineStateAndActions() {
        val widget = mutableStateOf(
            GenerativeWidgetSegment.Widget(
                title = "流式演示与完整展开后的操作入口",
                widgetCode = svg("card-first"),
                complete = false,
                renderer = "slides",
            ),
        )
        val prompts = mutableListOf<String>()
        val pixels = targetContext.resources.displayMetrics.widthPixels
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalToaster provides rememberToasterState(),
                LocalDensity provides Density(pixels / 320f, fontScale = 1.5f),
            ) {
                MaterialTheme {
                    GenerativeWidgetCard(widget = widget.value, onAction = prompts::add)
                }
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) { attachedWebViews().size == 1 }
        val inline = attachedWebViews().single()
        compose.waitUntil(timeoutMillis = 15_000) { cardPayload(inline).optString("html").contains("card-first") }
        compose.runOnIdle { widget.value = widget.value.copy(widgetCode = svg("card-middle")) }
        compose.waitUntil(timeoutMillis = 15_000) { cardPayload(inline).optString("html").contains("card-middle") }
        assertSame(inline, attachedWebViews().single())
        compose.runOnIdle {
            widget.value = widget.value.copy(
                widgetCode = svg("card-final"),
                complete = true,
                specJson = """{"slides":[{"title":"First page","content":["A"]},{"title":"Second page","content":["B"]}]}""",
                actions = listOf(GenerativeWidgetAction("draft", "继续整理演示", "继续整理这组幻灯片")),
            )
        }
        compose.waitUntil(timeoutMillis = 15_000) { cardPayload(inline).optString("html").contains("card-final") }
        captureCardStep("06a-card-final-before-expand", JSONObject().put("inlineIdentity", System.identityHashCode(inline)).put("attachedWebViews", 1), inline)
        compose.onNodeWithText(targetContext.getString(R.string.generative_widget_open_presentation))
            .assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 15_000) { attachedWebViews().size == 2 }
        val expanded = attachedWebViews().single { it !== inline }
        val expandedDetachCount = AtomicInteger()
        instrumentation.runOnMainSync {
            expanded.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { expandedDetachCount.incrementAndGet() }
            })
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            evaluateObject("JSON.stringify({count:window.__getSlideCount?window.__getSlideCount():0})", expanded).optInt("count") == 2
        }
        val shareLabel = targetContext.getString(R.string.generative_widget_share_zip)
        val downloadLabel = targetContext.getString(R.string.generative_widget_save_jpg)
        val closeLabel = targetContext.getString(R.string.generative_widget_close)
        val toolbar = listOf(shareLabel, downloadLabel, closeLabel).map { label ->
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction()
            node.fetchSemanticsNode().boundsInRoot
        }
        assertTrue("Share and save targets must not overlap", toolbar[0].right <= toolbar[1].left)
        assertTrue("Save and close targets must not overlap", toolbar[1].right <= toolbar[2].left)
        assertTrue("Toolbar targets must share a vertical center", toolbar.all { abs(it.center.y - toolbar[0].center.y) <= 2f })
        assertTrue("Close must remain inside the narrow viewport", toolbar[2].right <= pixels && toolbar[2].left >= 0f)
        captureCardStep(
            "06b-card-expanded-toolbar-320dp-font150",
            JSONObject().put("inlineIdentity", System.identityHashCode(inline)).put("expandedIdentity", System.identityHashCode(expanded))
                .put("attachedWebViews", 2).put("composeViewportDp", 320).put("fontScale", 1.5),
            expanded,
        )
        compose.onNodeWithContentDescription(closeLabel).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { attachedWebViews().size == 1 && expandedDetachCount.get() == 1 }
        assertSame("Closing the dialog must keep the inline receiver", inline, attachedWebViews().single())
        assertTrue(cardPayload(inline).getString("html").contains("card-final"))
        assertTrue("Opening/closing the renderer must not emit an action", prompts.isEmpty())
        compose.onNodeWithText("继续整理演示").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, prompts.size)
            assertTrue(prompts.single().contains("继续整理这组幻灯片"))
        }
        captureCardStep(
            "06c-card-returned-inline-and-action",
            JSONObject().put("inlineIdentity", System.identityHashCode(inline)).put("attachedWebViews", 1)
                .put("expandedDetachCount", expandedDetachCount.get()).put("actionCallbacks", prompts.size),
            inline,
        )
    }

    private fun attachedWebViews(): List<WebView> {
        val found = Collections.newSetFromMap(IdentityHashMap<WebView, Boolean>())
        fun collect(view: View) {
            if (view is WebView && view.isAttachedToWindow) found.add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { WindowInspector.getGlobalWindowViews().forEach(::collect) }
        return found.toList()
    }

    private fun cardPayload(webView: WebView): JSONObject = evaluateObject(
        """JSON.stringify({html:(document.getElementById('root')||{}).innerHTML||''})""",
        webView,
    )

    private fun captureCardStep(name: String, state: JSONObject, webView: WebView) {
        capture(name, state, webView)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        val count = roots.fetchSemanticsNodes().size
        val directory = File(targetContext.getExternalFilesDir(null), "modularization-phase-3-widget")
        File(directory, "$name.semantics.txt").writeText((0 until count).joinToString("\n\n") { roots[it].printToString() })
    }

    private fun captureRichDiagnostic(name: String, state: JSONObject) {
        val webView = requireNotNull(activeWebView.get())
        state.put(
            "domGeometry",
            evaluateObject(
                """
                JSON.stringify((function(){
                  function describe(el){
                    var r=el.getBoundingClientRect(),s=getComputedStyle(el);
                    var result={tag:el.tagName,id:el.id,className:String(el.className||''),text:(el.textContent||'').slice(0,100),
                      rect:{left:r.left,top:r.top,width:r.width,height:r.height},clientWidth:el.clientWidth,clientHeight:el.clientHeight,
                      scrollWidth:el.scrollWidth,scrollHeight:el.scrollHeight,scrollLeft:el.scrollLeft,
                      display:s.display,visibility:s.visibility,opacity:s.opacity,transform:s.transform,background:s.backgroundColor};
                    if(el.tagName==='CANVAS'){
                      result.bitmapWidth=el.width;result.bitmapHeight=el.height;
                      try{
                        var ctx=el.getContext('2d'),colored=0,nonempty=0;
                        if(ctx&&el.width&&el.height){
                          for(var y=0;y<16;y++)for(var x=0;x<16;x++){
                            var p=ctx.getImageData(Math.floor(x*(el.width-1)/15),Math.floor(y*(el.height-1)/15),1,1).data;
                            if(p[3]>0)nonempty++;
                            if(p[3]>0&&Math.max(p[0],p[1],p[2])-Math.min(p[0],p[1],p[2])>20)colored++;
                          }
                        }
                        result.sampledNonempty=nonempty;result.sampledColored=colored;
                      }catch(e){result.pixelReadError=String(e);}
                    }
                    return result;
                  }
                  return {innerWidth:innerWidth,innerHeight:innerHeight,dpr:devicePixelRatio,
                    visibilityState:document.visibilityState,hasFocus:document.hasFocus(),
                    elements:Array.from(document.querySelectorAll('html,body,#root,#deck,.slide,canvas,#loading,#error')).slice(0,12).map(describe)};
                })())
                """.trimIndent(),
                webView,
            ),
        )
        val source = Rect()
        instrumentation.runOnMainSync {
            val screen = IntArray(2)
            val window = IntArray(2)
            val visible = Rect()
            webView.getLocationOnScreen(screen)
            webView.getLocationInWindow(window)
            source.set(window[0], window[1], window[0] + webView.width, window[1] + webView.height)
            val parents = JSONArray()
            var current: View? = webView
            while (current != null) {
                parents.put(JSONObject().put("type", current.javaClass.name).put("width", current.width).put("height", current.height)
                    .put("alpha", current.alpha.toDouble()).put("visibility", current.visibility).put("shown", current.isShown))
                current = current.parent as? View
            }
            state.put("nativeGeometry", JSONObject().put("width", webView.width).put("height", webView.height)
                .put("attached", webView.isAttachedToWindow).put("windowVisibility", webView.windowVisibility)
                .put("globalVisible", webView.getGlobalVisibleRect(visible)).put("visibleRect", visible.toShortString())
                .put("screenX", screen[0]).put("screenY", screen[1]).put("windowRect", source.toShortString())
                .put("windowFocus", webView.hasWindowFocus()).put("parents", parents))
        }
        capture(name, state, webView)
        assertTrue("PixelCopy requires the actual measured WebView: $state", source.width() > 0 && source.height() > 0)
        val bitmap = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            val result = AtomicInteger(-1)
            instrumentation.runOnMainSync {
                PixelCopy.request(compose.activity.window, source, bitmap, { status ->
                    result.set(status)
                    copied.countDown()
                }, Handler(Looper.getMainLooper()))
            }
            assertTrue("PixelCopy callback must settle", copied.await(10, TimeUnit.SECONDS))
            state.put("pixelCopyResult", result.get())
            val directory = File(targetContext.getExternalFilesDir(null), "modularization-phase-3-widget")
            if (result.get() == PixelCopy.SUCCESS) {
                File(directory, "$name.webview-pixelcopy.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                var nonWhite = 0
                for (y in 0 until 32) for (x in 0 until 32) {
                    val pixel = bitmap.getPixel(x * (bitmap.width - 1) / 31, y * (bitmap.height - 1) / 31)
                    if (android.graphics.Color.red(pixel) < 235 || android.graphics.Color.green(pixel) < 235 || android.graphics.Color.blue(pixel) < 235) nonWhite++
                }
                state.put("pixelCopySampledNonWhite", nonWhite)
            }
            File(directory, "$name.json").writeText(state.toString(2))
            assertEquals("Capture the actual window region without software drawing fallback", PixelCopy.SUCCESS, result.get())
        } finally {
            bitmap.recycle()
        }
    }

    private fun showVchart() {
        compose.setContent {
            MaterialTheme {
                RichSandboxWebView(
                    renderer = "vchart",
                    specJson = CHART_SPEC,
                    setting = GenerativeUiSetting(enableInteractiveCharts = true),
                    modifier = Modifier.fillMaxWidth(),
                    maxHeightDp = 480,
                    onWebViewReady = activeWebView::set,
                )
            }
        }
    }

    private fun readState(): JSONObject {
        return evaluateObject(
            """
            JSON.stringify((function(){
              var canvas=document.querySelector('#root canvas');
              var rect=canvas?canvas.getBoundingClientRect():{width:0,height:0};
              var loading=document.getElementById('loading');
              return {
              renderReady: typeof window.__renderChart === 'function',
              libraryReady: typeof window.VChart !== 'undefined' && typeof window.VChart.default === 'function',
              canvasCount: document.querySelectorAll('#root canvas').length,
              canvasBitmapWidth: canvas?canvas.width:0,
              canvasBitmapHeight: canvas?canvas.height:0,
              canvasRectWidth: rect.width,
              canvasRectHeight: rect.height,
              loadingDisplay: loading?getComputedStyle(loading).display:'missing',
              error: (document.getElementById('error') || {}).textContent || ''
              };
            })())
            """.trimIndent(),
        )
    }

    private fun awaitSafeMarker(marker: String) {
        compose.waitUntil(timeoutMillis = 15_000) { readSafeState().optString("html").contains(marker) }
    }

    private fun readSafeState(): JSONObject = evaluateObject(
        """JSON.stringify({html:(document.getElementById('root')||{}).innerHTML||'',height:document.getElementById('root')?document.getElementById('root').getBoundingClientRect().height:0,paused:window.__amberWidgetPaused===true})""",
    )

    private fun readDeckState(): JSONObject = evaluateObject(
        """JSON.stringify({count:window.__getSlideCount?window.__getSlideCount():0,current:window.__getCurrentSlide?window.__getCurrentSlide():0,lowPower:window.fixtureLowPower===true,indicator:(document.getElementById('ind')||{}).textContent||''})""",
    )

    private fun svg(marker: String) = """<svg viewBox="0 0 300 360"><rect id="$marker" width="300" height="360" fill="#123456"/></svg>"""

    private fun evaluateObject(script: String, webView: WebView? = activeWebView.get()): JSONObject {
        if (webView == null) return JSONObject()
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                script,
            ) { raw -> result.set(raw); done.countDown() }
        }
        assertTrue("WebView JS callback must settle", done.await(5, TimeUnit.SECONDS))
        val jsonString = org.json.JSONTokener(result.get()).nextValue() as String
        return JSONObject(jsonString)
    }

    private fun awaitPresentedFrame(webView: WebView) {
        val drawn = CountDownLatch(1)
        val observed = AtomicBoolean()
        instrumentation.runOnMainSync {
            webView.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    val listener = object : ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            if (!observed.compareAndSet(false, true)) return
                            webView.post {
                                if (webView.viewTreeObserver.isAlive) webView.viewTreeObserver.removeOnDrawListener(this)
                            }
                            webView.postOnAnimation { drawn.countDown() }
                        }
                    }
                    webView.viewTreeObserver.addOnDrawListener(listener)
                    webView.invalidate()
                }
            })
        }
        assertTrue("The actual WebView must present its visual state before capture", drawn.await(10, TimeUnit.SECONDS))
    }

    private fun capture(name: String, state: JSONObject, webView: WebView? = activeWebView.get()) {
        if (webView != null) awaitPresentedFrame(webView)
        val directory = File(targetContext.getExternalFilesDir(null), "modularization-phase-3-widget").apply { mkdirs() }
        File(directory, "$name.json").writeText(state.toString(2))
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            screenshot.recycle()
        }
    }

    private companion object {
        const val CHART_SPEC = """{"type":"bar","data":[{"id":"data","values":[{"label":"A","value":12},{"label":"B","value":24}]}],"xField":"label","yField":"value","animation":false}"""
    }
}
