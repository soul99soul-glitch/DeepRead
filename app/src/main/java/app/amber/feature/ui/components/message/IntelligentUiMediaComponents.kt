package app.amber.feature.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin

@Composable
internal fun MediaIconLabelView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = node.icon?.take(2)?.ifBlank { "✦" } ?: "✦",
                fontFamily = AmberMono,
                fontSize = 11.sp,
                color = accent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.title?.takeIf { it.isNotBlank() } ?: node.body.orEmpty(),
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.ink,
            )
            node.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    fontFamily = AmberSans,
                    fontSize = 11.5.sp,
                    color = tokens.ink3,
                )
            }
        }
    }
}

@Composable
internal fun MediaAvatarItemView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val initial = (node.title?.trim()?.firstOrNull()?.uppercaseChar() ?: 'A').toString()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                fontFamily = AmberMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.title.orEmpty(),
                fontFamily = AmberSans,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink,
            )
            (node.subtitle ?: node.body)?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    color = tokens.ink3,
                )
            }
        }
        node.badge?.takeIf { it.isNotBlank() }?.let { b ->
            InlineToneBadge(text = b, color = accent)
        }
    }
}

@Composable
internal fun MediaImageCardView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = node.title?.takeIf { it.isNotBlank() } ?: "图像预览",
            fontFamily = AmberSans,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.ink,
        )
        (node.body ?: node.subtitle ?: node.imageUrl)?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(
                text = caption,
                fontFamily = AmberSans,
                fontSize = 12.sp,
                color = tokens.ink3,
            )
        }
    }
}

@Composable
internal fun MediaGalleryStripView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val items = node.items.ifEmpty { node.options.map { it.label } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { label ->
            Box(
                modifier = Modifier
                    .widthIn(min = 100.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.surface2)
                    .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = label,
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    color = tokens.ink2,
                )
            }
        }
    }
}

@Composable
internal fun MediaLinkOrFileCardView(
    node: IntelligentUiComponentNode,
    isFile: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val uriHandler = LocalUriHandler.current
    val url = node.url?.takeIf { it.isNotBlank() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
            .then(
                if (url != null) {
                    Modifier.clickable(role = Role.Button) {
                        runCatching { uriHandler.openUri(url) }
                    }
                } else {
                    Modifier
                },
            )
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isFile) Lucide.FileText else Lucide.ExternalLink,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = accent,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.title?.takeIf { it.isNotBlank() } ?: url ?: "外部资源",
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            (node.subtitle ?: node.body ?: url)?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    fontFamily = AmberMono,
                    fontSize = 11.sp,
                    color = tokens.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun MediaMapPinView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Lucide.MapPin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = accent,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.title?.takeIf { it.isNotBlank() } ?: "位置标记",
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink,
            )
            (node.subtitle ?: node.body)?.takeIf { it.isNotBlank() }?.let { addr ->
                Text(
                    text = addr,
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    color = tokens.ink3,
                )
            }
        }
    }
}
