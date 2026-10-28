package app.amber.core.service

import android.content.ContextWrapper
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.provider.providers.ClaudeProvider
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.review.ReflectionFixture
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiAuxiliaryMetadataRaceTest : DurableRuntimeTestBase() {
    @get:Rule val directory = TemporaryFolder()
    private var cleanup: (() -> Unit)? = null

    @Before
    fun pauseMainDispatcher() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun closeOwners() {
        cleanup?.invoke()
        Dispatchers.resetMain()
    }

    @Test
    fun titleResultWaitingForAWriterPreservesTheSuccessorTailAndSuggestions() = metadataRace(title = true)

    @Test
    fun suggestionResultWaitingForAWriterPreservesTheSuccessorTailAndTitle() = metadataRace(title = false)

    private fun metadataRace(title: Boolean) = runTest {
        val gate = SyncRestoreWriteGate()
        val appScope = AppScope()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), appScope, gate)
        val repo = ConversationRepository(
            database.conversationDao(), database.messageNodeDao(), database.messageStatsDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database), gate,
        )
        val model = Model(modelId = "auxiliary-metadata-fixture")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(Settings(
            providers = listOf(provider), chatModelId = model.id, titleModelId = model.id, suggestionModelId = model.id,
        )))
        val queue = PendingMessageQueue(scope, PendingMessageStore(object : ContextWrapper(context) {
            override fun getFilesDir() = directory.root
        }, Json), gate)
        val store = ConversationStore(repo, settings, scope, files, queue, ChatErrorStore(), gate)
        cleanup = {
            store.cleanup()
            queue.cleanup()
            scope.cancel()
            appScope.cancel()
        }
        val original = Conversation.ofId(Uuid.random()).copy(
            title = "Original title",
            chatSuggestions = listOf("original suggestion"),
            messageNodes = listOf(MessageNode.of(UIMessage.user("First turn"))),
        )
        repo.insertConversation(original)
        store.ensureInitialized(original.id)
        val gateway = PausedMetadataGateway(model, if (title) "Generated title" else "First suggestion\nSecond suggestion")
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected real network") }.build()
        val catalog = ProviderCatalog(
            OpenAIProvider(client, context), GoogleProvider(client, context), ClaudeProvider(client, context),
            openAITextGateway = gateway,
        )
        val generator = AiAuxiliaryGenerator(context, settings, catalog, store, gate)
        val generation = async(start = CoroutineStart.UNDISPATCHED) {
            if (title) generator.generateTitle(original.id, original, force = true)
            else generator.generateSuggestion(original.id, original)
        }
        gateway.entered.await()
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withCurrentWriterOrCancel {
                writerEntered.complete(Unit)
                releaseWriter.await()
            }
        }
        writerEntered.await()
        gateway.release.complete(Unit)
        gateway.returned.await()
        runCurrent()
        assertFalse("Metadata result must be waiting behind the ordinary writer", generation.isCompleted)

        // A successor turn projects outside the durable writer gate, exactly as the
        // production ChatTurnAgent does while independent auxiliary work finishes.
        val successor = store.getConversationFlow(original.id).value.copy(
            title = if (title) original.title else "User renamed during auxiliary work",
            chatSuggestions = if (title) listOf("successor suggestion") else emptyList(),
            messageNodes = original.messageNodes + MessageNode.of(UIMessage.assistant("SUCCESSOR_STREAMED_TAIL")),
        )
        store.updateConversation(original.id, successor)
        releaseWriter.complete(Unit)
        writer.join()
        generation.await()
        store.persistFinalWindow(original.id, false, original, store.getTimelineLoadStateFlow(original.id).value)

        val live = store.getConversationFlow(original.id).value
        val durable = repo.getConversationById(original.id)!!
        assertEquals(listOf(successor.messageNodes, successor.messageNodes), listOf(live.messageNodes, durable.messageNodes))
        val expectedTitle = if (title) "Generated title" else successor.title
        val expectedSuggestions = if (title) successor.chatSuggestions else listOf("First suggestion", "Second suggestion")
        assertEquals(listOf(expectedTitle, expectedTitle), listOf(live.title, durable.title))
        assertEquals(listOf(expectedSuggestions, expectedSuggestions), listOf(live.chatSuggestions, durable.chatSuggestions))
    }

    private class PausedMetadataGateway(private val model: Model, private val reply: String) : TextModelGateway<ProviderSetting.OpenAI> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        override suspend fun stream(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = error("Auxiliary generation must use complete")

        override suspend fun complete(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            entered.complete(Unit)
            release.await()
            returned.complete(Unit)
            return MessageChunk("auxiliary-metadata", model.modelId, listOf(UIMessageChoice(
                index = 0, message = UIMessage.assistant(reply), delta = null, finishReason = "stop",
            )))
        }
    }
}
