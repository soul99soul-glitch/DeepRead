package app.amber.feature.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiAlignment
import app.amber.core.ai.generative.IntelligentUiDensity
import app.amber.core.ai.generative.IntelligentUiStyle
import app.amber.core.ai.generative.IntelligentUiTone
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberTokens

internal val PositiveToneColor = Color(0xFF1F8A5B)
internal val WarningToneColor = Color(0xFFC2781E)
internal val DangerToneColor = Color(0xFFC93B2B)
private val InfoToneColor = Color(0xFF2B6CB0)

@Composable
internal fun InlineToneBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            fontFamily = AmberMono,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

internal fun IntelligentUiStyle.toneColor(tokens: AmberTokens, accent: Color): Color = when (tone) {
    IntelligentUiTone.ACCENT -> accent
    IntelligentUiTone.POSITIVE -> PositiveToneColor
    IntelligentUiTone.WARNING -> WarningToneColor
    IntelligentUiTone.DANGER -> DangerToneColor
    IntelligentUiTone.INFO -> InfoToneColor
    IntelligentUiTone.NEUTRAL -> tokens.ink2
}

internal fun IntelligentUiStyle.resolvedSpacing(defaultDp: Dp): Dp {
    val sp = spacingDp
    return when {
        sp != null -> sp.coerceIn(2, 24).dp
        density == IntelligentUiDensity.COMPACT -> 6.dp
        density == IntelligentUiDensity.SPACIOUS -> 12.dp
        else -> defaultDp
    }
}

internal fun IntelligentUiStyle.resolvedPadding(defaultDp: Dp): Dp {
    val pd = paddingDp
    return when {
        pd != null -> pd.coerceIn(4, 24).dp
        density == IntelligentUiDensity.COMPACT -> 9.dp
        density == IntelligentUiDensity.SPACIOUS -> 15.dp
        else -> defaultDp
    }
}

internal fun IntelligentUiStyle.horizontalAlignment(): Alignment.Horizontal = when (alignment) {
    IntelligentUiAlignment.CENTER -> Alignment.CenterHorizontally
    IntelligentUiAlignment.END -> Alignment.End
    IntelligentUiAlignment.START -> Alignment.Start
}

internal fun IntelligentUiStyle.textAlign(): TextAlign = when (alignment) {
    IntelligentUiAlignment.CENTER -> TextAlign.Center
    IntelligentUiAlignment.END -> TextAlign.End
    IntelligentUiAlignment.START -> TextAlign.Start
}
