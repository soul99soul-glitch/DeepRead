package app.amber.review

import android.app.Application
import android.content.ContextWrapper
import app.amber.core.files.FilesManager
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ConversationStore
import app.amber.core.service.ChatErrorStore
import app.amber.core.service.ChatApprovalActions
import app.amber.core.service.PendingMessageQueue
import app.amber.core.service.PendingMessageStore
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.model.Conversation
import app.amber.core.service.ChatService
import app.amber.core.service.ChatRunPresenter
import app.amber.core.service.ChatRunCoordinator
import app.amber.agent.AppScope
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskStore
import kotlinx.coroutines.cancel
import app.amber.core.service.ConversationSession
import app.amber.core.service.PendingUserMessage
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatRuntimeOwnershipTest {
    private val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val runId = AgentRunId("current-chat-run")
    @get:Rule val queueDirectory = TemporaryFolder()
    private val presenters = mutableListOf<ChatRunPresenter>()
    private val presenterScopes = mutableListOf<AppScope>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        presenters.forEach { it.cleanup() }
        presenterScopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun matchingNotificationStopCancelsItsDispatchAndPreservesPendingInput() = runTest {
        val runner = ControlledRunner(runId)
        val fixture = fixture(backgroundScope, runner)
        val queued = PendingUserMessage(id = "followup", parts = listOf(UIMessagePart.Text("keep queued")))
        fixture.queue.enqueuePendingUserMessage(conversationId, queued)
        val dispatch = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        fixture.session.setJob(dispatch)

        fixture.service.stopGeneration(conversationId, runId.value)
        runCurrent()
        val dispatchStopped = dispatch.isCompleted
        dispatch.cancel()

        assertTrue("A matching notification Stop must stop the queue-draining dispatch", dispatchStopped)
        assertEquals(listOf(queued), fixture.session.pendingUserMessages.value)
        assertEquals(listOf(runId), runner.cancelled)
    }

    @Test
    fun staleNotificationStopDoesNotCancelTheSuccessorDispatch() = runTest {
        val runner = ControlledRunner(runId)
        val fixture = fixture(backgroundScope, runner)
        val successor = AgentRunId("successor-user-run")
        fixture.activeRuns.value = mapOf(conversationId to successor)
        val dispatch = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        fixture.session.setJob(dispatch)

        fixture.service.stopGeneration(conversationId, runId.value)

        assertTrue(dispatch.isActive)
        assertTrue(runner.cancelled.isEmpty())
        assertEquals(successor, fixture.activeRuns.value[conversationId])
        dispatch.cancel()
    }

    @Test
    fun cancelledDispatchWaitsForItsRunnerFinalizationBeforeReleasingTheSession() = runTest {
        val runner = ControlledRunner(runId)
        val fixture = fixture(backgroundScope, runner)
        val dispatch = launch(start = CoroutineStart.LAZY) { fixture.dispatchTurn() }
        fixture.session.setJob(dispatch)
        dispatch.start()
        runner.launched.await()

        dispatch.cancel()
        runCurrent()
        val releasedBeforeCleanup = dispatch.isCompleted
        runner.releaseCleanup.complete(Unit)
        dispatch.join()

        assertFalse("Joining the dispatch must include its independently owned runner cleanup", releasedBeforeCleanup)
        assertEquals(listOf(runId), runner.settlementWaits)
        assertEquals(listOf(runId), runner.cancelled)
    }

    @Test
    fun terminalSnapshotDoesNotReleaseDispatchBeforeRunnerFinalization() = runTest {
        val runner = ControlledRunner(runId)
        val fixture = fixture(backgroundScope, runner)
        val dispatch = launch(start = CoroutineStart.LAZY) { fixture.dispatchTurn() }
        fixture.session.setJob(dispatch)
        dispatch.start()
        runner.launched.await()

        runner.publish(RunStatus.COMPLETED)
        runCurrent()
        val releasedBeforeCleanup = dispatch.isCompleted
        runner.releaseCleanup.complete(Unit)
        dispatch.join()

        assertFalse("The runner's terminal snapshot precedes handler finalization", releasedBeforeCleanup)
        assertEquals(listOf(runId), runner.settlementWaits)
        assertTrue(runner.cancelled.isEmpty())
    }

    @Test
    fun settledApprovalPauseReleasesDispatchWithoutCancellingTheRun() = runTest {
        val runner = ControlledRunner(runId)
        val fixture = fixture(backgroundScope, runner)
        val dispatch = launch(start = CoroutineStart.LAZY) { fixture.dispatchTurn() }
        fixture.session.setJob(dispatch)
        dispatch.start()
        runner.launched.await()

        runner.releaseCleanup.complete(Unit)
        runner.publish(RunStatus.WAITING_USER)
        dispatch.join()

        assertFalse(fixture.session.isGenerating)
        assertTrue(runner.cancelled.isEmpty())
    }

    private fun fixture(scope: CoroutineScope, runner: ControlledRunner): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val service = ReflectionFixture.allocate<ChatService>()
        val coordinator = ReflectionFixture.allocate<ChatRunCoordinator>()
        val activeRuns = MutableStateFlow(mapOf(conversationId to runId))
        val model = Model(modelId = "test", abilities = listOf(ModelAbility.TOOL))
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(
            Settings(chatModelId = model.id, providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))),
        ))
        val errorStore = ChatErrorStore()
        val fileContext = object : ContextWrapper(context) {
            override fun getFilesDir() = queueDirectory.root
        }
        val pendingStore = PendingMessageStore(fileContext, Json)
        val queue = PendingMessageQueue(scope, pendingStore)
        val store = ConversationStore(
            conversationRepo = ReflectionFixture.allocate<ConversationRepository>(),
            settingsStore = settings,
            appScope = scope,
            filesManager = ReflectionFixture.allocate<FilesManager>(),
            pendingMessageQueue = queue,
            errorStore = errorStore,
        )
        val session = store.session(conversationId)
        ReflectionFixture.set(coordinator, "conversationStore", store)
        ReflectionFixture.set(coordinator, "pendingMessageQueue", queue)
        ReflectionFixture.set(coordinator, "errorStore", errorStore)
        ReflectionFixture.set(coordinator, "context", context)
        ReflectionFixture.set(coordinator, "settingsStore", settings)
        ReflectionFixture.set(coordinator, "activeKernelRuns", activeRuns)
        ReflectionFixture.set(coordinator, "agentRunner", runner)
        ReflectionFixture.set(coordinator, "pausedForUserConversations", MutableStateFlow(emptySet<Uuid>()))
        ReflectionFixture.set(coordinator, "_lastRunOutcomes", MutableStateFlow(emptyMap<Uuid, RunStatus>()))
        ReflectionFixture.set(coordinator, "approvalActions", ChatApprovalActions(context, Json, store))
        val presenterScope = AppScope().also(presenterScopes::add)
        val presenter = ChatRunPresenter(
            context = fileContext,
            appScope = presenterScope,
            activityStore = AgentToolActivityStore(),
            liveStatusNotifier = AgentLiveStatusNotifier(context, NotificationApprovalTokenRegistry()),
            agentTaskScheduler = AgentTaskScheduler(AgentTaskStore(fileContext, Json, loadDispatcher = Dispatchers.Unconfined)),
        ).also(presenters::add)
        ReflectionFixture.set(coordinator, "runPresenter", presenter)
        ReflectionFixture.set(coordinator, "screenCaptureManager", ScreenCaptureManager(context))
        ReflectionFixture.set(service, "runCoordinator", coordinator)
        return Fixture(service, coordinator, session, activeRuns, runner, queue)
    }

    private inner class Fixture(
        val service: ChatService,
        val coordinator: ChatRunCoordinator,
        val session: ConversationSession,
        val activeRuns: MutableStateFlow<Map<Uuid, AgentRunId>>,
        val runner: ControlledRunner,
        val queue: PendingMessageQueue,
    ) {
        suspend fun dispatchTurn() {
            val dispatch = ChatRunCoordinator::class.java.declaredMethods.single {
                it.name.startsWith("dispatchKernelTurn") && !it.name.endsWith("\$default")
            }
            ReflectionFixture.invokeSuspend(
                coordinator, dispatch, conversationId, session, runner, Uuid.random(), "test", null, null, null,
            )
        }
    }

    private class ControlledRunner(private val runId: AgentRunId) : AgentRunner {
        private val descriptorId = AgentDescriptorId("chat_turn")
        val snapshots = MutableStateFlow(AgentRunSnapshot(runId, null, descriptorId, RunStatus.RUNNING, 1L, null))
        val launched = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<AgentRunId>()
        val settlementWaits = mutableListOf<AgentRunId>()

        override fun <I : AgentInput> launch(descriptorId: AgentDescriptorId, input: I, requestedRunId: AgentRunId?): Result<AgentRunHandle> {
            launched.complete(Unit)
            return Result.success(AgentRunHandle(runId, descriptorId))
        }

        override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> = snapshots
        override fun cancel(runId: AgentRunId) {
            cancelled += runId
            publish(RunStatus.CANCELLED)
        }

        override suspend fun awaitRunSettled(runId: AgentRunId) {
            settlementWaits += runId
            releaseCleanup.await()
        }

        override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> = listOf(snapshots.value).filterNot { it.status.isTerminal }

        fun publish(status: RunStatus) {
            snapshots.value = snapshots.value.copy(status = status)
        }
    }
}
