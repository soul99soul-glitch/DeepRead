package app.amber.feature.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Heart
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.ui.icons.HeartIcon
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import app.amber.core.utils.formatNumber

@Composable
internal fun PickerProviderHeader(
    provider: ProviderSetting,
    modelCount: Int,
    active: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    showTopCorners: Boolean,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val accent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent
    val shape = RoundedCornerShape(
        topStart = if (showTopCorners) 14.dp else 0.dp,
        topEnd = if (showTopCorners) 14.dp else 0.dp,
        bottomStart = if (expanded) 0.dp else 14.dp,
        bottomEnd = if (expanded) 0.dp else 14.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line.copy(alpha = 0.42f), shape)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = provider.providerSlugLabel(),
            style = type.meta.copy(
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (active) accent else t.ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = modelCount.toString(),
            style = type.meta.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
            color = t.ink3,
            maxLines = 1,
        )
        Icon(
            imageVector = Lucide.ArrowRight,
            contentDescription = null,
            tint = t.ink3,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
        )
    }
}

@Composable
internal fun PickerModelRow(
    model: Model,
    providerSetting: ProviderSetting,
    isActive: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    isLast: Boolean,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val shape = RoundedCornerShape(
        bottomStart = if (isLast) 14.dp else 0.dp,
        bottomEnd = if (isLast) 14.dp else 0.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line.copy(alpha = 0.42f), shape),
    ) {
        ModelItemRow(
            model = model,
            providerSetting = providerSetting,
            isActive = isActive,
            onSelect = onSelect,
            onDismiss = onDismiss,
            startPadding = 14.dp,
            endPadding = 2.dp,
            tail = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (isActive) {
                        Icon(
                            imageVector = Lucide.Check,
                            contentDescription = null,
                            tint = t.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    FavoriteToggleIcon(
                        favorite = favorite,
                        isActive = isActive,
                        onToggle = onToggleFavorite,
                    )
                }
            },
        )
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(t.line),
            )
        }
    }
}

@Composable
internal fun ModelItem(
    model: Model,
    providerSetting: ProviderSetting,
    select: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tail: @Composable RowScope.() -> Unit = {},
    dragHandle: @Composable (RowScope.() -> Unit)? = null,
    // V3 active 模型的 thinking-level segment（slot）
    thinkingSegment: (@Composable () -> Unit)? = null,
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    // Amber Redesign §5: selected = accent text + weight 700, NO fill block / NO
    // border / NO check. §2: row = 0 radius full-bleed. §3: hairline between rows
    // (added by the caller's list; this row itself is borderless).
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = true,
                onLongClick = {
                    onDismiss()
                    navController.navigate(
                        Screen.SettingProviderDetail(providerSetting.id.toString())
                    )
                },
                onClick = { onSelect(model) },
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
        ) {
            // §6: mono model name, accent + 700 only when selected.
            Text(
                text = model.displayName,
                style = LocalAmberType.current.meta.copy(
                    fontSize = 14.sp,
                    fontWeight = if (select) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (select) chatTheme.accent else chatTheme.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // §6: right-aligned mono context number (tabular-nums)
            model.contextWindowTokens?.let { ctx ->
                Text(
                    text = ctx.formatNumber(),
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 11.5.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = tokens.ink3,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(42.dp),
                )
            }
            tail()
        }
        // ── 第二行：thinking-level segment（仅 active + REASONING 时显示）
        thinkingSegment?.let {
            Box(modifier = Modifier.padding(top = 6.dp)) {
                it()
            }
        }
    }
}

/** V3 grouped 卡内的 model 行 —— ModelItem 但去掉外层 Card 包装，仅渲染内容行。
 *  设计稿：同 provider 多 model 在一张 SubCard 内用 hairline 分隔，所以单行不需要自己的 Card。
 */
@Composable
internal fun ModelItemRow(
    model: Model,
    providerSetting: ProviderSetting,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    isActive: Boolean = false,
    modelFontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    contextFontSize: androidx.compose.ui.unit.TextUnit = 11.5.sp,
    verticalPadding: androidx.compose.ui.unit.Dp = 9.dp,
    endPadding: androidx.compose.ui.unit.Dp = 6.dp,
    startPadding: androidx.compose.ui.unit.Dp = 28.dp,
    contextWidth: androidx.compose.ui.unit.Dp = 42.dp,
    tail: @Composable RowScope.() -> Unit = {},
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    // Graphite §6.2 row: mono "name … ctx", accent only when selected, no logo/check/badge.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = true,
                onLongClick = {
                    onDismiss()
                    navController.navigate(
                        Screen.SettingProviderDetail(providerSetting.id.toString())
                    )
                },
                onClick = { onSelect(model) },
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            )
            .padding(start = startPadding, end = endPadding, top = verticalPadding, bottom = verticalPadding),
    ) {
        Text(
            text = model.displayName,
            style = LocalAmberType.current.meta.copy(
                fontSize = modelFontSize,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (isActive) chatTheme.accent else chatTheme.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        model.contextWindowTokens?.let { ctx ->
            Text(
                text = ctx.formatNumber(),
                style = LocalAmberType.current.meta.copy(
                    fontSize = contextFontSize,
                    fontFeatureSettings = "tnum",
                ),
                color = tokens.ink3,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(contextWidth),
            )
        }
        tail()
    }
}

/** 收藏图标 —— 主题色感知的心形 */
@Composable
private fun FavoriteToggleIcon(
    favorite: Boolean,
    isActive: Boolean,
    onToggle: () -> Unit,
) {
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (favorite) HeartIcon else Lucide.Heart,
            contentDescription = stringResource(
                if (favorite) R.string.favorite_remove else R.string.favorite_add
            ),
            modifier = Modifier.size(20.dp),
            tint = when {
                favorite -> chatTheme.accent
                isActive -> chatTheme.accent
                else -> chatTheme.inkSoft
            },
        )
    }
}
