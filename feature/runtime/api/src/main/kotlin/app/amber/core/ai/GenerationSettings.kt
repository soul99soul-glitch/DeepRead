package app.amber.core.ai

import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.core.ai.transformers.TransformerSettings
import app.amber.core.ai.transformers.toTransformerSettings
import app.amber.core.context.ContextGenerationSettings
import app.amber.core.memory.model.MemoryRecallSettings
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.ResolvedSessionDefaults
import app.amber.core.settings.Settings
import app.amber.core.settings.SpeculativeToolExecutionSetting
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveSessionDefaults
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.settings.toCompactPolicy

/** One run's model-specific values; none of the consumers re-read live settings. */
data class GenerationSettings(
    val provider: ProviderSetting?,
    val defaults: ResolvedSessionDefaults,
    val temperature: Float?,
    val topP: Float?,
    val customHeaders: List<CustomHeader>,
    val customBodies: List<CustomBody>,
    val systemPrompt: String,
    val agentSoulMarkdown: String,
    val enableRecentChatsReference: Boolean,
    val generativeUi: GenerativeUiSetting,
    val streamOutput: Boolean,
    val speculativeToolExecution: SpeculativeToolExecutionSetting,
    val generationRetry: GenerationRetrySetting,
    val transformers: TransformerSettings,
    val memory: MemoryRecallSettings,
    val context: ContextGenerationSettings,
)

/** Map only after the caller chooses its model and applies its existing domain overrides. */
fun Settings.toGenerationSettings(model: Model) = GenerationSettings(
    provider = model.findProvider(providers),
    defaults = resolveSessionDefaults(model),
    temperature = temperature,
    topP = topP,
    customHeaders = customHeaders,
    customBodies = customBodies,
    systemPrompt = systemPrompt,
    agentSoulMarkdown = agentRuntime.agentSoulMarkdown,
    enableRecentChatsReference = agentRuntime.enableRecentChatsReference,
    generativeUi = agentRuntime.generativeUi,
    streamOutput = streamOutput,
    speculativeToolExecution = agentRuntime.speculativeToolExecution,
    generationRetry = agentRuntime.generationRetry,
    transformers = toTransformerSettings(),
    memory = toMemoryRecallSettings(),
    context = toContextGenerationSettings(model),
)

fun Settings.toMemoryRecallSettings() = MemoryRecallSettings(
    enableCoreMemory = agentRuntime.enableCoreMemory,
    enableShortTermMemory = agentRuntime.enableShortTermMemory,
    enableLongTermMemory = agentRuntime.enableLongTermMemory,
    recall = agentRuntime.memoryRecall,
)

/** Auto-compaction uses the current run model; manual callers can select their task model. */
fun Settings.toContextGenerationSettings(model: Model? = null): ContextGenerationSettings {
    val compressionModel = model ?: resolveTaskChatModel(compressModelId)
    return ContextGenerationSettings(
        policy = agentRuntime.contextCompaction.toCompactPolicy(),
        compressionModel = compressionModel,
        compressionProvider = compressionModel?.findProvider(providers),
        compressPrompt = compressPrompt,
    )
}
