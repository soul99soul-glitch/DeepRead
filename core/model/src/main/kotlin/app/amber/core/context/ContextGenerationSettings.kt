package app.amber.core.context

import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting

/** Nullable configuration stays inert until a compaction is actually required. */
data class ContextGenerationSettings(
    val policy: CompactPolicy,
    val compressionModel: Model?,
    val compressionProvider: ProviderSetting?,
    val compressPrompt: String,
)
