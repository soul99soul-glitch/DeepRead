package app.amber.feature.ui.components.message

import android.content.Context
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.amber.agent.R
import org.json.JSONObject

internal fun Context.localizeSlidesSandbox(template: String): String {
    fun jsString(resourceId: Int): String =
        getString(resourceId).toScriptSafeJsonLiteral()

    return template
        .replace(
            "{{AMBER_HTML_SLIDES_PREVIOUS_SLIDE}}",
            htmlEscape(getString(R.string.generative_widget_previous_slide)),
        )
        .replace(
            "{{AMBER_HTML_SLIDES_NEXT_SLIDE}}",
            htmlEscape(getString(R.string.generative_widget_next_slide)),
        )
        .replace(
            "{{AMBER_HTML_SLIDES_FALLBACK_SLIDE_JS}}",
            jsString(R.string.html_asset_slides_fallback_slide),
        )
        .replace(
            "{{AMBER_HTML_SLIDES_FALLBACK_CONTENT_JS}}",
            jsString(R.string.html_asset_slides_fallback_content),
        )
        .replace(
            "{{AMBER_HTML_SLIDES_FALLBACK_IMAGE_JS}}",
            jsString(R.string.html_asset_slides_fallback_image),
        )
        .replace(
            "{{AMBER_HTML_SLIDES_ERROR_EMPTY_JS}}",
            jsString(R.string.html_asset_slides_error_empty),
        )
        .replace(
            "{{AMBER_HTML_SLIDES_ERROR_PREFIX_JS}}",
            jsString(R.string.html_asset_slides_error_prefix),
        )
}

internal fun Context.localizeVChartSandbox(template: String): String {
    fun jsString(resourceId: Int): String =
        getString(resourceId).toScriptSafeJsonLiteral()

    return template
        .replace(
            "{{AMBER_HTML_VCHART_LOADING}}",
            htmlEscape(getString(R.string.html_asset_vchart_loading)),
        )
        .replace(
            "{{AMBER_HTML_VCHART_TIMEOUT_JS}}",
            jsString(R.string.html_asset_vchart_timeout),
        )
        .replace(
            "{{AMBER_HTML_VCHART_ERROR_PREFIX_JS}}",
            jsString(R.string.html_asset_vchart_error_prefix),
        )
}

internal fun htmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

internal fun String.toScriptSafeJsonLiteral(): String =
    JSONObject.quote(this)
        .replace("<", "\\u003C")
        .replace(">", "\\u003E")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

private val BUILTIN_FONTS = mapOf(
    "PlayfairDisplay-Regular.ttf" to "generative-libs/builtin-fonts/PlayfairDisplay-Regular.ttf",
    "Inter-Regular.ttf" to "generative-libs/builtin-fonts/Inter-Regular.ttf",
)

internal fun WebView.interceptVChartRuntimeRequest(request: WebResourceRequest?): WebResourceResponse? {
    if (request?.url?.toString() != "https://amberagent.local/vchart.min.js") return null
    return WebResourceResponse(
        "application/javascript",
        "utf-8",
        context.assets.open("generative-libs/vchart.min.js"),
    )
}

internal fun WebView.interceptBuiltinFontRequest(request: WebResourceRequest?): WebResourceResponse? {
    val uri = request?.url ?: return null
    if (uri.scheme != "https" || uri.host != "amberagent.local") return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments[0] != "builtin-fonts") return null
    val assetPath = BUILTIN_FONTS[segments[1]] ?: return null
    val mimeType = if (segments[1].endsWith(".otf")) "font/otf" else "font/ttf"
    return runCatching {
        WebResourceResponse(mimeType, null, context.assets.open(assetPath))
    }.getOrNull()
}
