package app.amber.feature.board.agent

import app.amber.agent.data.db.entity.BoardItemEntity
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.DailyReviewEntity
import app.amber.agent.data.files.CasTestFixtures
import app.amber.ai.core.MessageRole
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
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.BoardSignalSourceType
import app.amber.feature.board.collector.AppUsageCollector
import app.amber.feature.runtime.DurableRuntimeTestBase
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DailyReviewIdempotenceTest : DurableRuntimeTestBase() {
    private lateinit var repository: BoardRepository
    private lateinit var agent: DailyReviewAgent
    private lateinit var settingsStore: SettingsAggregator
    private val gateway = RecordingGateway()
    private val date = "2026-10-25"

    @Before
    fun setUpReview() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val gate = SyncRestoreWriteGate()
        repository = BoardRepository(
            database.boardSignalDao(), database.boardItemDao(), database.boardFocusRuleDao(),
            database.boardWeightDao(), database.dailyReviewDao(), gate,
        )
        database.boardItemDao().insert(BoardItemEntity(
            id = "completed-work", title = "Completed source work", sourceType = "calendar",
            sourceRef = "completed-work", sourceContent = "Work evidence", urgency = "medium",
            category = "todo", reason = "test", suggestion = "test", signalTime = 1L,
            status = "completed", completedAt = 2L, boardDate = date, createdAt = 1L,
        ))
        val root = File(context.cacheDir, "daily-review-${System.nanoTime()}").apply { mkdirs() }
        settingsStore = CasTestFixtures.settingsAggregator(context, root)
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        val model = Model(modelId = "daily-review-fixture")
        settingsStore.update(Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))), chatModelId = model.id))
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected real network") }.build()
        val catalog = ProviderCatalog(
            OpenAIProvider(client, context), GoogleProvider(client, context), ClaudeProvider(client, context),
            openAITextGateway = gateway,
        )
        agent = DailyReviewAgent(settingsStore, catalog, repository, conversationRepository(gate), AppUsageCollector(context), gate)
    }

    @After
    fun tearDownReview() {
        Dispatchers.resetMain()
    }

    @Test
    fun `completed evening suppresses repeated automatic evening and delayed noon calls`() = runBlocking {
        val existing = review("Noon body\n\nEvening body", DailyReviewAgent.PHASE_EVENING)
        repository.saveDailyReview(existing)

        agent.run(date, DailyReviewAgent.PHASE_EVENING, Locale.ENGLISH)
        agent.run(date, DailyReviewAgent.PHASE_NOON, Locale.ENGLISH)

        assertEquals(0, gateway.calls.get())
        assertEquals(existing, repository.getDailyReview(date))
    }

    @Test
    fun `noon and evening are generated once and evening appends exactly once`() = runBlocking {
        assertTrue(agent.run(date, DailyReviewAgent.PHASE_NOON, Locale.ENGLISH) is DailyReviewRunResult.Success)
        val noon = requireNotNull(repository.getDailyReview(date))
        agent.run(date, DailyReviewAgent.PHASE_NOON, Locale.ENGLISH)
        agent.run(date, DailyReviewAgent.PHASE_EVENING, Locale.ENGLISH)
        agent.run(date, DailyReviewAgent.PHASE_EVENING, Locale.ENGLISH)

        assertEquals(2, gateway.calls.get())
        assertEquals("review-body-1\n\nreview-body-2", repository.getDailyReview(date)?.content)
        assertEquals(noon.generatedAt, repository.getDailyReview(date)?.generatedAt)
        assertEquals(DailyReviewAgent.PHASE_EVENING, repository.getDailyReview(date)?.phase)
    }

    @Test
    fun `concurrent evening cycles share one generation and one durable supplement`() = runBlocking {
        repository.saveDailyReview(review("Noon body", DailyReviewAgent.PHASE_NOON))
        gateway.holdFirstCall = true
        val first = async { agent.run(date, DailyReviewAgent.PHASE_EVENING, Locale.ENGLISH) }
        withTimeout(5_000) { gateway.firstCallEntered.await() }
        val second = async { agent.run(date, DailyReviewAgent.PHASE_EVENING, Locale.ENGLISH) }
        yield()
        gateway.releaseFirstCall.complete(Unit)

        first.await()
        second.await()

        assertEquals(1, gateway.calls.get())
        assertEquals("Noon body\n\nreview-body-1", repository.getDailyReview(date)?.content)
    }

    @Test
    fun `disabled chat history is absent from actual daily review provider prompt`() = runBlocking {
        val privateTitle = "PRIVATE_CHAT_MUST_STAY_LOCAL"
        val now = System.currentTimeMillis()
        database.conversationDao().insert(ConversationEntity(
            id = Uuid.random().toString(), assistantId = Uuid.random().toString(), title = privateTitle,
            nodes = "[]", createAt = now, updateAt = now, chatSuggestions = "[]", isPinned = false,
        ))

        agent.run(date, DailyReviewAgent.PHASE_NOON, Locale.ENGLISH)
        assertTrue("The enabled-source control must exercise real conversation decoding", gateway.prompts.single().contains(privateTitle))

        settingsStore.update { settings -> settings.copy(agentRuntime = settings.agentRuntime.copy(
            todayBoard = settings.agentRuntime.todayBoard.copy(
                enabledSources = settings.agentRuntime.todayBoard.enabledSources - BoardSignalSourceType.CHAT_HISTORY,
            ),
        )) }

        agent.run(date, DailyReviewAgent.PHASE_EVENING, Locale.ENGLISH)

        assertEquals(2, gateway.calls.get())
        val prompt = gateway.prompts.last()
        assertTrue(prompt.contains("Completed source work"))
        assertFalse("The disabled chat source must not leave the device", prompt.contains(privateTitle))
    }

    private fun review(content: String, phase: String) = DailyReviewEntity(
        id = "review:$date", boardDate = date, content = content, phase = phase,
        generatedAt = 1_000L, updatedAt = 2_000L,
    )

    private class RecordingGateway : TextModelGateway<ProviderSetting.OpenAI> {
        val calls = AtomicInteger()
        val prompts = java.util.concurrent.CopyOnWriteArrayList<String>()
        var holdFirstCall = false
        val firstCallEntered = CompletableDeferred<Unit>()
        val releaseFirstCall = CompletableDeferred<Unit>()

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun stream(
            providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams,
        ): Flow<MessageChunk> = error("Daily review must call complete")

        override suspend fun complete(
            providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams,
        ): MessageChunk {
            val call = calls.incrementAndGet()
            prompts += messages.joinToString("\n") { it.toText() }
            if (call == 1 && holdFirstCall) {
                firstCallEntered.complete(Unit)
                releaseFirstCall.await()
            }
            return MessageChunk("review-$call", params.model.modelId, listOf(UIMessageChoice(
                index = 0, delta = null,
                message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("review-body-$call"))),
                finishReason = "stop",
            )))
        }
    }
}
