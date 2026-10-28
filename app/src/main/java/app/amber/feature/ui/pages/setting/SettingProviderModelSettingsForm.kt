package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Trash2
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.provider.BuiltInTools
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.registry.ModelRegistry
import app.amber.agent.R
import app.amber.feature.ui.pages.setting.components.LocalProviderFieldInCard
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderExpandableRow
import app.amber.feature.ui.pages.setting.components.ProviderFilterChip
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderListRow
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderRowDivider
import app.amber.feature.ui.pages.setting.components.ProviderRowSubtitle
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.feature.ui.pages.setting.components.ProviderSegOption
import app.amber.feature.ui.pages.setting.components.ProviderSegmented
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSheetHeader
import app.amber.feature.ui.pages.setting.components.ProviderSmallIconButton
import app.amber.feature.ui.pages.setting.components.ProviderSplitBar
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.pages.setting.components.ProviderToggle
import app.amber.feature.ui.pages.setting.components.providerDangerColor
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

private val providerModelJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

// Editable identification templates, not a full client fingerprint.
private val modelUserAgentPresets = listOf(
    "Cursor" to "Cursor",
    "OpenCode" to "opencode/1.2.27",
)

private fun parseContextWindowInput(input: String): Int? {
    val compact = input.trim()
        .replace(",", "")
        .replace("_", "")
        .replace(" ", "")
    if (compact.isBlank()) return null

    val multiplier = when {
        compact.endsWith("k", ignoreCase = true) -> 1_000.0
        compact.endsWith("m", ignoreCase = true) -> 1_000_000.0
        else -> 1.0
    }
    val number = compact
        .removeSuffix("K")
        .removeSuffix("k")
        .removeSuffix("M")
        .removeSuffix("m")
        .toDoubleOrNull()
        ?: return null
    return (number * multiplier)
        .coerceIn(1.0, Int.MAX_VALUE.toDouble())
        .toInt()
}

private fun Int.formatContextWindowInput(): String = when {
    this % 1_000_000 == 0 -> "${this / 1_000_000}M"
    this % 1_000 == 0 -> "${this / 1_000}K"
    else -> toString()
}

/* Model editor bottom sheet — title header + single scrolling form + pinned split bar. */
@Composable
internal fun ModelEditorSheet(
    model: Model,
    onModelChange: (Model) -> Unit,
    isEdit: Boolean,
    parentProvider: ProviderSetting?,
    @Suppress("UNUSED_PARAMETER") slug: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var contextWindowInvalid by remember { mutableStateOf(false) }
    val baseCanConfirm = if (isEdit) {
        model.displayName.isNotBlank()
    } else {
        model.modelId.isNotBlank() && model.displayName.isNotBlank()
    }
    val canConfirm = baseCanConfirm && !contextWindowInvalid

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = t.raised,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            ProviderSheetHeader(
                title = stringResource(
                    if (isEdit) R.string.provider_model_edit_title else R.string.provider_model_new_title
                ),
            )

            ModelSettingsForm(
                model = model,
                onModelChange = onModelChange,
                isEdit = isEdit,
                parentProvider = parentProvider,
                onContextWindowInvalidChange = { contextWindowInvalid = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            ProviderSplitBar(
                cancelText = stringResource(R.string.cancel),
                onCancel = onDismiss,
                confirmText = stringResource(
                    if (isEdit) R.string.confirm else R.string.setting_provider_page_add
                ),
                onConfirm = onConfirm,
                confirmEnabled = canConfirm,
            )
        }
    }
}

@Composable
internal fun ModelSettingsForm(
    model: Model,
    onModelChange: (Model) -> Unit,
    isEdit: Boolean,
    parentProvider: ProviderSetting? = null,
    onContextWindowInvalidChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val type = LocalAmberType.current
    var contextWindowInput by remember(model.id, model.modelId) {
        mutableStateOf(model.contextWindowTokens?.formatContextWindowInput().orEmpty())
    }
    // Invalid input keeps the previous value instead of silently dropping the
    // field; surface the problem and block confirm until it is fixed or cleared.
    val contextWindowInvalid = contextWindowInput.isNotBlank() && parseContextWindowInput(contextWindowInput) == null
    LaunchedEffect(contextWindowInvalid) {
        onContextWindowInvalidChange(contextWindowInvalid)
    }

    fun setModelId(id: String) {
        val inputModality = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id)
        val outputModality = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id)
        val abilities = ModelRegistry.MODEL_ABILITIES.getData(id)
        val contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id)
        onModelChange(
            model.copy(
                modelId = id,
                // A display name the user already typed (or renamed) must survive
                // typing the rest of the model id.
                displayName = if (model.displayName.isBlank() || model.displayName == model.modelId) id else model.displayName,
                inputModalities = inputModality,
                outputModalities = outputModality,
                abilities = abilities,
                contextWindowTokens = contextWindowTokens,
            )
        )
    }

    var toolsExpanded by remember { mutableStateOf(false) }
    var customExpanded by remember { mutableStateOf(false) }
    var overrideExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        // 基本
        ProviderSectionLabel(stringResource(R.string.provider_section_basic))
        ProviderCard(modifier = Modifier.fillMaxWidth()) {
            EditorFieldRow(label = "Model ID") {
                ProviderTextField(
                    value = model.modelId,
                    onValueChange = { if (!isEdit) setModelId(it.trim()) },
                    placeholder = stringResource(R.string.setting_provider_page_model_id_placeholder),
                    mono = true,
                    readOnly = isEdit,
                )
            }
            ProviderRowDivider()
            EditorFieldRow(label = stringResource(R.string.setting_provider_page_display_name)) {
                ProviderTextField(
                    value = model.displayName,
                    onValueChange = { onModelChange(model.copy(displayName = it)) },
                    placeholder = stringResource(R.string.setting_provider_page_model_display_name_placeholder),
                )
            }
            ProviderRowDivider()
            EditorFieldRow(label = stringResource(R.string.setting_provider_page_model_type)) {
                ProviderSegmented(
                    options = ModelType.entries.map { modelType ->
                        ProviderSegOption(
                            value = modelType,
                            label = stringResource(
                                when (modelType) {
                                    ModelType.CHAT -> R.string.setting_provider_page_chat_model
                                    ModelType.IMAGE -> R.string.setting_provider_page_image_model
                                    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                                }
                            ),
                        )
                    },
                    selected = model.type,
                    onSelected = { onModelChange(model.copy(type = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    height = 36.dp,
                )
            }
        }

        if (model.type == ModelType.CHAT) {
            // 能力：输入/输出模态 + 工具/推理，每个字段只出现一次。
            ProviderSectionLabel(stringResource(R.string.setting_provider_page_capabilities))
            ProviderCard(modifier = Modifier.fillMaxWidth()) {
                ModalityChipRow(
                    title = stringResource(R.string.provider_model_input),
                    options = listOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO),
                    selected = model.inputModalities,
                    onToggle = { modality ->
                        onModelChange(
                            model.copy(
                                inputModalities = model.inputModalities.withModality(
                                    modality,
                                    modality !in model.inputModalities,
                                )
                            )
                        )
                    },
                )
                ProviderRowDivider()
                ModalityChipRow(
                    title = stringResource(R.string.provider_model_output),
                    // AUDIO only when already set, so a legacy value stays removable.
                    options = listOfNotNull(
                        Modality.TEXT,
                        Modality.IMAGE,
                        Modality.AUDIO.takeIf { it in model.outputModalities },
                    ),
                    selected = model.outputModalities,
                    onToggle = { modality ->
                        onModelChange(
                            model.copy(
                                outputModalities = model.outputModalities.withModality(
                                    modality,
                                    modality !in model.outputModalities,
                                )
                            )
                        )
                    },
                )
                ProviderRowDivider()
                AbilityToggleRow(
                    title = stringResource(R.string.setting_provider_page_tool),
                    checked = ModelAbility.TOOL in model.abilities,
                    onCheckedChange = { enabled ->
                        onModelChange(model.copy(abilities = model.abilities.withAbility(ModelAbility.TOOL, enabled)))
                    },
                )
                ProviderRowDivider()
                AbilityToggleRow(
                    title = stringResource(R.string.setting_provider_page_reasoning),
                    checked = ModelAbility.REASONING in model.abilities,
                    onCheckedChange = { enabled ->
                        onModelChange(model.copy(abilities = model.abilities.withAbility(ModelAbility.REASONING, enabled)))
                    },
                )
            }

            ProviderCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                EditorFieldRow(label = stringResource(R.string.setting_provider_page_model_context_window)) {
                    ProviderTextField(
                        value = contextWindowInput,
                        onValueChange = {
                            contextWindowInput = it
                            val parsed = parseContextWindowInput(it)
                            if (it.isBlank() || parsed != null) {
                                onModelChange(model.copy(contextWindowTokens = parsed))
                            }
                        },
                        placeholder = stringResource(R.string.provider_model_context_auto),
                        mono = true,
                        isError = contextWindowInvalid,
                    )
                    if (contextWindowInvalid) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_model_context_window_invalid),
                            style = type.secondary.copy(fontSize = 12.sp),
                            color = providerDangerColor(),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // 高级
        ProviderSectionLabel(stringResource(R.string.provider_section_advanced))
        ProviderCard(modifier = Modifier.fillMaxWidth()) {
            val enabledToolNames = builtInToolEntries()
                .filter { it.tool in model.tools }
                .map { stringResource(it.titleRes) }
            ProviderExpandableRow(
                title = stringResource(R.string.setting_page_built_in_tools),
                summary = enabledToolNames.joinToString(" · ")
                    .ifBlank { stringResource(R.string.provider_model_tools_none) },
                expanded = toolsExpanded,
                onToggle = { toolsExpanded = !toolsExpanded },
            ) {
                BuiltInToolsSettings(
                    tools = model.tools,
                    onUpdateTools = { tools -> onModelChange(model.copy(tools = tools)) },
                )
            }
            ProviderRowDivider()
            ProviderExpandableRow(
                title = "${stringResource(R.string.provider_custom_headers)} / ${stringResource(R.string.provider_custom_bodies)}",
                summary = stringResource(R.string.provider_model_count_headers, model.customHeaders.size) +
                    " · " + stringResource(R.string.provider_model_count_bodies, model.customBodies.size),
                expanded = customExpanded,
                onToggle = { customExpanded = !customExpanded },
            ) {
                ModelCustomHeaders(
                    headers = model.customHeaders,
                    onUpdate = { headers -> onModelChange(model.copy(customHeaders = headers)) },
                )
                Spacer(Modifier.height(12.dp))
                ModelCustomBodies(
                    customBodies = model.customBodies,
                    onUpdate = { bodies -> onModelChange(model.copy(customBodies = bodies)) },
                )
            }
            ProviderRowDivider()
            ProviderExpandableRow(
                title = stringResource(R.string.setting_provider_page_provider_override),
                summary = model.providerOverwrite?.name ?: stringResource(R.string.provider_model_follow_provider),
                expanded = overrideExpanded,
                onToggle = { overrideExpanded = !overrideExpanded },
            ) {
                ProviderOverrideSettings(
                    providerOverride = model.providerOverwrite,
                    onUpdateProviderOverride = { providerOverride ->
                        onModelChange(model.copy(providerOverwrite = providerOverride))
                    },
                    parentProvider = parentProvider,
                )
            }
        }
    }
}

/** Label-above-value card row; the label turns accent while anything inside has focus. */
@Composable
private fun EditorFieldRow(
    label: String,
    content: @Composable () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = type.secondary.copy(fontSize = 12.sp),
            color = if (focused) t.accent else t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalProviderFieldInCard provides true) {
            content()
        }
    }
}

@Composable
private fun ModalityChipRow(
    title: String,
    options: List<Modality>,
    selected: List<Modality>,
    onToggle: (Modality) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            color = t.ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { modality ->
                ProviderFilterChip(
                    text = stringResource(
                        when (modality) {
                            Modality.TEXT -> R.string.setting_provider_page_text
                            Modality.IMAGE -> R.string.setting_provider_page_image
                            Modality.AUDIO -> R.string.setting_provider_page_audio
                        }
                    ),
                    selected = modality in selected,
                    onClick = { onToggle(modality) },
                )
            }
        }
    }
}

@Composable
private fun AbilityToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ProviderListRow(
        title = title,
        onClick = { onCheckedChange(!checked) },
        trailing = { ProviderToggle(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

private fun List<Modality>.withModality(modality: Modality, enabled: Boolean): List<Modality> =
    if (enabled) {
        if (modality in this) this else this + modality
    } else {
        filterNot { it == modality }
    }

private fun List<ModelAbility>.withAbility(ability: ModelAbility, enabled: Boolean): List<ModelAbility> =
    if (enabled) {
        if (ability in this) this else this + ability
    } else {
        filterNot { it == ability }
    }

/** Sans sub-group label inside an expanded advanced row: "Custom headers  2". */
@Composable
private fun ModelAdvancedSectionLabel(
    text: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f, fill = false),
            style = type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = t.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = count.toString(),
            style = type.secondary.copy(fontWeight = FontWeight.Medium),
            color = t.ink4,
        )
    }
}

/** Per-item caption + delete button above a header/body pair. */
@Composable
private fun ModelAdvancedItemHeader(
    text: String,
    deleteDescription: String,
    onDelete: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = type.secondary.copy(fontSize = 12.sp),
            color = t.ink3,
        )
        ProviderSmallIconButton(
            imageVector = Lucide.Trash2,
            contentDescription = deleteDescription,
            onClick = onDelete,
        )
    }
}

@Composable
private fun ModelCustomHeaders(
    headers: List<CustomHeader>,
    onUpdate: (List<CustomHeader>) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelAdvancedSectionLabel(
            text = stringResource(R.string.provider_custom_headers),
            count = headers.size,
        )

        Text(
            text = stringResource(R.string.provider_user_agent_presets),
            style = type.secondary,
            color = t.ink3,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modelUserAgentPresets.forEach { (label, value) ->
                ProviderFilterChip(
                    text = label,
                    selected = headers.any {
                        it.name.equals("User-Agent", ignoreCase = true) && it.value == value
                    },
                    onClick = {
                        onUpdate(
                            headers.filterNot { it.name.equals("User-Agent", ignoreCase = true) } +
                                CustomHeader("User-Agent", value)
                        )
                    },
                )
            }
        }

        headers.forEachIndexed { index, header ->
            var headerName by remember(header.name) { mutableStateOf(header.name) }
            var headerValue by remember(header.value) { mutableStateOf(header.value) }

            Column(Modifier.fillMaxWidth()) {
                ModelAdvancedItemHeader(
                    text = "Header ${index + 1}",
                    deleteDescription = stringResource(R.string.provider_delete_header),
                    onDelete = {
                        val updatedHeaders = headers.toMutableList()
                        updatedHeaders.removeAt(index)
                        onUpdate(updatedHeaders)
                    },
                )
                ProviderTextField(
                    value = headerName,
                    onValueChange = {
                        headerName = it
                        val updatedHeaders = headers.toMutableList()
                        updatedHeaders[index] = updatedHeaders[index].copy(name = it.trim())
                        onUpdate(updatedHeaders)
                    },
                    placeholder = stringResource(R.string.provider_header_name),
                    mono = true,
                )
                ProviderTextField(
                    value = headerValue,
                    onValueChange = {
                        headerValue = it
                        val updatedHeaders = headers.toMutableList()
                        updatedHeaders[index] = updatedHeaders[index].copy(value = it.trim())
                        onUpdate(updatedHeaders)
                    },
                    placeholder = stringResource(R.string.provider_header_value),
                    mono = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        ProviderGhostButton(
            text = stringResource(R.string.provider_add_header),
            imageVector = Lucide.Plus,
            onClick = {
                val updatedHeaders = headers.toMutableList()
                updatedHeaders.add(CustomHeader("", ""))
                onUpdate(updatedHeaders)
            },
        )
    }
}

@Composable
private fun ModelCustomBodies(
    customBodies: List<CustomBody>,
    onUpdate: (List<CustomBody>) -> Unit,
) {
    val context = LocalContext.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelAdvancedSectionLabel(
            text = stringResource(R.string.provider_custom_bodies),
            count = customBodies.size,
        )

        customBodies.forEachIndexed { index, body ->
            var bodyKey by remember(body.key) { mutableStateOf(body.key) }
            var bodyValueString by remember(body.value) {
                mutableStateOf(providerModelJson.encodeToString(JsonElement.serializer(), body.value))
            }
            var jsonParseError by remember { mutableStateOf<String?>(null) }

            Column(Modifier.fillMaxWidth()) {
                ModelAdvancedItemHeader(
                    text = "Body ${index + 1}",
                    deleteDescription = stringResource(R.string.provider_delete_body),
                    onDelete = {
                        val updatedBodies = customBodies.toMutableList()
                        updatedBodies.removeAt(index)
                        onUpdate(updatedBodies)
                    },
                )
                ProviderTextField(
                    value = bodyKey,
                    onValueChange = {
                        bodyKey = it
                        val updatedBodies = customBodies.toMutableList()
                        updatedBodies[index] = updatedBodies[index].copy(key = it.trim())
                        onUpdate(updatedBodies)
                    },
                    placeholder = stringResource(R.string.provider_body_key),
                    mono = true,
                )
                ProviderTextField(
                    value = bodyValueString,
                    onValueChange = { newString ->
                        bodyValueString = newString
                        try {
                            val newJsonValue = providerModelJson.parseToJsonElement(newString)
                            val updatedBodies = customBodies.toMutableList()
                            updatedBodies[index] = updatedBodies[index].copy(value = newJsonValue)
                            onUpdate(updatedBodies)
                            jsonParseError = null
                        } catch (e: Exception) {
                            jsonParseError = context.getString(
                                R.string.provider_invalid_json,
                                e.message?.take(100) ?: ""
                            )
                        }
                    },
                    placeholder = stringResource(R.string.provider_body_value),
                    mono = true,
                    singleLine = false,
                    minHeight = 98.dp,
                    isError = jsonParseError != null,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (jsonParseError != null) {
                    Text(
                        text = jsonParseError!!,
                        style = type.secondary.copy(fontSize = 12.sp),
                        color = providerDangerColor(),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        ProviderGhostButton(
            text = stringResource(R.string.provider_add_body),
            imageVector = Lucide.Plus,
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
        )
    }
}

private data class BuiltInToolEntry(
    val tool: BuiltInTools,
    val titleRes: Int,
    val descRes: Int,
)

private fun builtInToolEntries(): List<BuiltInToolEntry> = listOf(
    BuiltInToolEntry(
        BuiltInTools.Search,
        R.string.setting_page_built_in_tools_search,
        R.string.setting_page_built_in_tools_search_desc,
    ),
    BuiltInToolEntry(
        BuiltInTools.UrlContext,
        R.string.setting_page_built_in_tools_url_context,
        R.string.setting_page_built_in_tools_url_context_desc,
    ),
    BuiltInToolEntry(
        BuiltInTools.ImageGeneration,
        R.string.setting_page_built_in_tools_image_generation,
        R.string.setting_page_built_in_tools_image_generation_desc,
    ),
)

// Lives inside the form's scroll: no own verticalScroll / fillMaxSize (nested scroll would crash).
@Composable
private fun BuiltInToolsSettings(
    tools: Set<BuiltInTools>,
    onUpdateTools: (Set<BuiltInTools>) -> Unit
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_page_built_in_tools_desc),
            style = type.secondary,
            color = t.ink3,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        builtInToolEntries().forEach { entry ->
            val checked = entry.tool in tools
            val toggle: (Boolean) -> Unit = { enabled ->
                onUpdateTools(if (enabled) tools + entry.tool else tools - entry.tool)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(entry.titleRes),
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = t.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(entry.descRes),
                        style = type.secondary,
                        color = t.ink3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ProviderToggle(checked = checked, onCheckedChange = toggle)
            }
        }
    }
}

@Composable
private fun ProviderOverrideSettings(
    providerOverride: ProviderSetting?,
    onUpdateProviderOverride: (ProviderSetting?) -> Unit,
    parentProvider: ProviderSetting?
) {
    var showProviderConfig by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.setting_provider_page_provider_override_desc),
            style = type.secondary,
            color = t.ink3,
        )

        if (providerOverride != null) {
            ProviderListRow(
                title = providerOverride.name,
                subtitle = { ProviderRowSubtitle(stringResource(R.string.setting_provider_page_provider_override)) },
                leading = {
                    ProviderMonogram(
                        text = providerOverride.name.toProviderMonogram(),
                        size = 34.dp,
                    )
                },
                trailing = {
                    ProviderSmallIconButton(
                        imageVector = Lucide.Pencil,
                        contentDescription = stringResource(R.string.edit),
                        onClick = {
                            editingProvider = providerOverride
                            showProviderConfig = true
                        },
                    )
                    ProviderSmallIconButton(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.provider_model_override_remove),
                        onClick = { onUpdateProviderOverride(null) },
                    )
                },
            )
        } else {
            ProviderGhostButton(
                text = stringResource(R.string.setting_provider_page_add_provider_override),
                imageVector = Lucide.Plus,
                onClick = {
                    editingProvider = parentProvider?.copyProvider(
                        id = Uuid.random(),
                        builtIn = false,
                        models = emptyList(),
                        description = {},
                    )
                    showProviderConfig = true
                },
            )
        }

        if (showProviderConfig && editingProvider != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    showProviderConfig = false
                    editingProvider = null
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = t.raised,
                dragHandle = { ProviderSheetGrabber() },
            ) {
                var internalProvider by remember(editingProvider) { mutableStateOf(editingProvider!!) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f),
                ) {
                    ProviderSheetHeader(
                        title = stringResource(R.string.setting_provider_page_configure_provider_override),
                    )

                    ProviderConsole(
                        provider = internalProvider,
                        onEdit = { internalProvider = it },
                        onCommit = { internalProvider = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) { currentProvider ->
                        ProviderSplitBar(
                            cancelText = stringResource(R.string.cancel),
                            onCancel = {
                                showProviderConfig = false
                                editingProvider = null
                            },
                            confirmText = stringResource(R.string.setting_provider_page_save),
                            onConfirm = {
                                onUpdateProviderOverride(currentProvider)
                                showProviderConfig = false
                                editingProvider = null
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
