package app.amber.core.service.tools

import android.content.Context
import app.amber.ai.core.Tool
import app.amber.core.ai.tools.buildMemoryTools
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.settings.Settings
import app.amber.core.utils.appLocale
import app.amber.feature.runtime.CapabilityPermissionStore
import kotlinx.serialization.json.Json

/** Binds memory tools to repository CAS and their run's optional audit sink. */
class MemoryRunToolFactory(
    private val context: Context,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val capabilityPermissionStore: CapabilityPermissionStore? = null,
) {
    fun createTools(settings: Settings, runId: String? = null, casAuditEnabled: Boolean = false): List<Tool> {
        if (
            !settings.agentRuntime.enableCoreMemory &&
            !settings.agentRuntime.enableShortTermMemory &&
            !settings.agentRuntime.enableLongTermMemory
        ) {
            return emptyList()
        }
        val ledger = capabilityPermissionStore?.takeIf { casAuditEnabled }
            ?.let { app.amber.feature.runtime.CapabilityBackedCasLedger(it) }
        return buildMemoryTools(
            json = json,
            onList = { scope ->
                // memory_list is the model's view of the world: archived and
                // expired records stay out so their revisions can't be edited
                // and dead facts don't steer the answer.
                val now = System.currentTimeMillis()
                when (scope) {
                    "core" -> memoryRepository.getGlobalMemories()
                    "short_term" -> memoryRepository.getShortTermMemories()
                    "long_term" -> memoryRepository.getLongTermMemories()
                    else -> emptyList()
                }.filter {
                    !it.archived && it.expiresAt.let { exp -> exp == null || exp > now }
                }
            },
            onCreation = { request ->
                val finalContent = if (request.source.isNullOrBlank()) {
                    request.content
                } else {
                    "${request.content}\nSource: ${request.source}"
                }
                memoryRepository.addMemory(
                    scope = request.scope,
                    kind = request.kind,
                    content = finalContent,
                    assistantId = memoryBucket(request.scope.wireName),
                    sourceConversationId = request.sourceConversationId,
                    sourceMessageIds = request.sourceMessageIds,
                    expiresAt = request.expiresAt,
                    confidence = request.confidence,
                    sourceRunId = request.sourceRunId,
                    sourceTrigger = request.sourceTrigger,
                ).let {
                    app.amber.core.model.AssistantMemory(
                        id = it.id,
                        content = it.content,
                        scope = it.scope,
                        kind = it.kind,
                        expiresAt = it.expiresAt,
                        confidence = it.confidence,
                        pinned = it.pinned,
                        archived = it.archived,
                        revision = it.revision,
                        sourceRunId = it.sourceRunId,
                        sourceTrigger = it.sourceTrigger,
                        sourceConversationId = it.sourceConversationId,
                        sourceMessageIds = it.sourceMessageIds,
                        supersedesIds = it.supersedesIds,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                        lastUsedAt = it.lastUsedAt,
                    )
                }
            },
            // P2-06 CAS: the tool layer rejects a missing revision
            // (revision_required) before this callback; the SQL CAS is the
            // authoritative guard at the repository boundary.
            onUpdateCas = { id, content, expectedRevision ->
                val revision = requireNotNull(expectedRevision) {
                    "Memory update requires a revision; call memory_list first (CAS: the update " +
                        "must be bound to the version the user saw)"
                }
                memoryRepository.updateContentCas(
                    id = id,
                    content = content,
                    expectedRevision = revision,
                    sourceRunId = runId,
                    sourceTrigger = MemoryRepository.TRIGGER_TOOL,
                )
            },
            onDeleteCas = { id, expectedRevision ->
                val revision = requireNotNull(expectedRevision) {
                    "Memory delete requires the revision from memory_list (CAS: the delete " +
                        "must be bound to the version the user saw)"
                }
                memoryRepository.deleteMemoryCas(id, revision)
            },
            onAudit = { entry ->
                ledger?.recordApproval(entry)
            },
            runIdProvider = { runId },
            locale = context.appLocale(),
        )
    }

    private fun memoryBucket(scope: String): String = when (scope) {
        "core" -> MemoryRepository.GLOBAL_MEMORY_ID
        "short_term" -> MemoryRepository.SHORT_TERM_MEMORY_ID
        "long_term" -> MemoryRepository.LONG_TERM_MEMORY_ID
        else -> MemoryRepository.LONG_TERM_MEMORY_ID
    }
}
