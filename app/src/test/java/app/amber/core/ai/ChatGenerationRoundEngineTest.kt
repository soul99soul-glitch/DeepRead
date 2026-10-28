package app.amber.core.ai

import android.app.Application
import app.amber.ai.core.Tool
import app.amber.ai.core.TokenUsage
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.ImageModelGateway
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.ImageEncodingException
import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.ai.transformers.MessageTransformer
import app.amber.core.ai.transformers.OcrTransformer
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.runtime.sha256Hex
import app.amber.core.context.AgentCapabilitySnapshotBuilder
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.infra.AppScope
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.ContextCompactionSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.core.settings.ModelGroupSessionDefault
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.runtime.DurableRuntimeTestBase
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wire-attempt isolation of the round verdict: [ChatGenerationRoundEngine]
 * must decide `outputLimitReached` from the finish_reason of the wire attempt
 * whose output is actually ADOPTED — a truncation reported by a failed or
 * abandoned attempt (retry / Generative-UI repair / vision fallback) must
 * never leak into the verdict. The production components are real; the only
 * fake is the provider gateway boundary.
 */
@OptIn(ExperimentalUuidApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatGenerationRoundEngineTest : DurableRuntimeTestBase() {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Scripted fake at the provider boundary: wire attempt N replays
     * `attempts[N]` — emit its chunks, then optionally throw.
     */
    private class ScriptedGateway(
        private val attempts: List<Attempt>,
        private val beforeWire: (List<UIMessage>, TextGenerationParams) -> Unit = { _, _ -> },
    ) : TextModelGateway<ProviderSetting.OpenAI>, ImageModelGateway<ProviderSetting.OpenAI> {

        data class Attempt(
            val chunks: List<MessageChunk>,
            val errorAfterChunks: Throwable? = null,
        )

        val received = mutableListOf<List<UIMessage>>()
        val receivedParams = mutableListOf<TextGenerationParams>()
        private var call = 0

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun complete(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            beforeWire(messages, params)
            received += messages
            receivedParams += params
            val attempt = attempts.getOrNull(call++) ?: error("no script for wire attempt $call")
            attempt.errorAfterChunks?.let { throw it }
            return attempt.chunks.single()
        }

        override suspend fun stream(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            beforeWire(messages, params)
            received += messages
            receivedParams += params
            val attempt = attempts.getOrNull(call++) ?: error("no script for wire attempt $call")
            attempt.chunks.forEach { emit(it) }
            attempt.errorAfterChunks?.let { throw it }
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting.OpenAI,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("not used")
    }

    private fun visionModel() = Model(
        modelId = "engine-test-model",
        displayName = "Engine Test Model",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        contextWindowTokens = 128_000,
    )

    private fun providerSetting(model: Model) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "Engine Test OpenAI",
        models = listOf(model),
        baseUrl = "https://api.openai.com/v1",
    )

    private fun settings(model: Model, provider: ProviderSetting.OpenAI) = Settings(
        providers = listOf(provider.copy(models = listOf(model))),
        systemPrompt = "You are the engine test assistant.",
        agentRuntime = AgentRuntimeSetting(
            agentSoulMarkdown = "",
            enableRecentChatsReference = false,
            enableCoreMemory = false,
            enableShortTermMemory = false,
            enableLongTermMemory = false,
            generativeUi = GenerativeUiSetting(enabled = false),
            contextCompaction = ContextCompactionSetting(enabled = false),
        ),
    )

    private fun providerCatalog(gateway: ScriptedGateway) = ProviderCatalog(
        openAIProvider = app.amber.ai.provider.providers.OpenAIProvider(OkHttpClient(), context),
        googleProvider = app.amber.ai.provider.providers.GoogleProvider(OkHttpClient(), context),
        claudeProvider = app.amber.ai.provider.providers.ClaudeProvider(OkHttpClient(), context),
        openAITextGateway = gateway,
        openAIImageGateway = gateway,
    )

    private fun engine(
        gateway: ScriptedGateway,
        providerCatalog: ProviderCatalog = providerCatalog(gateway),
    ): ChatGenerationRoundEngine {
        val conversationRepo = conversationRepository()
        val memoryRepo = MemoryRepository(
            memoryDAO = database.memoryDao(),
            candidateDAO = database.memoryCandidateDao(),
            eventDAO = database.memoryEventDao(),
            appDatabase = database,
        )
        val contextEngine = ConversationContextEngine(
            providerCatalog = providerCatalog,
            json = json,
            contextRepository = ConversationContextRepository(
                compactDAO = database.conversationCompactDao(),
                eventDAO = database.conversationContextEventDao(),
                conversationRepository = conversationRepo,
            ),
            appScope = AppScope(),
            capabilitySnapshotBuilder = AgentCapabilitySnapshotBuilder(),
            promptConfigRepository = AgentPromptConfigRepository(context),
            context = context,
        )
        return ChatGenerationRoundEngine(
            context = context,
            providerCatalog = providerCatalog,
            json = json,
            memoryRecallStore = MemoryRecallStore(memoryRepo),
            conversationRepo = conversationRepo,
            aiLoggingManager = AILoggingManager(),
            conversationContextEngine = contextEngine,
        )
    }

    private suspend fun request(
        model: Model,
        settings: Settings,
        messages: List<UIMessage>,
        conversation: Conversation? = null,
        stream: Boolean = true,
        tools: List<Tool> = emptyList(),
        transformers: List<MessageTransformer> = emptyList(),
        events: AgentEventWriter? = null,
    ): GenerationRoundRequest {
        val resolvedConversation = conversation ?: conversationFor(messages)
        return GenerationRoundRequest(
            settings = settings.toGenerationSettings(model),
            messages = messages,
            transformers = transformers,
            model = model,
            tools = tools,
            memories = emptyList(),
            stream = stream,
            processingStatus = MutableStateFlow(null),
            conversation = resolvedConversation,
            speculativeRunner = null,
            loopBudgetPrompt = "",
            responsesResume = null,
            events = events,
            runId = if (events != null) "complete-attempt-test" else null,
            stepIndex = if (events != null) 3 else -1,
            durablePath = events != null,
        )
    }

    private suspend fun conversationFor(messages: List<UIMessage>): Conversation {
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = AMBER_AGENT_ID,
            messageNodes = messages.map { MessageNode.of(it) },
        )
        conversationRepository().insertConversation(conversation)
        return conversation
    }

    /** An image-carrying user turn — the vision fallback's precondition. */
    private fun imageUserMessage(): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Text("这张图里是什么？"),
            // Plain URL (not a data: URI): the conversation row refuses to
            // persist base64 parts, and the vision fallback only needs a
            // non-blank image URL.
            UIMessagePart.Image(url = "https://example.com/cat.png"),
        ),
    )

    private fun deltaChunk(
        text: String?,
        finishReason: String?,
    ): MessageChunk = MessageChunk(
        id = "engine_test_chunk",
        model = "engine-test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = if (text != null) {
                    UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))
                } else {
                    null
                },
                message = null,
                finishReason = finishReason,
            )
        ),
    )

    @Test
    fun `a truncation from a failed attempt does not leak into the adopted fallback attempt`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(
            listOf(
                // Attempt 1 (primary): reports `length`, then dies with an
                // image-decode failure → the vision fallback adopts attempt 2.
                ScriptedGateway.Attempt(
                    chunks = listOf(deltaChunk(text = "写到一半", finishReason = "length")),
                    errorAfterChunks = ImageEncodingException(
                        imageUrl = "https://example.com/cat.png",
                        cause = IllegalStateException("decode failed"),
                    ),
                ),
                // Attempt 2 (vision fallback): succeeds, no finish_reason.
                ScriptedGateway.Attempt(chunks = listOf(deltaChunk(text = "图里是一只猫。", finishReason = null))),
            ),
        )
        val settings = settings(model, providerSetting(model))
        val messages = listOf(imageUserMessage())
        val generationRequest = request(model, settings, messages)

        val outcome = engine(gateway).generateRound(generationRequest) { }

        assertTrue("the vision fallback must have run", gateway.received.size == 2)
        assertEquals(
            listOf(generationRequest.conversation!!.id.toString(), generationRequest.conversation!!.id.toString()),
            gateway.receivedParams.map { it.sessionId },
        )
        assertFalse(
            "the abandoned attempt's `length` must not truncate the adopted output",
            outcome.outputLimitReached,
        )
    }

    @Test
    fun `a truncation in the adopted attempt still reports outputLimitReached`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(
            listOf(
                // The only attempt completes normally with `length` — a real
                // truncation of the adopted output.
                ScriptedGateway.Attempt(chunks = listOf(deltaChunk(text = "写到一半", finishReason = "length"))),
            ),
        )
        val settings = settings(model, providerSetting(model))
        val messages = listOf(imageUserMessage())

        val outcome = engine(gateway).generateRound(request(model, settings, messages)) { }

        assertTrue(outcome.outputLimitReached)
    }

    @Test
    fun `a normal stop after a truncation within one attempt wins`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(
            listOf(
                ScriptedGateway.Attempt(
                    chunks = listOf(
                        deltaChunk(text = "写到一半", finishReason = "length"),
                        deltaChunk(text = null, finishReason = "stop"),
                    ),
                ),
            ),
        )
        val settings = settings(model, providerSetting(model))
        val messages = listOf(imageUserMessage())

        val outcome = engine(gateway).generateRound(request(model, settings, messages)) { }

        assertFalse(outcome.outputLimitReached)
    }

    @Test
    fun `conversation keeps one session across rounds and isolates another conversation`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(
            listOf(
                ScriptedGateway.Attempt(chunks = listOf(deltaChunk(text = "第一轮", finishReason = null))),
                ScriptedGateway.Attempt(chunks = listOf(deltaChunk(text = "第二轮", finishReason = null))),
                ScriptedGateway.Attempt(chunks = listOf(deltaChunk(text = "另一个会话", finishReason = null))),
            ),
        )
        val settings = settings(model, providerSetting(model))
        val firstConversation = conversationFor(listOf(UIMessage.user("同一会话")))
        val secondConversation = conversationFor(listOf(UIMessage.user("另一个会话")))
        val firstRound = request(
            model,
            settings,
            listOf(UIMessage.user("第一轮")),
            conversation = firstConversation,
        )
        val secondRound = request(
            model,
            settings,
            listOf(UIMessage.user("第二轮")),
            conversation = firstConversation,
        )
        val otherConversationRound = request(
            model,
            settings,
            listOf(UIMessage.user("另一个会话")),
            conversation = secondConversation,
        )
        val roundEngine = engine(gateway)

        roundEngine.generateRound(firstRound) { }
        roundEngine.generateRound(secondRound) { }
        roundEngine.generateRound(otherConversationRound) { }

        assertEquals(
            listOf(
                firstConversation.id.toString(),
                firstConversation.id.toString(),
                secondConversation.id.toString(),
            ),
            gateway.receivedParams.map { it.sessionId },
        )
        assertNotEquals(firstConversation.id.toString(), secondConversation.id.toString())
    }
    private class RecordingWriter(
        private val commitFailure: Throwable? = null,
    ) : AgentEventWriter {
        val snapshots = mutableListOf<ChatEventPayload.RequestSnapshot>()

        override fun emit(transient: AgentEventPayload.Transient) = Unit
        override suspend fun commit(final: AgentEventPayload.Final) {
            commitFailure?.let { throw it }
            snapshots += final as ChatEventPayload.RequestSnapshot
        }
        override suspend fun flush() = Unit
        override suspend fun commitError(throwable: Throwable, recoverable: Boolean) = Unit
    }

    private fun completeChunk(
        text: String,
        finishReason: String? = null,
        tokens: Int = 7,
    ) = MessageChunk(
        id = "complete_test_chunk",
        model = "engine-test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                message = UIMessage.assistant(text),
                delta = null,
                finishReason = finishReason,
            ),
        ),
        usage = TokenUsage(promptTokens = tokens, completionTokens = tokens + 1),
    )

    private val validWidget = """
        ```show-widget
        {"title":"Flow","widget_code":"<svg viewBox=\"0 0 680 240\"><text x=\"24\" y=\"40\">adopted repair</text></svg>"}
        ```
    """.trimIndent()

    private fun widgetSettings(model: Model, provider: ProviderSetting.OpenAI): Settings =
        settings(model, provider).let {
            it.copy(agentRuntime = it.agentRuntime.copy(generativeUi = GenerativeUiSetting(enabled = true)))
        }

    private fun List<UIMessage>.assistantText(): String =
        lastOrNull { it.role == MessageRole.ASSISTANT }?.parts
            ?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }.orEmpty()

    private fun assertWireMatchesSnapshot(
        writer: RecordingWriter,
        wireIndex: Int,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ) {
        assertEquals("snapshot commits before its wire call", wireIndex + 1, writer.snapshots.size)
        val snapshot = writer.snapshots.last()
        assertEquals(wireIndex, snapshot.attempt)
        assertEquals(3, snapshot.stepIndex)
        assertEquals(messages.size, snapshot.messageCount)
        assertEquals(
            sha256Hex(json.encodeToString(ListSerializer(UIMessage.serializer()), messages)),
            snapshot.messagesDigest,
        )
        assertEquals(sha256Hex(params.tools.map { it.name }.sorted().joinToString("\n")), snapshot.toolCatalogDigest)
        assertEquals(params.tools.size, snapshot.exposedToolCount)
    }

    @Test
    fun `complete repair adopts its own finish and usage with audited tool-free request`() = runBlocking {
        val model = visionModel()
        val provider = providerSetting(model)
        val writer = RecordingWriter()
        var wireIndex = 0
        val gateway = ScriptedGateway(
            attempts = listOf(
                ScriptedGateway.Attempt(listOf(completeChunk("discarded primary", "length", tokens = 101))),
                ScriptedGateway.Attempt(listOf(completeChunk(validWidget, tokens = 11))),
            ),
            beforeWire = { messages, params -> assertWireMatchesSnapshot(writer, wireIndex++, messages, params) },
        )
        val tool = Tool(name = "write_file", description = "side effect") { emptyList() }
        val generationRequest = request(
            model, widgetSettings(model, provider), listOf(UIMessage.user("画一个流程图")),
            stream = false, tools = listOf(tool), events = writer,
        )
        val updates = mutableListOf<GenerationUpdate>()

        val outcome = engine(gateway).generateRound(generationRequest) { updates += it }

        assertFalse(outcome.outputLimitReached)
        assertEquals(listOf("primary", "generative_ui_repair"), writer.snapshots.map { it.kind })
        assertEquals(listOf(1, 0), gateway.receivedParams.map { it.tools.size })
        assertEquals(List(2) { generationRequest.conversation!!.id.toString() }, gateway.receivedParams.map { it.sessionId })
        assertEquals(1, updates.size)
        assertEquals(validWidget, updates.single().messages.assistantText())
        assertEquals(TokenUsage(promptTokens = 11, completionTokens = 12, totalTokens = 23), updates.single().messages.last().usage)
        assertEquals(null, generationRequest.processingStatus.value)
    }

    @Test
    fun `complete length on the adopted repair still reports output limit`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(listOf(
            ScriptedGateway.Attempt(listOf(completeChunk("discarded primary", "stop"))),
            ScriptedGateway.Attempt(listOf(completeChunk(validWidget, "length"))),
        ))
        val generationRequest = request(
            model, widgetSettings(model, providerSetting(model)), listOf(UIMessage.user("画一个流程图")),
            stream = false,
        )
        var finalMessages = emptyList<UIMessage>()

        val outcome = engine(gateway).generateRound(generationRequest) { finalMessages = it.messages }

        assertTrue(outcome.outputLimitReached)
        assertEquals(validWidget, finalMessages.assistantText())
        assertEquals(2, gateway.received.size)
    }

    @Test
    fun `complete plain length without repair reports output limit`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(listOf(
            ScriptedGateway.Attempt(listOf(completeChunk("plain incomplete answer", "length"))),
        ))
        val outcome = engine(gateway).generateRound(request(
            model, settings(model, providerSetting(model)), listOf(UIMessage.user("ordinary question")), stream = false,
        )) { }
        assertTrue(outcome.outputLimitReached)
        assertEquals(1, gateway.received.size)
    }

    @Test
    fun `complete repair network failure resets outer retry and isolates accepted usage`() = runTest {
        val model = visionModel()
        val writer = RecordingWriter()
        var wireIndex = 0
        val gateway = ScriptedGateway(
            listOf(
                ScriptedGateway.Attempt(listOf(completeChunk("discarded primary", "length", tokens = 101))),
                ScriptedGateway.Attempt(emptyList(), java.net.SocketException("connection reset")),
                ScriptedGateway.Attempt(listOf(completeChunk(validWidget, tokens = 13))),
            ),
            beforeWire = { messages, params -> assertWireMatchesSnapshot(writer, wireIndex++, messages, params) },
        )
        val configured = widgetSettings(model, providerSetting(model)).let {
            it.copy(agentRuntime = it.agentRuntime.copy(generationRetry = GenerationRetrySetting(
                maxRetries = 1, initialDelayMs = 1_000L, jitterRatio = 0f,
            )))
        }
        val generationRequest = request(
            model, configured, listOf(UIMessage.user("画一个流程图")), stream = false,
            tools = listOf(Tool(name = "write_file", description = "side effect") { emptyList() }), events = writer,
        )
        val updates = mutableListOf<GenerationUpdate>()

        val outcome = engine(gateway).generateRound(generationRequest) { updates += it }

        assertFalse(outcome.outputLimitReached)
        assertEquals(listOf("primary", "generative_ui_repair", "primary"), writer.snapshots.map { it.kind })
        assertEquals(listOf(1, 0, 1), gateway.receivedParams.map { it.tools.size })
        assertEquals(gateway.received.first(), gateway.received.last())
        assertEquals(List(3) { generationRequest.conversation!!.id.toString() }, gateway.receivedParams.map { it.sessionId })
        assertEquals(2, updates.size)
        assertEquals(generationRequest.messages, updates.first().messages)
        assertEquals(validWidget, updates.last().messages.assistantText())
        assertEquals(TokenUsage(promptTokens = 13, completionTokens = 14, totalTokens = 27), updates.last().messages.last().usage)
        assertEquals(null, generationRequest.processingStatus.value)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `complete snapshot cancellation prevents wire and retry`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(emptyList())
        val generationRequest = request(
            model, settings(model, providerSetting(model)), listOf(UIMessage.user("question")), stream = false,
            events = RecordingWriter(CancellationException("stop during snapshot")),
        )
        val failure = runCatching { engine(gateway).generateRound(generationRequest) { error("no update") } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("stop during snapshot", failure?.message)
        assertTrue(gateway.received.isEmpty())
    }

    @Test
    fun `complete ordinary snapshot failure remains best effort`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(listOf(ScriptedGateway.Attempt(listOf(completeChunk("answer", "stop")))))
        val generationRequest = request(
            model, settings(model, providerSetting(model)), listOf(UIMessage.user("question")), stream = false,
            events = RecordingWriter(IllegalStateException("audit unavailable")),
        )
        val outcome = engine(gateway).generateRound(generationRequest) { }
        assertFalse(outcome.outputLimitReached)
        assertEquals(1, gateway.received.size)
    }

    @Test
    fun `complete vision fallback replaces image through actual OCR transformer`() = runBlocking {
        val model = visionModel()
        val ocrModel = visionModel().copy(id = Uuid.random(), modelId = "ocr-model")
        val provider = providerSetting(model).copy(models = listOf(model, ocrModel))
        val gateway = ScriptedGateway(listOf(
            ScriptedGateway.Attempt(emptyList(), ImageEncodingException("https://example.com/cat.png", IllegalStateException("decode failed"))),
            ScriptedGateway.Attempt(listOf(completeChunk("OCR recognized a tabby cat", "stop"))),
            ScriptedGateway.Attempt(listOf(completeChunk("adopted cat answer", "length"))),
        ))
        val catalog = providerCatalog(gateway)
        startKoin { modules(module {
            single<android.content.Context> { context }
            single { catalog }
        }) }
        try {
            val configured = settings(model, provider).copy(providers = listOf(provider), ocrModelId = ocrModel.id)
            val generationRequest = request(
                model, configured, listOf(imageUserMessage()), stream = false, transformers = listOf(OcrTransformer),
            )
            var finalMessages = emptyList<UIMessage>()

            val outcome = engine(gateway, catalog).generateRound(generationRequest) { finalMessages = it.messages }

            assertTrue(outcome.outputLimitReached)
            assertEquals(listOf(model.modelId, ocrModel.modelId, model.modelId), gateway.receivedParams.map { it.model.modelId })
            assertTrue(gateway.received.first().any { it.parts.any { part -> part is UIMessagePart.Image } })
            assertFalse(gateway.received.last().any { it.parts.any { part -> part is UIMessagePart.Image } })
            assertTrue(gateway.received.last().any { it.parts.any { part -> part is UIMessagePart.Text && "OCR recognized a tabby cat" in part.text } })
            assertEquals(gateway.receivedParams.first().sessionId, gateway.receivedParams.last().sessionId)
            assertEquals("adopted cat answer", finalMessages.assistantText())
            assertEquals(null, generationRequest.processingStatus.value)
        } finally {
            stopKoin()
        }
    }

    @Test
    fun `model group defaults and explicit overrides reach actual wire params`() = runBlocking {
        val model = visionModel().copy(modelId = "gpt-5.4")
        val groupSettings = settings(model, providerSetting(model)).copy(
            contextMessageSize = 0,
            modelGroupSessionDefaults = listOf(ModelGroupSessionDefault(
                groupId = "openai_reasoning",
                contextMessageSize = 2,
                maxTokens = 321,
            )),
        )
        val gateway = ScriptedGateway(listOf(
            ScriptedGateway.Attempt(listOf(completeChunk("group reply"))),
            ScriptedGateway.Attempt(listOf(completeChunk("explicit reply"))),
        ))
        val roundEngine = engine(gateway)
        val messages = listOf(UIMessage.user("Answer this question"))
        roundEngine.generateRound(request(model, groupSettings, messages, stream = false)) { }
        roundEngine.generateRound(request(model, groupSettings.copy(
            maxTokens = 654,
            reasoningLevel = ReasoningLevel.HIGH,
            temperature = 0.25f,
        ), messages, stream = false)) { }
        assertEquals(listOf(321, 654), gateway.receivedParams.map { it.maxTokens })
        assertEquals(listOf(ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), gateway.receivedParams.map { it.reasoningLevel })
        assertEquals(0.25f, gateway.receivedParams.last().temperature)
    }

}
