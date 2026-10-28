package app.amber.core.ai.transformers

import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.core.model.AssistantRegex
import app.amber.core.model.Lorebook
import app.amber.core.model.PromptInjection
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import kotlin.uuid.Uuid

/** Values captured from the run's settings snapshot, shared by input and output transforms. */
data class TransformerSettings(
    val userNickname: String,
    val messageTemplate: String,
    val regexes: List<AssistantRegex>,
    val enabledModeInjectionIds: Set<Uuid>,
    val enabledLorebookIds: Set<Uuid>,
    val modeInjections: List<PromptInjection.ModeInjection>,
    val lorebooks: List<Lorebook>,
    val timeReminderEnabled: Boolean,
    val miniAppEnabled: Boolean,
    val ocr: OcrSettings,
)

/** Missing configuration is checked only when an image actually needs recognition. */
data class OcrSettings(
    val model: Model?,
    val provider: ProviderSetting?,
    val prompt: String,
)

fun Settings.toOcrSettings(): OcrSettings {
    val model = findModelById(ocrModelId)
    return OcrSettings(model, model?.findProvider(providers), ocrPrompt)
}

fun Settings.toTransformerSettings() = TransformerSettings(
    userNickname = displaySetting.userNickname,
    messageTemplate = messageTemplate,
    regexes = regexes,
    enabledModeInjectionIds = enabledModeInjectionIds,
    enabledLorebookIds = enabledLorebookIds,
    modeInjections = modeInjections,
    lorebooks = lorebooks,
    timeReminderEnabled = agentRuntime.enableTimeReminder,
    miniAppEnabled = agentRuntime.miniApp.enabled,
    ocr = toOcrSettings(),
)
