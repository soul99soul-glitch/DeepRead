package app.amber.feature.board.hotlist.deepread

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderCatalog
import app.amber.agent.AppScope
import app.amber.agent.R
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.boardRequestBodies
import app.amber.feature.board.boardRequestHeaders
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.deepread.template.DeepReadSynthesisTemplate
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.feature.tools.AgentToolSetFactory
import app.amber.core.ai.RunKernel
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

class DeepReadAgentRunManager(
    private val appContext: Context,
    private val settingsStore: SettingsAggregator,
    kernel: RunKernel,
    private val hotListRepository: HotListRepository,
    toolSetFactory: AgentToolSetFactory,
    private val sourcePrefetcher: DeepReadSourcePrefetcher,
    playbookRepository: DeepReadPlaybookRepository,
    researchHarness: DeepReadResearchHarness,
    private val appScope: AppScope,
    providerCatalog: ProviderCatalog,
    artifactRepository: ArtifactRepository? = null,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val backgroundRuns = ConcurrentHashMap.newKeySet<String>()

    private val artifactPublisher = DeepReadArtifactPublisher(artifactRepository, hotListRepository)
    private val magazineGenerator = DeepReadMagazineGenerator(
        appContext, kernel, toolSetFactory, playbookRepository, researchHarness, artifactPublisher,
    )
    private val synthesisGenerator = DeepReadSynthesisGenerator(
        appContext, providerCatalog, hotListRepository, artifactPublisher,
    )

    private data class PreparedRun(
        val settings: Settings,
        val model: Model,
        val writer: DeepReadSectionWriterTools,
        val templateId: String,
        val sources: List<DeepReadSource>,
        val magazinePreparation: DeepReadMagazineGenerator.Preparation,
        val wasSynthesisEntry: Boolean,
        val synthesisChoice: DeepReadSynthesisTemplate?,
    )

    suspend fun runPreview(
        topicTitle: String,
        seedUrl: String,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadOutput> {
        val normalizedSeedUrl = seedUrl.takeIf { it.isHttpOrHttpsUrl() }
            ?: return Result.failure(
                IllegalArgumentException(appContext.getString(R.string.deep_read_demo_failed))
            )
        val topicId = previewTopicId(normalizedSeedUrl)
        return withCapturedRestoreWriteContext {
            topicMutex(topicId).withLock {
                try {
                    hotListRepository.clearDeepRead(topicId)
                    generateStages(
                        topicId = topicId,
                        topicTitle = topicTitle,
                        stages = DeepReadGenerationStage.entries,
                        seedUrl = normalizedSeedUrl,
                        force = true,
                        locale = locale,
                    )
                } finally {
                    withContext(NonCancellable) {
                        runCatching { hotListRepository.clearDeepRead(topicId) }
                    }
                }
            }
        }
    }

    suspend fun run(
        topicId: String,
        topicTitle: String,
        force: Boolean = false,
        seedUrl: String? = null,
        deferMissingStages: Boolean = true,
        propagateFailuresWithPartial: Boolean = false,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadOutput> = withCapturedRestoreWriteContext {
        topicMutex(topicId).withLock {
            val cached = if (force) null else fresh(topicId, topicTitle, seedUrl)
            if (cached?.isComplete() == true) {
                artifactPublisher.publish(topicId, topicTitle, cached)
                return@withLock Result.success(cached)
            }

            val missing = missingStages(cached ?: DeepReadOutput())
            if (shouldDeferDeepReadMissingStages(force, cached, missing, deferMissingStages)) {
                scheduleBackgroundFill(topicId, topicTitle, missing, seedUrl, locale)
                return@withLock Result.success(cached ?: DeepReadOutput())
            }
            if (!force && cached != null && cached.sectionsReady()) {
                val completed = cached.copy(
                    generationPhase = DeepReadGenerationPhase.COMPLETE,
                    generationComplete = true,
                )
                hotListRepository.saveDeepRead(
                    topicId = topicId,
                    title = topicTitle,
                    output = completed,
                    ttlDays = currentDeepReadTtlDays(),
                    sourceUrl = seedUrl,
                )
                artifactPublisher.publish(topicId, topicTitle, completed)
                return@withLock Result.success(completed)
            }

            val stagesToGenerate = when {
                missing.isNotEmpty() -> missing
                else -> DeepReadGenerationStage.entries
            }

            generateStages(
                topicId = topicId,
                topicTitle = topicTitle,
                stages = stagesToGenerate,
                seedUrl = seedUrl,
                force = force,
                propagateFailuresWithPartial = propagateFailuresWithPartial,
                runId = runId,
                events = events,
                locale = locale,
            )
        }
    }

    suspend fun runSection(
        topicId: String,
        topicTitle: String,
        stage: DeepReadGenerationStage,
        seedUrl: String? = null,
        propagateFailuresWithPartial: Boolean = false,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadOutput> = withCapturedRestoreWriteContext {
        topicMutex(topicId).withLock {
            markSectionRunning(topicId, topicTitle, seedUrl, stage)
            generateStages(
                topicId = topicId,
                topicTitle = topicTitle,
                stages = listOf(stage),
                seedUrl = seedUrl,
                markCollecting = false,
                planningPhase = DeepReadGenerationPhase.WRITING,
                propagateFailuresWithPartial = propagateFailuresWithPartial,
                runId = runId,
                events = events,
                locale = locale,
            )
        }
    }

    private suspend fun scheduleBackgroundFill(
        topicId: String,
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        seedUrl: String?,
        locale: Locale,
    ) {
        val key = "$topicId:${stages.joinToString(",") { it.name }}"
        if (!backgroundRuns.add(key)) return
        appScope.launch(captureRestoreWriteContext()) {
            try {
                topicMutex(topicId).withLock {
                    val current = fresh(topicId, topicTitle, seedUrl) ?: DeepReadOutput()
                    val stillMissing = stages.filter { current.statusOf(it) != DeepReadSectionStatus.READY }
                    // Explicit force = false: background fill should reuse the
                    // prefetch cache populated by the primary run instead of
                    // re-paying the 36s budget. This is the biggest measurable
                    // win from Phase E.
                    if (stillMissing.isNotEmpty()) {
                        generateStages(
                            topicId = topicId,
                            topicTitle = topicTitle,
                            stages = stillMissing,
                            seedUrl = seedUrl,
                            force = false,
                            locale = locale,
                        )
                    }
                }
            } finally {
                backgroundRuns.remove(key)
            }
        }
    }

    private suspend fun captureRestoreWriteContext(): CoroutineContext {
        currentCoroutineContext()[SyncRestoreWriteEpoch]?.let { return it }
        val gate = restoreWriteGate ?: return EmptyCoroutineContext
        // A direct/manual run without an owner token becomes one before the
        // model call. This only takes the short writer path and releases it
        // before any network or model work starts.
        gate.withWriter { Unit }
        return SyncRestoreWriteEpoch(gate.currentEpoch())
    }

    private suspend fun <T> withCapturedRestoreWriteContext(block: suspend () -> T): T =
        withContext(captureRestoreWriteContext()) { block() }

    private suspend fun markSectionRunning(
        topicId: String,
        topicTitle: String,
        seedUrl: String?,
        stage: DeepReadGenerationStage,
    ) {
        val current = fresh(topicId, topicTitle, seedUrl) ?: DeepReadOutput()
        val next = current.withSectionRetryRunning(stage)
        if (next == current) return
        hotListRepository.saveDeepRead(
            topicId = topicId,
            title = topicTitle,
            output = next,
            ttlDays = currentDeepReadTtlDays(),
            sourceUrl = seedUrl,
        )
    }

    private suspend fun generateStages(
        topicId: String,
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        seedUrl: String?,
        force: Boolean = false,
        markCollecting: Boolean = true,
        planningPhase: DeepReadGenerationPhase = DeepReadGenerationPhase.PLANNING,
        propagateFailuresWithPartial: Boolean = false,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadOutput> {
        val prepared = prepareRun(
            topicId = topicId,
            topicTitle = topicTitle,
            seedUrl = seedUrl,
            force = force,
            markCollecting = markCollecting,
            planningPhase = planningPhase,
            runId = runId,
            events = events,
            locale = locale,
        ).getOrElse { error ->
            if (error is CancellationException) throw error
            return Result.failure(error)
        }

        return prepared.synthesisChoice?.let { chosen ->
            synthesisGenerator.generate(
                topicId = topicId,
                topicTitle = topicTitle,
                seedUrl = seedUrl,
                settings = prepared.settings,
                model = prepared.model,
                writer = prepared.writer,
                sources = prepared.sources,
                templateId = prepared.templateId,
                template = chosen,
            )
        } ?: magazineGenerator.generate(
            topicId = topicId,
            settings = prepared.settings,
            model = prepared.model,
            topicTitle = topicTitle,
            seedUrl = seedUrl,
            writer = prepared.writer,
            locale = locale,
            preparation = prepared.magazinePreparation,
            stages = stages,
            wasSynthesisEntry = prepared.wasSynthesisEntry,
            propagateFailuresWithPartial = propagateFailuresWithPartial,
            runId = runId,
            events = events,
        )
    }

    private suspend fun prepareRun(
        topicId: String,
        topicTitle: String,
        seedUrl: String?,
        force: Boolean,
        markCollecting: Boolean,
        planningPhase: DeepReadGenerationPhase,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale,
    ): Result<PreparedRun> {
        return try {
            val settings = settingsStore.settingsFlow.filterNot { it.init }.first().copy(enableWebSearch = true)
            val resolvedModel = resolveModel(settings)
                ?: return Result.failure(
                    IllegalStateException(appContext.getString(R.string.setting_model_page_follow_chat_model_unavailable))
                )
            if (ModelAbility.TOOL !in resolvedModel.abilities) {
                return Result.failure(IllegalStateException(appContext.getString(R.string.tools_warning)))
            }
            val model = resolvedModel.withBoardRequestOptions(settings)

            // Resolve the effective template before `force` wipes the row so an
            // AUTO entry that already resolved to the magazine keeps that
            // choice (iOS `hasStructuredBody` guard in resume).
            val existingEntry = hotListRepository.getDeepReadEntry(topicId)
            val templateId = if (topicId.startsWith(PREVIEW_TOPIC_PREFIX)) {
                // Workbench previews always exercise the magazine pipeline —
                // its section output is what custom HTML templates consume.
                DeepReadTemplateIds.COMPOSE_MAGAZINE
            } else {
                DeepReadTemplateIds.normalize(
                    existingEntry?.templateId?.takeIf { it.isNotBlank() }
                        ?: hotListRepository.getHotTopic(topicId)?.deepReadTemplateId?.takeIf { it.isNotBlank() }
                        ?: settings.agentRuntime.todayBoard.deepReadTemplateId,
                )
            }
            val synthesisTemplate = DeepReadSynthesisTemplate.fromWireId(templateId)
            // iOS `hasStructuredBody` = auto already resolved to the classic
            // magazine: a magazine body with NO template article. An auto entry
            // that produced a synthesis re-picks on rerun.
            val autoAlreadyStructured = synthesisTemplate == DeepReadSynthesisTemplate.AUTO &&
                existingEntry?.structuredJson.isNullOrBlank() &&
                existingEntry?.output?.withInferredSectionStates()?.hasAnyReadySection() == true

            if (force) {
                // Restart content after admission succeeds. Keep the bookmarked row
                // and source URL, and create a direct empty row to block title fallback.
                hotListRepository.saveDeepRead(
                    topicId = topicId,
                    title = topicTitle,
                    output = DeepReadOutput(),
                    ttlDays = settings.agentRuntime.todayBoard.deepReadCacheTtlDays,
                    sourceUrl = seedUrl,
                    templateId = templateId,
                )
            }
            if (markCollecting) {
                hotListRepository.saveDeepRead(
                    topicId = topicId,
                    title = topicTitle,
                    output = (fresh(topicId, topicTitle, seedUrl, templateId) ?: DeepReadOutput()).copy(
                        generationPhase = DeepReadGenerationPhase.COLLECTING,
                        generationComplete = false,
                    ),
                    ttlDays = settings.agentRuntime.todayBoard.deepReadCacheTtlDays,
                    sourceUrl = seedUrl,
                    templateId = templateId,
                )
            }
            val prefetchedSources = sourcePrefetcher.collect(
                topicId = topicId,
                topicTitle = topicTitle,
                seedUrl = seedUrl,
                force = force,
                locale = locale,
            )
            if (prefetchedSources.isEmpty()) {
                val message = appContext.getString(R.string.deep_read_sources_unavailable)
                Log.w(TAG, "deep read prefetch returned no sources for topic=$topicId")
                if (markCollecting) {
                    hotListRepository.saveDeepRead(
                        topicId = topicId,
                        title = topicTitle,
                        output = (fresh(topicId, topicTitle, seedUrl, templateId) ?: DeepReadOutput()).copy(
                            generationPhase = DeepReadGenerationPhase.IDLE,
                        ),
                        ttlDays = settings.agentRuntime.todayBoard.deepReadCacheTtlDays,
                        sourceUrl = seedUrl,
                        templateId = templateId,
                    )
                }
                return Result.failure(IllegalStateException(message))
            }
            Log.i(TAG, "deep read prefetch ready: ${prefetchedSources.size} sources for topic=$topicId")

            // Generation templates (iOS DeepReadSynthesisTemplate) replace the
            // magazine section pipeline with a single structured synthesis call.
            // AUTO first asks the model which template fits the sources.
            var synthesisChoice: DeepReadSynthesisTemplate? = null
            if (synthesisTemplate != null && !autoAlreadyStructured) {
                synthesisChoice = if (synthesisTemplate == DeepReadSynthesisTemplate.AUTO) {
                    synthesisGenerator.pickTemplate(
                        settings = settings,
                        model = model,
                        topicTitle = topicTitle,
                        sources = prefetchedSources,
                    )
                } else {
                    synthesisTemplate
                }
            }
            if (synthesisChoice == null) {
                // A magazine-family run must not inherit a stale template
                // article (e.g. an earlier AUTO pick that has since re-rolled).
                hotListRepository.clearDeepReadStructuredJson(topicId)
            }

            val writer = DeepReadSectionWriterTools(
                repository = hotListRepository,
                topicId = topicId,
                topicTitle = topicTitle,
                imageCandidates = prefetchedSources.flatMap { it.imageCandidates },
                allowTitleFallback = seedUrl.isNullOrBlank(),
                ttlDays = settings.agentRuntime.todayBoard.deepReadCacheTtlDays,
            )
            val magazinePreparation = magazineGenerator.prepare(
                settings = settings,
                model = model,
                topicTitle = topicTitle,
                sources = prefetchedSources,
                writer = writer,
                planningPhase = planningPhase,
                skipPlanning = synthesisChoice != null,
                locale = locale,
                runId = runId,
                events = events,
            )
            Result.success(
                PreparedRun(
                    settings = settings,
                    model = model,
                    writer = writer,
                    templateId = templateId,
                    sources = prefetchedSources,
                    magazinePreparation = magazinePreparation,
                    wasSynthesisEntry = DeepReadTemplateIds.isSynthesis(existingEntry?.templateId) ||
                        !existingEntry?.structuredJson.isNullOrBlank(),
                    synthesisChoice = synthesisChoice,
                )
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun currentDeepReadTtlDays(): Int =
        settingsStore.settingsFlow.filterNot { it.init }.first()
            .agentRuntime.todayBoard.deepReadCacheTtlDays

    private fun resolveModel(settings: Settings): Model? {
        val boardModelId = settings.agentRuntime.todayBoard.boardModelId
        val specific = boardModelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
        return specific ?: settings.resolveTaskChatModel(settings.chatModelId)
    }

    private fun Model.withBoardRequestOptions(settings: Settings): Model = copy(
        customHeaders = boardRequestHeaders(settings.providers),
        customBodies = boardRequestBodies(settings.providers),
        tools = emptySet(),
    )

    private suspend fun fresh(
        topicId: String,
        topicTitle: String,
        seedUrl: String?,
        requestedTemplateId: String? = null,
    ): DeepReadOutput? {
        val output = if (seedUrl.isNullOrBlank()) {
            hotListRepository.materializeFreshDeepRead(
                topicId = topicId,
                title = topicTitle,
                requestedTemplateId = requestedTemplateId ?: resolveStoredTemplateId(topicId),
            )
        } else {
            hotListRepository.getFreshDeepRead(topicId)
        }
        return output?.withInferredSectionStates()
    }

    /** Same precedence as prepareRun: entry → hot topic → board default. */
    private suspend fun resolveStoredTemplateId(topicId: String): String =
        hotListRepository.getDeepReadEntry(topicId)?.templateId?.takeIf { it.isNotBlank() }
            ?: hotListRepository.getHotTopic(topicId)?.deepReadTemplateId?.takeIf { it.isNotBlank() }
            ?: settingsStore.settingsFlow.filterNot { it.init }.first()
                .agentRuntime.todayBoard.deepReadTemplateId

    private fun topicMutex(topicId: String): Mutex = mutexes.getOrPut(topicId) { Mutex() }

    private fun missingStages(output: DeepReadOutput): List<DeepReadGenerationStage> =
        DeepReadGenerationStage.entries.filter { output.statusOf(it) != DeepReadSectionStatus.READY }

    private fun previewTopicId(seedUrl: String): String =
        PREVIEW_TOPIC_PREFIX + MessageDigest.getInstance("SHA-256")
            .digest(seedUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun String.isHttpOrHttpsUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }

    companion object {
        private const val TAG = "DeepReadAgentRunManager"
        private const val PREVIEW_TOPIC_PREFIX = "template-demo-"
    }
}

internal fun shouldDeferDeepReadMissingStages(
    force: Boolean,
    cached: DeepReadOutput?,
    missing: List<DeepReadGenerationStage>,
    deferMissingStages: Boolean,
): Boolean =
    deferMissingStages && !force && cached?.hasAnyReadySection() == true && missing.isNotEmpty()

internal fun DeepReadOutput.withSectionRetryRunning(stage: DeepReadGenerationStage): DeepReadOutput {
    if (statusOf(stage) == DeepReadSectionStatus.READY) return this
    return copy(
        generationPhase = DeepReadGenerationPhase.WRITING,
        generationComplete = false,
    ).withSectionStatus(stage, DeepReadSectionStatus.RUNNING)
}
