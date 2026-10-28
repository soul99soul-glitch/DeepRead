package app.amber.feature.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlin.uuid.Uuid

@Composable
fun ProviderAccordionModelPicker(
    currentModel: Uuid?,
    providers: List<ProviderSetting>,
    modelType: ModelType,
    onSelect: (Model) -> Unit,
    modifier: Modifier = Modifier,
    preferredInputModality: Modality? = null,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
    dense: Boolean = false,
) {
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val providerFontSize = if (dense) 13.sp else 14.sp
    val modelFontSize = if (dense) 13.sp else 14.sp
    val contextFontSize = if (dense) 11.sp else 11.5.sp
    val providerVerticalPadding = if (dense) 7.dp else 11.dp
    val modelVerticalPadding = if (dense) 6.dp else 9.dp
    val trailingSlotWidth = 42.dp
    val trailingEndPadding = if (dense) 0.dp else 6.dp
    val providerStartPadding = if (dense) 30.dp else 14.dp
    val modelStartPadding = if (dense) 54.dp else 28.dp
    val typeFilteredModelsByProvider = remember(providers, modelType, preferredInputModality) {
        providers.pickerModelsByProvider(modelType, preferredInputModality)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (clearLabel != null && onClear != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClear)
                    .padding(
                        start = providerStartPadding,
                        end = trailingEndPadding,
                        top = providerVerticalPadding,
                        bottom = providerVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = clearLabel,
                    style = LocalAmberType.current.meta.copy(
                        fontSize = providerFontSize,
                        fontWeight = if (currentModel == null) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (currentModel == null) chatTheme.accent else tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        providers.fastForEach { providerSetting ->
            val groupModels = typeFilteredModelsByProvider[providerSetting.id].orEmpty()
            if (groupModels.isEmpty()) return@fastForEach

            val providerActive = groupModels.fastAny { it.id == currentModel }
            var expanded by remember(providerSetting.id) {
                mutableStateOf(providerActive)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(
                        start = providerStartPadding,
                        end = trailingEndPadding,
                        top = providerVerticalPadding,
                        bottom = providerVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = providerSetting.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = providerFontSize,
                        fontWeight = if (providerActive) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (providerActive) chatTheme.accent else tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "−" else "+",
                    style = LocalAmberType.current.meta.copy(
                        fontSize = if (dense) 16.sp else 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = tokens.ink3,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(trailingSlotWidth),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    groupModels.fastForEach { model ->
                        ModelItemRow(
                            model = model,
                            providerSetting = providerSetting,
                            isActive = model.id == currentModel,
                            onSelect = onSelect,
                            onDismiss = {},
                            modelFontSize = modelFontSize,
                            contextFontSize = contextFontSize,
                            verticalPadding = modelVerticalPadding,
                            endPadding = trailingEndPadding,
                            startPadding = modelStartPadding,
                            contextWidth = trailingSlotWidth,
                        )
                    }
                }
            }
        }
    }
}
