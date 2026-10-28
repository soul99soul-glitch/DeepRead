package app.amber.core.service

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.agent.R
import app.amber.core.settings.findProvider
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.model.Conversation
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.applyPlaceholders
import app.amber.core.utils.appLocaleDisplayName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

class AiAuxiliaryGenerator(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val conversationStore: ConversationStore,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) {
        val expectedRestoreEpoch = captureRestoreEpoch()
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.resolveTaskChatModel(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerCatalog.text(provider)
            val result = providerHandler.complete(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to context.appLocaleDisplayName(),
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() },
                        ),
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    sessionId = conversationId.toString(),
                ),
            )

            val title = result.choices[0].message?.toText()?.trim().orEmpty()
            withContext(expectedRestoreEpoch?.let(::SyncRestoreWriteEpoch) ?: EmptyCoroutineContext) {
                conversationStore.updateTitle(conversationId, title)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            it.printStackTrace()
            conversationStore.addError(it, conversationId, title = context.getString(R.string.error_title_generate_title))
        }
    }

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        val expectedRestoreEpoch = captureRestoreEpoch()
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.resolveTaskChatModel(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            withRestoreWrite(expectedRestoreEpoch) {
                if (conversationStore.getConversationFlowOrNull(conversationId) != null) {
                    conversationStore.updateConversationState(conversationId) {
                        it.copy(chatSuggestions = emptyList())
                    }
                }
            }

            val providerHandler = providerCatalog.text(provider)
            val result = providerHandler.complete(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to context.appLocaleDisplayName(),
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() },
                        ),
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    sessionId = conversationId.toString(),
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            withContext(expectedRestoreEpoch?.let(::SyncRestoreWriteEpoch) ?: EmptyCoroutineContext) {
                conversationStore.updateSuggestions(conversationId, suggestions.take(10))
            }
        }.onFailure {
            if (it is CancellationException) throw it
            it.printStackTrace()
        }
    }

    private suspend fun captureRestoreEpoch(): Long? {
        val gate = restoreWriteGate ?: return null
        return currentCoroutineContext()[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
    }

    private suspend fun <T> withRestoreWrite(
        expectedRestoreEpoch: Long?,
        block: suspend () -> T,
    ): T {
        val gate = restoreWriteGate ?: return block()
        val epoch = expectedRestoreEpoch
            ?: currentCoroutineContext()[SyncRestoreWriteEpoch]?.value
            ?: gate.currentEpoch()
        return withContext(SyncRestoreWriteEpoch(epoch)) {
            gate.withCurrentWriterOrCancel(block)
        }
    }
}
