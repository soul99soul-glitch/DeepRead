package app.amber.feature.ui.components.richtext

import app.amber.core.agent.utils.markdown.CjkCompatibleGfmFlavourDescriptor
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup

// HTML shares code/LaTeX normalization, without the AST path's bare-URL rewriting.

private fun preProcess(content: String): String =
    normalizeMarkdownLatexOutsideCode(stripSearchImageFencesForDisplay(content))

// ---- HTML generation ----

private val flavour by lazy {
    CjkCompatibleGfmFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
}

private val parser by lazy { MarkdownParser(flavour) }

private fun generateMarkdownHtml(content: String): String {
    val preprocessed = preProcess(content)
    // Phase 2 Step 4: try native pulldown-cmark HTML emit before falling
    // back to the JetBrains HtmlGenerator. Default config keeps native off
    // → renderHtmlOrNull returns null → JVM path runs unchanged.
    //
    // Diff sampling for the HTML stage now runs both outputs through
    // `HtmlDiffNormalizer` (Phase 3 C) before comparison, so cosmetic
    // divergences (whitespace, attr order, entity escape, void-tag style)
    // don't flood Crashlytics — only genuine semantic differences do.
    // `HTML_DIFF_ENABLED` remains as a one-flip kill-switch should the
    // normalizer itself need to be backed out.
    if (MarkdownNativeSwitch.isHtmlEnabled()) {
        // TODO: pulldown-cmark JNI call is uninterruptible — when the caller's
        //   mapLatest cancels the previous chunk's render, this still burns
        //   a Dispatchers.Default worker until completion. Acceptable for v1
        //   because individual renders are <ms; revisit if ANR signals
        //   point to Default-pool saturation on streamed messages.
        MarkdownNativeSwitch
            .renderHtmlOrNull(preprocessed) { generateMarkdownHtmlJvm(preprocessed) }
            ?.let { return it }
    }
    return generateMarkdownHtmlJvm(preprocessed)
}

private fun generateMarkdownHtmlJvm(preprocessed: String): String {
    val tree = parser.buildMarkdownTreeFromString(preprocessed)
    return HtmlGenerator(preprocessed, tree, flavour).generateHtml()
}

@androidx.annotation.VisibleForTesting
internal fun generateMarkdownHtmlForTest(content: String): String = generateMarkdownHtml(content)


internal fun parseMarkdownHtmlDocument(content: String) = runCatching {
    Jsoup.parse(generateMarkdownHtml(content))
}.getOrElse {
    Jsoup.parse("")
}
