package app.amber.feature.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiCardState
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun InputTextFieldNodeView(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    multiline: Boolean,
    numeric: Boolean,
    isDate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val value = state.fieldValues[node.id].orEmpty()
    val validationError = state.validationErrorFor(node)
    val borderColor = if (validationError != null) DangerToneColor else tokens.line2

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        InputFieldHeader(node)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (multiline) 72.dp else 38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.surface2)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = node.placeholder?.takeIf { it.isNotBlank() }
                                ?: if (isDate) "YYYY-MM-DD" else if (numeric) "请输入数值" else "请输入…",
                            fontFamily = if (numeric || isDate) AmberMono else AmberSans,
                            fontSize = 13.sp,
                            color = tokens.ink4,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { state.setFieldValue(node.id, it) },
                        singleLine = !multiline,
                        textStyle = TextStyle(
                            fontFamily = if (numeric || isDate) AmberMono else AmberSans,
                            fontSize = 13.sp,
                            color = tokens.ink,
                        ),
                        cursorBrush = SolidColor(tokens.accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                node.unit?.takeIf { it.isNotBlank() }?.let { unit ->
                    Text(
                        text = unit,
                        fontFamily = AmberMono,
                        fontSize = 11.5.sp,
                        color = tokens.ink3,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        if (validationError != null) {
            Text(
                text = validationError,
                fontFamily = AmberSans,
                fontSize = 11.5.sp,
                color = DangerToneColor,
            )
        }
    }
}

@Composable
internal fun InputSliderNodeView(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val min = node.min ?: 0.0
    val max = (node.max ?: 100.0).coerceAtLeast(min + 1.0)
    val currentVal = state.fieldValues[node.id]?.toDoubleOrNull()
        ?: node.numericValue
        ?: min
    val stepCount = node.step?.takeIf { it > 0.0 }?.let {
        (((max - min) / it).roundToInt() - 1).coerceIn(0, 100)
    } ?: 0

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = node.title?.takeIf { it.isNotBlank() } ?: node.id,
                fontFamily = AmberSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.ink,
            )
            Text(
                text = "${IntelligentUiCardState.formatNumber(currentVal)}${node.unit.orEmpty()}",
                fontFamily = AmberMono,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        Slider(
            value = currentVal.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { next ->
                state.setNumericFieldValue(
                    fieldId = node.id,
                    value = next.toDouble(),
                    min = min,
                    max = max,
                    step = node.step,
                )
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = stepCount,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = tokens.line2,
            ),
        )
    }
}

@Composable
internal fun InputStepperNodeView(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val step = node.step?.takeIf { it > 0.0 } ?: 1.0
    val current = state.fieldValues[node.id]?.toDoubleOrNull() ?: node.numericValue ?: (node.min ?: 0.0)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.surface2)
            .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.title?.takeIf { it.isNotBlank() } ?: node.id,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepperButton(
                symbol = "−",
                enabled = node.min == null || current - step >= node.min - 1e-9,
                onClick = { state.setNumericFieldValue(node.id, current - step, node.min, node.max, step) },
            )
            Text(
                text = "${IntelligentUiCardState.formatNumber(current)}${node.unit.orEmpty()}",
                fontFamily = AmberMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier.widthIn(min = 44.dp),
                textAlign = TextAlign.Center,
            )
            StepperButton(
                symbol = "+",
                enabled = node.max == null || current + step <= node.max + 1e-9,
                onClick = { state.setNumericFieldValue(node.id, current + step, node.min, node.max, step) },
            )
        }
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) tokens.surface else tokens.surface2)
            .border(1.dp, if (enabled) tokens.line2 else tokens.line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontFamily = AmberMono,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) tokens.ink else tokens.ink4,
        )
    }
}

@Composable
internal fun InputRatingNodeView(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val maxStars = (node.max?.roundToInt() ?: 5).coerceIn(3, 10)
    val current = (state.fieldValues[node.id]?.toDoubleOrNull() ?: node.numericValue ?: 4.0).roundToInt()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = node.title?.takeIf { it.isNotBlank() } ?: "评分",
            fontFamily = AmberSans,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (star in 1..maxStars) {
                val active = star <= current
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) accent.copy(alpha = 0.14f) else tokens.surface2)
                        .border(1.dp, if (active) accent else tokens.line, RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) {
                            state.setNumericFieldValue(node.id, star.toDouble(), 1.0, maxStars.toDouble(), 1.0)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "★",
                        fontSize = 13.sp,
                        color = if (active) accent else tokens.ink4,
                    )
                }
            }
        }
    }
}

@Composable
internal fun InputToggleCheckboxView(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    useSwitch: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val checked = state.fieldValues[node.id] == "true"
    val validationError = state.validationErrorFor(node)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.surface2)
                .border(1.dp, if (validationError != null) DangerToneColor else tokens.line, RoundedCornerShape(10.dp))
                .clickable(role = if (useSwitch) Role.Switch else Role.Checkbox) {
                    state.setFieldValue(node.id, (!checked).toString())
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = node.title?.takeIf { it.isNotBlank() } ?: node.body ?: node.id,
                        fontFamily = AmberSans,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.ink,
                    )
                    if (node.required) {
                        Text(
                            text = "*",
                            fontFamily = AmberMono,
                            fontSize = 12.sp,
                            color = DangerToneColor,
                        )
                    }
                }
                node.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                    Text(
                        text = sub,
                        fontFamily = AmberSans,
                        fontSize = 11.5.sp,
                        color = tokens.ink3,
                    )
                }
            }
            if (useSwitch) {
                Switch(
                    checked = checked,
                    onCheckedChange = { state.setFieldValue(node.id, it.toString()) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent,
                    ),
                )
            } else {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { state.setFieldValue(node.id, it.toString()) },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                )
            }
        }
        if (validationError != null) {
            Text(
                text = validationError,
                fontFamily = AmberSans,
                fontSize = 11.5.sp,
                color = DangerToneColor,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InputSingleChoiceView(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    segmented: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val selected = state.fieldValues[node.id].orEmpty()
    val validationError = state.validationErrorFor(node)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InputFieldHeader(node)
        if (segmented) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                node.options.forEach { opt ->
                    val isSelected = selected == opt.value || selected == opt.id
                    ChoiceChipPill(
                        label = opt.label,
                        selected = isSelected,
                        accent = accent,
                        onClick = { state.setFieldValue(node.id, opt.value) },
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.options.forEach { opt ->
                    val isSelected = selected == opt.value || selected == opt.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) accent.copy(alpha = 0.10f) else tokens.surface2)
                            .border(1.dp, if (isSelected) accent else tokens.line, RoundedCornerShape(10.dp))
                            .clickable(role = Role.RadioButton) { state.setFieldValue(node.id, opt.value) }
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, if (isSelected) accent else tokens.ink3, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(accent),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = opt.label,
                                fontFamily = AmberSans,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = tokens.ink,
                            )
                            opt.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Text(
                                    text = desc,
                                    fontFamily = AmberSans,
                                    fontSize = 11.5.sp,
                                    color = tokens.ink3,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (validationError != null) {
            Text(
                text = validationError,
                fontFamily = AmberSans,
                fontSize = 11.5.sp,
                color = DangerToneColor,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InputMultiChoiceView(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val selectedSet = state.multiFieldValues[node.id].orEmpty()
    val validationError = state.validationErrorFor(node)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InputFieldHeader(node)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            node.options.forEach { opt ->
                val isSelected = selectedSet.contains(opt.value) || selectedSet.contains(opt.id)
                ChoiceChipPill(
                    label = opt.label,
                    selected = isSelected,
                    accent = accent,
                    onClick = { state.toggleMultiOption(node.id, opt.value) },
                )
            }
        }
        if (validationError != null) {
            Text(
                text = validationError,
                fontFamily = AmberSans,
                fontSize = 11.5.sp,
                color = DangerToneColor,
            )
        }
    }
}

@Composable
private fun ChoiceChipPill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else tokens.surface2)
            .border(1.dp, if (selected) accent else tokens.line, RoundedCornerShape(8.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = AmberSans,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else tokens.ink2,
        )
    }
}

@Composable
private fun InputFieldHeader(node: IntelligentUiComponentNode) {
    val tokens = LocalAmberTokens.current
    val title = node.title?.takeIf { it.isNotBlank() } ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            fontFamily = AmberSans,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.ink,
        )
        if (node.required) {
            Text(
                text = "*",
                fontFamily = AmberMono,
                fontSize = 12.sp,
                color = DangerToneColor,
            )
        }
    }
}
