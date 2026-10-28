package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.core.infra.AppScope
import app.amber.core.settings.Settings
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.uuid.Uuid

/**
 * Coroutine-based tests for the Council Room generation path.
 *
 * These tests verify the PR2 wiring: host actions launch generation, streaming
 * updates merge into the room state, synthesis finalizes the room, and close()
 * behaves correctly (graceful vs cancel) while respecting the closing gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CouncilRoomManagerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        // Main dispatcher will be set per-test so AppScope captures the test
        // dispatcher and launched generation jobs participate in the test scheduler.
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `cancel after room publication completes task handoff and its stop callback`() = runTest {
        val enteredRegistration = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        val reporter = FakeTaskReporter(beforeRegister = {
            enteredRegistration.complete(Unit)
            releaseRegistration.await()
        })
        val modelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(responseChunks = listOf("Unexpected generation"))
        val env = createEnv(runner, modelId, reporter = reporter)
        val opener = launch { env.openWithGuest(env.guest()) }
        runCurrent()
        enteredRegistration.await()
        assertNotNull(env.store.peekRoom(env.conversationId))

        opener.cancel()
        runCurrent()
        releaseRegistration.complete(Unit)
        opener.join()

        assertTrue(opener.isCancelled)
        assertNotNull(env.store.peekRoom(env.conversationId))
        val task = reporter.registered.single()
        assertEquals("council_room:${env.conversationId}", task.taskId)
        assertEquals(env.conversationId.toString(), task.sourceConversationId)
        assertTrue(task.cancelCapability)
        assertTrue(checkNotNull(reporter.cancelCallbacks[task.taskId]).invoke())
        assertNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.CANCELLED, env.store.closedRooms.single().status)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `cancel while reading before publication creates neither room nor task`() = runTest {
        val enteredRead = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val reporter = FakeTaskReporter()
        val runner = FakeModelCouncilTextRunner(responseChunks = listOf("Unexpected generation"))
        val env = createEnv(runner, Uuid.random(), reporter = reporter, beforeObserve = {
            enteredRead.complete(Unit)
            releaseRead.await()
        })
        val opener = launch { env.openWithGuest(env.guest()) }
        runCurrent()
        enteredRead.await()

        opener.cancel()
        opener.join()
        releaseRead.complete(Unit)

        assertTrue(opener.isCancelled)
        assertNull(env.store.peekRoom(env.conversationId))
        assertTrue(reporter.registered.isEmpty())
        assertTrue(reporter.cancelCallbacks.isEmpty())
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `cancel caller after open returns preserves task stop ownership`() = runTest {
        val reporter = FakeTaskReporter()
        val runner = FakeModelCouncilTextRunner(responseChunks = listOf("Unexpected generation"))
        val env = createEnv(runner, Uuid.random(), reporter = reporter)
        val opened = CompletableDeferred<Unit>()
        val keepCallerAlive = CompletableDeferred<Unit>()
        val opener = launch {
            env.openWithGuest(env.guest())
            opened.complete(Unit)
            keepCallerAlive.await()
        }
        runCurrent()
        opened.await()

        opener.cancel()
        opener.join()

        assertNotNull(env.store.peekRoom(env.conversationId))
        val task = reporter.registered.single()
        assertTrue(checkNotNull(reporter.cancelCallbacks[task.taskId]).invoke())
        assertNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.CANCELLED, env.store.closedRooms.single().status)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `inviteNext launches guest generation and creates streaming message`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(responseChunks = listOf("Hello"), delayPerChunkMs = 10L)
        val env = createEnv(runner = runner, modelId = modelId)

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)

        val result = env.manager.hostAction(
            env.conversationId,
            HostAction.InviteNext(guest.id),
        )
        assertTrue(result is CouncilRoomOpResult.Ok)

        advanceUntilIdle()

        val room = env.store.peekRoom(env.conversationId)
        assertNotNull(room)
        val guestMessage = room!!.messages.firstOrNull { it.authorId == guest.id }
        assertNotNull(guestMessage)
        assertEquals("Hello", guestMessage!!.text)
        assertEquals(CouncilMessageStatus.COMPLETED, guestMessage.status)
        assertEquals(CouncilParticipantStatus.SPOKEN, room.participantById(guest.id)!!.status)

        val call = runner.calls.single()
        assertEquals(modelId, call.modelId)
        assertTrue(call.userPrompt.contains(guest.name))
    }

    @Test
    fun `streaming chunks merge into a single message`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val chunks = listOf("One ", "two ", "three")
        val runner = FakeModelCouncilTextRunner(responseChunks = chunks, delayPerChunkMs = 10L)
        val env = createEnv(runner = runner, modelId = modelId)

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)

        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))

        // Let the first chunk land and check the streaming merge.
        advanceTimeBy(15L)
        val midRoom = env.store.peekRoom(env.conversationId)!!
        val midMessage = midRoom.messages.single { it.authorId == guest.id }
        assertEquals(CouncilMessageStatus.STREAMING, midMessage.status)
        assertEquals("One ", midMessage.text)

        advanceUntilIdle()

        val finalRoom = env.store.peekRoom(env.conversationId)!!
        val finalMessage = finalRoom.messages.single { it.authorId == guest.id }
        assertEquals(CouncilMessageStatus.COMPLETED, finalMessage.status)
        assertEquals("One two three", finalMessage.text)
        // Only one message row was created (no duplicates per chunk).
        assertEquals(1, finalRoom.messages.count { it.authorId == guest.id })
    }

    @Test
    fun `synthesize launches host generation and finalizes room`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val guestModelId = Uuid.random()
        val hostModelId = Uuid.random()
        // Single runner, like production; guest and host responses are selected
        // by modelId.
        val runner = FakeModelCouncilTextRunner(
            responseChunks = listOf("Guest opinion"),
            delayPerChunkMs = 0L,
            byModelId = mapOf(
                guestModelId to FakeModelCouncilTextRunner.FakeResponse(listOf("Guest opinion")),
                hostModelId to FakeModelCouncilTextRunner.FakeResponse(listOf("Final verdict"), delayPerChunkMs = 10L),
            ),
        )
        val env = createEnv(runner = runner, modelId = guestModelId, hostModelId = hostModelId)

        val guest = env.guest(modelId = guestModelId)
        env.openWithGuest(guest)
        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        advanceUntilIdle()

        val synthResult = env.manager.synthesize(env.conversationId)
        assertTrue(synthResult is CouncilRoomOpResult.Ok)
        assertEquals(CouncilRoomStatus.FINALIZING, env.store.peekRoom(env.conversationId)!!.status)

        advanceUntilIdle()

        val room = env.store.peekRoom(env.conversationId)!!
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        assertEquals("Final verdict", room.synthesis)

        // Two calls: guest turn + host synthesis.
        assertEquals(2, runner.calls.size)
        val hostCall = runner.calls.last()
        assertEquals(hostModelId, hostCall.modelId)
        assertTrue(hostCall.userPrompt.contains("Guest opinion"))
    }

    @Test
    fun `close cancel=true cancels guest generation and marks cancelled`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(
            responseChunks = listOf("Never", " finishes"),
            delayPerChunkMs = 60_000L,
        )
        val env = createEnv(runner = runner, modelId = modelId)

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)

        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        // Start the generation but don't let it finish.
        advanceTimeBy(10L)

        val closeResult = env.manager.close(env.conversationId, cancel = true)
        assertTrue(closeResult is CouncilRoomOpResult.Ok)
        assertEquals(CouncilRoomStatus.CANCELLED, (closeResult as CouncilRoomOpResult.Ok).room.status)

        advanceUntilIdle()
        assertNull(env.store.peekRoom(env.conversationId))
    }

    @Test
    fun `close cancel=false during FINALIZING waits for synthesis`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val guestModelId = Uuid.random()
        val hostModelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(
            responseChunks = listOf("Guest opinion"),
            byModelId = mapOf(
                guestModelId to FakeModelCouncilTextRunner.FakeResponse(listOf("Guest opinion")),
                hostModelId to FakeModelCouncilTextRunner.FakeResponse(listOf("Synthesis ", "complete"), delayPerChunkMs = 50L),
            ),
        )
        val env = createEnv(runner = runner, modelId = guestModelId, hostModelId = hostModelId)

        val guest = env.guest(modelId = guestModelId)
        env.openWithGuest(guest)
        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        advanceUntilIdle()

        env.manager.synthesize(env.conversationId)
        // Synthesis is running but not done yet.
        advanceTimeBy(10L)

        // Graceful close should wait for synthesis rather than cancelling it.
        val closeResult = env.manager.close(env.conversationId, cancel = false)
        assertTrue(closeResult is CouncilRoomOpResult.Ok)
        val closedRoom = (closeResult as CouncilRoomOpResult.Ok).room
        assertEquals(CouncilRoomStatus.FINALIZED, closedRoom.status)
        assertEquals("Synthesis complete", closedRoom.synthesis)
    }

    @Test
    fun `closing gate prevents new generation jobs from starting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(
            responseChunks = listOf("Never finishes"),
            delayPerChunkMs = 60_000L,
        )
        val env = createEnv(runner = runner, modelId = modelId)

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)

        // Start a long-running generation and begin closing before it finishes.
        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        advanceTimeBy(10L)

        // Launch close in the background so the gate is set while we try to start
        // another guest turn.
        val closeJob = launch {
            env.manager.close(env.conversationId, cancel = true)
        }
        advanceTimeBy(5L)

        // A new InviteNext while closing should not launch another job. The result
        // is either Ok (gate blocked the launch, plan returned) or Err (room was
        // already evicted by close) — either is acceptable; the invariant we care
        // about is "no second generation call".
        val second = env.guest(id = "guest-2", modelId = modelId)
        env.manager.inviteParticipant(env.conversationId, second)
        env.manager.hostAction(
            env.conversationId,
            HostAction.InviteNext(second.id),
        )

        advanceUntilIdle()
        closeJob.join()

        // Only one generation call was made (the first guest).
        assertEquals(1, runner.calls.size)
        assertNull(env.store.peekRoom(env.conversationId))
    }

    @Test
    fun `background generation carries the store write context`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val writeContext = TestWriteContext("restore-epoch")
        val runner = FakeModelCouncilTextRunner(responseChunks = listOf("Hello"))
        val env = createEnv(
            runner = runner,
            modelId = modelId,
            writeContext = writeContext,
        )

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)
        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        advanceUntilIdle()

        assertEquals(listOf(writeContext), runner.writeContexts)
    }

    @Test
    fun `standard auto run honors explicit mode and orders host guest rounds and synthesis`() = runTest {
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events)
        val env = createEnv(runner, Uuid.random())
        val first = env.guest()
        val second = env.guest(id = "guest-2")
        env.openWithGuests(listOf(first, second), maxRounds = 2)
        assertTrue(env.manager.switchMode(env.conversationId, CouncilRoomMode.DEBATE) is CouncilRoomOpResult.Ok)

        env.manager.userMessage(env.conversationId, "Choose a practical approach")
        advanceUntilIdle()

        assertEquals(listOf("opening", "guest", "guest", "guest", "guest", "synthesis"), events)
        val room = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        assertEquals("Host conclusion", room.synthesis)
        val guestMessages = room.messages.filter { it.authorId in listOf(first.id, second.id) }
        assertEquals(listOf(first.id, second.id, first.id, second.id), guestMessages.map { it.authorId })
        val guestCalls = runner.calls.filter { "As host" !in it.userPrompt && "Now close the discussion" !in it.userPrompt }
        assertTrue(guestCalls.first().userPrompt.contains("Host framing"))
        assertTrue(guestCalls[2].userPrompt.contains("final Debate round"))
        assertTrue(guestCalls[2].userPrompt.contains("Guest contribution"))
        assertTrue(room.messages.none { it.status == CouncilMessageStatus.STREAMING })
    }

    @Test
    fun `full empty roster researches before planning and shares facts through final synthesis`() = runTest {
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events)
        val tools = FakeHostToolProvider(events, HostToolOutcome.Done("Verified source facts"))
        val env = createEnv(runner, Uuid.random(), powerMode = CouncilPowerMode.FULL, toolProvider = tools)
        env.openWithGuests(emptyList(), maxRounds = 1)

        env.manager.userMessage(env.conversationId, "Compare the current options")
        advanceUntilIdle()

        assertEquals(listOf("research", "roles", "classify", "opening", "guest", "synthesis"), events)
        val room = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        assertTrue(room.context.contains("Verified source facts"))
        assertEquals(listOf("Evidence Reader"), room.activeGuests.map { it.name })
        val roleCall = runner.calls.single { it.systemPrompt == "Output a JSON array only, with no extra text." }
        assertTrue(roleCall.userPrompt.contains("Verified source facts"))
        assertTrue(runner.calls.last().userPrompt.contains("Verified source facts"))
        assertEquals(1, tools.calls)
        assertTrue(room.messages.none { it.status == CouncilMessageStatus.STREAMING })
    }

    @Test
    fun `full existing roster withdraws no comment review and does not research again`() = runTest {
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events, review = CouncilRoomPrompts.NO_COMMENT_SENTINEL)
        val tools = FakeHostToolProvider(events, HostToolOutcome.Done("Unexpected research"))
        val env = createEnv(runner, Uuid.random(), powerMode = CouncilPowerMode.FULL, toolProvider = tools)
        env.openWithGuest(env.guest(), maxRounds = 2)

        env.manager.userMessage(env.conversationId, "Explore the options")
        advanceUntilIdle()

        assertEquals(listOf("classify", "opening", "guest", "review", "guest", "synthesis"), events)
        assertEquals(0, tools.calls)
        val room = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        assertEquals(1, room.messages.count { it.kind == CouncilMessageKind.WITHDRAWN })
        assertTrue(room.messages.none { it.status == CouncilMessageStatus.STREAMING })
    }

    @Test
    fun `full review question resumes the same run with a reply linked user answer`() = runTest {
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events, review = CouncilRoomPrompts.ASK_USER_SENTINEL + " Which constraint matters?")
        val env = createEnv(runner, Uuid.random(), powerMode = CouncilPowerMode.FULL)
        env.openWithGuest(env.guest(), maxRounds = 2)
        env.manager.userMessage(env.conversationId, "Explore the options")
        runCurrent()
        val waiting = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.INTERRUPTED, waiting.status)
        val question = waiting.messages.single { it.kind == CouncilMessageKind.ASK_USER }
        assertEquals("Which constraint matters?", question.text)
        assertEquals(1, waiting.messages.count { it.kind == CouncilMessageKind.WITHDRAWN })

        assertTrue(env.manager.resumeAfterUserAnswer(env.conversationId, "Keep the cost low") is CouncilRoomOpResult.Ok)
        advanceUntilIdle()

        val room = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        val answer = room.messages.single { it.authorId == COUNCIL_ROOM_USER_ID && it.replyToMessageId == question.id }
        assertEquals("Keep the cost low", answer.text)
        assertEquals(1, events.count { it == "opening" })
        assertEquals(2, events.count { it == "guest" })
        assertEquals(1, events.count { it == "synthesis" })
        assertTrue(runner.calls.any { it.userPrompt.contains("Keep the cost low") })
        assertTrue(runner.calls.last().userPrompt.contains("Guest contribution"))
        assertTrue(room.messages.none { it.status == CouncilMessageStatus.STREAMING })
    }

    @Test
    fun `close while research awaits answer cancels the same run without planning or generating`() = runTest {
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events)
        val tools = FakeHostToolProvider(events, HostToolOutcome.AskUser("{}", "Which direction?"))
        val env = createEnv(runner, Uuid.random(), powerMode = CouncilPowerMode.FULL, toolProvider = tools)
        env.openWithGuests(emptyList(), maxRounds = 1)
        env.manager.userMessage(env.conversationId, "Help choose a direction")
        runCurrent()
        val waiting = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.INTERRUPTED, waiting.status)
        assertEquals("Which direction?", waiting.messages.single { it.kind == CouncilMessageKind.ASK_USER }.text)

        assertTrue(env.manager.close(env.conversationId, cancel = true) is CouncilRoomOpResult.Ok)
        advanceUntilIdle()

        assertEquals(listOf("research"), events)
        assertTrue(runner.calls.isEmpty())
        assertNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.CANCELLED, env.store.closedRooms.single().status)
        assertTrue(env.manager.resumeAfterUserAnswer(env.conversationId, "Too late") is CouncilRoomOpResult.Err)
        assertNull(env.store.peekRoom(env.conversationId))
    }

    @Test
    fun `late classifier preserves a mode explicitly selected while classification is running`() = runTest {
        val classificationStarted = CompletableDeferred<Unit>()
        val releaseClassification = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events, beforeCall = { call ->
            if ("concise classifier" in call.systemPrompt) {
                classificationStarted.complete(Unit)
                releaseClassification.await()
            }
        })
        val env = createEnv(runner, Uuid.random())
        val guest = env.guest()
        env.openWithGuest(guest, maxRounds = 1)
        env.manager.userMessage(env.conversationId, "Choose an approach")
        runCurrent()
        classificationStarted.await()

        assertTrue(env.manager.switchMode(env.conversationId, CouncilRoomMode.DEBATE) is CouncilRoomOpResult.Ok)
        releaseClassification.complete(Unit)
        advanceUntilIdle()

        val room = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        assertEquals(CouncilRoomMode.DEBATE, room.messages.single { it.authorId == guest.id }.mode)
        assertTrue(runner.calls.single { "This is the Debate phase" in it.userPrompt }.userPrompt.contains("Choose an approach"))
        assertEquals(1, events.count { it == "classify" })
        assertEquals(1, events.count { it == "opening" })
    }

    @Test
    fun `composer answer resumes the pending question once and is not replayed as an interjection`() = runTest {
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events, review = CouncilRoomPrompts.ASK_USER_SENTINEL + " Which constraint matters?")
        val env = createEnv(runner, Uuid.random(), powerMode = CouncilPowerMode.FULL)
        env.openWithGuest(env.guest(), maxRounds = 2)
        env.manager.userMessage(env.conversationId, "Explore the options")
        runCurrent()
        val waiting = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.INTERRUPTED, waiting.status)
        val question = waiting.messages.single { it.kind == CouncilMessageKind.ASK_USER }

        assertTrue(env.manager.resumeAfterUserAnswer(env.conversationId, "Stale answer", "another-question") is CouncilRoomOpResult.Err)
        assertEquals(waiting.messages, env.store.peekRoom(env.conversationId)?.messages)
        assertTrue(env.manager.userMessage(env.conversationId, "Keep the cost low") is CouncilRoomOpResult.Ok)
        runCurrent()

        val room = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        assertEquals("Keep the cost low", room.messages.single { it.replyToMessageId == question.id }.text)
        assertEquals(1, events.count { it == "opening" })
        assertEquals(2, events.count { it == "guest" })
        assertEquals(0, events.count { it == "steer" })
        assertEquals(1, events.count { it == "synthesis" })
        assertTrue(runner.calls.any { "Keep the cost low" in it.userPrompt })
    }

    @Test
    fun `answer accepted while question publication is finishing wakes the original waiter`() = runTest {
        val questionPublished = CompletableDeferred<Unit>()
        val finishPublication = CompletableDeferred<Unit>()
        val reporter = FakeTaskReporter(beforeUpsert = { snapshot ->
            if (snapshot.status == AgentTaskStatus.INTERRUPTED && !questionPublished.isCompleted) {
                questionPublished.complete(Unit)
                finishPublication.await()
            }
        })
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events)
        val tools = FakeHostToolProvider(events, HostToolOutcome.AskUser("{}", "Which direction?"))
        val env = createEnv(
            runner,
            Uuid.random(),
            reporter = reporter,
            powerMode = CouncilPowerMode.FULL,
            toolProvider = tools,
        )
        env.openWithGuests(emptyList(), maxRounds = 1)
        env.manager.userMessage(env.conversationId, "Help choose a direction")
        runCurrent()
        questionPublished.await()
        val question = checkNotNull(env.store.peekRoom(env.conversationId))
            .messages.single { it.kind == CouncilMessageKind.ASK_USER }
        var answerResult: CouncilRoomOpResult? = null
        val answerJob = launch {
            answerResult = env.manager.resumeAfterUserAnswer(env.conversationId, "Prefer the simpler option")
        }
        runCurrent()
        finishPublication.complete(Unit)
        runCurrent()

        assertTrue(answerJob.isCompleted)
        assertTrue(answerResult is CouncilRoomOpResult.Ok)
        val room = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        assertEquals("Prefer the simpler option", room.messages.single { it.replyToMessageId == question.id }.text)
        assertEquals(1, events.count { it == "research" })
        assertEquals(1, events.count { it == "opening" })
        assertEquals(1, events.count { it == "guest" })
        assertEquals(0, events.count { it == "steer" })
        assertEquals(1, events.count { it == "synthesis" })
        assertTrue(runner.calls.single { "This is the Explore phase" in it.userPrompt }
            .userPrompt.contains("Prefer the simpler option"))
    }

    @Test
    fun `steering received during roster planning is processed once even with matching timestamps`() = runTest {
        val planningStarted = CompletableDeferred<Unit>()
        val releasePlanning = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val runner = orchestrationRunner(events, beforeCall = { call ->
            if (call.systemPrompt == "Output a JSON array only, with no extra text.") {
                planningStarted.complete(Unit)
                releasePlanning.await()
            }
        })
        val tools = FakeHostToolProvider(events, HostToolOutcome.Done("Verified source facts"))
        val env = createEnv(runner, Uuid.random(), powerMode = CouncilPowerMode.FULL, toolProvider = tools)
        env.openWithGuests(emptyList(), maxRounds = 1)
        env.manager.userMessage(env.conversationId, "Compare the options")
        runCurrent()
        planningStarted.await()
        env.manager.userMessage(env.conversationId, "purchase-cost-marker")
        env.manager.userMessage(env.conversationId, "maintenance-cost-marker")
        val duringPlanning = checkNotNull(env.store.peekRoom(env.conversationId))
        env.store.upsertRoom(duringPlanning.copy(messages = duringPlanning.messages.map {
            if (it.authorId == COUNCIL_ROOM_USER_ID) it.copy(createdAtMs = 1234L) else it
        }))

        releasePlanning.complete(Unit)
        advanceUntilIdle()

        val room = checkNotNull(env.store.peekRoom(env.conversationId))
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        val steeringCalls = runner.calls.filter { "User interjection:" in it.userPrompt }
        assertEquals(2, steeringCalls.size)
        assertTrue(steeringCalls[0].userPrompt.contains("User interjection: purchase-cost-marker"))
        assertTrue(steeringCalls[1].userPrompt.contains("User interjection: maintenance-cost-marker"))
        assertEquals(1, events.count { it == "research" })
        assertEquals(1, events.count { it == "roles" })
        assertEquals(1, events.count { it == "opening" })
        assertTrue(events.lastIndexOf("steer") < events.indexOf("synthesis"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────


    private fun TestScope.createEnv(
        runner: ModelCouncilTextRunner,
        modelId: Uuid,
        hostModelId: Uuid = modelId,
        writeContext: CoroutineContext = EmptyCoroutineContext,
        reporter: FakeTaskReporter = FakeTaskReporter(),
        beforeObserve: suspend () -> Unit = {},
        powerMode: CouncilPowerMode = CouncilPowerMode.STANDARD,
        toolProvider: CouncilHostToolProvider = NoOpCouncilHostToolProvider,
    ): TestEnv {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        val appScope = AppScope()
        val settings = settingsWithModel(modelId, hostModelId)
        val settingsFlow = MutableStateFlow(settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                modelCouncil = settings.agentRuntime.modelCouncil.copy(councilPowerMode = powerMode),
            ),
        ))
        val store = FakeCouncilRoomStore(writeContext, beforeObserve)
        val manager = CouncilRoomManager(
            appScope = appScope,
            settingsFlow = settingsFlow,
            json = json,
            modelRunner = runner,
            externalCliRunner = FakeExternalCliModelCouncilRunner(),
            store = store,
            taskReporter = reporter,
            toolProvider = toolProvider,
            dispatcher = testDispatcher,
        )
        return TestEnv(manager, store, appScope, modelId, hostModelId)
    }

    private fun settingsWithModel(guestModelId: Uuid, hostModelId: Uuid): Settings {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test Provider",
            models = listOf(
                Model(id = guestModelId, displayName = "Guest Model"),
                Model(id = hostModelId, displayName = "Host Model"),
            ),
            brand = OpenAIBrand.GENERIC,
        )
        return Settings(
            chatModelId = hostModelId,
            providers = listOf(provider),
        )
    }

    private class TestEnv(
        val manager: CouncilRoomManager,
        val store: FakeCouncilRoomStore,
        val appScope: AppScope,
        val guestModelId: Uuid,
        val hostModelId: Uuid,
    ) {
        val conversationId: Uuid = Uuid.random()

        fun guest(id: String = "guest-1", modelId: Uuid = guestModelId) = CouncilParticipant(
            id = id,
            name = "Guest ${id.last()}",
            role = "analyst",
            kind = CouncilParticipantKind.GUEST,
            modelId = modelId,
            runnerType = ModelCouncilSeatRunner.PROVIDER_MODEL,
        )

        suspend fun openWithGuest(guest: CouncilParticipant, maxRounds: Int = DEFAULT_COUNCIL_ROOM_MAX_ROUNDS) =
            openWithGuests(listOf(guest), maxRounds)

        suspend fun openWithGuests(guests: List<CouncilParticipant>, maxRounds: Int) {
            val result = manager.openRoom(
                conversationId = conversationId,
                hostAssistantId = Uuid.random(),
                hostName = "Host",
                objective = "Test objective",
                initialGuests = guests,
                maxRounds = maxRounds,
            )
            assertTrue(result is CouncilRoomOpResult.Ok)
        }
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    private fun orchestrationRunner(
        events: MutableList<String>,
        review: String = CouncilRoomPrompts.NO_COMMENT_SENTINEL,
        beforeCall: suspend (FakeModelCouncilTextRunner.Call) -> Unit = {},
    ) = FakeModelCouncilTextRunner(responseChunks = emptyList(), beforeCall = beforeCall, responseForCall = { call ->
        when {
            call.systemPrompt == "Output a JSON array only, with no extra text." -> {
                events += "roles"
                """[{"name":"Evidence Reader","perspective":"Check the gathered evidence."}]"""
            }
            "concise classifier" in call.systemPrompt -> {
                events += "classify"
                "explore"
            }
            "As host, open the meeting" in call.userPrompt -> {
                events += "opening"
                "Host framing"
            }
            "As host, decide whether this round" in call.userPrompt -> {
                events += "review"
                review
            }
            "Now close the discussion as the host" in call.userPrompt -> {
                events += "synthesis"
                "Host conclusion"
            }
            "User interjection:" in call.userPrompt -> {
                events += "steer"
                "Host steering"
            }
            else -> {
                events += "guest"
                "Guest contribution ${events.count { it == "guest" }}"
            }
        }
    })

    private class FakeHostToolProvider(
        private val events: MutableList<String>,
        private val outcome: HostToolOutcome,
    ) : CouncilHostToolProvider {
        var calls = 0

        override fun isAvailable(settings: Settings): Boolean = true

        override suspend fun generateWithTools(
            settings: Settings,
            modelId: Uuid,
            systemPrompt: String,
            userPrompt: String,
            reasoningLevel: ReasoningLevel,
            maxToolRounds: Int,
            timeoutMs: Long,
            outputBudgetChars: Int,
            onChunk: (String) -> Unit,
        ): HostToolOutcome {
            calls += 1
            events += "research"
            if (outcome is HostToolOutcome.Done) onChunk(outcome.text)
            return outcome
        }
    }

    /**
     * Mirrors the production shape: a single runner serves both guest turns and
     * host synthesis — the two are distinguished only by [modelId]. [byModelId]
     * overrides per-model responses so a test can hand back "Guest opinion" for
     * the guest model and "Final verdict" for the host model from one instance.
     */
    private class FakeModelCouncilTextRunner(
        private val responseChunks: List<String>,
        private val delayPerChunkMs: Long = 0L,
        private val byModelId: Map<Uuid, FakeResponse> = emptyMap(),
        private val responseForCall: ((Call) -> String)? = null,
        private val beforeCall: suspend (Call) -> Unit = {},
    ) : ModelCouncilTextRunner {
        data class Call(
            val modelId: Uuid,
            val systemPrompt: String,
            val userPrompt: String,
        )

        data class FakeResponse(
            val chunks: List<String>,
            val delayPerChunkMs: Long = 0L,
        )

        private val _calls = mutableListOf<Call>()
        val calls: List<Call> get() = _calls
        private val _writeContexts = mutableListOf<TestWriteContext?>()
        val writeContexts: List<TestWriteContext?> get() = _writeContexts

        override suspend fun generate(
            settings: Settings,
            modelId: Uuid,
            systemPrompt: String,
            userPrompt: String,
            outputBudgetChars: Int,
            reasoningLevel: ReasoningLevel?,
            temperature: Float?,
            userImageParts: List<UIMessagePart.Image>,
            onChunk: (String) -> Unit,
        ): ModelCouncilTextResult {
            _writeContexts += currentCoroutineContext()[TestWriteContext]
            val call = Call(modelId, systemPrompt, userPrompt)
            _calls.add(call)
            beforeCall(call)
            val resp = byModelId[modelId]
            val chunks = responseForCall?.let { listOf(it(call)) } ?: resp?.chunks ?: responseChunks
            val chunkDelay = resp?.delayPerChunkMs ?: delayPerChunkMs
            var cumulative = ""
            chunks.forEach { chunk ->
                if (chunkDelay > 0) delay(chunkDelay)
                cumulative += chunk
                onChunk(cumulative)
            }
            return ModelCouncilTextResult(cumulative)
        }
    }

    private class FakeExternalCliModelCouncilRunner : ModelCouncilExternalCliRunner {
        override suspend fun generate(
            seat: ModelCouncilSeat,
            systemPrompt: String,
            userPrompt: String,
            timeoutMs: Long,
            outputBudgetChars: Int,
            onChunk: (String) -> Unit,
        ): String = error("Unexpected external CLI call in test")
    }

    private class FakeCouncilRoomStore(
        private val writeContext: CoroutineContext = EmptyCoroutineContext,
        private val beforeObserve: suspend () -> Unit = {},
    ) : CouncilRoomStore {
        private val lock = Mutex()
        private val rooms = mutableMapOf<Uuid, MutableStateFlow<CouncilRoom?>>()
        val closedRooms = mutableListOf<CouncilRoom>()

        override suspend fun captureWriteContext(): CoroutineContext = writeContext

        override suspend fun observeRoom(conversationId: Uuid): StateFlow<CouncilRoom?> {
            beforeObserve()
            return lock.withLock {
                rooms.getOrPut(conversationId) { MutableStateFlow(null) }
            }
        }

        override fun peekRoom(conversationId: Uuid): CouncilRoom? =
            rooms[conversationId]?.value

        override suspend fun upsertRoom(room: CouncilRoom) {
            lock.withLock {
                rooms.getOrPut(room.conversationId) { MutableStateFlow(null) }.value = room
            }
        }

        override suspend fun closeAndEvict(room: CouncilRoom) {
            lock.withLock {
                closedRooms.add(room)
                rooms[room.conversationId]?.value = room
                rooms.remove(room.conversationId)
            }
        }

        override suspend fun evict(conversationId: Uuid) {
            lock.withLock { rooms.remove(conversationId) }
        }

        override suspend fun flush(conversationId: Uuid) = Unit

        override suspend fun deleteRoom(conversationId: Uuid) {
            lock.withLock { rooms.remove(conversationId) }
        }
    }

    private class TestWriteContext(
        val token: String,
    ) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<TestWriteContext>

        override val key: CoroutineContext.Key<TestWriteContext>
            get() = Key
    }

    private class FakeTaskReporter(
        private val beforeRegister: suspend () -> Unit = {},
        private val beforeUpsert: suspend (AgentTaskSnapshot) -> Unit = {},
    ) : CouncilRoomTaskReporter {
        val registered = mutableListOf<AgentTaskSnapshot>()
        val upserted = mutableListOf<AgentTaskSnapshot>()
        val cancelCallbacks = mutableMapOf<String, suspend () -> Boolean>()

        override suspend fun register(
            snapshot: AgentTaskSnapshot,
            cancel: (suspend () -> Boolean)?,
        ) {
            beforeRegister()
            registered.add(snapshot)
            if (cancel != null) cancelCallbacks[snapshot.taskId] = cancel
        }

        override suspend fun upsert(snapshot: AgentTaskSnapshot) {
            beforeUpsert(snapshot)
            upserted.add(snapshot)
        }
    }
}
