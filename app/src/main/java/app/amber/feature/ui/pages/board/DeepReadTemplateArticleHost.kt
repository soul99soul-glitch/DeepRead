package app.amber.feature.ui.pages.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateArticle
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRenderer
import app.amber.core.font.SlidesFontRepository

@Composable
internal fun DeepReadTemplateArticleView(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontCss: String,
    templateArticle: DeepReadTemplateArticle? = null,
    customTemplateHtml: String? = null,
    fontRepository: SlidesFontRepository,
    fontScale: Float,
    fallback: @Composable () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // Deep Read is a reading surface: the article paper stays light while
    // app chrome and progress overlays follow the active theme.
    val darkTheme = false
    var failedImageUrls by remember(output) { mutableStateOf<Set<String>>(emptySet()) }
    val displayOutput = remember(output, failedImageUrls) {
        output.withoutTemplateImages(failedImageUrls)
    }
    val rendered = remember(title, displayOutput, fontCss, customTemplateHtml, templateArticle, darkTheme) {
        runCatching {
            when {
                templateArticle != null -> DeepReadTemplateRenderer.renderTemplateArticle(
                    article = templateArticle,
                    fontCss = fontCss,
                    darkTheme = darkTheme,
                )
                customTemplateHtml != null -> DeepReadTemplateRenderer.renderCustom(
                    title = title,
                    output = displayOutput,
                    templateHtml = customTemplateHtml,
                    fontCss = fontCss,
                    darkTheme = darkTheme,
                )
                else -> DeepReadTemplateRenderer.renderEditorialSlant(
                    title = title,
                    output = displayOutput,
                    fontCss = fontCss,
                    darkTheme = darkTheme,
                )
            }
        }.getOrNull()
    }
    var failed by remember(rendered) { mutableStateOf(rendered == null) }
    if (failed || rendered == null) {
        fallback()
        return
    }
    key(rendered.html.hashCode()) {
        DeepReadStaticTemplateWebView(
            html = rendered.html,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background),
            baseUrl = DEEP_READ_TEMPLATE_BASE_URL,
            allowedImageUrls = rendered.allowedImageUrls,
            allowedLinkUrls = rendered.allowedLinkUrls,
            fontRepository = fontRepository,
            textScale = fontScale,
            backgroundColor = palette.background,
            onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
            onMainFrameError = { failed = true },
            onImageError = { url -> failedImageUrls = failedImageUrls + url },
        )
    }
}

private fun DeepReadOutput.withoutTemplateImages(urls: Set<String>): DeepReadOutput {
    if (urls.isEmpty()) return this
    return copy(
        heroImageUrl = heroImageUrl?.takeUnless { it in urls },
        heroImageConfidence = if (heroImageUrl != null && heroImageUrl in urls) null else heroImageConfidence,
        imageAssets = imageAssets.filterNot { it.url in urls },
        timeline = timeline?.map { event ->
            if (event.imageUrl in urls) {
                event.copy(imageUrl = null, imageCaption = null)
            } else {
                event
            }
        },
        corePoints = corePoints?.map { point ->
            if (point.imageUrl in urls) {
                point.copy(imageUrl = null, imageCaption = null)
            } else {
                point
            }
        },
    )
}
