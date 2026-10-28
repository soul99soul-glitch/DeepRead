package app.amber.feature.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiCategory
import app.amber.core.ai.generative.IntelligentUiStreamingPreview
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import kotlin.math.abs

/**
 * Progressive streaming preview card for an incomplete `task-ui` fence.
 *
 * Displays the already-streamed task kind badge, title, subtitle, and detected
 * component skeleton rows without exposing raw JSON to the user.
 */
@Composable
internal fun IntelligentUiStreamingPreviewCard(
    preview: IntelligentUiStreamingPreview,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val reduceMotion = remember(context) { UIGenerationMotion.isReduceMotionEnabled(context) }

    val transition = rememberInfiniteTransition(label = "task-ui-streaming")
    val pulsePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "task-ui-pulse-phase",
    )

    val cardBg = chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: tokens.surface
    val borderColor = chatTheme.surfaceEdge.takeIf { it.isSpecified } ?: tokens.line
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent
    val kindLabel = preview.kind?.displayLabel ?: "智能交互"
    val titleText = preview.title?.takeIf { it.isNotBlank() } ?: "正在构建${kindLabel}卡片…"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Lucide.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accent,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = kindLabel,
                        fontFamily = AmberMono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                    )
                }
                Text(
                    text = titleText,
                    fontFamily = AmberSans,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(3) { index ->
                        val alpha = if (reduceMotion) {
                            0.55f
                        } else {
                            val dist = abs(pulsePhase - index)
                            (1f - dist.coerceAtMost(1f)) * 0.65f + 0.25f
                        }
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = alpha)),
                        )
                    }
                }
            }

            preview.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    fontFamily = AmberSans,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = tokens.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val visibleItems = preview.detectedComponents.take(5)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (visibleItems.isEmpty()) {
                    repeat(2) { idx ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.surface2)
                                .border(0.5.dp, tokens.line, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(if (idx == 0) 48.dp else 56.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(tokens.raised),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(tokens.raised.copy(alpha = 0.7f)),
                            )
                        }
                    }
                } else {
                    visibleItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.surface2)
                                .border(0.5.dp, tokens.line, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(tokens.raised)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = item.category.shortChineseLabel(),
                                    fontFamily = AmberMono,
                                    fontSize = 10.sp,
                                    color = tokens.ink3,
                                )
                            }
                            Text(
                                text = item.canonicalName,
                                fontFamily = AmberSans,
                                fontSize = 12.5.sp,
                                color = tokens.ink2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun IntelligentUiCategory.shortChineseLabel(): String = when (this) {
    IntelligentUiCategory.LAYOUT -> "布局"
    IntelligentUiCategory.TEXT -> "文本"
    IntelligentUiCategory.STATUS -> "状态"
    IntelligentUiCategory.INPUT -> "输入"
    IntelligentUiCategory.DATA -> "数据"
    IntelligentUiCategory.MEDIA -> "媒体"
    IntelligentUiCategory.TASK_SEMANTIC -> "任务"
    IntelligentUiCategory.ACTION_CONTAINER -> "操作"
}
