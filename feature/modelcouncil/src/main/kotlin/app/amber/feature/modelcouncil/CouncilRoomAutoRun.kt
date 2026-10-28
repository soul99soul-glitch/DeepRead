package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import app.amber.core.settings.Settings
import kotlin.uuid.Uuid
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.msgId
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.nowMs
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.statusForMode
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.MAX_ROUNDS_CAP
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.buildAutoPrompt
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.mentionedGuests
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.modeShortLabel
import kotlinx.coroutines.flow.StateFlow

/** One claimed automatic deliberation; all durable mutations go through explicit owner commands. */
internal class CouncilRoomAutoRun(
    private val settingsFlow: StateFlow<Settings>,
    private val planner: CouncilRoomPlanner,
    private val host: CouncilRoomHostTurns,
    private val executor: CouncilRoomExecutor,
    private val toolProvider: CouncilHostToolProvider,
    private val sink: RoomMutationSink,
    private val peekRoom: (Uuid) -> CouncilRoom?,
    private val mutateRoomOrNull: suspend (Uuid, (CouncilRoom) -> CouncilRoom) -> CouncilRoom?,
    private val failActiveRoom: suspend (Uuid, String) -> Unit,
    private val autoAssembleSeats: suspend (Uuid, String, Settings) -> Unit,
    private val hasModeOverride: suspend (Uuid) -> Boolean,
    private val applyDetectedMode: suspend (Uuid, CouncilRoomMode) -> Unit,
    private val askUser: suspend (Uuid, CouncilMessage) -> String?,
) {
    /**
     * The deliberation loop. For each of the configured rounds, every active guest
     * speaks once in roster order — awaited one at a time so turns stream serially.
     * Round 1 is independent openings; rounds 2..N use the mode-specific response
     * prompts (EXPLORE adds breadth; DEBATE cross-rebuts, and its last round asks
     * for a final position). After the final round the host synthesizes.
     *
     * Every step re-reads the room and bails on a terminal status, so [CouncilRoomManager.close]
     * (which cancels this job AND flips the room terminal) stops it promptly.
     *
     * Wall-clock budget: the whole loop is bounded by a deadline (see below),
     * enforced cooperatively BETWEEN turns — an in-flight turn is bounded by its
     * own seat timeout, so no cancellation sweep is ever needed.
     */
    suspend fun run(conversationId: Uuid) {
        // Live settings: re-read at each round so mid-run changes (host model,
        // power mode) take effect, honoring resolveHostModelId's live-first rule.
        var settings = settingsFlow.value
        val initial = peekRoom(conversationId) ?: return
        val totalRounds = initial.maxRounds.coerceIn(1, MAX_ROUNDS_CAP)
        var lastConsumedUserMessageId = initial.messages.firstOrNull { it.authorId == COUNCIL_ROOM_USER_ID }?.id

        // FULL mode: gather external facts BEFORE any deliberation. The research
        // summary lands in room.context (the "背景" field every member/host/
        // synthesis prompt renders) and as a host message (so appendSteeringNote
        // injects it into later rounds). Runs before roster assembly so the bespoke
        // roles can be tailored to what the host actually found. STANDARD mode and
        // no-search-provider both no-op here. See runPreTopicResearch.
        if (initial.activeGuests.isEmpty()) {
            // FULL mode defers roster assembly to here (after research); STANDARD
            // already assembled in sendUserMessage. autoAssembleSeats is itself a
            // no-op once members exist, so this is safe for both paths.
            host.runPreTopicResearch(conversationId, settings)
            autoAssembleSeats(conversationId, initial.objective, settings)
        }

        val seeded = peekRoom(conversationId) ?: return
        val guestIds = seeded.activeGuests.map { it.id }
        if (guestIds.isEmpty()) {
            failActiveRoom(conversationId, "No council guests are available to run.")
            return
        }

        // Total wall-clock budget. The configured totalTimeoutMs is only a floor
        // alongside the realistic serial cost of the program — every guest turn
        // (rounds × seats) and host turn (opening + per-round reviews + synthesis,
        // plus FULL-mode pre-research rounds) is individually bounded by
        // seatTimeoutMs, so a merely-slow council is never cut short and the net
        // only catches true hangs. Mirrors the legacy batch manager's
        // effectiveCouncilTotalTimeoutMs floor.
        val seatTimeoutMs = seeded.seatTimeoutMs.coerceAtLeast(1_000L)
        val researchRounds = if (
            settings.agentRuntime.modelCouncil.councilPowerMode == CouncilPowerMode.FULL &&
            toolProvider.isAvailable(settings) &&
            initial.activeGuests.isEmpty() // research only runs for a not-yet-assembled roster
        ) 5L else 0L // runPreTopicResearch: maxToolRounds(4) tool rounds + 1 final
        val serialFloorMs = seatTimeoutMs *
            (totalRounds * guestIds.size + (totalRounds + 1L) + researchRounds) +
            COUNCIL_TOTAL_TIMEOUT_OVERHEAD_MS
        val totalBudgetMs = maxOf(seeded.totalTimeoutMs.coerceAtLeast(10_000L), serialFloorMs)
        var deadlineMs = System.currentTimeMillis() + totalBudgetMs

        // Auto-detect the discussion mode from the topic — unless the user already
        // picked one via the dropdown (recorded in the manager's mode override). Done on
        // the post-research room so detection can use the gathered facts too.
        val userPickedMode = hasModeOverride(conversationId)
        if (!userPickedMode) {
            planner.classifyMode(seeded, settings)?.let { detected ->
                applyDetectedMode(conversationId, detected)
            }
        }

        // Host opening: the host receives the topic and frames the core proposition
        // for the members BEFORE round 1 (the "主持承接命题" turn). Members pick the
        // framing up via appendSteeringNote on their first turn.
        val openingRoom = peekRoom(conversationId) ?: return
        if (!openingRoom.status.terminal) {
            resolveHostModelId(openingRoom, settings)?.let { hostModelId ->
                host.generateHostOpening(openingRoom, hostModelId, settings)
            }
        }
        val setupSteeringStartedAt = System.currentTimeMillis()
        lastConsumedUserMessageId = handleInterjections(conversationId, settings, lastConsumedUserMessageId)
        deadlineMs += System.currentTimeMillis() - setupSteeringStartedAt

        for (round in 1..totalRounds) {
            settings = settingsFlow.value
            mutateRoomOrNull(conversationId) { room ->
                room.copy(
                    round = round,
                    status = statusForMode(room.mode),
                    phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                        id = msgId(),
                        label = "Round $round · ${modeShortLabel(room.mode)}",
                        mode = room.mode,
                        createdAtMs = nowMs(),
                    ),
                    updatedAtMs = nowMs(),
                )
            } ?: return // null = terminal/missing → stop

            for (gid in guestIds) {
                // Budget check BETWEEN turns only: an in-flight turn is bounded
                // by its own seat timeout; at this point nothing is STREAMING,
                // so settling the room needs no sweep. (User-driven segments —
                // ask_user waits, interjection replies — pause the deadline, so
                // they neither trigger nor erode the budget.)
                if (System.currentTimeMillis() > deadlineMs) {
                    failActiveRoom(
                        conversationId,
                        "Council exceeded its total time budget (${totalBudgetMs / 1000}s); completed turns are kept.",
                    )
                    return
                }
                val current = peekRoom(conversationId) ?: return
                if (current.status.terminal) return
                val guest = current.participantById(gid) ?: continue
                if (guest.status == CouncilParticipantStatus.DISMISSED) continue
                // Suspends until this guest's turn completes (or times out); the
                // executor streams + finalizes the message via the sink.
                executor.generateGuestTurn(
                    room = current,
                    guest = guest,
                    messageId = msgId(),
                    userPrompt = buildAutoPrompt(current, guest, round, totalRounds),
                    invitedBy = COUNCIL_ROOM_HOST_ID,
                    settings = settings,
                )
                // Let the user steer between turns: @member → that member replies;
                // no @ → the host produces a redirect the next members will see.
                // User-driven segment: pause the budget while it runs.
                val interjectionStartedAt = System.currentTimeMillis()
                lastConsumedUserMessageId = handleInterjections(conversationId, settings, lastConsumedUserMessageId)
                deadlineMs += System.currentTimeMillis() - interjectionStartedAt
            }

            // FULL mode end-of-round host review: after all guests have spoken,
            // the host decides whether the round warrants commentary. A real
            // review (pointing out contradictions / drift / next-round focus) is
            // appended as a host message and auto-flows into the next round's
            // guest prompts via appendSteeringNote — giving the iterative
            // "accumulate & refine" feel. Skipped on the final round (synthesis
            // follows immediately) and when the host emits NO_COMMENT_SENTINEL.
            // Pure host text here (no tools) to keep round pacing tight; research
            // tools run once before synthesis (see the synthesis block below).
            if (settings.agentRuntime.modelCouncil.councilPowerMode == CouncilPowerMode.FULL &&
                round < totalRounds
            ) {
                val reviewRoom = peekRoom(conversationId) ?: return
                if (!reviewRoom.status.terminal) {
                    val question = host.runHostReviewTurn(conversationId, reviewRoom, round, totalRounds, settings)
                    // Host wants to ask the user a clarifying question. Persist it
                    // as an ASK_USER host message, flip the room to INTERRUPTED, and
                    // suspend until the user answers (resumeAfterUserAnswer appends
                    // the answer as a USER message + flips status back). The next
                    // round's buildAutoPrompt / appendSteeringNote then see the
                    // answer for free — no extra wiring.
                    if (question != null) {
                        val askRoom = peekRoom(conversationId) ?: return
                        val host = askRoom.host ?: return
                        val waitStartedAt = System.currentTimeMillis()
                        askUser(conversationId, CouncilMessage(
                            id = msgId(),
                            authorId = host.id,
                            authorName = host.name,
                            role = host.role,
                            round = round,
                            mode = askRoom.mode,
                            text = question,
                            createdAtMs = nowMs(),
                            status = CouncilMessageStatus.COMPLETED,
                            kind = CouncilMessageKind.ASK_USER,
                        ))
                        // User think-time must not consume the deliberation budget.
                        deadlineMs += System.currentTimeMillis() - waitStartedAt
                    }
                }
            }
        }

        // Drain any final interjection before synthesizing. User-driven segment:
        // pause the budget while it runs.
        val drainStartedAt = System.currentTimeMillis()
        handleInterjections(conversationId, settings, lastConsumedUserMessageId)
        deadlineMs += System.currentTimeMillis() - drainStartedAt

        // Last budget check before committing to synthesis.
        if (System.currentTimeMillis() > deadlineMs) {
            failActiveRoom(
                conversationId,
                "Council exceeded its total time budget (${totalBudgetMs / 1000}s); completed turns are kept.",
            )
            return
        }
        settings = settingsFlow.value

        // Final synthesis — inline (awaited) in this same job so close() cancels
        // it too. We ALWAYS drive the room to a terminal state here (never leave it
        // stuck mid-run): the only bare returns below are when the room is already
        // terminal/evicted. If the host model can't be resolved, finalize with an
        // honest note instead of silently stopping.
        val finalRoom = peekRoom(conversationId) ?: return
        if (finalRoom.status.terminal) return
        val hostModelId = resolveHostModelId(finalRoom, settings)
        if (hostModelId == null) {
            sink.completeSynthesis(
                conversationId = conversationId,
                synthesisMessageId = msgId(),
                synthesis = "Unable to synthesize: no host model is configured. Configure a primary model for the current assistant in Settings.",
                warnings = listOf("Host model not found; synthesis skipped."),
            )
            return
        }
        mutateRoomOrNull(conversationId) { room ->
            room.copy(
                mode = CouncilRoomMode.SYNTHESIZE,
                status = CouncilRoomStatus.FINALIZING,
                phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                    id = msgId(),
                    label = "Host synthesis",
                    mode = CouncilRoomMode.SYNTHESIZE,
                    createdAtMs = nowMs(),
                ),
                updatedAtMs = nowMs(),
            )
        } ?: return
        val councilSetting = settings.agentRuntime.modelCouncil
        // Synthesis. External facts were already gathered BEFORE the deliberation
        // (FULL mode's runPreTopicResearch, which wrote them into room.context — the
        // "背景" field synthesize() renders). So there's no pre-synthesis research
        // turn here any more: discussing first and gathering facts only at synthesis
        // left the whole deliberation fact-free, which is why the pre-synthesis
        // research was removed. Synthesis just reads the (fact-enriched) record.
        val finalSynthRoom = peekRoom(conversationId) ?: return
        executor.generateSynthesis(
            room = finalSynthRoom,
            hostModelId = hostModelId,
            hostSystemPrompt = CouncilRoomPrompts.hostSystemPrompt(finalSynthRoom),
            settings = settings,
            reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
            extraSystemPrompt = councilSetting.hostSystemPrompt,
            synthesisMessageId = msgId(),
        )
    }

    /**
     * Process user messages after the consumed message (including setup-time input).
     * @-mentioned members reply directly to the user message (public); an
     * un-targeted message goes to the host, who emits a redirect the subsequent
     * members will see. Advances the cursor per message. Everything stays
     * public — no private side-channels. Called between turns by the auto-run loop.
     */
    private suspend fun handleInterjections(
        conversationId: Uuid,
        settings: Settings,
        lastConsumedMessageId: String?,
    ): String? {
        var cursor = lastConsumedMessageId
        while (true) {
            val room = peekRoom(conversationId) ?: return cursor
            if (room.status.terminal) return cursor
            val consumedIndex = room.messages.indexOfFirst { it.id == cursor }
            val pending = room.messages.drop(consumedIndex + 1)
                .firstOrNull { it.authorId == COUNCIL_ROOM_USER_ID }
                ?: return cursor
            // Consume it up front so a failure below can't re-process it forever.
            cursor = pending.id
            // Accepted answers already guide subsequent prompts; they do not
            // request a second host/guest turn as an ordinary interjection does.
            if (room.messages.any { it.id == pending.replyToMessageId && it.kind == CouncilMessageKind.ASK_USER }) continue

            val mentioned = mentionedGuests(room, pending.text)
            if (mentioned.isNotEmpty()) {
                for (guest in mentioned) {
                    val current = peekRoom(conversationId) ?: return cursor
                    if (current.status.terminal) return cursor
                    if (current.participantById(guest.id)?.status == CouncilParticipantStatus.DISMISSED) continue
                    executor.generateGuestTurn(
                        room = current,
                        guest = guest,
                        messageId = msgId(),
                        userPrompt = CouncilRoomPrompts.followUpPrompt(current, guest, pending),
                        replyToMessageId = pending.id,
                        invitedBy = COUNCIL_ROOM_USER_ID,
                        settings = settings,
                    )
                }
            } else {
                val current = peekRoom(conversationId) ?: return cursor
                if (current.status.terminal) return cursor
                val hostModelId = resolveHostModelId(current, settings) ?: continue
                host.generateHostSteer(current, pending, hostModelId, settings)
            }
        }
    }

    private companion object {
        const val COUNCIL_TOTAL_TIMEOUT_OVERHEAD_MS = 30_000L
    }
}
