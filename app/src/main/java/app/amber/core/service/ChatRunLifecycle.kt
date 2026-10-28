package app.amber.core.service

import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Stored responses may resume only from these durable pause states. */
internal val RESPONSES_RESUME_STATES = setOf(
    app.amber.feature.runtime.RunTerminalState.RESUMABLE,
    app.amber.feature.runtime.RunTerminalState.WAITING_EXTERNAL,
)

/** Wait for this dispatch's immutable activation rather than a previous pause. */
internal suspend fun awaitChatRunDispatchEnd(
    snapshots: StateFlow<AgentRunSnapshot>,
    preLaunchSnapshot: AgentRunSnapshot?,
): AgentRunSnapshot {
    if (preLaunchSnapshot != null) {
        // The runner publishes a new immutable snapshot after its asynchronous
        // resume gate; a resumed activation may immediately pause again.
        snapshots.first { snapshot ->
            snapshot !== preLaunchSnapshot || snapshot.status.isTerminal
        }
    }
    return snapshots.first { snapshot ->
        snapshot.status.isTerminal || snapshot.status.isPause
    }
}

/** Keep the run's ownership through the dispatch job's remaining queue/notification work. */
internal fun clearChatRunOnDispatchCompletion(
    session: ConversationSession,
    activeRuns: MutableStateFlow<Map<Uuid, AgentRunId>>,
    runId: AgentRunId,
    dispatchJob: Job,
) {
    dispatchJob.invokeOnCompletion {
        activeRuns.update { runs ->
            val currentJob = session.getJob()
            if (runs[session.id] == runId && (currentJob == null || currentJob === dispatchJob)) {
                runs - session.id
            } else {
                runs
            }
        }
    }
}

/** Wait for this run's dispatch to release the session, without waiting for a successor run. */
internal suspend fun awaitChatRunDispatchHandoff(
    session: ConversationSession,
    activeRuns: StateFlow<Map<Uuid, AgentRunId>>,
    runId: AgentRunId,
) = withContext(Dispatchers.Main.immediate) {
    while (activeRuns.value[session.id] == runId) {
        val dispatchJob = session.getJob() ?: break
        coroutineScope {
            val transferred = async(start = CoroutineStart.UNDISPATCHED) {
                activeRuns.first { it[session.id] != runId }
            }
            try {
                select<Unit> {
                    dispatchJob.onJoin { }
                    transferred.onAwait { }
                }
            } finally {
                transferred.cancel()
            }
        }
        // A new approval dispatch can still own the same run id. Re-read the
        // real session job after the previous one completes before admitting a task.
    }
}

/** The one chat run admitted for an external task, including approval resumes. */
internal class ChatTaskRun(
    private val launchedRun: CompletableDeferred<AgentRunHandle>,
    private val runner: AgentRunner,
    private val dispatchJob: Job,
    private val awaitDispatchHandoff: suspend (AgentRunId) -> Unit,
    private val stopRun: suspend (AgentRunId) -> Unit,
) {
    init {
        // Covers cancellation before the launch coroutine enters its body.
        dispatchJob.invokeOnCompletion { error ->
            launchedRun.completeExceptionally(error ?: IllegalStateException("Chat generation did not start"))
        }
    }

    suspend fun awaitTerminal(): AgentRunSnapshot = try {
        val handle = launchedRun.await()
        val snapshots = runner.observe(handle.runId)
        var settled: AgentRunSnapshot
        do {
            snapshots.first { it.status.isTerminal }
            runner.awaitRunSettled(handle.runId)
            settled = snapshots.value
        } while (!settled.status.isTerminal)
        awaitDispatchHandoff(handle.runId)
        settled
    } catch (cancelled: CancellationException) {
        cancel()
        throw cancelled
    }

    suspend fun cancel() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        // ChatService delivers the launch handle on Main. Serializing this check
        // there avoids cancelling a dispatch that has already drained to a newer run.
        if (!launchedRun.isCompleted) dispatchJob.cancelAndJoin()
        val handle = runCatching { launchedRun.await() }.getOrNull()
        if (handle != null) {
            stopRun(handle.runId)
            runner.awaitRunSettled(handle.runId)
            awaitDispatchHandoff(handle.runId)
        }
    }
}

/** Close an admitted run if cancellation wins the dispatcher return handoff. */
internal suspend fun admitChatTaskRun(admit: suspend () -> ChatTaskRun): ChatTaskRun {
    var admitted: ChatTaskRun? = null
    try {
        return withContext(Dispatchers.Main.immediate) {
            admit().also { admitted = it }
        }
    } catch (cancelled: CancellationException) {
        admitted?.cancel()
        throw cancelled
    }
}
