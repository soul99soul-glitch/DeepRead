package app.amber.feature.ui.components.ai

import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.isCodexOAuthReviewModel
import kotlin.uuid.Uuid

private fun List<Model>.prioritizeInputModality(modality: Modality?): List<Model> =
    if (modality == null) {
        this
    } else {
        sortedWith(compareByDescending<Model> { modality in it.inputModalities }.thenBy { it.displayName })
    }

internal fun List<ProviderSetting>.pickerModelsByProvider(
    modelType: ModelType,
    preferredInputModality: Modality?,
): Map<Uuid, List<Model>> = associate { provider ->
    provider.id to provider.models.fastFilter {
        it.type == modelType && !provider.isHiddenCodexOAuthModel(it)
    }.prioritizeInputModality(preferredInputModality)
}

internal data class ModelWithProvider(
    val model: Model,
    val provider: ProviderSetting,
)

internal fun List<ProviderSetting>.buildModelProviderIndex(modelType: ModelType): Map<Uuid, ModelWithProvider> =
    buildMap {
        this@buildModelProviderIndex.fastForEach { provider ->
            provider.models.fastForEach { model ->
                if (model.type == modelType && !provider.isHiddenCodexOAuthModel(model)) {
                    putIfAbsent(model.id, ModelWithProvider(model, provider))
                }
            }
        }
    }

internal fun ProviderSetting.isCodexOAuthProvider(): Boolean {
    return this is ProviderSetting.OpenAI && authMode == OpenAIAuthMode.CODEX_OAUTH
}

private fun ProviderSetting.isHiddenCodexOAuthModel(model: Model): Boolean {
    return isCodexOAuthProvider() && model.isCodexOAuthReviewModel()
}

// V3: hasUsableAuth 已迁到 app.amber.ai.provider.hasUsableAuth (ai/ProviderSetting.kt),
// picker (这里) 和 data 层 fallback 共用同一份判定 (避免 picker 显示但 fallback 漏选
// 出无 auth 的模型). 调用方直接 import app.amber.ai.provider.hasUsableAuth.
