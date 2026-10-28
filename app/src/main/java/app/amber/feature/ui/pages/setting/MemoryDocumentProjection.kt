package app.amber.feature.ui.pages.setting

import app.amber.core.model.AssistantMemory
import app.amber.core.model.MemoryKind
import app.amber.core.model.MemoryScope
import java.util.Locale

internal data class MemoryDoc(
    val id: String,
    val fileName: String,
    /** Non-null when the document is a dream-synthesized topic file. */
    val topic: AssistantMemory?,
    /** Aggregate: the doc's records. Topic: resolved live member records. */
    val records: List<AssistantMemory>,
    val updatedAt: Long,
    val sizeBytes: Int,
    val preview: String,
)

internal fun buildMemoryDocs(
    coreMemories: List<AssistantMemory>,
    shortTermMemories: List<AssistantMemory>,
    longTermMemories: List<AssistantMemory>,
): List<MemoryDoc> {
    val byId = (coreMemories + shortTermMemories + longTermMemories).associateBy { it.id }
    val topics = (shortTermMemories + longTermMemories)
        .filter { it.kind == MemoryKind.TOPIC && !it.archived }
    // Members already filed under a topic must not reappear in the loose
    // per-kind documents — the doc list stays close to a partition.
    val topicMemberIds = topics.flatMap { it.memberIds }.toSet()
    val loose = (shortTermMemories + longTermMemories)
        .filter { it.kind != MemoryKind.TOPIC && !it.archived && it.id !in topicMemberIds }
    return buildList {
        val liveCore = coreMemories.filter { !it.archived }
        if (liveCore.isNotEmpty()) {
            add(memoryDoc("core", "core.md", null, liveCore))
        }
        // Two titles can normalize to the same slug — disambiguate with the id
        // so the rows stay distinguishable.
        val slugCounts = topics
            .groupingBy { memoryDocSlug(it.topicTitle ?: it.content).ifBlank { "topic" } }
            .eachCount()
        topics.sortedByDescending { it.updatedAt }.forEach { topic ->
            val members = topic.memberIds
                .distinct()
                .mapNotNull { byId[it] }
                .filter {
                    !it.archived &&
                        it.scope != MemoryScope.CORE &&
                        it.kind != MemoryKind.TOPIC
                }
            val slug = memoryDocSlug(topic.topicTitle ?: topic.content).ifBlank { "topic" }
            val fileName = if (slugCounts.getValue(slug) > 1) {
                "$slug-${topic.id}.md"
            } else {
                "$slug.md"
            }
            add(memoryDoc("topic-${topic.id}", fileName, topic, members))
        }
        MemoryKind.entries
            .filter { it != MemoryKind.TOPIC }
            .forEach { kind ->
                loose.filter { it.kind == kind }
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(memoryDoc("kind-${kind.wireName}", "${kind.wireName}.md", null, it)) }
            }
    }
}

private fun memoryDoc(
    id: String,
    fileName: String,
    topic: AssistantMemory?,
    records: List<AssistantMemory>,
): MemoryDoc {
    val sorted = records.sortedByDescending { it.updatedAt }
    val previewSource = topic?.content ?: sorted.firstOrNull()?.content.orEmpty()
    return MemoryDoc(
        id = id,
        fileName = fileName,
        topic = topic,
        records = sorted,
        updatedAt = listOfNotNull(topic?.updatedAt, sorted.firstOrNull()?.updatedAt).max(),
        sizeBytes = sorted.sumOf { it.content.toByteArray().size } +
            (topic?.content?.toByteArray()?.size ?: 0),
        preview = firstLineOf(previewSource),
    )
}

private fun memoryDocSlug(text: String): String =
    text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .take(48)

private fun firstLineOf(text: String): String =
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

internal fun formatMemorySize(bytes: Int): String =
    if (bytes >= 1024) {
        String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
    } else {
        "$bytes B"
    }
