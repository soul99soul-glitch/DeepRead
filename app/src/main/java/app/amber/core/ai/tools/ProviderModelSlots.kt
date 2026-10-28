package app.amber.core.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.prefs.SettingsAggregator
import kotlin.uuid.Uuid

internal data class ProviderModelSlot(
    val jsonKey: String,
    val expectedType: ModelType,
    val idOf: (Settings) -> Uuid,
)

internal fun providerModelSlotDefinitions(): List<ProviderModelSlot> = listOf(
    ProviderModelSlot("chat", ModelType.CHAT) { it.chatModelId },
    ProviderModelSlot("title", ModelType.CHAT) { it.titleModelId },
    ProviderModelSlot("ocr", ModelType.CHAT) { it.ocrModelId },
    ProviderModelSlot("compress", ModelType.CHAT) { it.compressModelId },
    ProviderModelSlot("suggestion", ModelType.CHAT) { it.suggestionModelId },
    ProviderModelSlot("image_generation", ModelType.IMAGE) { it.imageGenerationModelId },
)

internal fun settingsSetModelSlotTool(settingsStore: SettingsAggregator): Tool = Tool(
    name = TOOL_SETTINGS_SET_MODEL_SLOT,
    description = "Set one default model slot: chat, title, ocr, compress, suggestion, or image_generation. Pass model_id (a configured model's uuid) or model_ref (exact model_id/display name, or a unique substring match across ENABLED providers only). Multiple matches return candidates and nothing is written. A slot/type mismatch (e.g. an IMAGE-only model in chat) is rejected. Requires approval.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("slot", providerStringProp("Required. One of: chat, title, ocr, compress, suggestion, image_generation."))
                put("model_id", providerStringProp("Optional. Exact uuid of a configured model."))
                put("model_ref", providerStringProp("Optional. Exact model_id or display name; falls back to a unique substring match across enabled providers. Use when you do not know the uuid."))
            },
            required = listOf("slot")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        listOf(UIMessagePart.Text(runSetModelSlot(input.jsonObject, settingsStore).toString()))
    },
)

private suspend fun runSetModelSlot(
    root: JsonObject,
    settingsStore: SettingsAggregator,
): JsonObject {
    val settings = settingsStore.settingsFlow.value
    val slotKey = root["slot"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val slot = providerModelSlotDefinitions().firstOrNull { it.jsonKey == slotKey }
    if (slot == null) {
        return providerConfigFailure("slot must be one of ${providerModelSlotDefinitions().joinToString { it.jsonKey }}", written = false)
    }
    val modelIdRaw = root["model_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    val modelRef = root["model_ref"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    if (modelIdRaw == null && modelRef == null) {
        return providerConfigFailure("model_id or model_ref is required", written = false)
    }
    val resolution = resolveSlotModel(settings, slot, modelIdRaw, modelRef)
    if (resolution.error != null) return resolution.error
    val model = resolution.model!!
    val provider = resolution.provider!!
    settingsStore.update { current -> current.withSlot(slotKey, model.id) }
    return buildJsonObject {
        put("status", "ok")
        put("slot", slotKey)
        put("model_id", model.id.toString())
        put("model_label", model.displayName.ifBlank { model.modelId })
        put("provider_id", provider.id.toString())
        put("provider_name", provider.name)
        put("resolved", true)
    }
}

private class SlotResolution(
    val error: JsonObject? = null,
    val model: Model? = null,
    val provider: ProviderSetting? = null,
)

private fun resolveSlotModel(
    settings: Settings,
    slot: ProviderModelSlot,
    modelIdRaw: String?,
    modelRef: String?,
): SlotResolution {
    val candidates = mutableListOf<Pair<ProviderSetting, Model>>()
    val model: Model
    val provider: ProviderSetting
    if (modelIdRaw != null) {
        val id = runCatching { Uuid.parse(modelIdRaw) }.getOrNull()
        if (id == null) return SlotResolution(error = providerConfigFailure("model_id is not a valid uuid", written = false))
        model = settings.findModelById(id)
            ?: return SlotResolution(error = providerConfigFailure("model_id does not resolve to any configured model", written = false))
        provider = settings.providers.firstOrNull { p -> p.models.any { it.id == id } } ?: return SlotResolution(
            error = providerConfigFailure("model_id does not belong to any provider", written = false)
        )
    } else {
        val ref = modelRef!!.trim()
        // 只搜 enabled provider（iOS 契约：仅 enabled provider 参与 model_ref 解析）
        settings.providers.filter { it.enabled }.forEach { p ->
            p.models.forEach { m ->
                if (m.modelId == ref || m.displayName == ref) candidates += p to m
            }
        }
        if (candidates.size != 1) {
            settings.providers.filter { it.enabled }.forEach { p ->
                p.models.forEach { m ->
                    if (m.modelId.contains(ref, ignoreCase = true) || m.displayName.contains(ref, ignoreCase = true)) {
                        candidates += p to m
                    }
                }
            }
        }
        when {
            candidates.isEmpty() -> return SlotResolution(
                error = providerConfigFailure("no enabled provider model matches model_ref \"$ref\"", written = false)
            )
            candidates.size > 1 -> return SlotResolution(
                error = buildJsonObject {
                    put("status", "failed")
                    put("error", "model_ref \"$ref\" matches multiple models; pass model_id or a more specific ref")
                    put("written", false)
                    put("candidates", buildJsonArray {
                        candidates.distinctBy { it.second.id }.forEach { (p, m) ->
                            add(buildJsonObject {
                                put("model_id", m.id.toString())
                                put("model_ref", m.modelId)
                                put("label", m.displayName)
                                put("type", m.type.name.lowercase())
                                put("provider_id", p.id.toString())
                                put("provider_name", p.name)
                            })
                        }
                    })
                }
            )
        }
        val hit = candidates.first()
        provider = hit.first
        model = hit.second
    }
    if (model.type != slot.expectedType) {
        return SlotResolution(
            error = providerConfigFailure(
                "slot \"${slot.jsonKey}\" requires a ${slot.expectedType.name} model, but " +
                    "\"${model.displayName.ifBlank { model.modelId }}\" is ${model.type.name}",
                written = false,
            )
        )
    }
    return SlotResolution(model = model, provider = provider)
}

private fun Settings.withSlot(slotKey: String, modelId: Uuid): Settings = when (slotKey) {
    "chat" -> copy(chatModelId = modelId)
    "title" -> copy(titleModelId = modelId)
    "ocr" -> copy(ocrModelId = modelId)
    "compress" -> copy(compressModelId = modelId)
    "suggestion" -> copy(suggestionModelId = modelId)
    "image_generation" -> copy(imageGenerationModelId = modelId)
    else -> this
}
