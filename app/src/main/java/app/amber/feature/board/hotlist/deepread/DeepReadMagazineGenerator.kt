package app.amber.feature.board.hotlist.deepread

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.agent.R
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.feature.subagent.toIsolatedSubAgentSettings
import app.amber.feature.tools.AgentToolSetFactory
import app.amber.feature.tools.DeepReadToolDescriptionContext
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.toGenerationSettings
import app.amber.core.ai.RunKernel
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.settings.Settings
import java.net.URI
import java.util.Locale

internal class DeepReadMagazineGenerator(
    private val appContext: Context,
    private val kernel: RunKernel,
    private val toolSetFactory: AgentToolSetFactory,
    private val playbookRepository: DeepReadPlaybookRepository,
    private val researchHarness: DeepReadResearchHarness,
    private val artifactPublisher: DeepReadArtifactPublisher,
) {
    private data class Session(
        val settings: Settings,
        val model: Model,
        val topicTitle: String,
        val seedUrl: String?,
        val writer: DeepReadSectionWriterTools,
        val locale: Locale,
        val preparation: Preparation,
        val runId: String?,
        val events: AgentEventWriter?,
    )

    internal data class Preparation(
        val hiddenSettings: Settings,
        val evidencePack: DeepReadEvidencePack,
        val evidenceRegistry: DeepReadEvidenceRegistry,
        val articlePlan: DeepReadArticlePlan,
        val playbookMarkdown: String,
    )

    suspend fun prepare(
        settings: Settings,
        model: Model,
        topicTitle: String,
        sources: List<DeepReadSource>,
        writer: DeepReadSectionWriterTools,
        planningPhase: DeepReadGenerationPhase,
        skipPlanning: Boolean,
        locale: Locale,
        runId: String?,
        events: AgentEventWriter?,
    ): Preparation {
        val evidencePack = researchHarness.buildEvidencePack(topicTitle, sources)
        val evidenceRegistry = DeepReadEvidenceRegistry()
        seedEvidenceRegistry(evidenceRegistry, sources, evidencePack, output = null)
        val hiddenSettings = DeepReadHiddenAssistantFactory.create(
            settings.toIsolatedSubAgentSettings(),
            locale = locale,
        )
        val playbook = playbookRepository.read()
        writer.markPhase(planningPhase)
        val articlePlan = if (skipPlanning) DeepReadArticlePlan() else generateArticlePlan(
            settings = hiddenSettings,
            model = model,
            topicTitle = topicTitle,
            evidencePack = evidencePack,
            playbookMarkdown = playbook.markdown,
            locale = locale,
            runId = runId,
            events = events,
        )
        return Preparation(hiddenSettings, evidencePack, evidenceRegistry, articlePlan, playbook.markdown)
    }

    suspend fun generate(
        topicId: String,
        settings: Settings,
        model: Model,
        topicTitle: String,
        seedUrl: String?,
        writer: DeepReadSectionWriterTools,
        locale: Locale,
        preparation: Preparation,
        stages: List<DeepReadGenerationStage>,
        wasSynthesisEntry: Boolean,
        propagateFailuresWithPartial: Boolean,
        runId: String?,
        events: AgentEventWriter?,
    ): Result<DeepReadOutput> {
        val context = Session(settings, model, topicTitle, seedUrl, writer, locale, preparation, runId, events)
        // A synthesis entry whose rerun resolved to the magazine (AUTO re-pick)
        // has no magazine body at all — generate every missing stage instead of
        // only the one the caller asked for.
        val effectiveStages = if (wasSynthesisEntry) {
            missingStages(context.writer.currentOutput()).ifEmpty { stages }
        } else {
            stages
        }

        return runCatching {
            context.writer.markRunning(effectiveStages)

            // OVERVIEW must settle before NARRATIVE/ANALYSIS so they can read
            // overview.summary and overview.key_entities from writer state when
            // building their prompts.
            if (DeepReadGenerationStage.OVERVIEW in effectiveStages) {
                runStageSupervisorLoop(context, DeepReadGenerationStage.OVERVIEW)
            }

            // NARRATIVE should settle before ANALYSIS. In practice the hidden
            // model is more reliable when analysis can read the narrative it is
            // meant to interpret, and it avoids two long generation/tool loops
            // competing at once on mobile networks.
            if (DeepReadGenerationStage.NARRATIVE in effectiveStages) {
                runStageSupervisorLoop(context, DeepReadGenerationStage.NARRATIVE)
            }
            if (DeepReadGenerationStage.ANALYSIS in effectiveStages) {
                runStageSupervisorLoop(context, DeepReadGenerationStage.ANALYSIS)
            }

            // EXTENDED_READING collates references and image_assets; needs the
            // prior stages settled.
            if (DeepReadGenerationStage.EXTENDED_READING in effectiveStages) {
                runStageSupervisorLoop(context, DeepReadGenerationStage.EXTENDED_READING)
            }

            val allTargetedReady = effectiveStages.all {
                context.writer.currentOutput().statusOf(it) == DeepReadSectionStatus.READY
            }
            val allSectionsReady = context.writer.currentOutput().sectionsReady()
            if (allTargetedReady && allSectionsReady) {
                val completed = finishIfPossible(context.writer, context.locale)
                artifactPublisher.publish(topicId, topicTitle, completed)
                return@runCatching completed
            }
            if (allTargetedReady) {
                context.writer.markPhase(DeepReadGenerationPhase.IDLE)
                return@runCatching context.writer.currentOutput()
            }

            // Anything that didn't reach READY gets a clear FAILED so the UI
            // shows an error instead of a perpetual loading skeleton.
            val missing = effectiveStages.filter {
                context.writer.currentOutput().statusOf(it) != DeepReadSectionStatus.READY
            }
            markMissingFailed(
                context.writer,
                missing,
                appContext.getString(R.string.deep_read_generation_failed),
            )
            context.writer.markPhase(DeepReadGenerationPhase.IDLE)
            context.writer.currentOutput()
        }.fold(
            onSuccess = { output ->
                if (output.hasAnyReadySection() || output.isComplete()) Result.success(output) else Result.failure(
                    IllegalStateException(firstFailure(output) ?: appContext.getString(R.string.deep_read_generation_failed))
                )
            },
            onFailure = { error ->
                // kotlin.runCatching swallows CancellationException, which would
                // otherwise unwind structured concurrency correctly. Re-throw it
                // so user-initiated cancel (UI navigation away, parent scope
                // cancel) is not silently turned into a Result.failure here.
                if (error is CancellationException) throw error
                Log.e(TAG, "deep read hidden run failed", error)
                if (propagateFailuresWithPartial) {
                    context.writer.markPhase(DeepReadGenerationPhase.IDLE)
                    return@fold Result.failure(error)
                }
                markMissingFailed(
                    writer = context.writer,
                    stages = stages,
                    message = error.message ?: error::class.simpleName.orEmpty(),
                )
                val output = context.writer.markPhase(DeepReadGenerationPhase.IDLE)
                if (output.hasAnyReadySection()) Result.success(output) else Result.failure(error)
            },
        )
    }

    private fun seedEvidenceRegistry(
        registry: DeepReadEvidenceRegistry,
        sources: List<DeepReadSource>,
        evidencePack: DeepReadEvidencePack,
        output: DeepReadOutput?,
    ) {
        sources.forEach { source ->
            registry.mark(source.url)
            source.evidenceText.takeIf { it.isNotBlank() }?.let { evidenceText ->
                registry.mark(source.url, evidenceText)
            }
        }
        evidencePack.cards.forEach { card ->
            card.evidenceExcerpt.takeIf { it.isNotBlank() }?.let { evidenceText ->
                registry.mark(card.source.url, evidenceText)
            }
        }
        output?.referencedEvidenceUrls().orEmpty().forEach(registry::mark)
    }

    private fun DeepReadOutput.referencedEvidenceUrls(): List<String> = buildList {
        references.forEach { add(it.url) }
        extendedReading.forEach { add(it.url) }
        heroImageUrl?.let(::add)
        imageAssets.forEach { add(it.url) }
        timeline.orEmpty().forEach { it.imageUrl?.let(::add) }
        corePoints.orEmpty().forEach { it.imageUrl?.let(::add) }
    }.filter { it.isHttpOrHttpsUrl() }

    private suspend fun runStageSupervisorLoop(
        context: Session,
        stage: DeepReadGenerationStage,
        coverageReport: DeepReadCoverageReport? = null,
    ) {
        val singleStage = listOf(stage)
        val writer = context.writer
        val writerToolsForStage = writer.tools(stages = setOf(stage), locale = context.locale)
            .filter { it.name == stage.deepReadWriterToolName() }
        val stageTimeoutMs = collectRunTimeoutFor(stage)
        val stageTools = toolSetFactory.forDeepRead(
            settings = context.settings,
            writerTools = writerToolsForStage,
            descriptionContext = DeepReadToolDescriptionContext(
                stageLabel = stage.deepReadLocalizedLabel(context.locale),
                writerToolName = stage.deepReadWriterToolName(),
                stageTimeoutSeconds = stageTimeoutMs.toWholeSeconds(),
            ),
        )
            .map { it.withEvidenceRecording(context.preparation.evidenceRegistry) }
        val stageWriterToolNamesSet = writerToolsForStage.map { it.name }.toSet()
        val stageEvidence = context.preparation.evidencePack.cardsFor(
            stage = stage,
            plan = context.preparation.articlePlan,
            forceIncludeSourceIds = coverageReport?.missingRequiredSourceIds.orEmpty(),
        )
        val stageSources = stageEvidence.map { it.source }
        val scrapeWebAvailable = stageTools.any { it.name == "scrape_web" }
        var messages = listOf(
            UIMessage.user(
                DeepReadMagazinePrompt.buildPrompt(
                    topicTitle = context.topicTitle,
                    stages = singleStage,
                    existingOutput = writer.currentOutput(),
                    seedUrl = context.seedUrl,
                    scrapeWebAvailable = scrapeWebAvailable,
                    evidencePack = context.preparation.evidencePack,
                    articlePlan = context.preparation.articlePlan,
                    stageEvidence = stageEvidence,
                    targetStage = stage,
                    stageTimeoutMs = stageTimeoutMs,
                    playbookMarkdown = context.preparation.playbookMarkdown,
                    coverageReport = coverageReport,
                    locale = context.locale,
                )
            )
        )
        try {
            val initialRequiredWrites = writer.requiredWriteCount
            repeat(MAX_SUPERVISOR_PASSES) { pass ->
                val beforeWrites = writer.requiredWriteCount
                messages = withTimeout(stageTimeoutMs) {
                    collectRun(
                        settings = context.preparation.hiddenSettings,
                        model = context.model,
                        messages = messages,
                        tools = stageTools,
                        writerToolNames = stageWriterToolNamesSet,
                        statusLabel = if (context.locale.isDeepReadChineseLocale()) {
                            "深度阅读 ${stage.deepReadLocalizedLabel(context.locale)}"
                        } else {
                            "Deep Read ${stage.deepReadLocalizedLabel(context.locale)}"
                        },
                        runId = context.runId,
                        events = context.events,
                    )
                }
                val stageReady = writer.currentOutput().statusOf(stage) == DeepReadSectionStatus.READY
                val supplementWritten = coverageReport == null ||
                    writer.requiredWriteCount > initialRequiredWrites
                if (stageReady && supplementWritten) {
                    return
                }
                if (writer.requiredWriteCount == beforeWrites) {
                    messages = messages + UIMessage.user(DeepReadMagazinePrompt.buildWriterReminder(singleStage, pass, context.locale))
                }
            }
            val needsFallback = writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY ||
                (coverageReport != null && writer.requiredWriteCount == initialRequiredWrites)
            if (needsFallback) {
                tryFallbackAfterStageFailure(
                    writer = writer,
                    stage = stage,
                    messages = messages,
                    sources = stageSources,
                    reason = "missing writer tool",
                    allowReadyRewrite = coverageReport != null,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "deep read stage ${stage.label} timed out", timeout)
            val recovered = tryFallbackAfterStageFailure(
                writer = writer,
                stage = stage,
                messages = messages,
                sources = stageSources,
                reason = "timeout",
                allowReadyRewrite = coverageReport != null,
            )
            if (!recovered && writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY) {
                writer.markFailed(stage, timeoutFailureMessage(stage, stageTimeoutMs))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (other: Throwable) {
            Log.e(TAG, "deep read stage ${stage.label} failed", other)
            val recovered = tryFallbackAfterStageFailure(
                writer = writer,
                stage = stage,
                messages = messages,
                sources = stageSources,
                reason = if (other.isDeepReadTimeoutLike()) "provider timeout" else "failure",
                allowReadyRewrite = coverageReport != null,
            )
            if (!recovered && writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY) {
                writer.markFailed(
                    stage,
                    stageFailureMessage(stage, other, collectRunTimeoutFor(stage)),
                )
            }
        }
    }

    private suspend fun collectRun(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        tools: List<app.amber.ai.core.Tool>,
        writerToolNames: Set<String>,
        statusLabel: String,
        runId: String? = null,
        events: AgentEventWriter? = null,
    ): List<UIMessage> {
        var latest = messages
        suspend fun runWith(stream: Boolean) {
            kernel.run(
                GenerationRunSession(
                    settings = settings.copy(streamOutput = stream).toGenerationSettings(model),
                model = model,
                messages = messages,
                memories = emptyList(),
                tools = tools,
                maxSteps = MAX_GENERATION_STEPS,
                processingStatus = MutableStateFlow(statusLabel),
                autoApproveTools = true,
                autoApproveHighRiskTools = false,
                autoApprovedToolNames = writerToolNames,
                invocationContext = ToolInvocationContext.Normal,
                conversation = null,
                // Step 5: thread the run scope's identity and event writer the
                // same way SubAgentRunner does; the kernel's durable-path gate
                // decides whether anything is emitted.
                runId = runId,
                toolLifecycleEvents = events,
                // Step 6: v1 default codifies no new restriction; narrowing
                // arrives via sub-agent payloads.
                executionPolicy = app.amber.feature.runtime.ExecutionPolicy.permissive(),
                    inputTransformers = emptyList(),
                    outputTransformers = emptyList(),
                ),
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) latest = chunk.messages
            }
        }
        try {
            runWith(stream = settings.streamOutput)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error.isDeepReadTimeoutLike()) throw error
            Log.w(TAG, "deep read stream failed; retrying through non-stream generator path", error)
            latest = messages
            runWith(stream = false)
        }
        return latest
    }

    private suspend fun generateArticlePlan(
        settings: Settings,
        model: Model,
        topicTitle: String,
        evidencePack: DeepReadEvidencePack,
        playbookMarkdown: String,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale,
    ): DeepReadArticlePlan {
        val fallback = researchHarness.fallbackPlan(topicTitle, evidencePack, locale)
        val messages = runCatching {
            collectRun(
                settings = settings.copy(streamOutput = false),
                model = model,
                messages = listOf(
                    UIMessage.user(
                        researchHarness.buildPlanningPrompt(
                            topicTitle = topicTitle,
                            pack = evidencePack,
                            playbookMarkdown = playbookMarkdown,
                            locale = locale,
                        )
                    )
                ),
                tools = emptyList(),
                writerToolNames = emptySet(),
                statusLabel = if (locale.isDeepReadChineseLocale()) "深度阅读 结构规划" else "Deep Read structure planning",
                runId = runId,
                events = events,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "deep read planning fell back to local plan", error)
            emptyList()
        }
        val parsed = researchHarness.parsePlan(messages.latestAssistantText())
        return researchHarness.normalizePlan(
            parsed = parsed ?: fallback,
            topicTitle = topicTitle,
            pack = evidencePack,
            locale = locale,
        )
    }

    private suspend fun finishIfPossible(writer: DeepReadSectionWriterTools, locale: Locale): DeepReadOutput {
        val finish = writer.tools(locale = locale).first { it.name == "deep_read_finish" }
        finish.execute(kotlinx.serialization.json.buildJsonObject { })
        return writer.currentOutput()
    }

    private suspend fun markMissingFailed(
        writer: DeepReadSectionWriterTools,
        stages: List<DeepReadGenerationStage>,
        message: String,
    ): DeepReadOutput {
        var current = writer.currentOutput()
        stages.forEach { stage ->
            val status = current.statusOf(stage)
            if (status != DeepReadSectionStatus.READY && status != DeepReadSectionStatus.FAILED) {
                current = writer.markFailed(stage, message)
            }
        }
        return current
    }

    private suspend fun tryFallbackAfterStageFailure(
        writer: DeepReadSectionWriterTools,
        stage: DeepReadGenerationStage,
        messages: List<UIMessage>,
        sources: List<DeepReadSource>,
        reason: String,
        allowReadyRewrite: Boolean,
    ): Boolean {
        if (writer.currentOutput().statusOf(stage) == DeepReadSectionStatus.READY) return true
        // writeFallbackSection is now links-only (spec A1): it merges real source links but
        // never synthesizes占位 body and never marks the section READY. So a failing stage
        // can no longer "recover" via fallback — we only preserve the genuine links, then
        // the caller (runStageSupervisorLoop timeout/other branches) marks the stage FAILED.
        // Returning false here is by design: markFailed on an already-failed stage is a no-op.
        return try {
            writer.writeFallbackSection(
                stage = stage,
                assistantText = messages.latestAssistantText(),
                sources = sources,
                allowReadyRewrite = allowReadyRewrite,
            )
            Log.i(TAG, "deep read stage ${stage.label} preserved source links after $reason")
            false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(TAG, "deep read stage ${stage.label} link preservation failed after $reason", error)
            false
        }
    }

    private fun Tool.withEvidenceRecording(registry: DeepReadEvidenceRegistry): Tool {
        if (name !in EVIDENCE_RECORDING_TOOL_NAMES) return this
        val original = this
        return copy(
            execute = { input ->
                val parts = original.execute(input)
                registry.markToolResult(original.name, input, parts)
                parts
            }
        )
    }

    private fun firstFailure(output: DeepReadOutput): String? =
        DeepReadGenerationStage.entries.firstNotNullOfOrNull { output.errorOf(it) }

    private fun collectRunTimeoutFor(stage: DeepReadGenerationStage): Long = when (stage) {
        DeepReadGenerationStage.OVERVIEW -> 90_000L
        DeepReadGenerationStage.NARRATIVE -> 110_000L
        DeepReadGenerationStage.ANALYSIS -> 150_000L
        DeepReadGenerationStage.EXTENDED_READING -> 90_000L
    }

    private fun Long.toWholeSeconds(): Long = (this / 1_000L).coerceAtLeast(1L)

    private fun timeoutFailureMessage(stage: DeepReadGenerationStage, timeoutMs: Long): String =
        "${appContext.getString(R.string.notification_live_status_timed_out)}: " +
            "${stageLabel(stage)} (${timeoutMs.toWholeSeconds()}s)"

    private fun stageFailureMessage(
        stage: DeepReadGenerationStage,
        error: Throwable,
        timeoutMs: Long,
    ): String =
        if (error.isDeepReadTimeoutLike()) {
            timeoutFailureMessage(stage, timeoutMs)
        } else {
            "${stageFailureLabel(stage)}: ${error.message ?: error::class.simpleName.orEmpty()}"
        }

    private fun stageLabel(stage: DeepReadGenerationStage): String = appContext.getString(
        when (stage) {
            DeepReadGenerationStage.OVERVIEW -> R.string.deep_read_overview
            DeepReadGenerationStage.NARRATIVE -> R.string.deep_read_narrative
            DeepReadGenerationStage.ANALYSIS -> R.string.deep_read_analysis
            DeepReadGenerationStage.EXTENDED_READING -> R.string.deep_read_reading
        }
    )

    private fun stageFailureLabel(stage: DeepReadGenerationStage): String = appContext.getString(
        when (stage) {
            DeepReadGenerationStage.OVERVIEW -> R.string.deep_read_overview_failed
            DeepReadGenerationStage.NARRATIVE -> R.string.deep_read_narrative_failed
            DeepReadGenerationStage.ANALYSIS -> R.string.deep_read_analysis_failed
            DeepReadGenerationStage.EXTENDED_READING -> R.string.deep_read_reading_failed
        }
    )

    private fun Throwable.isDeepReadTimeoutLike(): Boolean {
        if (this is TimeoutCancellationException) return true
        val haystack = listOfNotNull(
            message,
            localizedMessage,
            this::class.simpleName,
            this::class.qualifiedName,
        ).joinToString(" ")
        return DEEP_READ_TIMEOUT_MARKERS.any { marker -> haystack.contains(marker, ignoreCase = true) }
    }

    private fun missingStages(output: DeepReadOutput): List<DeepReadGenerationStage> =
        DeepReadGenerationStage.entries.filter { output.statusOf(it) != DeepReadSectionStatus.READY }

    private fun String.isHttpOrHttpsUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }

    private companion object {
        const val TAG = "DeepReadAgentRunManager"
        const val MAX_GENERATION_STEPS = 32
        const val MAX_SUPERVISOR_PASSES = 2
        val EVIDENCE_RECORDING_TOOL_NAMES = setOf("search_web", "scrape_web")
        val DEEP_READ_TIMEOUT_MARKERS = listOf("timed out", "timeout", "SocketTimeout", "deadline exceeded", "Read timed out")
    }
}

private fun List<UIMessage>.latestAssistantText(): String =
    asReversed()
        .firstOrNull { it.role == app.amber.ai.core.MessageRole.ASSISTANT }
        ?.toText()
        .orEmpty()
