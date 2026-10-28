package app.amber.core.service

import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.RunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatDispatchActivationTest {
    private val runId = AgentRunId("paused-chat-run")

    @Test
    fun `ordinary approval resume waits for its activation before accepting another pause`() = runTest {
        val previousPause = snapshot(RunStatus.WAITING_USER)
        val snapshots = MutableStateFlow(previousPause)
        val activationGate = CompletableDeferred<Unit>()
        val activation = launch {
            activationGate.await()
            snapshots.value = snapshot(RunStatus.WAITING_USER).copy(startedAt = 2L)
        }
        val dispatch = async {
            awaitChatRunDispatchEnd(snapshots, previousPause)
        }

        runCurrent()
        assertFalse("The old WAITING_USER snapshot is not the resumed activation", dispatch.isCompleted)

        activationGate.complete(Unit)
        activation.join()
        assertEquals(2L, dispatch.await().startedAt)
    }

    @Test
    fun `automatic resume retains its activation barrier`() = runTest {
        val previousPause = snapshot(RunStatus.WAITING_USER)
        val snapshots = MutableStateFlow(previousPause)
        val dispatch = async {
            awaitChatRunDispatchEnd(snapshots, previousPause)
        }

        runCurrent()
        assertFalse(dispatch.isCompleted)
        snapshots.value = snapshot(RunStatus.RUNNING)
        runCurrent()
        assertFalse(dispatch.isCompleted)
        snapshots.value = snapshot(RunStatus.COMPLETED)
        assertEquals(RunStatus.COMPLETED, dispatch.await().status)
    }

    @Test
    fun `cancellation while the resume gate is pending releases the dispatch`() = runTest {
        val previousPause = snapshot(RunStatus.WAITING_USER)
        val snapshots = MutableStateFlow(previousPause)
        val dispatch = async {
            awaitChatRunDispatchEnd(snapshots, previousPause)
        }

        runCurrent()
        assertFalse(dispatch.isCompleted)
        snapshots.value = snapshot(RunStatus.CANCELLED)
        assertEquals(RunStatus.CANCELLED, dispatch.await().status)
    }

    @Test
    fun `fresh dispatch accepts its first terminal snapshot`() = runTest {
        val snapshots = MutableStateFlow(snapshot(RunStatus.FAILED))
        assertEquals(
            RunStatus.FAILED,
            awaitChatRunDispatchEnd(snapshots, preLaunchSnapshot = null).status,
        )
    }

    private fun snapshot(status: RunStatus) = AgentRunSnapshot(
        runId = runId,
        parentRunId = null,
        descriptorId = AgentDescriptorId("chat_turn"),
        status = status,
        startedAt = 1L,
        finishedAt = null,
    )
}
