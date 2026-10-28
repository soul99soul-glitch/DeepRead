package app.amber.feature.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiCardState
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.core.ai.generative.IntelligentUiDocument
import app.amber.core.ai.generative.IntelligentUiTone
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide

@Composable
internal fun LayoutSectionView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val spacing = node.style.resolvedSpacing(defaultDp = 8.dp)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (!node.title.isNullOrBlank() || !node.subtitle.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                node.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        text = title,
                        fontFamily = AmberSans,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.ink,
                    )
                }
                node.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        fontFamily = AmberSans,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = tokens.ink3,
                    )
                }
            }
        }
        node.body?.takeIf { it.isNotBlank() }?.let { body ->
            Text(
                text = body,
                fontFamily = AmberSans,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = tokens.ink2,
            )
        }
        node.children.forEach { child ->
            IntelligentUiComponentView(child, document, state, onActionPrompt)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LayoutRowView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = node.style.resolvedSpacing(defaultDp = 8.dp)
    if (node.children.size <= 3) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Top,
        ) {
            node.children.forEach { child ->
                Box(modifier = Modifier.weight(1f)) {
                    IntelligentUiComponentView(child, document, state, onActionPrompt)
                }
            }
        }
    } else {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            node.children.forEach { child ->
                IntelligentUiComponentView(child, document, state, onActionPrompt)
            }
        }
    }
}

@Composable
internal fun LayoutColumnView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = node.style.resolvedSpacing(defaultDp = 8.dp)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalAlignment = node.style.horizontalAlignment(),
    ) {
        node.children.forEach { child ->
            IntelligentUiComponentView(child, document, state, onActionPrompt)
        }
    }
}

@Composable
internal fun LayoutGridView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cols = (node.columns ?: 2).coerceIn(1, 3)
    val spacing = node.style.resolvedSpacing(defaultDp = 8.dp)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        node.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = LocalAmberTokens.current.ink,
            )
        }
        node.children.chunked(cols).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                rowItems.forEach { child ->
                    Box(modifier = Modifier.weight(1f)) {
                        IntelligentUiComponentView(child, document, state, onActionPrompt)
                    }
                }
                repeat(cols - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun LayoutCardView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent
    val toneColor = node.style.toneColor(tokens, accent)
    val pad = node.style.resolvedPadding(defaultDp = 12.dp)
    val spacing = node.style.resolvedSpacing(defaultDp = 8.dp)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (node.style.tone == IntelligentUiTone.NEUTRAL) tokens.surface2 else toneColor.copy(alpha = 0.08f),
        contentColor = tokens.ink,
        border = BorderStroke(
            width = 1.dp,
            color = if (node.style.tone == IntelligentUiTone.NEUTRAL) tokens.line else toneColor.copy(alpha = 0.35f),
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            if (!node.title.isNullOrBlank() || !node.badge.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    node.title?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(
                            text = title,
                            fontFamily = AmberSans,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    node.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                        InlineToneBadge(text = badge, color = toneColor)
                    }
                }
            }
            node.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = tokens.ink3,
                )
            }
            node.body?.takeIf { it.isNotBlank() }?.let { body ->
                Text(
                    text = body,
                    fontFamily = AmberSans,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = tokens.ink2,
                )
            }
            node.children.forEach { child ->
                IntelligentUiComponentView(child, document, state, onActionPrompt)
            }
        }
    }
}

@Composable
internal fun LayoutSplitView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = node.style.resolvedSpacing(defaultDp = 10.dp)
    if (node.secondaryChildren.isNotEmpty()) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                node.children.forEach { child ->
                    IntelligentUiComponentView(child, document, state, onActionPrompt)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                node.secondaryChildren.forEach { child ->
                    IntelligentUiComponentView(child, document, state, onActionPrompt)
                }
            }
        }
    } else if (node.children.size >= 2) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                IntelligentUiComponentView(node.children[0], document, state, onActionPrompt)
            }
            Box(modifier = Modifier.weight(1f)) {
                IntelligentUiComponentView(node.children[1], document, state, onActionPrompt)
            }
        }
    } else {
        LayoutColumnView(node, document, state, onActionPrompt, modifier)
    }
}

@Composable
internal fun LayoutTabsView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent
    val tabs = node.tabs
    if (tabs.isEmpty()) {
        LayoutColumnView(node, document, state, onActionPrompt, modifier)
        return
    }
    val selectedTabId = state.activeTabByNodeId[node.id] ?: tabs.first().id
    val activeTab = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.first()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.surface2)
                .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { tab ->
                val active = tab.id == activeTab.id
                Box(
                    modifier = Modifier
                        .heightIn(min = 30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) tokens.surface else Color.Transparent)
                        .clickable(role = Role.Tab) { state.activeTabByNodeId[node.id] = tab.id }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = tab.label,
                            fontFamily = AmberSans,
                            fontSize = 12.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) accent else tokens.ink2,
                        )
                        tab.badge?.takeIf { it.isNotBlank() }?.let { b ->
                            Text(
                                text = b,
                                fontFamily = AmberMono,
                                fontSize = 10.sp,
                                color = if (active) accent else tokens.ink3,
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            activeTab.children.forEach { child ->
                IntelligentUiComponentView(child, document, state, onActionPrompt)
            }
        }
    }
}

@Composable
internal fun LayoutDisclosureView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val expanded = state.expandedByNodeId[node.id] ?: node.expandedByDefault

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface2,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { state.expandedByNodeId[node.id] = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.title?.takeIf { it.isNotBlank() } ?: "展开详情",
                        fontFamily = AmberSans,
                        fontSize = 13.5.sp,
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
                Icon(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = tokens.ink3,
                )
            }
            if (expanded) {
                HorizontalDivider(thickness = 0.5.dp, color = tokens.line)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    node.body?.takeIf { it.isNotBlank() }?.let { body ->
                        Text(
                            text = body,
                            fontFamily = AmberSans,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = tokens.ink2,
                        )
                    }
                    node.children.forEach { child ->
                        IntelligentUiComponentView(child, document, state, onActionPrompt)
                    }
                }
            }
        }
    }
}
