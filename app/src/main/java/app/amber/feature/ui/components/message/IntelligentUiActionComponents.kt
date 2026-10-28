package app.amber.feature.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiCardState
import app.amber.core.ai.generative.IntelligentUiComponentKind
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.core.ai.generative.IntelligentUiDocument
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens

@Composable
internal fun ActionButtonNodeView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val label = node.actionLabel?.takeIf { it.isNotBlank() }
        ?: node.title?.takeIf { it.isNotBlank() }
        ?: "执行操作"
    val customPrompt = node.actionPrompt?.takeIf { it.isNotBlank() }
    val enabled = onActionPrompt != null && (customPrompt != null || state.validationErrors(document).isEmpty())

    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) accent.copy(alpha = 0.14f) else tokens.surface2)
            .border(1.dp, if (enabled) accent.copy(alpha = 0.50f) else tokens.line, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, role = Role.Button) {
                val prompt = customPrompt ?: state.buildSubmissionPrompt(document).also {
                    state.submittedSummary = it
                }
                onActionPrompt?.invoke(prompt)
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = AmberSans,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) accent else tokens.ink4,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionBarNodeView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (node.children.isNotEmpty()) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            node.children.forEach { child ->
                IntelligentUiComponentView(child, document, state, onActionPrompt)
            }
        }
    } else if (node.options.isNotEmpty()) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            node.options.forEach { opt ->
                val synthetic = IntelligentUiComponentNode(
                    id = opt.id,
                    kind = IntelligentUiComponentKind.ACTION_BUTTON,
                    title = opt.label,
                    actionInstruction = opt.description ?: opt.label,
                )
                ActionButtonNodeView(synthetic, document, state, onActionPrompt, accent)
            }
        }
    } else if (!node.title.isNullOrBlank() || !node.actionInstruction.isNullOrBlank()) {
        ActionButtonNodeView(node, document, state, onActionPrompt, accent, modifier)
    }
}

@Composable
internal fun SummaryOrCtaCardView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    LayoutCardView(node, document, state, onActionPrompt, modifier)
}
