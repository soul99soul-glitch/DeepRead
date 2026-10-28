package app.amber.feature.ui.components.message

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import app.amber.agent.R
import app.amber.core.utils.exportJpegImage

internal suspend fun captureWidgetToJpg(
    webView: WebView,
    activity: android.app.Activity,
    context: Context,
    toaster: com.dokar.sonner.ToasterState,
) {
    toaster.show(context.getString(R.string.generative_widget_saving_jpg))
    val bitmap = withContext(Dispatchers.Main) {
        webView.captureWidgetBitmap()
    }
    if (bitmap == null) {
        toaster.show(
            context.getString(R.string.generative_widget_jpg_not_rendered),
            type = ToastType.Error,
        )
        return
    }
    val saved = withContext(Dispatchers.IO) {
        context.exportJpegImage(activity, bitmap)
    }
    bitmap.recycle()
    toaster.show(
        message = context.getString(
            if (saved) {
                R.string.generative_widget_saved_jpg
            } else {
                R.string.generative_widget_save_failed
            },
        ),
        type = if (saved) ToastType.Success else ToastType.Error,
    )
}

internal suspend fun captureSlidesToJpg(
    webView: WebView,
    activity: android.app.Activity,
    context: android.content.Context,
    deckTitle: String?,
    toaster: com.dokar.sonner.ToasterState,
    settleDelayMs: Long = 160L,
) {
    toaster.show(context.getString(R.string.generative_widget_save_presentation))
    val count = evaluateJsAwait(webView, "window.__getSlideCount && window.__getSlideCount()")
        ?.toIntOrNull() ?: 0
    if (count <= 0) {
        toaster.show(
            context.getString(R.string.generative_widget_slides_not_rendered),
            type = ToastType.Error,
        )
        return
    }
    val originalSlide = evaluateJsAwait(webView, "window.__getCurrentSlide && window.__getCurrentSlide()")
        ?.toIntOrNull() ?: 0
    withContext(Dispatchers.Main) {
        webView.evaluateJavascript("window.__beginCapture && window.__beginCapture();", null)
    }
    val baseTime = System.currentTimeMillis()
    val safeTitle = (deckTitle?.takeIf { it.isNotBlank() } ?: "deck")
        .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
        .take(40)
        .ifBlank { "deck" }
    var saved = 0
    try {
        for (i in 0 until count) {
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    "window.__jumpToSlideInstant && window.__jumpToSlideInstant($i);",
                    null,
                )
            }
            delay(settleDelayMs)
            val bitmap = withContext(Dispatchers.Main) { webView.captureWidgetBitmap() } ?: continue
            val ok = withContext(Dispatchers.IO) {
                context.exportJpegImage(
                    activity = activity,
                    bitmap = bitmap,
                    fileName = "AmberAgent_${safeTitle}_${baseTime}_p${i + 1}.jpg",
                )
            }
            bitmap.recycle()
            if (ok) saved++
        }
    } finally {
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("window.__endCapture && window.__endCapture();", null)
            webView.evaluateJavascript(
                "window.__jumpToSlideInstant && window.__jumpToSlideInstant($originalSlide);",
                null,
            )
        }
    }
    when {
        saved == count -> toaster.show(
            context.getString(R.string.generative_widget_saved_slides_to_gallery, count),
            type = ToastType.Success,
        )
        saved > 0 -> toaster.show(
            context.getString(R.string.generative_widget_saved_slides_partial, saved, count),
            type = ToastType.Warning,
        )
        else -> toaster.show(
            context.getString(R.string.generative_widget_save_failed),
            type = ToastType.Error,
        )
    }
}

internal suspend fun evaluateJsAwait(webView: WebView, js: String): String? {
    val deferred = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        webView.evaluateJavascript(js) { raw ->
            val cleaned = raw
                ?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
                ?.removeSurrounding("\"")
            deferred.complete(cleaned)
        }
    }
    return deferred.await()
}

internal fun WebView.captureWidgetBitmap(): Bitmap? {
    val width = width.takeIf { it > 0 } ?: return null
    val height = height.takeIf { it > 0 } ?: return null
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        draw(canvas)
    }
}
