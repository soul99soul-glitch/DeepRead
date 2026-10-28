package app.amber.feature.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens

@Composable
internal fun StatusPillNodeView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val text = node.title?.takeIf { it.isNotBlank() } ?: node.badge ?: node.body.orEmpty()
    if (text.isBlank()) return
    val color = node.style.toneColor(tokens, accent)
    Box(modifier = modifier) {
        InlineToneBadge(text = text, color = color)
    }
}

@Composable
internal fun StatusDotNodeView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val color = node.style.toneColor(tokens, accent)
    val label = node.title?.takeIf { it.isNotBlank() } ?: node.body.orEmpty()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            fontFamily = AmberSans,
            fontSize = 12.5.sp,
            color = tokens.ink2,
        )
    }
}

@Composable
internal fun StatusAlertBannerView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val color = node.style.toneColor(tokens, accent)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        node.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
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

@Composable
internal fun StatusEmptyOrSkeletonView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = node.title?.takeIf { it.isNotBlank() } ?: "暂无内容",
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.ink2,
            )
            node.body?.takeIf { it.isNotBlank() }?.let { body ->
                Text(
                    text = body,
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    color = tokens.ink3,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun TimelineStepItemView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val color = node.style.toneColor(tokens, accent)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = node.title.orEmpty(),
                    fontFamily = AmberSans,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                    modifier = Modifier.weight(1f),
                )
                node.badge?.takeIf { it.isNotBlank() }?.let { b ->
                    Text(
                        text = b,
                        fontFamily = AmberMono,
                        fontSize = 10.5.sp,
                        color = tokens.ink3,
                    )
                }
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
