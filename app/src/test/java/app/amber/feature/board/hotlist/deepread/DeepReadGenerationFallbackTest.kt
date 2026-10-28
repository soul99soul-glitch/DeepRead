package app.amber.feature.board.hotlist.deepread

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.agent.R
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.core.ai.AILoggingManager
import app.amber.core.ai.ChatGenerationRoundEngine
import app.amber.core.ai.DefaultRunKernel
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.ai.tools.LocalTools
import app.amber.core.ai.tools.createTimeTool
import app.amber.core.context.AgentCapabilitySnapshotBuilder
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.infra.AppScope
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.ContextCompactionSetting
import app.amber.core.ai.GenerationRetrySetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.board.hotlist.deepread.template.DeepReadSynthesisTemplate
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateArticle
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.HotTopic
import app.amber.feature.board.hotlist.HotTopicSource
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.tools.AgentToolSetFactory
import app.amber.feature.workspace.WorkspaceManager
import app.amber.review.ReflectionFixture
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public Manager -> recording/delegating real kernel -> real engine -> gateway -> writer -> Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeepReadGenerationFallbackTest : DurableRuntimeTestBase() {
    private val json = Json { ignoreUnknownKeys = true }
    private val model = Model(modelId = "deepread-fixture", abilities = listOf(ModelAbility.TOOL), contextWindowTokens = 128_000)
    private lateinit var scope: AppScope
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var repository: HotListRepository
    private lateinit var gate: SyncRestoreWriteGate

    @Before
    fun setUpDeepRead() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = AppScope()
        gate = SyncRestoreWriteGate()
        repository = HotListRepository(database.hotListDao(), json, gate)
        settingsStore = createSettingsStore()
        withTimeout(10_000) { settingsStore.settingsFlow.first { !it.init } }
        assertTrue("Agent search must be enabled in fresh settings", settingsStore.settingsFlow.value.enableWebSearch)
        settingsStore.update { Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            chatModelId = model.id,
            systemPrompt = "Parent prompt must not reach the hidden DeepRead run",
            enableWebSearch = false,
            searchServices = emptyList(),
            searchEnabledServiceIds = emptyList(),
            searchBuiltinDuckDuckGoEnabled = false,
            searchBuiltinBingEnabled = false,
            agentRuntime = AgentRuntimeSetting(
                agentSoulMarkdown = "",
                enableRecentChatsReference = false,
                enableCoreMemory = false,
                enableShortTermMemory = false,
                enableLongTermMemory = false,
                generativeUi = GenerativeUiSetting(enabled = false),
                contextCompaction = ContextCompactionSetting(enabled = false),
                generationRetry = GenerationRetrySetting(enabled = false),
            ),
        ) }
        withTimeout(10_000) { settingsStore.settingsFlow.first { it.chatModelId == model.id } }
        repository.upsertTopic(HotTopic(
            id = TOPIC,
            title = TITLE,
            sources = listOf(HotTopicSource(
                providerId = "fixture", providerName = "Fixture Source", rank = 1, title = TITLE,
                url = SOURCE,
                content = "This supplied fixture source describes the event, its timeline, independently reported facts and remaining uncertainty. ".repeat(18),
            )),
            sourceCount = 1, bestRank = 1, latestFetchedAt = System.currentTimeMillis(),
            deepReadTemplateId = DeepReadTemplateIds.COMPOSE_MAGAZINE,
        ))
    }

    @After
    fun tearDownDeepRead() = runBlocking {
        if (::scope.isInitialized) {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `URL sources are fetched when search services are disabled`() = runBlocking {
        val articleUrl = "https://93.184.215.14/deepread-fixture"
        val body = "LINK_BODY: This article provides independently verified facts, a detailed timeline and source context. ".repeat(8)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html><body><article><p>$body</p></article></body></html>".toResponseBody("text/html".toMediaType()))
                .build()
        }.build()

        val sources = DeepReadSourcePrefetcher(settingsStore, repository, client).collect(
            topicId = "url-only-fixture", topicTitle = TITLE, seedUrl = articleUrl, locale = Locale.ENGLISH,
        )

        assertEquals(1, sources.size)
        assertEquals(articleUrl, sources.single().url)
        assertTrue(sources.single().evidenceText.contains("LINK_BODY"))
    }

    @Test
    fun `DeepRead collects builtin search results without a configured service`() = runBlocking {
        settingsStore.update { it.copy(searchBuiltinDuckDuckGoEnabled = true) }
        withTimeout(10_000) { settingsStore.settingsFlow.first { it.searchBuiltinDuckDuckGoEnabled } }
        val articleUrl = "https://93.184.215.14/builtin-search-fixture"
        val body = "SEARCH_BODY: The linked report supplies independently verified details, context and an event timeline. ".repeat(8)
        val requestedHosts = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requestedHosts += request.url.host
            val responseBody = when (request.url.host) {
                "html.duckduckgo.com" -> """<div class="result"><a class="result__a" href="$articleUrl">Source report</a><div class="result__snippet">$body</div></div>"""
                "93.184.215.14" -> "<html><body><article><p>$body</p></article></body></html>"
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseBody.toResponseBody("text/html".toMediaType())).build()
        }.build()

        val sources = DeepReadSourcePrefetcher(settingsStore, repository, client).collect(
            topicId = "builtin-search-fixture", topicTitle = TITLE, locale = Locale.ENGLISH,
        )

        assertTrue("DeepRead must use enabled built-in search even without a configured API", "html.duckduckgo.com" in requestedHosts)
        assertEquals(articleUrl, sources.single().url)
        assertTrue(sources.single().evidenceText.contains("SEARCH_BODY"))
    }

    @Test
    fun `manager completes the full magazine after stream IO with real nonstream writer calls`() = runBlocking {
        val gateway = ScriptedGateway(Failure.IO)
        val kernel = recordingKernel(gateway)

        val result = manager(kernel, gateway.catalog).run(
            TOPIC, TITLE, seedUrl = SOURCE, deferMissingStages = false, runId = RUN_ID, locale = Locale.ENGLISH,
        )

        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        val persisted = requireNotNull(repository.getFreshDeepRead(TOPIC))
        assertTrue(persisted.generationComplete)
        assertTrue(persisted.isComplete())
        DeepReadGenerationStage.entries.forEach { assertEquals(DeepReadSectionStatus.READY, persisted.statusOf(it)) }
        assertTrue(persisted.summary.contains("OVERVIEW_WRITTEN"))
        assertTrue(persisted.timeline.orEmpty().any { it.event.contains("NARRATIVE_WRITTEN") })
        assertTrue(persisted.analysis.coreDispute.orEmpty().contains("ANALYSIS_WRITTEN"))
        assertTrue(persisted.extendedReading.any { it.url == SOURCE })
        assertTrue("All four real writer results must return through the next provider round", gateway.calls
            .filter { it.writer != null }.mapNotNull { it.executedWriter }.toSet().containsAll(WRITERS))
        assertEquals(WRITERS, gateway.calls.mapNotNull { it.writer }.distinct())

        val sessions = kernel.sessions
        assertTrue(sessions.first().tools.isEmpty())
        assertFalse("Planning is its own nonstream session", sessions.first().settings.streamOutput)
        val overviewSessions = sessions.filter { it.tools.any { tool -> tool.name == WRITERS.first() } }
        val searchStatus = overviewSessions.first().tools.first { it.name == "search_sources_status" }
            .execute(buildJsonObject { }).single() as UIMessagePart.Text
        assertTrue("DeepRead must enable Agent search for its run", json.parseToJsonElement(searchStatus.text)
            .jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertFalse("The run override must not change the saved chat preference", settingsStore.settingsFlow.value.enableWebSearch)
        assertEquals(listOf(true, false), overviewSessions.map { it.settings.streamOutput })
        assertEquals(overviewSessions.first().messages, overviewSessions.last().messages)
        assertEquals(model.id, overviewSessions.first().model.id)
        assertEquals(model.id, overviewSessions.last().model.id)
        assertEquals(listOf(RUN_ID, RUN_ID), overviewSessions.map { it.runId })
        assertEquals(overviewSessions.first().autoApprovedToolNames, overviewSessions.last().autoApprovedToolNames)
        assertTrue(WRITERS.first() in overviewSessions.last().autoApprovedToolNames)
        assertEquals(listOf("stream", "complete", "complete"), gateway.calls.filter { it.writer == WRITERS.first() }.map { it.transport })
        assertTrue(gateway.calls.filter { it.transport == "complete" && it.writer == WRITERS.first() }
            .none { call -> call.messages.any { it.toText().contains(ABANDONED) } })
        assertTrue(gateway.calls.flatMap { it.messages }.none { it.toText().contains("Parent prompt must not reach") })
        assertFalse(persisted.summary.contains(ABANDONED))
        assertEquals(persisted, result.getOrThrow())
    }

    @Test
    fun `single section retry keeps ready siblings and completes through the real writer`() = runBlocking {
        val initialGateway = ScriptedGateway(Failure.IO)
        val initialKernel = recordingKernel(initialGateway)
        val complete = manager(initialKernel, initialGateway.catalog).run(
            TOPIC, TITLE, seedUrl = SOURCE, deferMissingStages = false, locale = Locale.ENGLISH,
        ).getOrThrow()
        val partial = complete.copy(
            timeline = emptyList(),
            generationComplete = false,
            generationPhase = DeepReadGenerationPhase.IDLE,
        ).withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.FAILED, "Retry fixture")
        repository.saveDeepRead(TOPIC, TITLE, partial, sourceUrl = SOURCE)
        val retryGateway = ScriptedGateway(Failure.IO)
        val retryKernel = recordingKernel(retryGateway)

        val result = manager(retryKernel, retryGateway.catalog).runSection(
            TOPIC, TITLE, DeepReadGenerationStage.NARRATIVE, seedUrl = SOURCE, locale = Locale.ENGLISH,
        ).getOrThrow()

        val saved = requireNotNull(repository.getFreshDeepRead(TOPIC))
        assertTrue(saved.isComplete())
        assertEquals(saved, result)
        assertEquals(complete.summary, saved.summary)
        assertEquals(complete.analysis, saved.analysis)
        assertEquals(complete.extendedReading, saved.extendedReading)
        assertEquals(listOf(WRITERS[1]), retryGateway.calls.mapNotNull { it.writer }.distinct())
        assertTrue(retryGateway.calls.any { it.executedWriter == WRITERS[1] })
        assertTrue(saved.timeline.orEmpty().any { it.event.contains("NARRATIVE_WRITTEN") })
    }

    @Test
    fun `synthesis uses the direct provider and preserves complete cache and forced metadata`() = runBlocking {
        val template = DeepReadSynthesisTemplate.BRIEF
        repository.upsertTopic(requireNotNull(repository.getHotTopic(TOPIC)).copy(deepReadTemplateId = template.wireId))
        val gateway = ScriptedGateway(
            Failure.IO,
            synthesisReply = """{"title":"SYNTHESIS_TITLE","lede":"SYNTHESIS_LEDE","points":["A source-backed point","A remaining uncertainty"],"background":"Supplied source context","impact":"Check the original evidence"}""",
        )
        val kernel = recordingKernel(gateway)
        val manager = manager(kernel, gateway.catalog)

        val first = manager.run(TOPIC, TITLE, seedUrl = SOURCE, deferMissingStages = false).getOrThrow()

        assertTrue(first.isComplete())
        assertEquals("template_synthesis", first.topicType)
        assertEquals("SYNTHESIS_LEDE", first.summary)
        assertTrue("Synthesis must retain its direct provider path", kernel.sessions.isEmpty())
        assertEquals(listOf("complete"), gateway.calls.map { it.transport })
        assertTrue(gateway.calls.single().messages.first().toText().contains("深度阅读结构化写作助手"))
        val entry = requireNotNull(repository.getDeepReadEntry(TOPIC))
        assertEquals(template.wireId, entry.templateId)
        val article = requireNotNull(DeepReadTemplateArticle.decode(entry.structuredJson))
        assertEquals("SYNTHESIS_TITLE", article.title)
        assertEquals(SOURCE, article.sources.single().url)
        assertEquals(2, article.brief!!.points.size)

        val cached = manager.run(TOPIC, TITLE, seedUrl = SOURCE, deferMissingStages = false).getOrThrow()
        assertEquals(first, cached)
        assertEquals("Complete cache must bypass generation", 1, gateway.calls.size)
        repository.setDeepReadPinned(TOPIC, true)
        val forced = manager.run(TOPIC, TITLE, force = true, seedUrl = SOURCE, deferMissingStages = false).getOrThrow()
        assertEquals(first, forced)
        assertEquals(2, gateway.calls.size)
        val regenerated = requireNotNull(repository.getDeepReadEntry(TOPIC))
        assertTrue(regenerated.pinned)
        assertEquals(SOURCE, regenerated.sourceUrl)
        assertEquals(template.wireId, regenerated.templateId)
        assertTrue(kernel.sessions.isEmpty())
    }

    @Test
    fun `synthesis workspace artifact retains full body and references on generation and cached republish`() = runBlocking {
        repository.upsertTopic(requireNotNull(repository.getHotTopic(TOPIC)).copy(
            deepReadTemplateId = DeepReadSynthesisTemplate.BRIEF.wireId,
        ))
        val artifacts = ArtifactRepository(
            database.artifactDao(), WorkspaceManager(context), database.messageNodeDao(), database.conversationDao(),
        )
        val gateway = ScriptedGateway(
            Failure.IO,
            synthesisReply = """{"title":"Portable article","lede":"Portable lede","points":["SYNTHESIS_BODY_UNIQUE","Remaining uncertainty"],"background":"Background evidence","impact":"Check original evidence"}""",
        )
        val kernel = recordingKernel(gateway)
        val manager = manager(kernel, gateway.catalog, artifacts)

        val generated = manager.run(TOPIC, TITLE, seedUrl = SOURCE, deferMissingStages = false).getOrThrow()
        val published = artifacts.list().single()
        val firstContent = requireNotNull(artifacts.readContent(published))

        assertTrue(generated.isComplete())
        assertTrue("The portable artifact must retain the article body", firstContent.contains("SYNTHESIS_BODY_UNIQUE"))
        assertTrue("The portable artifact must retain source references", firstContent.contains(SOURCE))

        artifacts.saveDeepRead(TOPIC, TITLE, "stale summary-only artifact")
        manager.run(TOPIC, TITLE, seedUrl = SOURCE, deferMissingStages = false).getOrThrow()

        val republished = requireNotNull(artifacts.readContent(artifacts.list().single()))
        assertTrue(republished.contains("SYNTHESIS_BODY_UNIQUE"))
        assertTrue(republished.contains(SOURCE))
        assertEquals("Republishing a complete cached article must not call the provider again", 1, gateway.calls.size)
    }

    @Test
    fun `cancellation escapes the manager without a nonstream stage retry`() = runBlocking {
        val gateway = ScriptedGateway(Failure.CANCEL)
        val kernel = recordingKernel(gateway)

        val failure = runCatching { manager(kernel, gateway.catalog).runSection(
            TOPIC, TITLE, DeepReadGenerationStage.OVERVIEW, seedUrl = SOURCE, locale = Locale.ENGLISH,
        ) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("stream"), gateway.calls.filter { it.writer == WRITERS.first() }.map { it.transport })
        assertEquals(listOf(true), kernel.sessions.filter { it.tools.isNotEmpty() }.map { it.settings.streamOutput })
        assertFalse(requireNotNull(repository.getFreshDeepRead(TOPIC)).summary.contains("OVERVIEW_WRITTEN"))
    }

    @Test
    fun `provider timeout fails the target section without the nonstream fallback`() = runBlocking {
        val gateway = ScriptedGateway(Failure.TIMEOUT)
        val kernel = recordingKernel(gateway)

        val result = manager(kernel, gateway.catalog).runSection(
            TOPIC, TITLE, DeepReadGenerationStage.OVERVIEW, seedUrl = SOURCE, locale = Locale.ENGLISH,
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("stream"), gateway.calls.filter { it.writer == WRITERS.first() }.map { it.transport })
        val saved = requireNotNull(repository.getFreshDeepRead(TOPIC))
        assertEquals(DeepReadSectionStatus.FAILED, saved.statusOf(DeepReadGenerationStage.OVERVIEW))
        assertTrue("The reader must retain the timeout reason instead of a generic failure", saved.errorOf(DeepReadGenerationStage.OVERVIEW)
            .orEmpty().contains(context.getString(R.string.notification_live_status_timed_out)))
    }

    @Test
    fun `a real writer from the old run cannot overwrite a restored article`() = runBlocking {
        val gateway = ScriptedGateway(Failure.HOLD)
        val kernel = recordingKernel(gateway)
        val running = async { manager(kernel, gateway.catalog).runSection(
            TOPIC, TITLE, DeepReadGenerationStage.OVERVIEW, seedUrl = SOURCE, locale = Locale.ENGLISH,
        ) }
        withTimeout(10_000) { gateway.reached.await() }
        val restored = DeepReadOutput(summary = "RESTORED_ARTICLE_MUST_SURVIVE", generationPhase = DeepReadGenerationPhase.IDLE)
        gate.withRestore {
            val row = requireNotNull(database.hotListDao().getDeepRead(TOPIC))
            database.hotListDao().upsertDeepRead(row.copy(outputJson = json.encodeToString(restored)))
        }
        gateway.release.complete(Unit)

        val failure = runCatching { running.await() }.exceptionOrNull()

        assertTrue("The production restore writer must reject the old epoch", failure is SyncRestoreWriteRejectedException)
        assertEquals(restored, repository.getFreshDeepRead(TOPIC))
        assertEquals(listOf("stream"), gateway.calls.filter { it.writer == WRITERS.first() }.map { it.transport })
        assertFalse(gateway.calls.any { it.executedWriter != null })
    }

    private class RecordingKernel(private val delegate: RunKernel) : RunKernel {
        val sessions = CopyOnWriteArrayList<GenerationRunSession>()
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> {
            sessions += session
            return delegate.run(session)
        }
    }

    private enum class Failure { IO, CANCEL, TIMEOUT, HOLD }
    private data class WireCall(
        val transport: String,
        val writer: String?,
        val messages: List<UIMessage>,
        val executedWriter: String?,
    )

    private inner class ScriptedGateway(
        private val failure: Failure,
        private val synthesisReply: String? = null,
    ) : TextModelGateway<ProviderSetting.OpenAI> {
        val calls = CopyOnWriteArrayList<WireCall>()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var failedStream = false
        lateinit var catalog: ProviderCatalog
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        private fun record(transport: String, messages: List<UIMessage>, params: TextGenerationParams): WireCall {
            val writer = params.tools.firstOrNull { it.name in WRITERS }?.name
            val executed = messages.flatMap { it.getTools() }.firstOrNull { it.toolName == writer && it.isExecuted }?.toolName
            return WireCall(transport, writer, messages, executed).also { calls += it }
        }
        override suspend fun complete(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk =
            response(record("complete", messages, params), streaming = false)
        override suspend fun stream(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> = flow {
            val call = record("stream", messages, params)
            if (call.writer == WRITERS.first() && !failedStream) {
                failedStream = true
                when (failure) {
                    Failure.IO -> {
                        emit(chunk(UIMessagePart.Text(ABANDONED), streaming = true))
                        throw IOException("scripted connection reset")
                    }
                    Failure.CANCEL -> throw CancellationException("scripted user cancel")
                    Failure.TIMEOUT -> throw SocketTimeoutException("scripted Read timed out")
                    Failure.HOLD -> { reached.complete(Unit); release.await() }
                }
            }
            emit(response(call, streaming = true))
        }
        private fun response(call: WireCall, streaming: Boolean): MessageChunk {
            val part = when {
                call.writer == null && call.messages.firstOrNull()?.toText()?.contains("深度阅读结构化写作助手") == true ->
                    UIMessagePart.Text(requireNotNull(synthesisReply) { "Unexpected direct synthesis request" })
                call.writer == null -> UIMessagePart.Text("{}") // Actual nonstream planning, parsed by the real harness.
                call.executedWriter != null -> UIMessagePart.Text("Stage written.")
                else -> UIMessagePart.Tool(toolCallId = "write_${call.writer}", toolName = call.writer, input = payload(call.writer))
            }
            return chunk(part, streaming)
        }
    }

    private fun chunk(part: UIMessagePart, streaming: Boolean): MessageChunk {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))
        return MessageChunk(id = "deepread_fixture", model = model.modelId, choices = listOf(UIMessageChoice(
            index = 0, delta = message.takeIf { streaming }, message = message.takeUnless { streaming },
            finishReason = if (part is UIMessagePart.Tool) "tool_calls" else "stop",
        )))
    }

    private fun payload(writer: String): String = when (writer) {
        WRITERS[0] -> """{"topic_type":"event","summary":"OVERVIEW_WRITTEN ${"The verified fixture source explains what happened, why it matters, the actors and the remaining uncertainty. ".repeat(3)}","key_entities":["Fixture"]}"""
        WRITERS[1] -> """{"timeline":[{"date":"2026-10-18","event":"NARRATIVE_WRITTEN: the supplied source describes the event and its public timeline.","is_highlight":true}]}"""
        WRITERS[2] -> """{"core_dispute":"ANALYSIS_WRITTEN: the sources distinguish verified facts from uncertain future implications.","perspectives":[{"holder":"Observer","viewpoint":"Verify the source before making a causal conclusion."}],"implications":"The event changes what should be independently checked next."}"""
        WRITERS[3] -> """{"links":[{"title":"Fixture Source","url":"$SOURCE","source":"Fixture"}]}"""
        else -> error("Unexpected writer $writer")
    }

    private fun recordingKernel(gateway: ScriptedGateway): RecordingKernel {
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected real network: ${it.request().url}") }.build()
        val catalog = ProviderCatalog(
            openAIProvider = app.amber.ai.provider.providers.OpenAIProvider(client, context),
            googleProvider = app.amber.ai.provider.providers.GoogleProvider(client, context),
            claudeProvider = app.amber.ai.provider.providers.ClaudeProvider(client, context),
            openAITextGateway = gateway,
        )
        gateway.catalog = catalog
        val conversations = conversationRepository(gate)
        val memory = MemoryRepository(database.memoryDao(), database.memoryCandidateDao(), database.memoryEventDao(), database)
        val engine = ChatGenerationRoundEngine(
            context, catalog, json, MemoryRecallStore(memory), conversations, AILoggingManager(),
            ConversationContextEngine(
                providerCatalog = catalog, json = json,
                contextRepository = ConversationContextRepository(database.conversationCompactDao(), database.conversationContextEventDao(), conversations),
                appScope = scope, capabilitySnapshotBuilder = AgentCapabilitySnapshotBuilder(),
                promptConfigRepository = AgentPromptConfigRepository(context), context = context,
            ),
        )
        return RecordingKernel(DefaultRunKernel(context, AgentToolDispatcher(json, PermissionDecisionResolver()), engine))
    }

    private fun manager(
        kernel: RunKernel,
        catalog: ProviderCatalog,
        artifactRepository: ArtifactRepository? = null,
    ): DeepReadAgentRunManager {
        // Only unrelated platform services in LocalTools are unconstructed. Its actual DeepRead
        // time/catalog methods run with real dependencies; no generation/tool behavior is faked.
        val localTools = ReflectionFixture.allocate<LocalTools>()
        ReflectionFixture.set(localTools, "context", context)
        ReflectionFixture.set(localTools, "permissionBroker", AgentPermissionBroker(context, isDebugBuild = true))
        ReflectionFixture.set(localTools, "timeTool\$delegate", lazy { createTimeTool(Locale.ENGLISH) })
        val prefetchClient = OkHttpClient.Builder().addInterceptor { error("Inline seed must avoid network: ${it.request().url}") }.build()
        return DeepReadAgentRunManager(
            appContext = context, settingsStore = settingsStore, kernel = kernel, hotListRepository = repository,
            toolSetFactory = AgentToolSetFactory(localTools, context),
            sourcePrefetcher = DeepReadSourcePrefetcher(settingsStore, repository, prefetchClient),
            playbookRepository = DeepReadPlaybookRepository(context), researchHarness = DeepReadResearchHarness(),
            appScope = scope, providerCatalog = catalog, artifactRepository = artifactRepository, restoreWriteGate = gate,
        )
    }

    private fun createSettingsStore(): SettingsAggregator {
        val root = File(context.cacheDir, "deepread-generation-${Uuid.random()}").apply { mkdirs() }
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") }
        val secrets = SecretStore(object : SecretStoreBackend {
            private val values = mutableMapOf<String, String>()
            override fun get(key: String): String? = values[key]
            override fun put(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
            override fun keys(): Set<String> = values.keys.toSet()
        }, object : SecretCipher {
            override fun encrypt(plaintext: String): String = "test:$plaintext"
            override fun decrypt(stored: String): String? = stored.removePrefix("test:")
        })
        return SettingsAggregator(store, UIPrefs(store, scope), SearchPrefs(store, scope, secrets), AgentPrefs(store, scope),
            ProviderPrefs(store, scope, secrets), ChatPrefs(store, scope, secrets), ExtensionPrefs(store, scope, secrets), scope, SecretRedactor(secrets))
    }

    companion object {
        private const val TOPIC = "deepread-fallback-fixture"
        private const val TITLE = "DeepRead generation fallback fixture"
        private const val SOURCE = "https://example.com/deepread-fixture"
        private const val RUN_ID = "deepread-fixture-run"
        private const val ABANDONED = "ABANDONED_STREAM_OUTPUT_MUST_NOT_SURVIVE"
        private val WRITERS = listOf("deep_read_write_overview", "deep_read_write_narrative", "deep_read_write_analysis", "deep_read_write_extended_reading")
    }
}
