package app.amber.core.service

import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatTaskRunTest {
    private val runId = AgentRunId("cron-run")
    private val descriptorId = AgentDescriptorId("chat_turn")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `failed generation retains its terminal status and cause`() = runTest {
        val error = IllegalStateException("provider rejected request")
        val runner = ControlledRunner(runId, RunStatus.FAILED, error)
        val run = owner(runner)

        val result = run.awaitTerminal()

        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(error, result.error)
    }

    @Test
    fun `approval pause keeps waiting until that run actually completes`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.WAITING_USER)
        val waiting = async { owner(runner).awaitTerminal() }

        runCurrent()
        assertFalse(waiting.isCompleted)
        runner.publish(runId, RunStatus.RUNNING)
        runCurrent()
        assertFalse(waiting.isCompleted)
        runner.publish(runId, RunStatus.COMPLETED)

        assertEquals(RunStatus.COMPLETED, waiting.await().status)
    }

    @Test
    fun `timeout cancels only the admitted run`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.RUNNING)
        val stopped = mutableListOf<AgentRunId>()
        val run = owner(runner, stopped = stopped)
        val waiting = async {
            runCatching { withTimeout(10) { run.awaitTerminal() } }.exceptionOrNull()
        }

        assertTrue(waiting.await() is CancellationException)
        assertEquals(listOf(runId), stopped)
    }

    @Test
    fun `cancel waits for actual run cleanup after stop returns and cancelled snapshot is visible`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.RUNNING)
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanup = launch { releaseCleanup.await() }
        runner.settlementJobs[runId] = cleanup
        var stopReturned = false
        val run = ChatTaskRun(
            launchedRun = CompletableDeferred(AgentRunHandle(runId, descriptorId)),
            runner = runner,
            dispatchJob = Job().apply { complete() },
            awaitDispatchHandoff = {},
        ) { id ->
            runner.cancel(id)
            stopReturned = true
        }
        val cancelling = async { run.cancel() }

        runCurrent()
        assertTrue(stopReturned)
        assertEquals(RunStatus.CANCELLED, runner.observe(runId).value.status)
        val returnedBeforeCleanup = cancelling.isCompleted
        releaseCleanup.complete(Unit)
        cleanup.join()
        cancelling.await()

        assertFalse("Cancellation must retain its task owner until the real run cleanup finishes", returnedBeforeCleanup)
    }

    @Test
    fun `observing cancelled does not finish before the run cleanup completes`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.CANCELLED)
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanup = launch { releaseCleanup.await() }
        runner.settlementJobs[runId] = cleanup
        val waiting = async { owner(runner).awaitTerminal() }

        runCurrent()
        val returnedBeforeCleanup = waiting.isCompleted
        releaseCleanup.complete(Unit)
        cleanup.join()

        assertEquals(RunStatus.CANCELLED, waiting.await().status)
        assertFalse(returnedBeforeCleanup)
    }

    @Test
    fun `cancelled snapshot reconciled to waiting external continues waiting after settlement`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.CANCELLED)
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanup = launch {
            releaseCleanup.await()
            runner.publish(runId, RunStatus.WAITING_EXTERNAL)
        }
        runner.settlementJobs[runId] = cleanup
        val waiting = async { owner(runner).awaitTerminal() }

        runCurrent()
        releaseCleanup.complete(Unit)
        cleanup.join()
        runCurrent()
        val returnedWhileStillPaused = waiting.isCompleted
        runner.publish(runId, RunStatus.COMPLETED)

        assertEquals(RunStatus.COMPLETED, waiting.await().status)
        assertFalse("Optimistic cancellation must not hide the final paused owner", returnedWhileStillPaused)
    }

    @Test
    fun `cancel before launch stops dispatch without claiming another run`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.RUNNING)
        val launched = CompletableDeferred<AgentRunHandle>()
        val gate = CompletableDeferred<Unit>()
        val stopped = mutableListOf<AgentRunId>()
        val dispatch = launch {
            gate.await()
            launched.complete(AgentRunHandle(runId, descriptorId))
        }
        val run = ChatTaskRun(launched, runner, dispatch, awaitDispatchHandoff = {}) { stopped += it }
        val waiting = async { run.awaitTerminal() }
        runCurrent()

        waiting.cancelAndJoin()

        assertTrue(dispatch.isCancelled)
        assertTrue(stopped.isEmpty())
    }

    @Test
    fun `cancel before dispatch ever starts completes the launch handoff`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.RUNNING)
        val launched = CompletableDeferred<AgentRunHandle>()
        var enteredDispatch = false
        val dispatch = launch(start = CoroutineStart.LAZY) {
            enteredDispatch = true
            launched.complete(AgentRunHandle(runId, descriptorId))
        }
        val stopped = mutableListOf<AgentRunId>()
        val run = ChatTaskRun(launched, runner, dispatch, awaitDispatchHandoff = {}) { stopped += it }

        run.cancel()

        assertFalse(enteredDispatch)
        assertTrue(dispatch.isCancelled)
        assertTrue(launched.isCompleted)
        assertTrue(stopped.isEmpty())
    }

    @Test
    fun `cancelled admission return closes the run whose handle was not delivered`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.RUNNING)
        val stopped = mutableListOf<AgentRunId>()
        val run = owner(runner, stopped)
        val admission = async {
            admitChatTaskRun {
                currentCoroutineContext().job.cancel()
                run
            }
        }

        admission.join()

        assertTrue(admission.isCancelled)
        assertEquals(listOf(runId), stopped)
    }

    @Test
    fun `late old task cancellation cannot cancel the successor dispatch`() = runTest {
        val runner = ControlledRunner(runId, RunStatus.COMPLETED)
        val successor = AgentRunId("new-user-run")
        runner.publish(successor, RunStatus.RUNNING)
        val stopped = mutableListOf<AgentRunId>()
        val launched = CompletableDeferred(AgentRunHandle(runId, descriptorId))
        val drainGate = CompletableDeferred<Unit>()
        val dispatch = launch { drainGate.await() }
        val run = ChatTaskRun(launched, runner, dispatch, awaitDispatchHandoff = {}) { stopped += it }

        run.cancel()

        assertTrue("The dispatch can now be draining another user's message", dispatch.isActive)
        assertEquals(listOf(runId), stopped)
        assertEquals(RunStatus.RUNNING, runner.observe(successor).value.status)
        dispatch.cancelAndJoin()
    }

    private fun owner(
        runner: ControlledRunner,
        stopped: MutableList<AgentRunId> = mutableListOf(),
    ) = ChatTaskRun(
        launchedRun = CompletableDeferred(AgentRunHandle(runId, descriptorId)),
        runner = runner,
        dispatchJob = Job().apply { complete() },
        awaitDispatchHandoff = {},
        stopRun = { stopped += it },
    )

    private class ControlledRunner(
        runId: AgentRunId,
        status: RunStatus,
        error: Throwable? = null,
    ) : AgentRunner {
        private val snapshots = mutableMapOf<AgentRunId, MutableStateFlow<AgentRunSnapshot>>()
        val settlementJobs = mutableMapOf<AgentRunId, Job>()

        init {
            publish(runId, status, error)
        }

        fun publish(runId: AgentRunId, status: RunStatus, error: Throwable? = null) {
            val snapshot = AgentRunSnapshot(
                runId = runId,
                parentRunId = null,
                descriptorId = AgentDescriptorId("chat_turn"),
                status = status,
                startedAt = 1L,
                finishedAt = null,
                error = error,
            )
            snapshots.getOrPut(runId) { MutableStateFlow(snapshot) }.value = snapshot
        }

        override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> = snapshots.getValue(runId)
        override fun cancel(runId: AgentRunId) = publish(runId, RunStatus.CANCELLED)
        override suspend fun awaitRunSettled(runId: AgentRunId) {
            check(runId in snapshots)
            settlementJobs[runId]?.join()
        }
        override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> =
            snapshots.values.map { it.value }.filterNot { it.status.isTerminal }

        override fun <I : AgentInput> launch(
            descriptorId: AgentDescriptorId,
            input: I,
            requestedRunId: AgentRunId?,
        ): Result<AgentRunHandle> = error("The test controls admission directly")
    }
}
