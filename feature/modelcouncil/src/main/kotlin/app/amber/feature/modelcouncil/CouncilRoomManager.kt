package app.amber.feature.modelcouncil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.ui.UIMessagePart
import app.amber.core.infra.AppScope
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import kotlin.uuid.Uuid
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.msgId
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.nowMs
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.statusForMode
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.modeShortLabel

/**
 * Long-lived, mutable state machine for a host-led Council Room.
 *
 * Contrast with [ModelCouncilManager] which is a one-shot batch (start → fixed
 * seats/rounds/mode → finish). Here every dimension is mutable at runtime:
 * invite/dismiss participants, switch mode, inject user messages, trigger host
 * actions, synthesize. All mutations go through this manager, which enforces
 * status transitions and persists via [CouncilRoomStore].
 *
 * Concurrency: one in-flight mutation per conversation (per-room [Mutex]); the
 * generation coroutines launched by host actions run on [appScope] and append
 * turns back through the same mutex via [RoomMutationSink].
 *
 * Memory: active rooms are held in [store]'s in-memory cache; [close] evicts.
 */
class CouncilRoomManager(
    private val appScope: AppScope,
    private val settingsFlow: StateFlow<Settings>,
    json: Json,
    private val modelRunner: ModelCouncilTextRunner,
    private val externalCliRunner: ModelCouncilExternalCliRunner,
    private val store: CouncilRoomStore,
    private val taskReporter: CouncilRoomTaskReporter,
    // Tool-augmented host turns (FULL mode). Defaults to NoOp so STANDARD mode
    // and unit tests are unaffected; DI injects AppCouncilHostToolProvider.
    private val toolProvider: CouncilHostToolProvider = NoOpCouncilHostToolProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomMutationSink {
    /** Lazy executor — constructed once with `this` as the sink. */
    private val executor: CouncilRoomExecutor by lazy {
        CouncilRoomExecutor(modelRunner, externalCliRunner, this, dispatcher)
    }

    private val planner = CouncilRoomPlanner(modelRunner, json)
    private val hostTurns by lazy {
        CouncilRoomHostTurns(
            executor = executor,
            toolProvider = toolProvider,
            sink = this,
            peekRoom = ::peekRoom,
            mutate = ::mutate,
            mutateRoomOrNull = ::mutateRoomOrNull,
            askUser = ::askUser,
        )
    }
    private val autoRun by lazy {
        CouncilRoomAutoRun(
            settingsFlow = settingsFlow,
            planner = planner,
            host = hostTurns,
            executor = executor,
            toolProvider = toolProvider,
            sink = this,
            peekRoom = ::peekRoom,
            mutateRoomOrNull = ::mutateRoomOrNull,
            failActiveRoom = ::failActiveRoom,
            autoAssembleSeats = ::autoAssembleSeats,
            hasModeOverride = { id -> jobsLock.withLock { id in userModeOverrideIds } },
            applyDetectedMode = ::applyDetectedMode,
            askUser = ::askUser,
        )
    }

    /**
     * Per-conversation in-flight generation jobs and close-gate state.
     *
     * A single [jobsLock] protects the guest job list, the active synthesis job,
     * and the set of conversations currently being closed. The closing gate
     * prevents new generation jobs from starting after [close] begins, plugging
     * the race where [trackGenerationJob] used to be async and could miss a
     * concurrent close.
     */
    private val jobsLock = Mutex()
    private val generationJobs = mutableMapOf<Uuid, MutableList<Job>>()
    private val synthesisJobs = mutableMapOf<Uuid, Job>()
    private val closingConversationIds = mutableSetOf<Uuid>()

    /**
     * Conversations whose automatic deliberation loop is in flight. Guarded by
     * [jobsLock]. Set synchronously before launching the auto-run job so two
     * near-simultaneous user messages can't start two parallel runs.
     */
    private val autoRunConversationIds = mutableSetOf<Uuid>()

    /**
     * Conversations whose council is currently being auto-assembled by the host
     * (the host model is picking role lenses + models for an empty room). Guarded
     * by [jobsLock] so two near-simultaneous first messages can't double-assemble.
     */
    private val assemblingConversationIds = mutableSetOf<Uuid>()

    /**
     * Conversations where the user explicitly picked a discussion mode (via the
     * top-bar dropdown → [switchMode]). Auto-detect won't override these. Guarded
     * by [jobsLock]; cleared when a fresh room opens.
     */
    private val userModeOverrideIds = mutableSetOf<Uuid>()

    /**
     * Per-conversation pending ask_user awaiter. When the host asks the user a
     * question (FULL mode), [CouncilRoomAutoRun.run] suspends on
     * [askUser], which parks a [CompletableDeferred] here. The user's
     * answer via [resumeAfterUserAnswer] completes it, un-suspending the loop.
     * [close] cancels it so a closing room tears the awaiter down cleanly. Guarded
     * by [jobsLock].
     */
    private data class PendingQuestion(val messageId: String, val answer: CompletableDeferred<String>)
    private val pendingAskUser = mutableMapOf<Uuid, PendingQuestion>()

    /** Per-conversation mutation lock; prevents races between host actions / user input. */
    private val locks = mutableMapOf<Uuid, Mutex>()
    private val locksLock = Mutex()

    private suspend fun lockFor(conversationId: Uuid): Mutex = locksLock.withLock {
        locks.getOrPut(conversationId) { Mutex() }
    }

    /**
     * Open a Room for a conversation. Fails if one already exists and is not
     * terminal. The host participant is materialized from [hostAssistantId]
     * (the conversation's canonical Amber identity).
     *
     * Returns the new Room id (= conversationId) on Ok.
     */
    suspend fun openRoom(
        conversationId: Uuid,
        hostAssistantId: Uuid,
        hostName: String,
        objective: String,
        context: String = "",
        initialMode: CouncilRoomMode = CouncilRoomMode.EXPLORE,
        initialGuests: List<CouncilParticipant> = emptyList(),
        maxRounds: Int = DEFAULT_COUNCIL_ROOM_MAX_ROUNDS,
        maxParticipants: Int = DEFAULT_COUNCIL_ROOM_MAX_PARTICIPANTS,
        hostModelIdOverride: Uuid? = null,
    ): CouncilRoomOpResult {
        // SYNTHESIZE is a terminal-ish phase reachable only via synthesize().
        if (initialMode == CouncilRoomMode.SYNTHESIZE) {
            return CouncilRoomOpResult.Err(
                code = "invalid_initial_mode",
                message = "Cannot open a Room in SYNTHESIZE mode; use EXPLORE or DEBATE.",
            )
        }
        val effectiveMaxParticipants = maxParticipants.coerceIn(2, MAX_PARTICIPANTS_CAP)
        // Roster overflow is an explicit error rather than silent truncation —
        // a caller passing more guests than capacity is almost certainly a bug.
        if (initialGuests.size + 1 > effectiveMaxParticipants) {
            return CouncilRoomOpResult.Err(
                code = "initial_roster_overflow",
                message = "initialGuests (${initialGuests.size}) + host exceeds maxParticipants ($effectiveMaxParticipants).",
            )
        }
        val mutex = lockFor(conversationId)
        return withCapturedWriteContext {
            mutex.withLock {
            val flow = store.observeRoom(conversationId)
            val existing = flow.value
            if (existing != null && !existing.status.terminal) {
                return@withLock CouncilRoomOpResult.Err(
                    code = "room_already_open",
                    message = "A Council Room is already active for this conversation. Close it first.",
                )
            }
            val now = nowMs()
            // Resolve the host's model display name so the host bubble can show it.
            // When a hostModelIdOverride is supplied it takes priority (matches
            // resolveHostModelId()'s resolution order), so the bubble shows the model
            // the host will ACTUALLY run on, not a stale room-level model.
            val settings = settingsFlow.value
            val hostModelName = run {
                val modelId = hostModelIdOverride?.let { settings.findModelById(it)?.id }
                    ?: settings.chatModelId
                settings.findModelById(modelId)?.displayName.orEmpty()
            }
            val host = CouncilParticipant(
                id = COUNCIL_ROOM_HOST_ID,
                name = hostName.ifBlank { "Host" },
                role = "host",
                kind = CouncilParticipantKind.HOST,
                status = CouncilParticipantStatus.IDLE,
                modelName = hostModelName,
            )
            val participants = buildList {
                add(host)
                initialGuests.forEach { guest -> add(guest.copy(status = CouncilParticipantStatus.INVITED)) }
            }
            val room = CouncilRoom(
                id = conversationId.toString(),
                conversationId = conversationId,
                hostAssistantId = hostAssistantId,
                objective = objective.take(MAX_OBJECTIVE_CHARS),
                context = context.take(MAX_CONTEXT_CHARS),
                mode = initialMode,
                status = statusForMode(initialMode),
                participants = participants,
                phaseMarkers = listOf(CouncilPhaseMarker(
                    id = msgId(),
                    label = modeShortLabel(initialMode) + " · Opening",
                    mode = initialMode,
                    createdAtMs = now,
                )),
                maxRounds = maxRounds.coerceIn(1, MAX_ROUNDS_CAP),
                maxParticipants = effectiveMaxParticipants,
                hostModelIdOverride = hostModelIdOverride,
                createdAtMs = now,
                updatedAtMs = now,
            )
            currentCoroutineContext().ensureActive()
            // Once observers can adopt the room, finish its local stop-task handoff.
            // Keep the captured store write context; only caller cancellation is deferred.
            withContext(NonCancellable) {
                store.upsertRoom(room)
                registerTask(room)
                // Fresh room → reset auto-detect bookkeeping.
                jobsLock.withLock {
                    userModeOverrideIds.remove(conversationId)
                }
            }
                CouncilRoomOpResult.Ok(room)
            }
        }
    }

    /** Observe the Room state. Cold-loads from storage on first access. */
    suspend fun observeRoom(conversationId: Uuid): StateFlow<CouncilRoom?> =
        store.observeRoom(conversationId)

    /** Synchronous snapshot; null if absent/not loaded. */
    fun peekRoom(conversationId: Uuid): CouncilRoom? = store.peekRoom(conversationId)

    // ── RoomMutationSink (writes streaming generation back into the room) ───

    override suspend fun upsertStreamingMessage(conversationId: Uuid, message: CouncilMessage) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Ok(room)
            }
            val existing = room.messages.firstOrNull { it.id == message.id }
            val messages = if (existing != null) {
                // Streaming updates are sparse (only id + text); merge text into
                // the existing message so author/round/mode/reference graph stay.
                room.messages.map { m ->
                    if (m.id == message.id) {
                        m.copy(
                            text = message.text,
                            status = CouncilMessageStatus.STREAMING,
                        )
                    } else {
                        m
                    }
                }
            } else {
                room.messages + message
            }
            val authorId = existing?.authorId ?: message.authorId
            val participants = room.participants.map { p ->
                if (p.id == authorId) {
                    p.copy(status = CouncilParticipantStatus.SPEAKING)
                } else {
                    p
                }
            }
            CouncilRoomOpResult.Ok(room.copy(
                messages = messages,
                participants = participants,
                updatedAtMs = nowMs(),
            ))
        }
    }

    override suspend fun completeMessage(
        conversationId: Uuid,
        messageId: String,
        status: CouncilMessageStatus,
        text: String,
        warnings: List<String>,
        error: String,
        authorId: String,
        authorStatus: CouncilParticipantStatus,
    ) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Ok(room)
            }
            val messages = room.messages.map { m ->
                if (m.id == messageId) {
                    m.copy(
                        status = status,
                        text = text,
                        warnings = warnings,
                        error = error,
                    )
                } else {
                    m
                }
            }
            val participants = room.participants.map { p ->
                if (p.id == authorId) p.copy(status = authorStatus) else p
            }
            CouncilRoomOpResult.Ok(room.copy(
                messages = messages,
                participants = participants,
                updatedAtMs = nowMs(),
            ))
        }
    }

    override suspend fun completeSynthesis(
        conversationId: Uuid,
        synthesisMessageId: String,
        synthesis: String,
        warnings: List<String>,
    ) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Ok(room)
            }
            val now = nowMs()
            // The synthesis was streamed into a host message row (mode ==
            // SYNTHESIZE) by generateSynthesis; finalize that row in place
            // rather than appending a duplicate. Fall back to inserting a new
            // row if the streaming seed is missing (hostless fixtures, legacy
            // callers) so the conclusion still surfaces inline.
            val host = room.participants.firstOrNull { it.id == COUNCIL_ROOM_HOST_ID }
            val existing = room.messages.firstOrNull { it.id == synthesisMessageId }
            val messages = when {
                existing != null -> room.messages.map { m ->
                    if (m.id == synthesisMessageId) {
                        m.copy(
                            text = synthesis.take(MAX_MESSAGE_CHARS),
                            status = CouncilMessageStatus.COMPLETED,
                            warnings = warnings,
                        )
                    } else {
                        m
                    }
                }
                synthesis.isNotBlank() && host != null -> room.messages + CouncilMessage(
                    id = synthesisMessageId.ifBlank { msgId() },
                    authorId = host.id,
                    authorName = host.name,
                    role = host.role,
                    round = room.round,
                    mode = CouncilRoomMode.SYNTHESIZE,
                    text = synthesis.take(MAX_MESSAGE_CHARS),
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                )
                else -> room.messages
            }
            CouncilRoomOpResult.Ok(room.copy(
                mode = CouncilRoomMode.SYNTHESIZE,
                status = CouncilRoomStatus.FINALIZED,
                synthesis = synthesis,
                warnings = warnings,
                messages = messages,
                finishedAtMs = now,
                updatedAtMs = now,
            ))
        }
    }

    /**
     * Invite a participant at runtime. No-op (Err) if the room is terminal or
     * the roster is full. Dedupes by participant id.
     */
    suspend fun inviteParticipant(
        conversationId: Uuid,
        participant: CouncilParticipant,
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        if (room.status.terminal) {
            return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot invite.")
        }
        if (room.participants.any { it.id == participant.id }) {
            return@mutate CouncilRoomOpResult.Err("duplicate_participant", "Participant ${participant.id} already in room.")
        }
        if (room.participants.size >= room.maxParticipants) {
            return@mutate CouncilRoomOpResult.Err("roster_full", "Room is at max participants (${room.maxParticipants}).")
        }
        val guest = participant.copy(
            kind = CouncilParticipantKind.GUEST,
            status = CouncilParticipantStatus.INVITED,
        )
        val now = nowMs()
        CouncilRoomOpResult.Ok(room.copy(
            participants = room.participants + guest,
            phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                id = msgId(),
                label = "${guest.name} joined",
                mode = room.mode,
                createdAtMs = now,
            ),
            updatedAtMs = now,
        ))
    }

    /** Dismiss a participant (marks DISMISSED, keeps history). Host cannot be dismissed. */
    suspend fun dismissParticipant(
        conversationId: Uuid,
        participantId: String,
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        if (participantId == COUNCIL_ROOM_HOST_ID) {
            return@mutate CouncilRoomOpResult.Err("cannot_dismiss_host", "Host cannot be dismissed.")
        }
        val updated = room.participants.map { p ->
            if (p.id == participantId) p.copy(status = CouncilParticipantStatus.DISMISSED) else p
        }
        if (updated == room.participants) {
            return@mutate CouncilRoomOpResult.Err("not_found", "Participant $participantId not in room.")
        }
        CouncilRoomOpResult.Ok(room.copy(participants = updated, updatedAtMs = nowMs()))
    }

    /**
     * Switch discussion mode. Valid targets: EXPLORE ↔ DEBATE. SYNTHESIZE is
     * reached only via [synthesize]. Emits a phase marker. Resets round to 0
     * when entering DEBATE from EXPLORE (debate counts its own rounds).
     */
    suspend fun switchMode(
        conversationId: Uuid,
        mode: CouncilRoomMode,
    ): CouncilRoomOpResult {
        // User-driven mode pick — record it so the auto-detector won't override.
        jobsLock.withLock { userModeOverrideIds.add(conversationId) }
        return mutate(conversationId) { room ->
        if (room.status.terminal) {
            return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot switch mode.")
        }
        if (mode == CouncilRoomMode.SYNTHESIZE) {
            return@mutate CouncilRoomOpResult.Err("invalid_mode_switch", "Use synthesize() to enter SYNTHESIZE.")
        }
        if (room.mode == mode) {
            return@mutate CouncilRoomOpResult.Ok(room)
        }
        val now = nowMs()
        CouncilRoomOpResult.Ok(room.copy(
            mode = mode,
            status = statusForMode(mode),
            round = if (mode == CouncilRoomMode.DEBATE) 0 else room.round,
            phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                id = msgId(),
                label = "Switched to ${modeShortLabel(mode)}",
                mode = mode,
                createdAtMs = now,
            ),
            updatedAtMs = now,
        ))
        }
    }

    private suspend fun applyDetectedMode(conversationId: Uuid, mode: CouncilRoomMode) {
        mutate(conversationId) { room ->
            val overridden = jobsLock.withLock { conversationId in userModeOverrideIds }
            if (room.status.terminal || overridden || room.mode == mode) {
                CouncilRoomOpResult.Ok(room)
            } else {
                CouncilRoomOpResult.Ok(room.copy(mode = mode, status = statusForMode(mode)))
            }
        }
    }

    /**
     * Append a user message into the room timeline. [mentionTargets] are
     * participant ids the user @-mentioned; PR2 uses these to route follow-ups.
     * Returns the updated room.
     */
    suspend fun userMessage(
        conversationId: Uuid,
        text: String,
        mentionTargets: List<String> = emptyList(),
        attachments: List<UIMessagePart> = emptyList(),
        attachmentText: String = "",
    ): CouncilRoomOpResult = acceptUserMessage(conversationId, text, attachments, attachmentText)

    private suspend fun acceptUserMessage(
        conversationId: Uuid,
        text: String,
        attachments: List<UIMessagePart> = emptyList(),
        attachmentText: String = "",
        questionId: String? = null,
        answerOnly: Boolean = false,
    ): CouncilRoomOpResult = withCapturedWriteContext {
        val result = lockFor(conversationId).withLock {
            val room = store.observeRoom(conversationId).value
                ?: return@withLock CouncilRoomOpResult.Err("not_found", "No room for this conversation.")
            if (room.status.terminal) {
                return@withLock CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot send messages.")
            }
            // An attachment-only message (image / file, no text) is still valid.
            if (text.isBlank() && attachments.isEmpty()) {
                return@withLock CouncilRoomOpResult.Err("empty_message", "Message text is empty.")
            }
            val question = room.messages.lastOrNull { it.kind == CouncilMessageKind.ASK_USER }
                ?.takeUnless { ask -> room.messages.any { it.authorId == COUNCIL_ROOM_USER_ID && it.replyToMessageId == ask.id } }
                ?.takeIf { ask ->
                    if (questionId != null) ask.id == questionId else answerOnly || room.status == CouncilRoomStatus.INTERRUPTED
                }
            if (answerOnly && question == null) {
                return@withLock CouncilRoomOpResult.Err("no_pending_question", "No pending ask_user for this room.")
            }
            val now = nowMs()
            val isFirstUserMessage = room.messages.none { it.authorId == COUNCIL_ROOM_USER_ID }
            val userMessage = CouncilMessage(
                id = msgId(),
                authorId = COUNCIL_ROOM_USER_ID,
                authorName = "You",
                role = "user",
                round = room.round,
                mode = room.mode,
                text = text.take(MAX_MESSAGE_CHARS),
                createdAtMs = now,
                status = CouncilMessageStatus.COMPLETED,
                attachments = attachments,
                attachmentText = attachmentText.take(MAX_CONTEXT_CHARS),
                replyToMessageId = question?.id,
            )
            // The first user message IS the topic — promote it to the room
            // objective so every member/synthesis prompt deliberates on it. For an
            // attachment-only first message (no text), derive a topic from the
            // attachment so mode-detection and host framing aren't left blank.
            val objective = if (isFirstUserMessage) {
                text.take(MAX_OBJECTIVE_CHARS).ifBlank {
                    val docName = attachments.filterIsInstance<UIMessagePart.Document>()
                        .firstOrNull()?.fileName
                    when {
                        docName != null -> "Discuss the file provided by the user: $docName"
                        attachments.any { it is UIMessagePart.Image } -> "Discuss the image provided by the user"
                        else -> room.objective
                    }
                }
            } else {
                room.objective
            }
            val next = room.copy(
                objective = objective,
                messages = room.messages + userMessage,
                status = if (question != null && room.status == CouncilRoomStatus.INTERRUPTED) statusForMode(room.mode) else room.status,
                updatedAtMs = now,
            )
            currentCoroutineContext().ensureActive()
            // Once the answer is visible, finish the matching waiter handoff even
            // if the submitting page is leaving. Model work stays cancellable below.
            withContext(NonCancellable) {
                store.upsertRoom(next)
                updateTaskStatus(next)
                if (question != null) {
                    jobsLock.withLock {
                        pendingAskUser[conversationId]?.takeIf { it.messageId == question.id }
                            ?.answer?.complete(userMessage.text)
                    }
                }
            }
            CouncilRoomOpResult.Ok(next)
        }
        if (result is CouncilRoomOpResult.Ok) {
            val current = peekRoom(conversationId)
            // STANDARD mode: assemble the council immediately (the original path).
            // FULL mode: DEFER assembly to CouncilRoomAutoRun.run, so it runs AFTER the
            // host's pre-topic research turn — that way the bespoke roles are tailored
            // to the facts the host actually found, not generic presets. The deferred
            // call lives in CouncilRoomAutoRun.run (gated on activeGuests.isEmpty()).
            val isFullMode = settingsFlow.value.agentRuntime.modelCouncil.councilPowerMode ==
                CouncilPowerMode.FULL
            if (current != null && !current.status.terminal &&
                current.activeGuests.isEmpty() && current.objective.isNotBlank() &&
                !isFullMode
            ) {
                autoAssembleSeats(conversationId, current.objective, settingsFlow.value)
            }
            // Fire the automatic deliberation: members speak across rounds, then
            // the host synthesizes — no manual turn-by-turn driving. No-op if a
            // run is already in flight (mid-run interjection is handled elsewhere).
            maybeStartAutoRun(conversationId)
        }
        result
    }

    /**
     * Execute a host action. PR1 implements the bookkeeping (mode switches,
     * phase markers, host message echoes); PR2 adds the guest generation that
     * [InviteNext] / [AskFollowUp] / [LetGuestsRespond] trigger.
     */
    suspend fun hostAction(
        conversationId: Uuid,
        action: HostAction,
    ): CouncilRoomOpResult = when (action) {
        HostAction.SwitchToExplore -> switchMode(conversationId, CouncilRoomMode.EXPLORE)
        HostAction.SwitchToDebate -> switchMode(conversationId, CouncilRoomMode.DEBATE)
        HostAction.Synthesize -> synthesize(conversationId)
        HostAction.Stop -> close(conversationId, cancel = true)
        is HostAction.InviteNext -> hostActionInviteNext(conversationId, action)
        is HostAction.AskFollowUp -> hostActionAskFollowUp(conversationId, action)
        is HostAction.LetGuestsRespond -> hostActionLetGuestsRespond(conversationId, action)
        is HostAction.Redirect -> hostActionRedirect(conversationId, action)
    }

    private suspend fun hostActionInviteNext(
        conversationId: Uuid,
        action: HostAction.InviteNext,
    ): CouncilRoomOpResult = withCapturedWriteContext {
        val preflight = mutatePreflight(conversationId) { room ->
            val guest = room.participantById(action.participantId)
                ?: return@mutatePreflight preflightNoParticipant(action.participantId)
            if (guest.kind != CouncilParticipantKind.GUEST) {
                return@mutatePreflight PreflightResult.Err("not_a_guest", "Only guests can be invited to speak.")
            }
            val now = nowMs()
            val hostEcho = if (action.instruction.isBlank()) {
                "${guest.name}, please speak."
            } else {
                "${guest.name}，${action.instruction}"
            }
            val updated = room.copy(
                participants = room.participants.map { p ->
                    if (p.id == guest.id) p.copy(status = CouncilParticipantStatus.WAITING) else p
                },
                messages = room.messages + CouncilMessage(
                    id = msgId(),
                    authorId = COUNCIL_ROOM_HOST_ID,
                    authorName = room.host?.name ?: "Host",
                    role = "host",
                    round = room.round,
                    mode = room.mode,
                    text = hostEcho.take(MAX_MESSAGE_CHARS),
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                ),
                updatedAtMs = now,
            )
            PreflightResult.Plan(GuestTurnPlan(
                room = updated,
                guest = guest,
                userPrompt = CouncilRoomPrompts.invitedByHostPrompt(updated, guest, action.instruction),
                replyToMessageId = null,
                continuesFromMessageId = null,
                invitedBy = COUNCIL_ROOM_HOST_ID,
            ))
        }
        launchGuestTurn(preflight, conversationId)
    }

    private suspend fun hostActionAskFollowUp(
        conversationId: Uuid,
        action: HostAction.AskFollowUp,
    ): CouncilRoomOpResult = withCapturedWriteContext {
        val preflight = mutatePreflight(conversationId) { room ->
            val guest = room.participantById(action.toParticipantId)
                ?: return@mutatePreflight preflightNoParticipant(action.toParticipantId)
            val seed = room.messages.firstOrNull { it.id == action.aboutMessageId }
                ?: return@mutatePreflight PreflightResult.Err("seed_not_found", "Message ${action.aboutMessageId} not found.")
            if (guest.kind != CouncilParticipantKind.GUEST) {
                return@mutatePreflight PreflightResult.Err("not_a_guest", "Only guests can be asked.")
            }
            val now = nowMs()
            val hostName = room.host?.name ?: "Host"
            val updated = room.copy(
                participants = room.participants.map { p ->
                    if (p.id == guest.id) p.copy(status = CouncilParticipantStatus.WAITING) else p
                },
                messages = room.messages + CouncilMessage(
                    id = msgId(),
                    authorId = COUNCIL_ROOM_HOST_ID,
                    authorName = hostName,
                    role = "host",
                    round = room.round,
                    mode = room.mode,
                    text = "${guest.name}, please respond to ${seed.authorName}'s contribution.".take(MAX_MESSAGE_CHARS),
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                ),
                updatedAtMs = now,
            )
            PreflightResult.Plan(GuestTurnPlan(
                room = updated,
                guest = guest,
                userPrompt = CouncilRoomPrompts.followUpPrompt(updated, guest, seed),
                replyToMessageId = seed.id,
                continuesFromMessageId = null,
                invitedBy = COUNCIL_ROOM_HOST_ID,
            ))
        }
        launchGuestTurn(preflight, conversationId)
    }

    private suspend fun hostActionLetGuestsRespond(
        conversationId: Uuid,
        action: HostAction.LetGuestsRespond,
    ): CouncilRoomOpResult = withCapturedWriteContext {
        // Multi-target: preflight validates + writes the host echo, then we launch
        // one generation per target guest. Each gets its own continuesFromMessageId.
        val preflight = mutatePreflight(conversationId) { room ->
            val seed = room.messages.firstOrNull { it.id == action.seedMessageId }
                ?: return@mutatePreflight PreflightResult.Err("seed_not_found", "Message ${action.seedMessageId} not found.")
            val targets = action.participantIds.ifEmpty { room.activeGuests.map { it.id } }
            val validGuests = targets.mapNotNull { id -> room.participantById(id) }
                .filter { it.kind == CouncilParticipantKind.GUEST && it.status != CouncilParticipantStatus.DISMISSED }
            if (validGuests.isEmpty()) {
                return@mutatePreflight PreflightResult.Err("no_guests", "No guests to respond.")
            }
            val now = nowMs()
            val hostName = room.host?.name ?: "Host"
            val updated = room.copy(
                participants = room.participants.map { p ->
                    if (p.id in validGuests.map { it.id }) p.copy(status = CouncilParticipantStatus.WAITING) else p
                },
                messages = room.messages + CouncilMessage(
                    id = msgId(),
                    authorId = COUNCIL_ROOM_HOST_ID,
                    authorName = hostName,
                    role = "host",
                    round = room.round,
                    mode = room.mode,
                    text = "Please add to or challenge ${seed.authorName}'s contribution.".take(MAX_MESSAGE_CHARS),
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                ),
                updatedAtMs = now,
            )
            // Multi-plan: return the first as the primary; launch rest separately.
            PreflightResult.Plan(GuestTurnPlan(
                room = updated,
                guest = validGuests.first(),
                userPrompt = CouncilRoomPrompts.followUpPrompt(updated, validGuests.first(), seed),
                replyToMessageId = null,
                continuesFromMessageId = seed.id,
                invitedBy = COUNCIL_ROOM_HOST_ID,
                extraGuests = validGuests.drop(1).map { g ->
                    GuestTurnPlan(
                        room = updated,
                        guest = g,
                        userPrompt = CouncilRoomPrompts.followUpPrompt(updated, g, seed),
                        replyToMessageId = null,
                        continuesFromMessageId = seed.id,
                        invitedBy = COUNCIL_ROOM_HOST_ID,
                    )
                },
            ))
        }
        launchGuestTurn(preflight, conversationId)
    }

    private suspend fun hostActionRedirect(
        conversationId: Uuid,
        action: HostAction.Redirect,
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        val guest = room.participantById(action.participantId)
            ?: return@mutate noParticipant(room, action.participantId)
        if (guest.kind != CouncilParticipantKind.GUEST) {
            return@mutate CouncilRoomOpResult.Err("not_a_guest", "Only guests can be redirected.")
        }
        if (guest.status == CouncilParticipantStatus.DISMISSED) {
            return@mutate CouncilRoomOpResult.Err("participant_dismissed", "${guest.name} has been dismissed.")
        }
        val now = nowMs()
        val hostName = room.host?.name ?: "Host"
        CouncilRoomOpResult.Ok(room.copy(
            messages = room.messages + CouncilMessage(
                id = msgId(),
                authorId = COUNCIL_ROOM_HOST_ID,
                authorName = hostName,
                role = "host",
                round = room.round,
                mode = room.mode,
                text = "${guest.name}, consider this direction: ${action.newDirection}".take(MAX_MESSAGE_CHARS),
                createdAtMs = now,
                status = CouncilMessageStatus.COMPLETED,
            ),
            updatedAtMs = now,
        ))
    }

    /**
     * Trigger synthesis. Switches to SYNTHESIZE, marks FINALIZING, and launches
     * the host's synthesis generation on [appScope] *outside* the room mutex.
     * The final verdict is written back via [RoomMutationSink.completeSynthesis],
     * which transitions the room to FINALIZED.
     */
    suspend fun synthesize(conversationId: Uuid): CouncilRoomOpResult = withCapturedWriteContext restore@{
        val settings = settingsFlow.value
        // Capture the resolved host model id inside the validation mutate so we
        // don't flip to FINALIZING and then discover the host has no model.
        var hostModelId: Uuid? = null
        val result = mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot synthesize.")
            }
            if (room.messages.none { it.authorId != COUNCIL_ROOM_HOST_ID && it.authorId != COUNCIL_ROOM_USER_ID }) {
                return@mutate CouncilRoomOpResult.Err("nothing_to_synthesize", "No guest messages to synthesize.")
            }
            if (room.host == null) {
                return@mutate CouncilRoomOpResult.Err("no_host", "Host participant missing.")
            }
            hostModelId = resolveHostModelId(room, settings)
                ?: return@mutate CouncilRoomOpResult.Err("no_host_model", "Host model not found.")
            val now = nowMs()
            CouncilRoomOpResult.Ok(room.copy(
                mode = CouncilRoomMode.SYNTHESIZE,
                status = CouncilRoomStatus.FINALIZING,
                phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                    id = msgId(),
                    label = "Host synthesis",
                    mode = CouncilRoomMode.SYNTHESIZE,
                    createdAtMs = now,
                ),
                updatedAtMs = now,
            ))
        }
        if (result is CouncilRoomOpResult.Err) return@restore result
        val updatedRoom = (result as CouncilRoomOpResult.Ok).room
        val resolvedHostModelId = hostModelId
            ?: return@restore CouncilRoomOpResult.Err("no_host_model", "Host model not found.")

        val job = launchSynthesisJob(conversationId) {
            runCatching {
                val councilSetting = settings.agentRuntime.modelCouncil
                executor.generateSynthesis(
                    room = updatedRoom,
                    hostModelId = resolvedHostModelId,
                    hostSystemPrompt = CouncilRoomPrompts.hostSystemPrompt(updatedRoom),
                    settings = settings,
                    reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
                    extraSystemPrompt = councilSetting.hostSystemPrompt,
                    synthesisMessageId = msgId(),
                )
            }.onFailure { error ->
                if (error !is CancellationException) {
                    android.util.Log.e(TAG, "Host synthesis failed", error)
                }
            }
        }
        if (job == null) {
            // The closing gate blocked the synthesis job from starting. The room
            // is already persisted as FINALIZING; the concurrent close() will
            // finalize it (graceful close waits for the synthesis job, sees none,
            // and falls through to mark FINALIZED). We surface a distinct error
            // code so the caller knows synthesis did not actually start rather
            // than silently returning Ok.
            return@restore CouncilRoomOpResult.Err(
                code = "room_closing",
                message = "Room is being closed; synthesis was not started.",
            )
        }
        CouncilRoomOpResult.Ok(updatedRoom)
    }

    // ── ask_user (FULL mode HITL) ───────────────────────────────────────────

    /** Register the question's waiter before publishing its actionable timeline row. */
    private suspend fun askUser(conversationId: Uuid, question: CouncilMessage): String? {
        val pending = PendingQuestion(question.id, CompletableDeferred())
        return try {
            val result = mutate(conversationId) { room ->
                if (room.status.terminal) {
                    return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended.")
                }
                jobsLock.withLock { pendingAskUser[conversationId] = pending }
                val messages = if (room.messages.any { it.id == question.id }) {
                    room.messages.map { if (it.id == question.id) question else it }
                } else {
                    room.messages + question
                }
                CouncilRoomOpResult.Ok(room.copy(
                    messages = messages,
                    participants = room.participants.map { participant ->
                        if (participant.id == question.authorId) participant.copy(status = CouncilParticipantStatus.IDLE) else participant
                    },
                    status = CouncilRoomStatus.INTERRUPTED,
                    updatedAtMs = nowMs(),
                ))
            }
            val room = (result as? CouncilRoomOpResult.Ok)?.room ?: return null
            val bound = room.totalTimeoutMs.coerceAtLeast(60_000L)
            withTimeoutOrNull(bound) { pending.answer.await() }
        } finally {
            withContext(NonCancellable) {
                jobsLock.withLock {
                    if (pendingAskUser[conversationId] === pending) pendingAskUser.remove(conversationId)
                }
            }
        }
    }

    /** Answer cards and the ordinary composer share acceptance and waiter ownership. */
    suspend fun resumeAfterUserAnswer(
        conversationId: Uuid,
        answer: String,
        questionId: String? = null,
    ): CouncilRoomOpResult = acceptUserMessage(
        conversationId = conversationId,
        text = answer,
        questionId = questionId,
        answerOnly = true,
    )

    // ── automatic orchestration ─────────────────────────────────────────────

    /**
     * Start the automatic deliberation if the room is live with guests and no run
     * is already in flight. The whole pipeline (rounds + synthesis) runs as one
     * cancellable job registered in [generationJobs], so [close] tears it down.
     *
     * Dedupe is synchronous via [autoRunConversationIds] under [jobsLock]: two
     * near-simultaneous user messages can't both pass and launch parallel runs.
     */
    private suspend fun maybeStartAutoRun(conversationId: Uuid) {
        val room = peekRoom(conversationId) ?: return
        if (room.status.terminal) return
        // FULL mode defers roster assembly into CouncilRoomAutoRun.run (after the
        // pre-topic research), so a FULL-mode room legitimately has no guests yet at
        // this point — it must still be allowed to start the orchestration, which
        // will research → assemble → run. STANDARD mode assembles eagerly in
        // sendUserMessage, so for it the empty-guests guard still means "nothing to
        // run" and we bail. Without this distinction FULL-mode rooms deadlock:
        // empty → don't start → never assemble.
        val isFullMode = settingsFlow.value.agentRuntime.modelCouncil.councilPowerMode ==
            CouncilPowerMode.FULL
        if (!isFullMode && room.activeGuests.isEmpty()) return
        val claimed = jobsLock.withLock {
            if (conversationId in autoRunConversationIds || conversationId in closingConversationIds) {
                false
            } else {
                autoRunConversationIds.add(conversationId)
                true
            }
        }
        if (!claimed) return
        val job = launchGuestJob(conversationId) {
            try {
                autoRun.run(conversationId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                android.util.Log.e(TAG, "Council auto-run failed", error)
                failActiveRoom(
                    conversationId = conversationId,
                    message = "Council auto-run failed: ${error.message ?: error::class.java.simpleName}",
                )
            } finally {
                jobsLock.withLock { autoRunConversationIds.remove(conversationId) }
            }
        }
        if (job == null) {
            // The closing gate blocked the launch — release the dedupe slot so a
            // later (re)open can run.
            jobsLock.withLock { autoRunConversationIds.remove(conversationId) }
        }
    }

    /** Mutate that returns the new room, or null if the room is terminal/missing. */
    private suspend fun mutateRoomOrNull(
        conversationId: Uuid,
        transform: (CouncilRoom) -> CouncilRoom,
    ): CouncilRoom? {
        val result = mutate(conversationId) { room ->
            if (room.status.terminal) {
                CouncilRoomOpResult.Err("room_terminal", "Room has ended.")
            } else {
                CouncilRoomOpResult.Ok(transform(room))
            }
        }
        return (result as? CouncilRoomOpResult.Ok)?.room
    }

    private suspend fun failActiveRoom(
        conversationId: Uuid,
        message: String,
    ) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                CouncilRoomOpResult.Ok(room)
            } else {
                val now = nowMs()
                CouncilRoomOpResult.Ok(room.copy(
                    status = CouncilRoomStatus.FAILED,
                    warnings = room.warnings + message,
                    finishedAtMs = now,
                    updatedAtMs = now,
                ))
            }
        }
    }

    /**
     * Build a council for a host-only room from [objective]. The three core seats
     * (supporter/opponent/judge) are always included; the host model additionally
     * picks the domain lenses that fit the topic (best-effort). Each role is
     * assigned a chat model from the user's providers — the default model first,
     * then the rest round-robin so a multi-model setup yields a diverse council.
     * Members are added via [inviteParticipant] so the roster fills in live. No-op
     * if no chat models are configured or an assembly is already running.
     */
    private suspend fun autoAssembleSeats(conversationId: Uuid, objective: String, settings: Settings) {
        val claimed = jobsLock.withLock {
            if (conversationId in assemblingConversationIds) {
                false
            } else {
                assemblingConversationIds.add(conversationId)
                true
            }
        }
        if (!claimed) return
        try {
            val room = peekRoom(conversationId) ?: return
            if (room.status.terminal || room.activeGuests.isNotEmpty()) return

            val chatModels = settings.providers
                .flatMap { it.models }
                .filter { it.type == ModelType.CHAT }
            if (chatModels.isEmpty()) return

            // Let the user see the host is working before the (network) pick begins.
            mutateRoomOrNull(conversationId) { r ->
                r.copy(
                    phaseMarkers = r.phaseMarkers + CouncilPhaseMarker(
                        id = msgId(),
                        label = "Host is assembling a topic-specific council…",
                        mode = r.mode,
                        createdAtMs = nowMs(),
                    ),
                    updatedAtMs = nowMs(),
                )
            }

            // Cap so host + guests stay within the roster limit.
            val maxGuests = (room.maxParticipants - 1).coerceAtLeast(1)
            // The host DESIGNS the roster for this specific objective — bespoke
            // roles (name + perspective), not a fixed preset list. Falls back to the
            // classic supporter/opponent/judge trio only if generation fails.
            val roles = planner.planRoles(room, objective, settings, maxGuests)
                .ifEmpty { ModelCouncilRolePresets.coreSeats.map { it.name to it.prompt } }
                .distinctBy { it.first }
                .take(maxGuests)

            val defaultModel = settings.findModelById(settings.chatModelId)
            val ordered: List<Model> =
                (listOfNotNull(defaultModel) + chatModels.filter { it.id != defaultModel?.id })
                    .ifEmpty { chatModels }

            roles.forEachIndexed { index, (name, perspective) ->
                val model = ordered[index % ordered.size]
                val invited = inviteParticipant(
                    conversationId,
                    CouncilParticipant(
                        id = "auto-${Uuid.random()}",
                        name = name,
                        role = "auto",
                        kind = CouncilParticipantKind.GUEST,
                        modelId = model.id,
                        systemPrompt = perspective.ifBlank {
                            "From your role's perspective, give a clear, testable, and focused judgment on the topic."
                        },
                        modelName = model.displayName,
                    ),
                )
                // Roster full or room ended — stop adding more.
                if (invited is CouncilRoomOpResult.Err) return
            }
        } finally {
            jobsLock.withLock { assemblingConversationIds.remove(conversationId) }
        }
    }

    /**
     * Close the Room. Cancels all in-flight generation jobs, then: if [cancel],
     * mark CANCELLED; else mark FINALIZED. Persists the terminal state AND
     * evicts the in-memory entry atomically (via [CouncilRoomStore.closeAndEvict])
     * so no concurrent re-open can slip between persist and evict. Also releases
     * the per-conversation mutex from [locks] to bound memory. Idempotent.
     *
     * Graceful close ([cancel] = false) behaves specially when the room is in
     * [CouncilRoomStatus.FINALIZING]: it waits for the host synthesis job to
     * finish so the final verdict can be written back via
     * [RoomMutationSink.completeSynthesis]. If the synthesis does not finish
     * within [CouncilRoom.totalTimeoutMs] the wait times out, the job is
     * cancelled, and the room is finalized with whatever state has already been
     * written back.
     */
    suspend fun close(
        conversationId: Uuid,
        cancel: Boolean = false,
    ): CouncilRoomOpResult {
        return withCapturedWriteContext {
            // Gate: prevent any new generation job from starting for this conversation.
            // This closes the race between launching a job and cancelling existing ones.
            jobsLock.withLock { closingConversationIds.add(conversationId) }

            try {
            // Graceful close while synthesizing: give the host synthesis a chance
            // to complete naturally before we cancel it.
            if (!cancel) {
                val room = peekRoom(conversationId)
                if (room?.status == CouncilRoomStatus.FINALIZING) {
                    val synthesisJob = jobsLock.withLock { synthesisJobs[conversationId] }
                    if (synthesisJob != null && synthesisJob.isActive) {
                        val timeoutMs = room.totalTimeoutMs.coerceAtLeast(1_000L)
                        val completed = withTimeoutOrNull(timeoutMs) { synthesisJob.join() } != null
                        if (!completed) {
                            android.util.Log.w(
                                TAG,
                                "Graceful close timed out waiting for synthesis after ${timeoutMs}ms",
                            )
                        }
                    }
                }
            }

            // Cancel all in-flight jobs (guest + synthesis) without holding the
            // room mutex — the jobs may be blocked waiting to write back via
            // [RoomMutationSink].
            val (guestJobs, synthesisJob, askDeferred) = jobsLock.withLock {
                val guests = generationJobs.remove(conversationId) ?: emptyList()
                val synth = synthesisJobs.remove(conversationId)
                val ask = pendingAskUser.remove(conversationId)
                Triple(guests, synth, ask)
            }
            guestJobs.forEach { it.cancelAndJoin() }
            synthesisJob?.cancelAndJoin()
            // If the council was paused on an ask_user, cancel the parked awaiter
            // so the suspended CouncilRoomAutoRun.run dies cleanly (its askUser
            // throws CancellationException, which the loop propagates to close).
            askDeferred?.answer?.cancel()

            val mutex = lockFor(conversationId)
            val result = mutex.withLock {
                val flow = store.observeRoom(conversationId)
                val room = flow.value
                    ?: return@withLock CouncilRoomOpResult.Err("not_found", "No room for this conversation.")
                if (room.status.terminal) {
                    // Already terminal: just evict any leftover in-memory state.
                    store.closeAndEvict(room)
                    return@withLock CouncilRoomOpResult.Ok(room)
                }
                val now = nowMs()
                val nextStatus = when {
                    cancel -> CouncilRoomStatus.CANCELLED
                    room.status == CouncilRoomStatus.FINALIZING || room.synthesis.isNotBlank() -> CouncilRoomStatus.FINALIZED
                    else -> CouncilRoomStatus.FINALIZED  // graceful close without synthesis
                }
                // Sweep in-flight streaming rows / SPEAKING participants so a closed room
                // never reopens with permanent "正在发言" bubbles (isStreaming stay true).
                val messages = room.messages.map { m ->
                    if (m.status.running) {
                        m.copy(status = CouncilMessageStatus.FAILED, error = m.error.ifBlank { "interrupted" })
                    } else {
                        m
                    }
                }
                val participants = room.participants.map { p ->
                    if (p.status == CouncilParticipantStatus.SPEAKING) {
                        p.copy(status = CouncilParticipantStatus.IDLE)
                    } else {
                        p
                    }
                }
                val updated = room.copy(
                    status = nextStatus,
                    messages = messages,
                    participants = participants,
                    finishedAtMs = now,
                    updatedAtMs = now,
                )
                updateTaskStatus(updated)
                // Atomic persist + evict under the store's lock — no window for a
                // concurrent upsert/open to race in and leave disk inconsistent.
                store.closeAndEvict(updated)
                CouncilRoomOpResult.Ok(updated)
            }
            // Release the per-conversation mutex slot now that the room is gone.
            // Done under the room mutex above would be ideal, but locksLock is a
            // separate lock acquired by lockFor; removing it here (after the room
            // mutex is released and the room is evicted) is safe because any
            // concurrent openRoom that grabs a fresh mutex will observe the
            // terminal/evicted state via observeRoom and refuse to proceed.
            locksLock.withLock { locks.remove(conversationId) }
                result
            } finally {
                jobsLock.withLock { closingConversationIds.remove(conversationId) }
            }
        }
    }

    // ── internal helpers ──────────────────────────────────────────────────

    /**
     * Mutate the room under its per-conversation lock. The [transform] returns
     * either the next room (will be persisted) or an Err (no-op). On success
     * the task status is also updated.
     */
    private suspend fun mutate(
        conversationId: Uuid,
        transform: suspend (CouncilRoom) -> CouncilRoomOpResult,
    ): CouncilRoomOpResult {
        return withCapturedWriteContext {
            val mutex = lockFor(conversationId)
            mutex.withLock {
                val flow = store.observeRoom(conversationId)
                val room = flow.value
                    ?: return@withLock CouncilRoomOpResult.Err("not_found", "No room for this conversation.")
                when (val result = transform(room)) {
                    is CouncilRoomOpResult.Err -> result
                    is CouncilRoomOpResult.Ok -> {
                        val now = nowMs()
                        val next = result.room.copy(updatedAtMs = now)
                        store.upsertRoom(next)
                        updateTaskStatus(next)
                        CouncilRoomOpResult.Ok(next)
                    }
                }
            }
        }
    }

    /**
     * Like [mutate], but the transform returns a [PreflightResult] embedding the
     * [GuestTurnPlan] (which itself carries the next room state). The next room
     * is persisted under the lock; the plan is returned to the caller so it can
     * launch generation *after* the lock is released (m3 fix — generation must
     * not hold the room mutex).
     */
    private suspend fun mutatePreflight(
        conversationId: Uuid,
        transform: suspend (CouncilRoom) -> PreflightResult,
    ): PreflightResult {
        return withCapturedWriteContext {
            val mutex = lockFor(conversationId)
            mutex.withLock {
                val flow = store.observeRoom(conversationId)
                val room = flow.value
                    ?: return@withLock PreflightResult.Err("not_found", "No room for this conversation.")
                when (val result = transform(room)) {
                    is PreflightResult.Err -> result
                    is PreflightResult.Plan -> {
                        val now = nowMs()
                        val next = result.plan.room.copy(updatedAtMs = now)
                        store.upsertRoom(next)
                        updateTaskStatus(next)
                        PreflightResult.Plan(result.plan.copy(room = next))
                    }
                }
            }
        }
    }

    /**
     * Launch generation for a [GuestTurnPlan] (the primary + any extraGuests).
     * Generation runs on [appScope] WITHOUT holding the room mutex — streaming
     * updates flow back via [RoomMutationSink], which re-acquires the mutex per
     * write. Validation errors from [PreflightResult.Err] are returned
     * synchronously.
     *
     * If the closing gate blocks some (but not all) plans — possible for
     * multi-guest [HostAction.LetGuestsRespond] — the blocked guests would
     * otherwise stay stuck in WAITING forever (no completeMessage to advance
     * them). We detect this and roll their status back to IDLE so the roster
     * pill doesn't lie. The host echo message is already persisted, so the
     * blocked turn just becomes a no-op for those guests.
     */
    private suspend fun launchGuestTurn(
        preflight: PreflightResult,
        conversationId: Uuid,
    ): CouncilRoomOpResult {
        if (preflight is PreflightResult.Err) {
            return CouncilRoomOpResult.Err(preflight.code, preflight.message)
        }
        val plan = (preflight as PreflightResult.Plan).plan
        val settings = settingsFlow.value
        val plans = listOf(plan) + plan.extraGuests
        val gatedGuestIds = mutableListOf<String>()
        plans.forEach { turnPlan ->
            val messageId = msgId()
            val launched = launchGuestJob(conversationId) {
                runCatching {
                    executor.generateGuestTurn(
                        room = turnPlan.room,
                        guest = turnPlan.guest,
                        messageId = messageId,
                        userPrompt = turnPlan.userPrompt,
                        replyToMessageId = turnPlan.replyToMessageId,
                        continuesFromMessageId = turnPlan.continuesFromMessageId,
                        invitedBy = turnPlan.invitedBy,
                        settings = settings,
                    )
                }.onFailure { error ->
                    if (error !is CancellationException) {
                        android.util.Log.e(TAG, "Guest turn failed for ${turnPlan.guest.name}", error)
                    }
                }
            }
            if (launched == null) {
                gatedGuestIds.add(turnPlan.guest.id)
            }
        }
        if (gatedGuestIds.isNotEmpty()) {
            // Roll blocked guests back to IDLE so they don't appear stuck
            // WAITING. Best-effort: if the room was evicted (close finished),
            // this mutate is a no-op (not_found), which is fine.
            mutate(conversationId) { room ->
                if (room.status.terminal) return@mutate CouncilRoomOpResult.Ok(room)
                val touched = room.participants.any { it.id in gatedGuestIds && it.status == CouncilParticipantStatus.WAITING }
                if (!touched) return@mutate CouncilRoomOpResult.Ok(room)
                CouncilRoomOpResult.Ok(room.copy(
                    participants = room.participants.map { p ->
                        if (p.id in gatedGuestIds && p.status == CouncilParticipantStatus.WAITING) {
                            p.copy(status = CouncilParticipantStatus.IDLE)
                        } else {
                            p
                        }
                    },
                    updatedAtMs = nowMs(),
                ))
            }
        }
        return CouncilRoomOpResult.Ok(plan.room)
    }

    /**
     * Launch a guest generation job on [appScope]. Returns immediately; the job
     * registers itself synchronously inside its own coroutine. If the room is
     * already closing, no job is launched.
     */
    private suspend fun launchGuestJob(conversationId: Uuid, block: suspend () -> Unit): Job? {
        // Capture the restore epoch before the model/network work starts. The
        // opaque context is supplied by the store implementation, so this
        // feature module stays independent from app-level restore classes.
        val writeContext = store.captureWriteContext()
        lateinit var job: Job
        job = appScope.launch(context = writeContext, start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                jobsLock.withLock {
                    generationJobs[conversationId]?.remove(job)
                }
            }
        }
        val registered = jobsLock.withLock {
            if (conversationId in closingConversationIds) false else {
                generationJobs.getOrPut(conversationId) { mutableListOf() }.add(job)
                true
            }
        }
        if (!registered) {
            job.cancel()
            return null
        }
        job.start()
        return job
    }

    /**
     * Launch the host synthesis job on [appScope]. Like [launchGuestJob], it
     * registers itself synchronously and respects the closing gate.
     */
    private suspend fun launchSynthesisJob(conversationId: Uuid, block: suspend () -> Unit): Job? {
        // Synthesis can outlive the initiating UI call; carry the same epoch
        // through its completion callbacks so a restore cannot be overwritten
        // by an old host result.
        val writeContext = store.captureWriteContext()
        lateinit var job: Job
        job = appScope.launch(context = writeContext, start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                jobsLock.withLock {
                    if (synthesisJobs[conversationId] === job) synthesisJobs.remove(conversationId)
                }
            }
        }
        val registered = jobsLock.withLock {
            if (conversationId in closingConversationIds) false else {
                synthesisJobs[conversationId] = job
                true
            }
        }
        if (!registered) {
            job.cancel()
            return null
        }
        job.start()
        return job
    }

    /** Carry one restore epoch across a room read/transform/write window. */
    private suspend fun <T> withCapturedWriteContext(block: suspend () -> T): T {
        val writeContext = store.captureWriteContext()
        return withContext(writeContext) { block() }
    }

    private suspend fun registerTask(room: CouncilRoom) {
        // Awaiting registration inline (rather than fire-and-forget on appScope)
        // guarantees the cancel callback is attached before openRoom returns Ok,
        // so the room's stop button always works. A lost race with the first
        // updateTaskStatus would otherwise leave the task unregistered and the
        // cancel affordance dead.
        runCatching {
            taskReporter.register(
                snapshot = room.toTaskSnapshot(),
                cancel = {
                    close(room.conversationId, cancel = true)
                    true
                },
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            // Room is already persisted; a failed task registration means the
            // room won't surface in the task panel and its cancel callback is
            // dead. Log loudly so this isn't silently lost — the room itself
            // still works, but the user loses the cancel affordance.
            android.util.Log.e(TAG, "registerTask failed for room ${room.id}", error)
        }
    }

    private suspend fun updateTaskStatus(room: CouncilRoom) {
        runCatching {
            taskReporter.upsert(room.toTaskSnapshot())
        }.onFailure { error ->
            android.util.Log.w(TAG, "updateTaskStatus failed for room ${room.id}: ${error.message}")
        }
    }

    companion object {
        const val MAX_OBJECTIVE_CHARS = 4_000
        const val MAX_CONTEXT_CHARS = CouncilRoomTurnPolicy.MAX_CONTEXT_CHARS
        const val MAX_MESSAGE_CHARS = 12_000
        const val MAX_ROUNDS_CAP = CouncilRoomTurnPolicy.MAX_ROUNDS_CAP
        const val MAX_PARTICIPANTS_CAP = 12

        private const val TAG = "CouncilRoomManager"
    }
}

// ── module-local helpers (no Compose/Android deps) ──────────────────────────

private fun noParticipant(room: CouncilRoom, id: String): CouncilRoomOpResult =
    CouncilRoomOpResult.Err("not_found", "Participant $id not in room.")

private fun preflightNoParticipant(id: String): PreflightResult.Err =
    PreflightResult.Err("not_found", "Participant $id not in room.")
