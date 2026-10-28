package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiCardState
import app.amber.core.ai.generative.IntelligentUiComponentKind
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.core.ai.generative.IntelligentUiDocument
import app.amber.core.ai.generative.IntelligentUiTone
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun DataMetricNodeView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val boundNum = state.boundNumericValue(
        document = document,
        valueFieldId = node.valueFieldId,
        fallback = node.numericValue,
        scale = node.scale,
        offset = node.offset,
    )
    val displayValue = if (boundNum != null) {
        IntelligentUiCardState.formatNumber(boundNum)
    } else {
        node.valueText?.takeIf { it.isNotBlank() } ?: node.body ?: "—"
    }
    val toneColor = node.style.toneColor(tokens, accent)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface2,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            node.title?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = title,
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    color = tokens.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = displayValue,
                    fontFamily = AmberMono,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (node.style.tone == IntelligentUiTone.NEUTRAL) tokens.ink else toneColor,
                )
                node.unit?.takeIf { it.isNotBlank() }?.let { u ->
                    Text(
                        text = u,
                        fontFamily = AmberMono,
                        fontSize = 11.5.sp,
                        color = tokens.ink3,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            val meta = node.delta ?: node.subtitle ?: node.badge
            meta?.takeIf { it.isNotBlank() }?.let { delta ->
                Text(
                    text = delta,
                    fontFamily = AmberMono,
                    fontSize = 11.sp,
                    color = toneColor,
                )
            }
        }
    }
}

@Composable
internal fun DataMetricGridView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (node.children.isNotEmpty()) {
        LayoutGridView(node, document, state, onActionPrompt, modifier)
        return
    }
    val cols = (node.columns ?: 2).coerceIn(1, 3)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        node.dataPoints.chunked(cols).forEach { rowPoints ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowPoints.forEach { pt ->
                    val synthetic = IntelligentUiComponentNode(
                        id = "${node.id}_${pt.label}",
                        kind = IntelligentUiComponentKind.METRIC,
                        title = pt.label,
                        numericValue = pt.value,
                        unit = pt.unit,
                        valueFieldId = pt.valueFieldId,
                        scale = pt.scale,
                        offset = pt.offset,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        DataMetricNodeView(synthetic, document, state, accent)
                    }
                }
                repeat(cols - rowPoints.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun DataProgressNodeView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val maxVal = (node.max ?: 100.0).coerceAtLeast(1.0)
    val currentVal = state.boundNumericValue(
        document = document,
        valueFieldId = node.valueFieldId,
        fallback = node.numericValue ?: 0.0,
        scale = node.scale,
        offset = node.offset,
    ) ?: 0.0
    val fraction = (currentVal / maxVal).toFloat().coerceIn(0f, 1f)
    val toneColor = node.style.toneColor(tokens, accent)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = node.title?.takeIf { it.isNotBlank() } ?: "进度",
                fontFamily = AmberSans,
                fontSize = 12.5.sp,
                color = tokens.ink2,
            )
            Text(
                text = "${IntelligentUiCardState.formatNumber(currentVal)}${node.unit ?: "%"}",
                fontFamily = AmberMono,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = toneColor,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = toneColor,
            trackColor = tokens.line2,
        )
    }
}

@Composable
internal fun DataStepProgressNodeView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val totalSteps = max(node.items.size, node.max?.roundToInt() ?: 4).coerceIn(2, 8)
    val activeStep = (state.boundNumericValue(
        document = document,
        valueFieldId = node.valueFieldId,
        fallback = node.numericValue ?: 1.0,
        scale = node.scale,
        offset = node.offset,
    )?.roundToInt() ?: 1).coerceIn(0, totalSteps)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        node.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontFamily = AmberSans,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.ink,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (stepIdx in 1..totalSteps) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (stepIdx <= activeStep) accent else tokens.line2),
                )
            }
        }
    }
}

@Composable
internal fun DataComparisonRowView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val left = node.leftLabel ?: node.keyValues.getOrNull(0)?.value.orEmpty()
    val right = node.rightLabel ?: node.keyValues.getOrNull(1)?.value.orEmpty()

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
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink3,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = left,
                fontFamily = AmberSans,
                fontSize = 12.5.sp,
                color = tokens.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "vs",
                fontFamily = AmberMono,
                fontSize = 11.sp,
                color = tokens.ink3,
            )
            Text(
                text = right,
                fontFamily = AmberSans,
                fontSize = 12.5.sp,
                color = tokens.ink,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun DataProsConsView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(PositiveToneColor.copy(alpha = 0.08f))
                .border(1.dp, PositiveToneColor.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "优势 Pros",
                fontFamily = AmberMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = PositiveToneColor,
            )
            node.pros.forEach { pro ->
                Text(
                    text = "+ $pro",
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = tokens.ink,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(WarningToneColor.copy(alpha = 0.08f))
                .border(1.dp, WarningToneColor.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "局限 Cons",
                fontFamily = AmberMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = WarningToneColor,
            )
            node.cons.forEach { con ->
                Text(
                    text = "− $con",
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = tokens.ink,
                )
            }
        }
    }
}

@Composable
internal fun DataTableView(
    node: IntelligentUiComponentNode,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val headers = node.headers
    val rows = node.rows
    if (headers.isEmpty() && rows.isEmpty()) return
    val colCount = max(headers.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(10.dp)),
    ) {
        if (headers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.raised)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (i in 0 until colCount) {
                    Text(
                        text = headers.getOrNull(i).orEmpty(),
                        fontFamily = AmberSans,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.ink,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        rows.forEachIndexed { rIdx, row ->
            if (rIdx > 0 || headers.isNotEmpty()) {
                HorizontalDivider(thickness = 0.5.dp, color = tokens.line)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (i in 0 until colCount) {
                    Text(
                        text = row.getOrNull(i).orEmpty(),
                        fontFamily = AmberSans,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = tokens.ink2,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DataRankingListView(
    node: IntelligentUiComponentNode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = modifier.fillMaxWidth(),
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
        if (node.dataPoints.isNotEmpty()) {
            node.dataPoints.forEachIndexed { idx, pt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(tokens.surface2)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "#${idx + 1}",
                        fontFamily = AmberMono,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (idx == 0) accent else tokens.ink3,
                    )
                    Text(
                        text = pt.label,
                        fontFamily = AmberSans,
                        fontSize = 12.5.sp,
                        color = tokens.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${IntelligentUiCardState.formatNumber(pt.value)}${pt.unit.orEmpty()}",
                        fontFamily = AmberMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.ink2,
                    )
                }
            }
        } else {
            ListItemsView(node, numbered = true)
        }
    }
}

@Composable
internal fun DataBarChartView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val resolvedPoints = node.dataPoints.map { pt ->
        val v = state.boundNumericValue(
            document = document,
            valueFieldId = pt.valueFieldId ?: node.valueFieldId,
            fallback = pt.value,
            scale = pt.scale,
            offset = pt.offset,
        ) ?: pt.value
        pt.copy(value = v)
    }
    if (resolvedPoints.isEmpty()) return
    val maxVal = (resolvedPoints.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1.0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        node.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontFamily = AmberSans,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink,
            )
        }
        resolvedPoints.forEach { pt ->
            val ratio = (pt.value / maxVal).toFloat().coerceIn(0.04f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = pt.label,
                        fontFamily = AmberSans,
                        fontSize = 12.sp,
                        color = tokens.ink2,
                    )
                    Text(
                        text = "${IntelligentUiCardState.formatNumber(pt.value)}${pt.unit ?: node.unit.orEmpty()}",
                        fontFamily = AmberMono,
                        fontSize = 11.5.sp,
                        color = tokens.ink,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(tokens.line),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DataLineChartView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val resolvedPoints = node.dataPoints.map { pt ->
        val v = state.boundNumericValue(
            document = document,
            valueFieldId = pt.valueFieldId ?: node.valueFieldId,
            fallback = pt.value,
            scale = pt.scale,
            offset = pt.offset,
        ) ?: pt.value
        pt.copy(value = v)
    }
    if (resolvedPoints.isEmpty()) return
    val minVal = resolvedPoints.minOf { it.value }
    val maxVal = resolvedPoints.maxOf { it.value }
    val span = (maxVal - minVal).takeIf { it > 1e-6 } ?: 1.0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        node.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontFamily = AmberSans,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp),
        ) {
            val count = resolvedPoints.size
            val path = Path()
            resolvedPoints.forEachIndexed { idx, pt ->
                val x = if (count == 1) size.width / 2f else (idx.toFloat() / (count - 1)) * size.width
                val normY = ((pt.value - minVal) / span).toFloat().coerceIn(0f, 1f)
                val y = size.height - normY * (size.height - 12.dp.toPx()) - 6.dp.toPx()
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(color = accent, radius = 3.dp.toPx(), center = Offset(x, y))
            }
            if (count > 1) {
                drawPath(path = path, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            resolvedPoints.take(6).forEach { pt ->
                Text(
                    text = pt.label,
                    fontFamily = AmberMono,
                    fontSize = 10.sp,
                    color = tokens.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}
