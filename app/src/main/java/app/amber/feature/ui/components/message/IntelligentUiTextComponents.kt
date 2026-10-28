package app.amber.feature.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.core.ai.generative.IntelligentUiEmphasis
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens

@Composable
internal fun TextHeadingView(
    node: IntelligentUiComponentNode,
    isSubheading: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val text = node.title?.takeIf { it.isNotBlank() } ?: node.body.orEmpty()
    if (text.isBlank()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = node.style.horizontalAlignment(),
    ) {
        Text(
            text = text,
            fontFamily = AmberSans,
            fontSize = when {
                node.style.emphasis == IntelligentUiEmphasis.HERO -> 17.sp
                isSubheading -> 13.5.sp
                else -> 15.sp
            },
            lineHeight = if (isSubheading) 19.sp else 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.ink,
            textAlign = node.style.textAlign(),
        )
        node.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
            Text(
                text = sub,
                fontFamily = AmberSans,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = tokens.ink3,
                textAlign = node.style.textAlign(),
            )
        }
    }
}

@Composable
internal fun TextBodyView(
    node: IntelligentUiComponentNode,
    isCaption: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val text = node.body?.takeIf { it.isNotBlank() } ?: node.title.orEmpty()
    if (text.isBlank()) return
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        fontFamily = AmberSans,
        fontSize = if (isCaption) 11.5.sp else 13.5.sp,
        lineHeight = if (isCaption) 16.sp else 20.sp,
        fontWeight = if (node.style.emphasis == IntelligentUiEmphasis.STRONG) FontWeight.Medium else FontWeight.Normal,
        color = if (isCaption || node.style.emphasis == IntelligentUiEmphasis.SUBTLE) tokens.ink3 else tokens.ink2,
        textAlign = node.style.textAlign(),
    )
}

@Composable
internal fun TextEyebrowView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val text = node.title?.takeIf { it.isNotBlank() } ?: node.body.orEmpty()
    if (text.isBlank()) return
    Text(
        text = text.uppercase(),
        modifier = modifier.fillMaxWidth(),
        fontFamily = AmberMono,
        fontSize = 10.5.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.Medium,
        color = node.style.toneColor(tokens, accent),
        textAlign = node.style.textAlign(),
    )
}

@Composable
internal fun TextCalloutView(
    node: IntelligentUiComponentNode,
    isQuote: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val toneColor = node.style.toneColor(tokens, accent)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isQuote) tokens.surface2 else toneColor.copy(alpha = 0.08f))
            .border(0.5.dp, if (isQuote) tokens.line else toneColor.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(toneColor),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            node.title?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = title,
                    fontFamily = AmberSans,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                )
            }
            node.body?.takeIf { it.isNotBlank() }?.let { body ->
                Text(
                    text = body,
                    fontFamily = AmberSans,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = tokens.ink2,
                )
            }
        }
    }
}

@Composable
internal fun CodeBlockNodeView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val code = node.code?.takeIf { it.isNotBlank() } ?: node.body.orEmpty()
    if (code.isBlank()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!node.title.isNullOrBlank() || !node.language.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = node.title.orEmpty(),
                    fontFamily = AmberSans,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.ink3,
                )
                node.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    Text(
                        text = lang,
                        fontFamily = AmberMono,
                        fontSize = 10.5.sp,
                        color = tokens.ink3,
                    )
                }
            }
        }
        Text(
            text = code,
            fontFamily = AmberMono,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = tokens.ink,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
internal fun ListItemsView(
    node: IntelligentUiComponentNode,
    numbered: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val entries = node.items.ifEmpty {
        node.body?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        node.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink,
            )
        }
        entries.forEachIndexed { idx, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (numbered) "${idx + 1}." else "•",
                    fontFamily = AmberMono,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = tokens.ink3,
                )
                Text(
                    text = item,
                    fontFamily = AmberSans,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = tokens.ink2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun KeyValueSingleView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val keyText = node.title?.takeIf { it.isNotBlank() } ?: node.keyValues.firstOrNull()?.key.orEmpty()
    val valText = node.valueText?.takeIf { it.isNotBlank() }
        ?: node.body?.takeIf { it.isNotBlank() }
        ?: node.keyValues.firstOrNull()?.value.orEmpty()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = keyText,
            fontFamily = AmberSans,
            fontSize = 12.5.sp,
            color = tokens.ink3,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            text = listOfNotNull(valText.takeIf { it.isNotBlank() }, node.unit?.takeIf { it.isNotBlank() }).joinToString(" "),
            fontFamily = AmberMono,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
internal fun DefinitionListView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        node.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink,
            )
        }
        node.keyValues.forEachIndexed { idx, kv ->
            if (idx > 0) {
                HorizontalDivider(thickness = 0.5.dp, color = tokens.line)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = kv.key,
                    fontFamily = AmberSans,
                    fontSize = 12.5.sp,
                    color = tokens.ink3,
                    modifier = Modifier.weight(0.42f),
                )
                Text(
                    text = kv.value,
                    fontFamily = AmberSans,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.ink,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.58f),
                )
            }
        }
    }
}
