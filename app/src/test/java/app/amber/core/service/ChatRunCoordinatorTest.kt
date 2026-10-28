package app.amber.core.service

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.providers.openai.StoredResponseApi
import app.amber.ai.provider.providers.openai.StoredResponseCancelResult
import app.amber.ai.provider.providers.openai.StoredResponseStatus
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.RunTransitionResult
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.ai.mcp.McpManager
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.context.ConversationContextEngine
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.JsonInstant
import app.amber.feature.bubble.AgentBubbleReducer
import app.amber.feature.bubble.AgentBubbleUiState
import app.amber.feature.bubble.AgentBubblePhase
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.StoredResponseGateway
import app.amber.feature.runtime.StoredResponseStopCancel
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskStore
import app.amber.review.ReflectionFixture
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRunCoordinatorTest : DurableRuntimeTestBase() {
    @get:Rule val directory = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun closeOwners() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `same drain cannot launch queued input before the first handler settles`() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture(durable = false)
            val id = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(id))
            fixture.coordinator.sendMessage(id, listOf(UIMessagePart.Text("First")))
            fixture.runner.firstLaunched.await()
            fixture.coordinator.sendMessage(id, listOf(UIMessagePart.Text("Second")))
            val dispatch = fixture.store.session(id).getJob()!!

            fixture.runner.publish(0, RunStatus.COMPLETED)
            fixture.runner.firstSettlementEntered.await()

            assertEquals(1, fixture.runner.handles.size)
            assertTrue(fixture.store.session(id).isGenerating)
            assertEquals(1, fixture.queue.flow(id).value.size)
            fixture.runner.firstSettled.complete(Unit)
            fixture.runner.secondLaunched.await()
            assertEquals(2, fixture.runner.handles.size)
            assertTrue(fixture.queue.flow(id).value.isEmpty())
            assertEquals(listOf("First", "Second"), fixture.repo.getConversationById(id)!!.currentMessages.map { it.toText() })
            fixture.runner.publish(1, RunStatus.COMPLETED)
            dispatch.join()
        }
    }

    @Test
    fun `sending during cancelled handler cleanup waits before its next wire and still executes`() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture(durable = false)
            val id = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(id))
            fixture.coordinator.sendMessage(id, listOf(UIMessagePart.Text("First")))
            fixture.runner.firstLaunched.await()
            val oldDispatch = fixture.store.session(id).getJob()!!
            oldDispatch.cancel()
            fixture.runner.firstSettlementEntered.await()

            assertTrue(fixture.coordinator.sendMessage(id, listOf(UIMessagePart.Text("Second"))))
            // Hold the first handler's cleanup, and observe the independent wire
            // boundary before releasing it. This timeout belongs only to the fixture.
            val launchedBeforeSettlement = withTimeoutOrNull(1_000) {
                fixture.runner.secondLaunched.await()
                true
            } == true
            assertFalse("A new input must wait for the cancelled handler to settle", launchedBeforeSettlement)
            fixture.runner.firstSettled.complete(Unit)
            oldDispatch.join()
            fixture.runner.secondLaunched.await()
            assertFalse(fixture.runner.secondWireBeforeFirstSettled)
            val resumed = fixture.store.session(id).getJob()!!
            fixture.runner.publish(1, RunStatus.COMPLETED)
            resumed.join()
            assertEquals(listOf("First", "Second"), fixture.repo.getConversationById(id)!!.currentMessages.map { it.toText() })
        }
    }

    @Test
    fun `server cancellation returning late cannot stop a successor dispatch`() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture()
            val id = Uuid.random()
            val oldRun = AgentRunId("old-run")
            val successor = AgentRunId("successor-run")
            fixture.repo.insertConversation(Conversation.ofId(id).copy(messageNodes = listOf(pendingTool())))
            runTerminalStore.begin(oldRun.value, id.toString(), null)
            fixture.activeRuns.value = mapOf(id to oldRun)
            val oldJob = Job(fixture.scope.coroutineContext[Job])
            fixture.store.session(id).setJob(oldJob)
            val stop = launch { fixture.coordinator.stopGeneration(id, oldRun.value) }
            fixture.api.entered.await()

            val successorJob = Job(fixture.scope.coroutineContext[Job])
            fixture.store.session(id).setJob(successorJob)
            fixture.activeRuns.value = mapOf(id to successor)
            fixture.api.release.complete(StoredResponseCancelResult.CancelFailed)
            stop.join()

            assertTrue(successorJob.isActive)
            assertEquals(successor, fixture.activeRuns.value[id])
            assertTrue(fixture.runner.cancelled.isEmpty())
            assertTrue(fixture.repo.getConversationById(id)!!.currentMessages.last().getTools().single().isPending)
            assertFalse(fixture.stopCancel.consumeStopUnconfirmed(oldRun.value))
        }
    }

    @Test
    fun `unconfirmed server Stop of a persisted approval retains waiting external and cursor`() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture()
            val id = Uuid.random()
            val runId = "paused-response-run"
            fixture.repo.insertConversation(Conversation.ofId(id).copy(messageNodes = listOf(pendingTool())))
            fixture.store.ensureInitialized(id)
            runTerminalStore.begin(runId, id.toString(), null)
            runTerminalStore.pause(runId, RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
            fixture.events.appendRun(AgentRunRecord(
                runId, null, "chat_turn", "1.0.0", id.toString(), null, null, null,
                RunStatus.CREATED, "digest", null, 1, System.currentTimeMillis(), null, null,
            ))
            fixture.events.transitionRun(AgentRunId(runId), emptySet(), RunStatus.RUNNING)
            fixture.events.transitionRun(AgentRunId(runId), emptySet(), RunStatus.WAITING_USER)
            fixture.api.release.complete(StoredResponseCancelResult.CancelFailed)

            fixture.coordinator.stopGeneration(id, runId)

            val persisted = runTerminalStore.get(runId)!!
            assertEquals(RunTerminalState.WAITING_EXTERNAL, persisted.state)
            assertEquals(RunStatus.WAITING_EXTERNAL, fixture.events.runs.getValue(runId).status)
            assertNotNull(fixture.resumeStore.load(runId))
            assertEquals(setOf(id), fixture.coordinator.activeConversationIds.value)
            assertFalse(fixture.coordinator.lastRunOutcomes.value.containsKey(id))
            val bubble = AgentBubbleReducer.reduce(
                previous = AgentBubbleUiState(), pickedConversationId = id.toString(), activity = null,
                messages = fixture.store.getConversationFlow(id).value.currentMessages, nowMillis = 0,
            )
            assertEquals(AgentBubblePhase.RUNNING, bubble.phase)
            assertFalse("the stopped approval must no longer offer approve/reply", bubble.waitingApproval)
            assertFalse("there is no handler left to consume an in-process Stop latch", fixture.stopCancel.consumeStopUnconfirmed(runId))

            fixture.coordinator.stopGeneration(id, runId)
            assertEquals(RunTerminalState.WAITING_EXTERNAL, runTerminalStore.get(runId)!!.state)
            assertEquals(RunStatus.WAITING_EXTERNAL, fixture.events.runs.getValue(runId).status)
            assertNotNull(fixture.resumeStore.load(runId))
            assertFalse("repeated Stop must not leave a latch without a handler", fixture.stopCancel.consumeStopUnconfirmed(runId))
        }
    }

    @Test
    fun `same run activation claimed before Stop event CAS keeps both owners running`() = runBlocking {
        withTimeout(15_000) {
            val claimEntered = CompletableDeferred<Unit>()
            val releaseClaim = CompletableDeferred<Unit>()
            val durableEvents = InMemoryAgentEventStore()
            val gatedEvents = object : AgentEventStore by durableEvents {
                override suspend fun transitionRun(
                    runId: AgentRunId, expected: Set<RunStatus>, to: RunStatus, reason: String?,
                ): RunTransitionResult {
                    if (to == RunStatus.RUNNING && RunStatus.WAITING_USER in expected) {
                        claimEntered.complete(Unit)
                        releaseClaim.await()
                    }
                    return durableEvents.transitionRun(runId, expected, to, reason)
                }
            }
            val fixture = fixture(events = durableEvents, eventOwner = gatedEvents)
            val id = Uuid.random()
            val runId = "same-run-resumed"
            fixture.repo.insertConversation(Conversation.ofId(id).copy(messageNodes = listOf(pendingTool())))
            fixture.store.ensureInitialized(id)
            runTerminalStore.begin(runId, id.toString(), null)
            runTerminalStore.pause(runId, RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
            durableEvents.appendRun(AgentRunRecord(
                runId, null, "chat_turn", "1.0.0", id.toString(), null, null, null,
                RunStatus.CREATED, "digest", null, 1, System.currentTimeMillis(), null, null,
            ))
            durableEvents.transitionRun(AgentRunId(runId), setOf(RunStatus.CREATED), RunStatus.RUNNING)
            durableEvents.transitionRun(AgentRunId(runId), setOf(RunStatus.RUNNING), RunStatus.WAITING_USER)
            fixture.api.release.complete(StoredResponseCancelResult.CancelFailed)
            val stop = launch { fixture.coordinator.stopGeneration(id, runId) }
            try {
                claimEntered.await()
                val resumedJob = Job(fixture.scope.coroutineContext[Job])
                fixture.store.session(id).setJob(resumedJob)
                fixture.activeRuns.value = mapOf(id to AgentRunId(runId))
                val claim = durableEvents.transitionRun(AgentRunId(runId), RunStatus.PAUSE_STATES, RunStatus.RUNNING)
                assertTrue("the fixture activation must win the real pause-only claim", claim is RunTransitionResult.Applied)
                runTerminalStore.begin(runId, id.toString(), null)
                releaseClaim.complete(Unit)
                stop.join()

                assertEquals(RunTerminalState.RUNNING, runTerminalStore.get(runId)!!.state)
                assertEquals(RunStatus.RUNNING, durableEvents.runs.getValue(runId).status)
                assertTrue(resumedJob.isActive)
                @Suppress("UNCHECKED_CAST")
                val paused = ChatRunCoordinator::class.java.getDeclaredField("pausedForUserConversations").let {
                    it.isAccessible = true
                    it.get(fixture.coordinator) as StateFlow<Set<Uuid>>
                }
                assertFalse(id in paused.value)
                assertFalse(fixture.coordinator.lastRunOutcomes.value.containsKey(id))
                assertNotNull(fixture.resumeStore.load(runId))
                assertTrue(fixture.repo.getConversationById(id)!!.currentMessages.last().getTools().single().isPending)
                assertTrue(fixture.store.getConversationFlow(id).value.currentMessages.last().getTools().single().isPending)
            } finally {
                releaseClaim.complete(Unit)
            }
        }
    }

    @Test
    fun `matching old Stop preserves input accepted while the cancelled handler settles`() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture(durable = false)
            val id = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(id))
            fixture.coordinator.sendMessage(id, listOf(UIMessagePart.Text("First")))
            fixture.runner.firstLaunched.await()
            val oldDispatch = fixture.store.session(id).getJob()!!
            oldDispatch.cancel()
            fixture.runner.firstSettlementEntered.await()
            assertTrue(fixture.coordinator.sendMessage(id, listOf(UIMessagePart.Text("Second"))))

            fixture.coordinator.stopGeneration(id, fixture.runner.handles.first().runId.value)
            fixture.runner.firstSettled.complete(Unit)
            oldDispatch.join()

            assertEquals(1, fixture.runner.handles.size)
            assertEquals(listOf("Second"), fixture.queue.flow(id).value.map { it.previewText() })
            val writer = PendingMessageQueue::class.java.getDeclaredField("pendingMessagePersistDispatcher").let {
                it.isAccessible = true
                it.get(fixture.queue) as CoroutineDispatcher
            }
            // Wait behind the real writer without manufacturing a persistence call.
            withContext(writer) { }
            assertEquals(fixture.queue.flow(id).value, fixture.pendingStore.load(id))
        }
    }

    @Test
    fun `registered generation task cancels only the run captured by its hook`() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture(durable = false)
            val id = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(id))
            val hooksFactory = ReflectionFixture.allocate<ChatRunHooksFactory>()
            ReflectionFixture.set(hooksFactory, "context", context)
            ReflectionFixture.set(hooksFactory, "settingsStore", fixture.settings)
            ReflectionFixture.set(hooksFactory, "conversationStore", fixture.store)
            ReflectionFixture.set(hooksFactory, "approvalActions", fixture.approval)
            ReflectionFixture.set(hooksFactory, "runPresenter", fixture.presenter)
            val hooks = hooksFactory.create(id, fixture.coordinator::stopGeneration)
            hooks.onRunStarted("old-task-run")
            val successor = AgentRunId("successor-run")
            val successorJob = Job(fixture.scope.coroutineContext[Job])
            fixture.store.session(id).setJob(successorJob)
            fixture.activeRuns.value = mapOf(id to successor)

            fixture.scheduler.cancel("generation-$id")

            assertTrue(successorJob.isActive)
            assertEquals(successor, fixture.activeRuns.value[id])
            assertTrue(fixture.runner.cancelled.isEmpty())
        }
    }

    private fun pendingTool() = MessageNode.of(UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("call", "file_write", "{}", approvalState = ToolApprovalState.Pending)),
    ))

    private fun fixture(
        durable: Boolean = true,
        events: InMemoryAgentEventStore = InMemoryAgentEventStore(),
        eventOwner: AgentEventStore = events,
    ): Fixture {
        val root = directory.newFolder()
        val fileContext = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val scope = AppScope()
        val files = FilesManager(fileContext, FilesRepository(database.managedFileDao()), scope)
        val repo = ConversationRepository(
            database.conversationDao(), database.messageNodeDao(), database.messageStatsDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database),
        )
        val model = Model(modelId = "test", abilities = listOf(ModelAbility.TOOL))
        val initialSettings = Settings(chatModelId = model.id, providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(initialSettings.copy(agentRuntime = initialSettings.agentRuntime.copy(
            enableLiveStatusNotification = false,
            keepGenerationAliveInBackground = false,
        ))))
        // Match production DI: queued creation times must be encoded even within their default millisecond.
        val pendingStore = PendingMessageStore(fileContext, JsonInstant)
        val queue = PendingMessageQueue(scope, pendingStore)
        val errors = ChatErrorStore()
        val store = ConversationStore(repo, settings, scope, files, queue, errors)
        val flags = if (durable) CapabilityFlags(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }) else null
        val recovery = RunRecoveryService(ledger, runTerminalStore, repo, Json)
        val approval = ChatApprovalActions(context, Json, store, ledger, runTerminalStore, recovery, flags)
        val scheduler = AgentTaskScheduler(AgentTaskStore(fileContext, Json, loadDispatcher = Dispatchers.Unconfined))
        val presenter = ChatRunPresenter(fileContext, scope, AgentToolActivityStore(), AgentLiveStatusNotifier(context, NotificationApprovalTokenRegistry()), scheduler)
        val runner = QueueRunner()
        val resume = MemoryResumeStore()
        val api = PendingCancelApi()
        val gateway = object : StoredResponseGateway {
            override suspend fun resolve(runId: String) = StoredResponseGateway.StoredResponseSession(
                ResponseCursor("stored-response", 2, "provider"),
                ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1", useResponseApi = true), api,
            )
        }
        val stopCancel = StoredResponseStopCancel(gateway, resume)
        val coordinator = ChatRunCoordinator(
            context, scope, settings, store, queue, errors, presenter, approval,
            ReflectionFixture.allocate<ConversationContextEngine>(), ScreenCaptureManager(context), UserInputPreprocessor(settings),
            ReflectionFixture.allocate<ConversationEditor>(), ReflectionFixture.allocate<McpManager>(), agentRunner = runner, agentEventStore = eventOwner, capabilityFlags = flags,
            toolEffectLedger = ledger, runTerminalStore = runTerminalStore, runRecovery = recovery,
            responsesResumeStore = if (durable) resume else null,
            storedResponseGateway = if (durable) gateway else null,
            storedResponseStopCancel = stopCancel,
        )
        @Suppress("UNCHECKED_CAST")
        val activeRuns = ChatRunCoordinator::class.java.getDeclaredField("activeKernelRuns").let {
            it.isAccessible = true
            it.get(coordinator) as MutableStateFlow<Map<Uuid, AgentRunId>>
        }
        return Fixture(coordinator, store, queue, repo, scope, runner, activeRuns, api, resume, stopCancel, scheduler, presenter, settings, approval, events, pendingStore).also(fixtures::add)
    }

    private class Fixture(
        val coordinator: ChatRunCoordinator, val store: ConversationStore, val queue: PendingMessageQueue,
        val repo: ConversationRepository, val scope: AppScope, val runner: QueueRunner,
        val activeRuns: MutableStateFlow<Map<Uuid, AgentRunId>>, val api: PendingCancelApi,
        val resumeStore: MemoryResumeStore, val stopCancel: StoredResponseStopCancel,
        val scheduler: AgentTaskScheduler, val presenter: ChatRunPresenter,
        val settings: SettingsAggregator, val approval: ChatApprovalActions, val events: InMemoryAgentEventStore, val pendingStore: PendingMessageStore,
    ) {
        fun close() {
            runner.firstSettled.complete(Unit)
            api.release.complete(StoredResponseCancelResult.CancelFailed)
            coordinator.cleanup()
            presenter.cleanup()
            store.cleanup()
            queue.cleanup()
            scope.cancel()
        }
    }

    private class MemoryResumeStore : ResponseResumeStore {
        private var cursor: ResponseCursor? = ResponseCursor("stored-response", 2, "provider")
        override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) {
            cursor = ResponseCursor(responseId, sequence, providerId)
        }
        override suspend fun load(runId: String): ResponseCursor? = cursor
        override suspend fun clear(runId: String) { cursor = null }
    }

    private class PendingCancelApi : StoredResponseApi {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<StoredResponseCancelResult>()
        override suspend fun cancel(providerSetting: ProviderSetting.OpenAI, responseId: String): StoredResponseCancelResult {
            entered.complete(Unit)
            return release.await()
        }
        override suspend fun fetchStatus(providerSetting: ProviderSetting.OpenAI, responseId: String): StoredResponseStatus = error("unused")
        override suspend fun streamStored(providerSetting: ProviderSetting.OpenAI, responseId: String, cursor: ResponseCursor?, store: ResponseResumeStore, runId: String): Flow<MessageChunk> = emptyFlow()
    }

    private class QueueRunner : AgentRunner {
        private val descriptor = AgentDescriptorId("chat_turn")
        val handles = mutableListOf<AgentRunHandle>()
        private val snapshots = mutableMapOf<AgentRunId, MutableStateFlow<AgentRunSnapshot>>()
        val firstLaunched = CompletableDeferred<Unit>()
        val secondLaunched = CompletableDeferred<Unit>()
        val firstSettlementEntered = CompletableDeferred<Unit>()
        val firstSettled = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<AgentRunId>()
        var secondWireBeforeFirstSettled = false
        override fun <I : AgentInput> launch(descriptorId: AgentDescriptorId, input: I, requestedRunId: AgentRunId?): Result<AgentRunHandle> {
            val handle = AgentRunHandle(requestedRunId ?: AgentRunId("queue-run-${handles.size}"), descriptor)
            handles += handle
            snapshots[handle.runId] = MutableStateFlow(AgentRunSnapshot(handle.runId, null, descriptor, RunStatus.RUNNING, 1, null))
            if (handles.size == 1) {
                firstLaunched.complete(Unit)
            } else {
                secondWireBeforeFirstSettled = !firstSettled.isCompleted
                secondLaunched.complete(Unit)
            }
            return Result.success(handle)
        }
        override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> = snapshots.getValue(runId)
        override fun cancel(runId: AgentRunId) { cancelled += runId }
        override suspend fun awaitRunSettled(runId: AgentRunId) {
            if (handles.firstOrNull()?.runId == runId) {
                firstSettlementEntered.complete(Unit)
                firstSettled.await()
            }
        }
        override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> = snapshots.values.map { it.value }.filterNot { it.status.isTerminal }
        fun publish(index: Int, status: RunStatus) {
            val flow = snapshots.getValue(handles[index].runId)
            flow.value = flow.value.copy(status = status)
        }
    }
}
