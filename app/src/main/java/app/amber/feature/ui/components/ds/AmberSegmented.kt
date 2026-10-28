package app.amber.feature.ui.components.ds

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sliding-thumb segmented track. Chip layout matches the original Row (weight or wrap,
 * [itemSpacing] gaps, content padding). Only the thumb travels, in [AmberFeedback.OptionMs].
 */
@Composable
internal fun AmberSegmentedTrack(
    selectedIndex: Int,
    optionCount: Int,
    onSelect: (Int) -> Unit,
    shape: Shape,
    trackColor: Color,
    thumbColor: Color,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    equalWeight: Boolean = true,
    enabled: Boolean = true,
    itemSpacing: Dp = 2.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
    content: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val clampedIndex = if (optionCount <= 0) 0 else selectedIndex.coerceIn(0, optionCount - 1)
    val widths = remember(optionCount) { List(optionCount) { mutableIntStateOf(0) } }
    val density = LocalDensity.current
    val spacingPx = with(density) { itemSpacing.roundToPx() }
    val selectedWidthPx = widths.getOrNull(clampedIndex)?.intValue ?: 0
    val selectedXPx = (0 until clampedIndex).sumOf { widths[it].intValue } + spacingPx * clampedIndex
    var animateThumb by remember { mutableStateOf(false) }
    val thumbSpec = if (animateThumb) {
        tween<Dp>(durationMillis = AmberFeedback.OptionMs, easing = AmberFeedback.EaseOut)
    } else {
        snap()
    }
    val thumbX by animateDpAsState(
        targetValue = with(density) { selectedXPx.toDp() },
        animationSpec = thumbSpec,
        label = "amber-seg-x",
    )
    val thumbW by animateDpAsState(
        targetValue = with(density) { selectedWidthPx.toDp() },
        animationSpec = thumbSpec,
        label = "amber-seg-w",
    )
    SideEffect {
        if (selectedWidthPx > 0) animateThumb = true
    }
    Box(
        modifier
            .clip(shape)
            .background(trackColor)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .padding(3.dp),
    ) {
        if (selectedWidthPx > 0) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = thumbX)
                        .width(thumbW)
                        .fillMaxHeight()
                        .background(thumbColor, shape),
                )
            }
        }
        Row(
            modifier = Modifier
                .then(if (equalWeight) Modifier.fillMaxWidth() else Modifier)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(optionCount) { index ->
                val selected = index == clampedIndex
                val interaction = remember(index) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .then(if (equalWeight) Modifier.weight(1f) else Modifier)
                        .onSizeChanged { size ->
                            if (widths[index].intValue != size.width) {
                                widths[index].intValue = size.width
                            }
                        }
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            interactionSource = interaction,
                            indication = null,
                            onClick = { if (index != clampedIndex) onSelect(index) },
                        )
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    content(index, selected)
                }
            }
        }
    }
}
