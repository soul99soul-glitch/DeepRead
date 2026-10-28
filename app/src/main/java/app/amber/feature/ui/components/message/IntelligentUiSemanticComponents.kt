package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens

@Composable
internal fun SemanticOptionCardView(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val selected = state.selectedDecisionOptionId == node.id

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton) {
                state.selectedDecisionOptionId = node.id
                state.submittedSummary = null
            },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.10f) else tokens.surface2,
        contentColor = tokens.ink,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent else tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = node.title?.takeIf { it.isNotBlank() } ?: node.id,
                    fontFamily = AmberSans,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                    modifier = Modifier.weight(1f),
                )
                val badge = node.badge ?: if (node.recommended) "推荐" else null
                badge?.let { b ->
                    InlineToneBadge(text = b, color = accent)
                }
            }
            (node.body ?: node.subtitle)?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    fontFamily = AmberSans,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = tokens.ink2,
                )
            }
            if (node.pros.isNotEmpty() || node.cons.isNotEmpty()) {
                DataProsConsView(node)
            }
        }
    }
}

@Composable
internal fun SemanticRecommendationHeroView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = node.badge?.takeIf { it.isNotBlank() } ?: "推荐结论",
                fontFamily = AmberMono,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        node.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontFamily = AmberSans,
                fontSize = 14.5.sp,
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

@Composable
internal fun SemanticConstraintOrRiskView(
    node: IntelligentUiComponentNode,
    isRisk: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val color = if (isRisk) DangerToneColor else WarningToneColor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InlineToneBadge(text = node.badge ?: if (isRisk) "风险" else "约束", color = color)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = tokens.ink2,
                )
            }
        }
    }
}
