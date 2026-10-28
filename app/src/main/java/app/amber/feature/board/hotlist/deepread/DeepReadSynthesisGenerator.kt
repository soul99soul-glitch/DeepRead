package app.amber.feature.board.hotlist.deepread

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.agent.R
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.deepread.template.DeepReadSynthesisTemplate
import app.amber.feature.board.hotlist.deepread.template.DeepReadSynthesisWriter
import app.amber.feature.board.hotlist.deepread.template.displayNameZh
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import java.io.IOException

internal class DeepReadSynthesisGenerator(
    private val appContext: Context,
    private val providerCatalog: ProviderCatalog,
    private val hotListRepository: HotListRepository,
    private val artifactPublisher: DeepReadArtifactPublisher,
) {
    suspend fun pickTemplate(
        settings: Settings,
        model: Model,
        topicTitle: String,
        sources: List<DeepReadSource>,
    ): DeepReadSynthesisTemplate? {
        val numbered = DeepReadSynthesisWriter.numbered(sources)
        if (numbered.isEmpty()) return null
        // Pick failures — including a timed-out pick — degrade to the classic
        // magazine pipeline rather than failing the run (iOS pick == nil).
        val raw = try {
            synthesizeTemplateText(
                settings = settings,
                model = model,
                prompt = DeepReadSynthesisWriter.pickPrompt(topicTitle, numbered),
                timeoutMs = SYNTHESIS_PICK_TIMEOUT_MS,
            )
        } catch (timeout: SynthesisCallTimeout) {
            null
        } ?: return null
        return DeepReadSynthesisWriter.parsePick(raw)
    }

    /**
     * Synthesis provider call exceeded its deadline. Deliberately NOT a
     * CancellationException so it lands as a retryable worker failure
     * ("timeout" marker) instead of a silent cancelled run.
     */
    private class SynthesisCallTimeout(timeoutMs: Long) :
        Exception("deep read synthesis timed out after ${timeoutMs}ms")

    /** Direct provider call for synthesis templates — iOS `synthesizeJSON` parity. */
    private suspend fun synthesizeTemplateText(
        settings: Settings,
        model: Model,
        prompt: String,
        timeoutMs: Long,
    ): String? {
        val provider = model.findProvider(settings.providers) ?: return null
        val params = TextGenerationParams(
            model = model,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )
        return try {
            withTimeout(timeoutMs) {
                providerCatalog.text(provider).complete(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system(SYNTHESIS_SYSTEM_PROMPT),
                        UIMessage.user(prompt),
                    ),
                    params = params,
                )
            }.choices.firstOrNull()?.message?.toText()?.trim()
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "deep read synthesis call timed out after ${timeoutMs}ms")
            throw SynthesisCallTimeout(timeoutMs)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: IOException) {
            // Network failures are retryable at the WorkManager layer — let
            // them fail the run instead of masquerading as an empty reply.
            Log.w(TAG, "deep read synthesis call hit a network error", error)
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "deep read synthesis call failed", error)
            null
        }
    }

    /**
     * Single-shot structured generation for one concrete synthesis template.
     * Stores the article JSON in `structured_json` next to a complete marker
     * output so cache/history/export keep working (iOS task.structuredJSON).
     */
    suspend fun generate(
        topicId: String,
        topicTitle: String,
        seedUrl: String?,
        settings: Settings,
        model: Model,
        writer: DeepReadSectionWriterTools,
        sources: List<DeepReadSource>,
        templateId: String,
        template: DeepReadSynthesisTemplate,
    ): Result<DeepReadOutput> {
        val numbered = DeepReadSynthesisWriter.numbered(sources)
        if (numbered.isEmpty()) {
            return failSynthesis(
                topicId,
                writer,
                appContext.getString(R.string.deep_read_generation_failed),
            )
        }
        writer.markPhase(DeepReadGenerationPhase.WRITING)
        val raw = synthesizeTemplateText(
            settings = settings,
            model = model,
            prompt = DeepReadSynthesisWriter.prompt(template, topicTitle, numbered),
            timeoutMs = SYNTHESIS_TIMEOUT_MS,
        )
        val article = raw?.let {
            DeepReadSynthesisWriter.parse(it, template, topicTitle, numbered)
        } ?: return failSynthesis(
            topicId,
            writer,
            appContext.getString(
                R.string.deep_read_synthesis_template_failed,
                template.displayNameZh,
            ),
        )
        val output = DeepReadOutput(
            topicType = "template_synthesis",
            generationComplete = true,
            generationPhase = DeepReadGenerationPhase.COMPLETE,
            summary = article.lede.ifBlank { article.title },
            sectionStates = DeepReadGenerationStage.entries.associateWith {
                DeepReadSectionState(DeepReadSectionStatus.READY)
            },
        )
        hotListRepository.saveDeepRead(
            topicId = topicId,
            title = topicTitle,
            output = output,
            ttlDays = settings.agentRuntime.todayBoard.deepReadCacheTtlDays,
            sourceUrl = seedUrl,
            templateId = templateId,
            structuredJson = article.encoded(),
        )
        artifactPublisher.publish(topicId, topicTitle, output)
        Log.i(TAG, "deep read synthesis completed: topic=$topicId template=${template.wireId}")
        return Result.success(output)
    }

    private suspend fun failSynthesis(
        topicId: String,
        writer: DeepReadSectionWriterTools,
        message: String,
    ): Result<DeepReadOutput> {
        writer.markPhase(DeepReadGenerationPhase.IDLE)
        Log.w(TAG, "deep read synthesis failed: topic=$topicId reason=$message")
        return Result.failure(IllegalStateException(message))
    }

    private companion object {
        const val TAG = "DeepReadAgentRunManager"
        const val SYNTHESIS_PICK_TIMEOUT_MS = 60_000L
        const val SYNTHESIS_TIMEOUT_MS = 150_000L
        const val SYNTHESIS_SYSTEM_PROMPT =
            "你是 AmberAgent 的深度阅读结构化写作助手。只基于提供的来源写作，不编造，只输出合法 JSON 对象。"
    }
}
