package app.amber.core.memory.model

/** Recall policy captured for one generation, without unrelated runtime settings. */
data class MemoryRecallSettings(
    val enableCoreMemory: Boolean,
    val enableShortTermMemory: Boolean,
    val enableLongTermMemory: Boolean,
    val recall: MemoryRecallSetting,
)
