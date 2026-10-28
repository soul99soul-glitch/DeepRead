package app.amber.core.service

import android.content.ContextWrapper
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AssistantId
import app.amber.core.agent.runtime.ConversationId
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.chat.api.ChatTurnInput
import app.amber.feature.chat.impl.ChatSession
import app.amber.feature.chat.impl.ChatSessionResolver
import app.amber.feature.chat.impl.ChatTurnAgent
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.review.ReflectionFixture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationProjectionAtomicityTest : DurableRuntimeTestBase() {
    @get:Rule val queueDirectory = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()

    @After
    fun closeOwners() = fixtures.forEach { it.close() }

    @Test
    fun metadataTransformCannotReplaceANewerStreamedTail() = runBlocking {
        val fixture = fixture()
        val original = conversation()
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val captured = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val firstTransform = AtomicBoolean(true)
        val metadata = async(Dispatchers.Default) {
            fixture.store.updateConversationState(original.id) { current ->
                // Suspend only the first invocation; a legitimate CAS retry must use fresh state.
                if (firstTransform.compareAndSet(true, false)) {
                    captured.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS)) { "Metadata projection was not released" }
                }
                current.copy(title = "Renamed")
            }
        }
        val latest = original.copy(messageNodes = original.messageNodes.dropLast(1) +
            original.messageNodes.last().copy(messages = listOf(
                original.currentMessages.last().copy(parts = listOf(UIMessagePart.Text("LATEST_TAIL"))),
            )))
        try {
            withTimeout(5_000) { captured.await() }
            // This is the same store projection command used by the real ChatTurnAgent.
            fixture.store.updateConversation(original.id, latest, checkDeletedFiles = false)
        } finally {
            release.countDown()
        }
        metadata.await()
        val live = fixture.store.getConversationFlow(original.id).value
        assertTrue(fixture.store.checkpointConversation(original.id, live, force = true))
        val durable = fixture.repo.getConversationById(original.id)!!
        assertEquals("Renamed", live.title)
        assertEquals(latest.messageNodes, live.messageNodes)
        assertEquals(latest.messageNodes, durable.messageNodes)
    }

    @Test
    fun actualStreamingProjectionCannotUndoARenameCommittedAfterItsStateRead() = runBlocking {
        val fixture = fixture()
        val original = conversation()
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val session = fixture.store.session(original.id)
        val currentState = session.state
        val captured = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        // A thread can be descheduled immediately after reading the state. Keep that real
        // boundary deterministic, without replacing either the agent merge or the store owner.
        val pausedState = object : MutableStateFlow<Conversation> by currentState {
            override var value: Conversation
                get() {
                    val snapshot = currentState.value
                    if (firstRead.compareAndSet(true, false)) {
                        captured.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS)) { "Stream projection was not released" }
                    }
                    return snapshot
                }
                set(value) { currentState.value = value }
        }
        ReflectionFixture.set(session, "state", pausedState)
        val reply = original.currentMessages.last().copy(parts = listOf(UIMessagePart.Text("LATEST_TAIL")))
        val agent = ChatTurnAgent(
            kernel = object : RunKernel {
                override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                    emit(GenerationChunk.Messages(listOf(reply)))
                }
            },
            sessionResolver = object : ChatSessionResolver {
                override suspend fun resolve(input: ChatTurnInput, runId: String, events: AgentEventWriter?): ChatSession =
                    ChatSession(
                        settings = Settings(), model = Model(modelId = "projection-test"),
                        messages = original.currentMessages,
                        inputTransformers = emptyList(), outputTransformers = emptyList(),
                        memories = null, tools = emptyList(), maxSteps = 1,
                        autoApproveTools = false, autoApproveHighRiskTools = false,
                        autoApprovedToolNames = emptySet(), invocationContext = ToolInvocationContext.Normal,
                        conversation = original,
                    )
            },
            conversationAccess = fixture.store,
        )
        val streaming = async(Dispatchers.Default) {
            agent.handler.handle(
                ChatTurnInput(
                    conversationId = ConversationId(original.id.toString()),
                    messageNodeId = MessageNodeId(original.messageNodes.last().id.toString()),
                    assistantId = AssistantId("default"), userMessageText = "Question",
                ),
                LegacyRunScope(AgentRunId("projection-test")),
            )
        }
        try {
            withTimeout(5_000) { captured.await() }
            fixture.store.updateTitle(original.id, "Committed rename")
        } finally {
            release.countDown()
        }
        streaming.await()
        val live = fixture.store.getConversationFlow(original.id).value
        assertTrue(fixture.store.checkpointConversation(original.id, live, force = true))
        val durable = fixture.repo.getConversationById(original.id)!!
        assertEquals("LATEST_TAIL", live.currentMessages.last().toText())
        assertEquals("Committed rename", live.title)
        assertEquals("Committed rename", durable.title)
        assertEquals(live.messageNodes, durable.messageNodes)
    }

    private fun conversation() = Conversation.ofId(Uuid.random()).copy(
        title = "Original title",
        messageNodes = listOf(MessageNode.of(UIMessage.user("Question")), MessageNode.of(UIMessage.assistant("partial"))),
    )

    private fun fixture(): Fixture {
        val gate = SyncRestoreWriteGate()
        val repo = conversationRepository(gate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val appScope = AppScope()
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(Settings()))
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), appScope, gate)
        val root = queueDirectory.newFolder()
        val queue = PendingMessageQueue(scope, PendingMessageStore(object : ContextWrapper(context) {
            override fun getFilesDir() = root
        }, Json), gate)
        val store = ConversationStore(repo, settings, scope, files, queue, ChatErrorStore(), gate)
        return Fixture(store, repo, queue, scope, appScope).also(fixtures::add)
    }

    private class Fixture(
        val store: ConversationStore,
        val repo: ConversationRepository,
        private val queue: PendingMessageQueue,
        private val scope: CoroutineScope,
        private val appScope: AppScope,
    ) {
        fun close() {
            store.cleanup()
            queue.cleanup()
            scope.cancel()
            appScope.cancel()
        }
    }
}
