package app.amber.feature.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.GenerativeWidgetCopy
import app.amber.feature.miniapp.MiniAppStreamingCodePreview
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.JetbrainsMono
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles

/**
 * Single-card placeholder shown while a MiniApp JSON/HTML payload is streaming.
 *
 * Aligns with iOS `ChatMiniAppStreamingCard` and Harmony `MiniAppStreamingCard`:
 * - Default state renders a dot-matrix canvas placeholder + pulsing 3-dot indicator without
 *   decoding or laying out the streaming JSON on every chunk.
 * - Tapping the card toggles a 200dp code window showing the last 2,000 decoded characters
 *   of the `"html"` field, auto-scrolling to the bottom.
 */
@Composable
internal fun MiniAppStreamingCard(
    content: String,
    streaming: Boolean,
    copy: GenerativeWidgetCopy,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val reduceMotion = remember(context) { UIGenerationMotion.isReduceMotionEnabled(context) }

    var showCode by remember { mutableStateOf(false) }
    val preview = remember { MiniAppStreamingCodePreview() }
    val codeText = remember(content, showCode) {
        if (!showCode) {
            ""
        } else {
            preview.update(content.trim().removeSurroundingMiniAppFenceInternal())
            preview.displayText()
        }
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(codeText.length, showCode, streaming) {
        if (showCode && streaming) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    val title = when {
        showCode && streaming -> "正在生成前端代码"
        streaming -> copy.miniAppGenerating
        else -> copy.miniAppJsonTitle
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (showCode) 180f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "miniapp-streaming-chevron",
    )

    val cardBg = chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: tokens.surface
    val innerBg = tokens.surface2
    val borderColor = chatTheme.surfaceEdge.takeIf { it.isSpecified } ?: tokens.line

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { showCode = !showCode }
            .animateContentSize(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
            .semantics { contentDescription = "$title，点按切换预览与代码" },
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (showCode) Lucide.CodeXml else Lucide.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = chatTheme.accent,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(chevronRotation),
                    tint = tokens.ink3,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            ) {
                if (showCode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(innerBg)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = codeText.ifEmpty { " " },
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetbrainsMono,
                                fontSize = 11.5.sp,
                                lineHeight = 16.5.sp,
                            ),
                            color = tokens.ink2,
                        )
                    }
                } else {
                    MiniAppDotMatrixPlaceholder(
                        accent = chatTheme.accent,
                        dotColor = tokens.ink3.copy(alpha = 0.22f),
                        backgroundColor = innerBg,
                        animating = streaming && !reduceMotion,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniAppDotMatrixPlaceholder(
    accent: Color,
    dotColor: Color,
    backgroundColor: Color,
    animating: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "miniapp-dot-loading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "miniapp-dot-phase",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 14.dp.toPx()
            val radius = 1.1.dp.toPx()
            var x = step / 2f
            while (x < size.width) {
                var y = step / 2f
                while (y < size.height) {
                    drawCircle(
                        color = dotColor,
                        radius = radius,
                        center = Offset(x, y),
                    )
                    y += step
                }
                x += step
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val activeAlpha = if (!animating) {
                    0.55f
                } else {
                    val distance = kotlin.math.abs(phase - index)
                    (1f - distance.coerceAtMost(1f)) * 0.65f + 0.25f
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = activeAlpha)),
                )
            }
        }
    }
}

internal fun String.removeSurroundingMiniAppFenceInternal(): String {
    val trimmed = trim()
    if (!trimmed.startsWith("```")) return trimmed
    val firstLineEnd = trimmed.indexOf('\n')
    if (firstLineEnd < 0) return trimmed.removePrefix("```").trimStart()
    val body = trimmed.substring(firstLineEnd + 1)
    val endFence = body.lastIndexOf("```")
    return if (endFence >= 0) body.substring(0, endFence).trim() else body.trim()
}
