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
import app.amber.core.settings.prefs.SettingsAggregator
import kotlin.uuid.Uuid

internal fun providerRefreshModelsTool(
    settingsStore: SettingsAggregator,
    modelFetcher: ProviderModelFetcher,
): Tool = Tool(
    name = TOOL_PROVIDER_REFRESH_MODELS,
    description = "Fetch the model catalog for ONE provider through the provider's own API (same path as the settings UI) and write it back. mode=merge (default) keeps all existing models and appends new ones; mode=replace_chat replaces only the CHAT models and keeps IMAGE/EMBEDDING entries. On failure (invalid key, network) existing models are never modified. Requires approval; the API key is read from the secret store and never included in the result.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", providerStringProp("Provider id (preferred). Optional when provider_name resolves uniquely."))
                put("provider_name", providerStringProp("Provider name for unique resolution; when ambiguous the result returns candidates and nothing is written."))
                put("mode", providerStringProp("merge (default) or replace_chat."))
            }
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        listOf(UIMessagePart.Text(runRefreshModels(input.jsonObject, settingsStore, modelFetcher).toString()))
    },
)

private suspend fun runRefreshModels(
    root: JsonObject,
    settingsStore: SettingsAggregator,
    modelFetcher: ProviderModelFetcher,
): JsonObject {
    val settings = settingsStore.settingsFlow.value
    val providerId = root["provider_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    val providerName = root["provider_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    val mode = root["mode"]?.jsonPrimitive?.contentOrNull?.ifBlank { "merge" } ?: "merge"
    if (mode != "merge" && mode != "replace_chat") {
        return providerConfigFailure("mode must be \"merge\" or \"replace_chat\"", written = false)
    }
    if (providerId == null && providerName == null) {
        return providerConfigFailure("provider_id or provider_name is required", written = false)
    }
    val resolved = resolveProvider(settings, providerId, providerName)
    if (resolved.error != null) return resolved.error
    val provider = resolved.provider!!
    val requestSource = provider.catalogSource()
    val fetched = try {
        modelFetcher.fetch(provider)
    } catch (error: Throwable) {
        return refreshFailureJson(error)
    }
    // 在写事务内计算 merge 结果（以真正落盘的快照为准）
    val outcome = arrayOfNulls<JsonObject>(1)
    var deleted = false
    var sourceChanged = false
    settingsStore.update { current ->
        val existing = current.providers.find { it.id == provider.id }
        if (existing == null) {
            // provider 已被并发删除：不写、不报 ok，走 provider-not-found 结构化错误
            deleted = true
            return@update current
        }
        if (existing.catalogSource() != requestSource) {
            sourceChanged = true
            return@update current
        }
        val fresh = fetched.distinctBy { it.modelId }
        val merged = when (mode) {
            "replace_chat" -> existing.models.filter { it.type != ModelType.CHAT } + fresh
            else -> mergeModelLists(existing.models, fresh)
        }
        outcome[0] = refreshOutcomeJson(mode, existing.models, fresh, merged, existing.name)
        current.copy(
            providers = current.providers.map {
                if (it.id == provider.id) it.copyModels(merged) else it
            }
        )
    }
    if (deleted) {
        return providerConfigError("provider not found for id ${provider.id}")
    }
    if (sourceChanged) {
        return providerConfigFailure("provider configuration changed while fetching models; refresh again", written = false)
    }
    return buildJsonObject {
        put("status", "ok")
        put("provider_id", provider.id.toString())
        outcome[0]?.forEach { (key, value) -> put(key, value) }
    }
}

private class ResolveResult(
    val error: JsonObject? = null,
    val provider: ProviderSetting? = null,
)

/** id 优先；name 精确唯一 → 子串唯一 → 否则候选错误。 */
private fun resolveProvider(
    settings: Settings,
    providerId: String?,
    providerName: String?,
): ResolveResult {
    if (providerId != null) {
        val id = runCatching { Uuid.parse(providerId) }.getOrNull()
        if (id == null) return ResolveResult(error = providerConfigError("provider_id is not a valid uuid"))
        val provider = settings.providers.find { it.id == id }
        return if (provider != null) {
            ResolveResult(provider = provider)
        } else {
            ResolveResult(error = providerConfigError("provider not found for id $providerId"))
        }
    }
    val exact = settings.providers.filter { it.name == providerName }
    val fuzzy = settings.providers.filter { it.name.contains(providerName.orEmpty(), ignoreCase = true) }
    return when {
        exact.size == 1 -> ResolveResult(provider = exact.first())
        exact.size > 1 -> ResolveResult(error = providerConfigError("provider name \"$providerName\" is ambiguous", candidates = exact))
        fuzzy.size == 1 -> ResolveResult(provider = fuzzy.first())
        fuzzy.size > 1 -> ResolveResult(error = providerConfigError("provider name \"$providerName\" is ambiguous", candidates = fuzzy))
        else -> ResolveResult(error = providerConfigError("provider not found for name \"$providerName\""))
    }
}

private fun refreshOutcomeJson(
    mode: String,
    existingModels: List<Model>,
    fetched: List<Model>,
    merged: List<Model>,
    providerName: String,
): JsonObject = buildJsonObject {
    val existingIds = existingModels.map { it.modelId }.toSet()
    val added = fetched.count { it.modelId !in existingIds }
    val kept = when (mode) {
        "replace_chat" -> existingModels.count { it.type != ModelType.CHAT }
        else -> existingModels.size
    }
    put("mode", mode)
    put("provider_name", providerName)
    put("added", added)
    put("kept", kept)
    put("total_models", merged.size)
    put("total_chat_models", merged.count { it.type == ModelType.CHAT })
    put("sample_labels", buildJsonArray {
        fetched.take(20).forEach { model ->
            add(model.displayName.ifBlank { model.modelId })
        }
    })
}

private fun mergeModelLists(existing: List<Model>, fetched: List<Model>): List<Model> {
    val existingIds = existing.map { it.modelId }.toSet()
    return (existing + fetched.filter { it.modelId !in existingIds }).distinctBy { it.id }
}

private fun refreshFailureJson(error: Throwable): JsonObject {
    val statusCode = error.message?.let { msg ->
        Regex("\\b(4\\d{2}|5\\d{2})\\b").find(msg)?.groupValues?.get(1)?.toIntOrNull()
    }
    val message = when (statusCode) {
        401, 403 -> "API key is invalid or lacks model-list permission (HTTP $statusCode). Fix the key via provider_config_apply, or check the base_url. Existing models were NOT modified."
        else -> "Failed to fetch models: ${error.message ?: error.toString()}. Existing models were NOT modified."
    }
    return buildJsonObject {
        put("status", "failed")
        put("error", message)
        put("models_modified", false)
        put("retryable", statusCode == null || statusCode >= 500)
    }
}

private fun ProviderSetting.copyModels(models: List<Model>): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(models = models)
    is ProviderSetting.Google -> copy(models = models)
    is ProviderSetting.Claude -> copy(models = models)
}

/** Exact values used by listModels; presentation and the editable catalog are not request identity. */
private data class ProviderCatalogSource(
    val id: Uuid,
    val type: String,
    val baseUrl: String,
    val apiKey: String,
    val authMode: String,
    val vertexAI: Boolean = false,
    val useServiceAccount: Boolean = false,
    val privateKey: String = "",
    val serviceAccountEmail: String = "",
    val location: String = "",
    val projectId: String = "",
)

private fun ProviderSetting.catalogSource(): ProviderCatalogSource = when (this) {
    is ProviderSetting.OpenAI -> ProviderCatalogSource(id, "openai", baseUrl, apiKey, authMode.name)
    is ProviderSetting.Claude -> ProviderCatalogSource(id, "claude", baseUrl, apiKey, "api_key")
    is ProviderSetting.Google -> ProviderCatalogSource(
        id, "google", baseUrl, apiKey, authMode.name,
        vertexAI, useServiceAccount, privateKey, serviceAccountEmail, location, projectId,
    )
}
