package app.amber.core.service

import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.model.Conversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class ChatDispatchHandoffTest {
    private val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val runId = AgentRunId("cron-run")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `stopped dispatch retains ownership until its real session job finishes cleanup`() = runTest {
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope = backgroundScope, onIdle = {})
        val activeRuns = MutableStateFlow(mapOf(conversationId to runId))
        val releaseTail = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val dispatch = launch(start = CoroutineStart.LAZY) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseTail.await()
                }
            }
        }
        session.setJob(dispatch)
        clearChatRunOnDispatchCompletion(session, activeRuns, runId, dispatch)
        dispatch.start()
        runCurrent()
        dispatch.cancel()
        cleanupStarted.await()
        val handoff = async { awaitChatRunDispatchHandoff(session, activeRuns, runId) }

        runCurrent()
        val returnedBeforeCleanup = handoff.isCompleted
        val retainedOwner = activeRuns.value[conversationId]
        releaseTail.complete(Unit)
        handoff.await()
        dispatch.join()

        assertFalse(returnedBeforeCleanup)
        assertEquals(runId, retainedOwner)
        assertNull(session.getJob())
        assertNull(activeRuns.value[conversationId])
    }

    @Test
    fun `same dispatch job transfers to a successor without waiting for its model work`() = runTest {
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope = backgroundScope, onIdle = {})
        val activeRuns = MutableStateFlow(mapOf(conversationId to runId))
        val successor = AgentRunId("queued-user-run")
        val beginSuccessor = CompletableDeferred<Unit>()
        val releaseSuccessor = CompletableDeferred<Unit>()
        val dispatch = launch(start = CoroutineStart.LAZY) {
            beginSuccessor.await()
            activeRuns.value = mapOf(conversationId to successor)
            releaseSuccessor.await()
        }
        session.setJob(dispatch)
        clearChatRunOnDispatchCompletion(session, activeRuns, runId, dispatch)
        dispatch.start()
        val handoff = async { awaitChatRunDispatchHandoff(session, activeRuns, runId) }

        runCurrent()
        assertFalse(handoff.isCompleted)
        beginSuccessor.complete(Unit)
        handoff.await()

        assertTrue("The queued user run is still executing on the original drain job", session.isGenerating)
        assertEquals(successor, activeRuns.value[conversationId])
        releaseSuccessor.complete(Unit)
        dispatch.join()
    }

    @Test
    fun `old pause completion cannot clear a newer approval dispatch with the same run id`() = runTest {
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope = backgroundScope, onIdle = {})
        val activeRuns = MutableStateFlow(mapOf(conversationId to runId))
        val releasePrevious = CompletableDeferred<Unit>()
        val oldDispatch = launch(start = CoroutineStart.LAZY) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { releasePrevious.await() }
            }
        }
        session.setJob(oldDispatch)
        clearChatRunOnDispatchCompletion(session, activeRuns, runId, oldDispatch)
        oldDispatch.start()
        runCurrent()
        val releaseResume = CompletableDeferred<Unit>()
        val resumedDispatch = launch(start = CoroutineStart.LAZY) { releaseResume.await() }
        session.setJob(resumedDispatch)
        clearChatRunOnDispatchCompletion(session, activeRuns, runId, resumedDispatch)
        resumedDispatch.start()
        releasePrevious.complete(Unit)
        oldDispatch.join()
        val handoff = async { awaitChatRunDispatchHandoff(session, activeRuns, runId) }

        runCurrent()
        val returnedBeforeResumeEnded = handoff.isCompleted
        val ownerAfterOldCompletion = activeRuns.value[conversationId]
        releaseResume.complete(Unit)
        resumedDispatch.join()
        handoff.await()

        assertEquals(runId, ownerAfterOldCompletion)
        assertFalse(returnedBeforeResumeEnded)
        assertNull(activeRuns.value[conversationId])
    }

    @Test
    fun `ordinary pause releases the owner when its dispatch completes`() = runTest {
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), scope = backgroundScope, onIdle = {})
        val activeRuns = MutableStateFlow(mapOf(conversationId to runId))
        val dispatch = launch(start = CoroutineStart.LAZY) { }
        session.setJob(dispatch)
        clearChatRunOnDispatchCompletion(session, activeRuns, runId, dispatch)

        dispatch.start()
        dispatch.join()

        assertFalse(session.isGenerating)
        assertNull(activeRuns.value[conversationId])
    }
}
