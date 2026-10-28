package app.amber.core.ai

import android.util.Log
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.context.TokenBudgetFitter
import app.amber.core.context.TokenFitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

private const val TAG = "GenerationRoundEngine"
private const val PERF_TAG = "AmberChatPerf"

private val OUTPUT_LIMIT_FINISH_REASONS = setOf("length", "max_tokens", "max_output_tokens")

/** Provider finish vocabularies normalize here, before the loop consumes the verdict. */
internal fun finishReasonTruncatesOutput(finishReason: String?): Boolean =
    finishReason != null && finishReason.trim().lowercase() in OUTPUT_LIMIT_FINISH_REASONS

/** Message accumulation drops finish reasons, so read the last non-null choice on the wire. */
internal fun MessageChunk.lastFinishReason(): String? =
    choices.lastOrNull { it.finishReason != null }?.finishReason

/**
 * One round's wire boundary: issuance index, final fit, audit and adopted finish reason.
 * Streaming message accumulation and UI flushes stay with the round's streaming consumer.
 */
internal class ProviderAttemptExecutor(
    private val model: Model,
    private val provider: ProviderSetting,
    private val gateway: TextModelGateway<ProviderSetting>,
    private val json: Json,
    private val conversationId: String?,
    private val systemParts: List<UIMessagePart>,
    private val runId: String?,
    private val stepIndex: Int,
    private val events: AgentEventWriter?,
    private val durablePath: Boolean,
    private val perfEnabled: Boolean,
) {
    private var nextAttempt = 0
    private var adoptedFinishReason: String? = null

    val outputLimitReached: Boolean
        get() = finishReasonTruncatesOutput(adoptedFinishReason)

    suspend fun stream(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        kind: String,
    ): Flow<MessageChunk> {
        val attempt = nextAttempt++
        adoptedFinishReason = null
        val fit = prepare(messages, params, attempt, kind, transport = "stream")
        return flow {
            var finishReason: String? = null
            val startedAt = if (perfEnabled) System.nanoTime() else 0L
            var firstChunkLogged = false
            gateway.stream(
                providerSetting = provider,
                messages = fit.messages,
                params = params,
            ).collect { chunk ->
                if (perfEnabled && !firstChunkLogged) {
                    firstChunkLogged = true
                    Log.d(PERF_TAG, "round.providerFirstChunk.stream step=$stepIndex attempt=$attempt elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000.0}")
                }
                chunk.lastFinishReason()?.let { finishReason = it }
                emit(chunk)
            }
            adoptedFinishReason = finishReason
        }
    }

    suspend fun complete(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        kind: String,
    ): MessageChunk {
        val attempt = nextAttempt++
        adoptedFinishReason = null
        val fit = prepare(messages, params, attempt, kind, transport = "complete")
        val startedAt = if (perfEnabled) System.nanoTime() else 0L
        val chunk = gateway.complete(
            providerSetting = provider,
            messages = fit.messages,
            params = params,
        )
        if (perfEnabled) {
            Log.d(PERF_TAG, "round.providerComplete step=$stepIndex attempt=$attempt elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000.0}")
        }
        adoptedFinishReason = chunk.lastFinishReason()
        return chunk
    }

    private suspend fun prepare(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        attempt: Int,
        kind: String,
        transport: String,
    ): TokenFitResult {
        val startedAt = if (perfEnabled) System.nanoTime() else 0L
        // This fit happens for every retry/repair/fallback, after all input transforms.
        val fit = TokenBudgetFitter.fit(
            messages = messages,
            tools = params.tools,
            model = model,
            provider = provider,
            maxTokens = params.maxTokens,
            json = json,
            conversationId = conversationId,
        )
        if (perfEnabled) {
            Log.d(PERF_TAG, "round.fit.$transport step=$stepIndex attempt=$attempt elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000.0}")
        }
        commitRequestSnapshot(attempt, kind, fit, params)
        return fit
    }

    private suspend fun commitRequestSnapshot(
        attempt: Int,
        kind: String,
        fit: TokenFitResult,
        params: TextGenerationParams,
    ) {
        val writer = events
        if (!durablePath || writer == null || runId == null || stepIndex < 0) return
        runCatching {
            val startedAt = if (perfEnabled) System.nanoTime() else 0L
            val snapshot = buildRequestSnapshot(
                json = json,
                stepIndex = stepIndex,
                attempt = attempt,
                kind = kind,
                model = model,
                providerSettingId = provider.id.toString(),
                fitMessages = fit.messages,
                tools = params.tools,
                systemParts = systemParts,
                estimatedTokens = fit.receipt.estimatedAfter,
            )
            if (perfEnabled) {
                Log.d(PERF_TAG, "round.requestSnapshot step=$stepIndex attempt=$attempt elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000.0}")
            }
            writer.commit(snapshot)
        }.onFailure { error ->
            // A stopped audit writer must stop the imminent wire request too.
            if (error is CancellationException) throw error
            Log.w(TAG, "Request snapshot commit failed; generation continues", error)
        }
    }
}
