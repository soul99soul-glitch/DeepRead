package app.amber.feature.ui.pages.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MAX
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MIN
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRenderer
import app.amber.feature.ui.components.message.SearchSourcesRegistry
import app.amber.feature.ui.components.message.SourceRef
import app.amber.feature.ui.components.message.normalizeSearchSourceHost
import app.amber.feature.ui.components.richtext.MarkdownNew
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun DeepReadScaledText(
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val safeScale = fontScale.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX)
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * safeScale),
        content = content,
    )
}

// Vertical spacing between consecutive block paragraphs rendered inside DeepRead body
// text (summary / timeline / core_points / perspectives). Magazine-style breathing room
// so multi-paragraph viewpoints don't collapse into one dense block.
private val DEEP_READ_PARAGRAPH_SPACING = 12.dp

/**
 * Builds a [SearchSourcesRegistry] from the article's collected sources so markdown links
 * inside the body (model-embedded `[text](url)`) render as source pills when their host
 * matches a prefetched source. Drives the claim-level citation feature: only links whose
 * host appears here become clickable pills; with [LocalStripUnverifiedLinks] enabled,
 * every other link is dropped entirely so readers only see verified sources.
 */
internal fun buildDeepReadSourceRegistry(output: DeepReadOutput): SearchSourcesRegistry {
    val byHost = linkedMapOf<String, SourceRef>()
    (output.references + output.extendedReading).forEach { link ->
        val host = normalizeSearchSourceHost(link.url) ?: return@forEach
        byHost.putIfAbsent(
            host,
            SourceRef(
                host = host,
                name = link.source?.takeIf { it.isNotBlank() } ?: host,
                url = link.url,
                id = null,
            ),
        )
    }
    return SearchSourcesRegistry(byHost)
}

@Composable
internal fun DeepReadMarkdownText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    paragraphSpacing: Dp = DEEP_READ_PARAGRAPH_SPACING,
) {
    val safeHtml = remember(text) {
        DeepReadTemplateRenderer.renderSafeMarkdownHtml(text)
    }
    // Source pills (claim citations) and any surviving hyperlinks open via MarkdownNew's
    // default context.openUrl, matching the chat page behavior. No custom onClickUrl needed.
    MarkdownNew(
        content = safeHtml,
        modifier = modifier.fillMaxWidth(),
        style = style,
        paragraphSpacing = paragraphSpacing,
    )
}

@Composable
internal fun EntityPill(text: String, palette: MagazinePalette, hero: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (hero) palette.heroSurface else palette.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (hero) palette.heroInk.copy(alpha = 0.22f) else palette.line,
        ),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (hero) palette.heroInk else palette.muted,
        )
    }
}

@Composable
internal fun SectionKicker(text: String, palette: MagazinePalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "//",
            style = LocalAmberType.current.eyebrow.copy(fontSize = 11.sp),
            color = palette.accent,
        )
        Text(
            text.uppercase(),
            style = LocalAmberType.current.eyebrow.copy(fontSize = 11.sp),
            color = palette.muted,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(palette.line),
        )
    }
}


internal fun TextStyle.withReadingFont(fontFamily: FontFamily?): TextStyle =
    if (fontFamily == null) this else copy(fontFamily = fontFamily)

@Composable
internal fun magazinePalette(): MagazinePalette {
    val tokens = LocalAmberTokens.current
    // The reading surface follows the redesign: paper stays light in both themes,
    // while the surrounding app chrome keeps the active Amber base.
    return MagazinePalette(
        background = Color(0xFFFAFAF8),
        surface = Color(0xFFF0EEE8),
        ink = Color(0xFF1E1C18),
        muted = Color(0xFF57534B),
        line = Color(0xFFE9E5DC),
        accent = tokens.accent,
        heroBackground = if (tokens.isDark) tokens.bg else tokens.surface2,
        heroSurface = if (tokens.isDark) tokens.surface else tokens.raised,
        heroInk = if (tokens.isDark) tokens.ink else tokens.ink,
        heroMuted = if (tokens.isDark) tokens.ink2 else tokens.ink2,
        isDark = tokens.isDark,
    )
}

internal data class MagazinePalette(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val heroBackground: Color,
    val heroSurface: Color,
    val heroInk: Color,
    val heroMuted: Color,
    val isDark: Boolean,
)
