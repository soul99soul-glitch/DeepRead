package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import app.amber.core.settings.Settings
import kotlin.uuid.Uuid
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.msgId
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.nowMs
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.statusForMode
import app.amber.feature.modelcouncil.CouncilRoomTurnPolicy.MAX_CONTEXT_CHARS

/** Host generation and tool-result interpretation; room/ask ownership stays with the manager. */
internal class CouncilRoomHostTurns(
    private val executor: CouncilRoomExecutor,
    private val toolProvider: CouncilHostToolProvider,
    private val sink: RoomMutationSink,
    private val peekRoom: (Uuid) -> CouncilRoom?,
    private val mutate: suspend (Uuid, suspend (CouncilRoom) -> CouncilRoomOpResult) -> CouncilRoomOpResult,
    private val mutateRoomOrNull: suspend (Uuid, (CouncilRoom) -> CouncilRoom) -> CouncilRoom?,
    private val askUser: suspend (Uuid, CouncilMessage) -> String?,
) {
    /**
     * Host redirect for an un-targeted interjection: generate a short steering
     * message off the host model and append it as a host turn. Members pick it up
     * via [CouncilRoomTurnPolicy.buildAutoPrompt]'s steering block on their next turn.
     */
    suspend fun generateHostSteer(
        room: CouncilRoom,
        userMessage: CouncilMessage,
        hostModelId: Uuid,
        settings: Settings,
    ) {
        val councilSetting = settings.agentRuntime.modelCouncil
        executor.generateHostTurn(
            room = room,
            hostModelId = hostModelId,
            systemPrompt = CouncilRoomPrompts.hostSystemPrompt(room),
            userPrompt = CouncilRoomPrompts.hostInterjectionPrompt(room, userMessage.text),
            messageId = msgId(),
            settings = settings,
            reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
            extraSystemPrompt = councilSetting.hostSystemPrompt,
        )
    }

    /**
     * FULL mode: PRE-TOPIC host research turn. Runs BEFORE mode classification,
     * roster assembly, and the host opening — so external facts (recent releases,
     * benchmarks, project status) enter the room BEFORE any member speaks. This is
     * the fix for "members discuss glm5.2 with no training-data knowledge": the
     * host gathers the facts first.
     *
     * The research summary is persisted into [CouncilRoom.context] (the "背景"
     * field every member opening / host opening / synthesis prompt renders), AND it
     * is already appended as a host message by [runHostToolTurn], so
     * the subsequent guest prompt also injects it into later-round member prompts — two
     * channels covering the whole deliberation.
     *
     * No-op (returns without side effects) when:
     *   - power mode != FULL, or
     *   - no search provider is configured ([toolProvider.isAvailable] gate), or
     *   - the host produced no usable summary (the host judged the topic self-
     *     contained; an honest placeholder message still lands in the timeline).
     *
     * @param conversationId the room to research into.
     * @return the research summary text, or null if none was produced. The caller
     *   ([CouncilRoomAutoRun.run]) does NOT need this — context is already written —
     *   but it is returned for testability.
     */
    suspend fun runPreTopicResearch(
        conversationId: Uuid,
        settings: Settings,
    ): String? {
        val councilSetting = settings.agentRuntime.modelCouncil
        if (councilSetting.councilPowerMode != CouncilPowerMode.FULL) return null
        if (!toolProvider.isAvailable(settings)) return null

        val room = peekRoom(conversationId) ?: return null
        if (room.status.terminal) return null
        val hostModelId = resolveHostModelId(room, settings) ?: return null

        // Surface to the user that the host is gathering facts before discussing.
        mutateRoomOrNull(conversationId) { r ->
            r.copy(
                phaseMarkers = r.phaseMarkers + CouncilPhaseMarker(
                    id = msgId(),
                    label = "Host is researching current facts…",
                    mode = r.mode,
                    createdAtMs = nowMs(),
                ),
                updatedAtMs = nowMs(),
            )
        } ?: return null

        val researchRoom = peekRoom(conversationId) ?: return null
        val summary = runHostToolTurn(
            conversationId = conversationId,
            hostModelId = hostModelId,
            systemPrompt = CouncilRoomPrompts.hostSystemPrompt(researchRoom, hasTools = true),
            userPrompt = CouncilRoomPrompts.hostPreTopicResearchPrompt(researchRoom),
            settings = settings,
            // Give the host latitude for several search+scrape cycles so it can
            // cover multiple dimensions of a complex topic (e.g. for a new model:
            // overview → benchmark → specific capability → detail-page scrape).
            // 2 rounds was too shallow — the host could only "search once, maybe
            // scrape once", missing whole dimensions. With ~30 results/round (the
            // user's searchCommonOptions.resultSize), 4 rounds give the host plenty
            // of room to build a complete fact base before the council deliberates.
            maxToolRounds = 4,
            kindLabel = "Host research",
        ) ?: return null

        // Fold the summary into room.context — the shared fact base that every
        // member opening / host opening / synthesis prompt renders. Truncated to
        // MAX_CONTEXT_CHARS so a very long research digest can't blow up prompt size.
        if (summary.isNotBlank()) {
            mutateRoomOrNull(conversationId) { r ->
                // Only set if blank: never overwrite an existing non-empty context
                // (defensive — there is no UI that sets it today, but a future caller
                // might pass an explicit background, and we shouldn't clobber it).
                val merged = if (r.context.isBlank()) {
                    summary.trim().take(MAX_CONTEXT_CHARS)
                } else {
                    (r.context.trim() + "\n\n--- Host research supplement ---\n" + summary.trim()).take(MAX_CONTEXT_CHARS)
                }
                r.copy(context = merged, updatedAtMs = nowMs())
            }
        }
        return summary
    }

    /**
     * Host's opening turn — generated off the host model and appended as a host
     * message. The host receives the topic and frames the core proposition before
     * the members deliberate (they pick it up via the subsequent guest prompt).
     */
    suspend fun generateHostOpening(
        room: CouncilRoom,
        hostModelId: Uuid,
        settings: Settings,
    ) {
        val councilSetting = settings.agentRuntime.modelCouncil
        executor.generateHostTurn(
            room = room,
            hostModelId = hostModelId,
            systemPrompt = CouncilRoomPrompts.hostSystemPrompt(room),
            userPrompt = CouncilRoomPrompts.hostOpeningPrompt(room),
            messageId = msgId(),
            settings = settings,
            reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
            extraSystemPrompt = councilSetting.hostSystemPrompt,
        )
    }

    /**
     * FULL mode: run a host turn that may call read-only tools (search/scrape),
     * streaming the host's own text into the timeline as a normal host message.
     * Used for pre-topic research, gated on [CouncilHostToolProvider.isAvailable]
     * by the caller. Round reviews use the separate text-only turn below.
     *
     * Returns the host's final text (already persisted as a COMPLETED host
     * message), or null if the provider returned empty/no usable text (caller
     * then skips — no empty bubble enters the timeline).
     *
     * `ask_user` pauses through the manager's existing waiter; the answer joins the
     * same public timeline before the remaining orchestration continues.
     */
    private suspend fun runHostToolTurn(
        conversationId: Uuid,
        hostModelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        settings: Settings,
        maxToolRounds: Int,
        kindLabel: String,
    ): String? {
        val room = peekRoom(conversationId) ?: return null
        val councilSetting = settings.agentRuntime.modelCouncil
        val messageId = msgId()
        val host = room.host ?: return null
        val now = nowMs()
        // Seed a streaming host message so the user sees the host "working".
        sink.upsertStreamingMessage(
            conversationId,
            CouncilMessage(
                id = messageId,
                authorId = host.id,
                authorName = host.name,
                role = host.role,
                round = room.round,
                mode = room.mode,
                text = "",
                createdAtMs = now,
                status = CouncilMessageStatus.STREAMING,
            ),
        )
        val outcome = runCatching {
            // Same streaming spine as the guest/host turns: the executor bridges
            // the sync onChunk callback onto the suspending sink.
            executor.streamInto(conversationId, messageId) { onChunk ->
                toolProvider.generateWithTools(
                    settings = settings,
                    modelId = hostModelId,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
                    maxToolRounds = maxToolRounds,
                    timeoutMs = room.seatTimeoutMs.coerceAtLeast(1_000L),
                    outputBudgetChars = room.outputBudgetChars,
                    onChunk = onChunk,
                )
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Retain whatever the user already saw streaming (the last write the
            // sink accepted) instead of wiping the bubble back to empty.
            val partial = peekRoom(conversationId)?.messages
                ?.firstOrNull { it.id == messageId && it.status == CouncilMessageStatus.STREAMING }
                ?.text.orEmpty()
            sink.completeMessage(
                conversationId = conversationId,
                messageId = messageId,
                status = CouncilMessageStatus.FAILED,
                text = partial,
                warnings = emptyList(),
                error = "$kindLabel failed: ${error.message ?: error::class.java.simpleName}",
                authorId = host.id,
                authorStatus = CouncilParticipantStatus.IDLE,
            )
            return null
        }
        return when (outcome) {
            is HostToolOutcome.Done -> {
                val finalText = outcome.text.trim()
                if (finalText.isEmpty()) {
                    // The host ran the research turn but produced no summary text —
                    // meaning it judged the discussion self-contained (no tools needed,
                    // or tools returned nothing usable). Rather than leave an empty
                    // bubble, finalize the placeholder with a one-line honest note so
                    // the timeline reads naturally. Returns null so the caller knows
                    // there is no research summary to fold into synthesis.
                    sink.completeMessage(
                        conversationId = conversationId,
                        messageId = messageId,
                        status = CouncilMessageStatus.COMPLETED,
                        text = "The host found the discussion self-contained; no additional external research was needed.",
                        warnings = outcome.warnings,
                        error = "",
                        authorId = host.id,
                        authorStatus = CouncilParticipantStatus.IDLE,
                    )
                    null
                } else {
                    sink.completeMessage(
                        conversationId = conversationId,
                        messageId = messageId,
                        status = CouncilMessageStatus.COMPLETED,
                        text = finalText,
                        warnings = outcome.warnings,
                        error = "",
                        authorId = host.id,
                        authorStatus = CouncilParticipantStatus.IDLE,
                    )
                    finalText
                }
            }
            is HostToolOutcome.AskUser -> {
                // The host wants to ask the user a clarifying question during
                // research. Surface it as an ASK_USER CouncilMessage (timeline
                // shows the answer card), flip the room to INTERRUPTED so the UI
                // reflects "waiting", then suspend until the user answers. The
                // answer is appended as a normal USER message (picked up by the
                // subsequent roster/opening/rounds via appendSteeringNote) and the
                // room resumes its running status. We return null (no research
                // summary) — the user's clarification IS the contribution here.
                val question = outcome.displayQuestion.trim().ifBlank {
                    "Please clarify your request."
                }
                askUser(conversationId, CouncilMessage(
                    id = messageId,
                    authorId = host.id,
                    authorName = host.name,
                    role = host.role,
                    round = room.round,
                    mode = room.mode,
                    text = question,
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                    kind = CouncilMessageKind.ASK_USER,
                ))
                null
            }
        }
    }

    /**
     * FULL mode end-of-round host review. Pure text (no tools, to keep round
     * pacing tight): the host reads this round's guest turns and either writes a
     * pointed review + next-round steer (appended as a host message, which
     * the subsequent guest prompt then injects into the next round's guest prompts) or
     * emits [CouncilRoomPrompts.NO_COMMENT_SENTINEL] (in which case nothing is
     * appended — no empty/noise bubble).
     *
     * STREAMED like a guest turn: a STREAMING host message is inserted first so
     * the user sees the review type in live (instead of a frozen screen), then
     * finalized after the sentinel check. A "no comment" decision marks the
     * placeholder WITHDRAWN so a bubble already shown to the user does not jump away.
     */
    suspend fun runHostReviewTurn(
        conversationId: Uuid,
        room: CouncilRoom,
        round: Int,
        totalRounds: Int,
        settings: Settings,
    ): String? {
        val councilSetting = settings.agentRuntime.modelCouncil
        val hostModelId = resolveHostModelId(room, settings) ?: return null
        val host = room.host ?: return null
        val reviewMessageId = msgId()
        val now = nowMs()
        // Seed a STREAMING host review message so the user sees live progress
        // ("主持人正在点评") instead of a frozen screen while the review generates.
        mutate(conversationId) { r ->
            if (r.status.terminal) return@mutate CouncilRoomOpResult.Ok(r)
            CouncilRoomOpResult.Ok(r.copy(
                messages = r.messages + CouncilMessage(
                    id = reviewMessageId,
                    authorId = host.id,
                    authorName = host.name,
                    role = host.role,
                    round = round,
                    mode = r.mode,
                    text = "",
                    createdAtMs = now,
                    status = CouncilMessageStatus.STREAMING,
                ),
                updatedAtMs = now,
            ))
        }
        // Stream the review text into the seeded message, exactly like a guest turn.
        val result = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(room.seatTimeoutMs.coerceAtLeast(1_000L)) {
                executor.streamIntoReview(
                    conversationId = conversationId,
                    messageId = reviewMessageId,
                    room = room,
                    hostModelId = hostModelId,
                    systemPrompt = CouncilRoomPrompts.hostSystemPrompt(room),
                    userPrompt = CouncilRoomPrompts.hostRoundReviewPrompt(room, round, totalRounds),
                    settings = settings,
                    reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
                )
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
        val text = result?.text?.trim().orEmpty()
        // Sentinel or empty → host decided this round needs no commentary. Mark
        // the streaming message as WITHDRAWN (like a chat "撤回") instead of
        // deleting it: the user already saw the host "speaking", so a clean
        // removal would read as a visual jump. A withdrawn bubble keeps the
        // timeline continuous and signals "主持人 considered but had nothing to add".
        if (text.isEmpty() || CouncilRoomPrompts.NO_COMMENT_SENTINEL in text) {
            mutate(conversationId) { r ->
                CouncilRoomOpResult.Ok(r.copy(
                    messages = r.messages.map { m ->
                        if (m.id == reviewMessageId) {
                            m.copy(status = CouncilMessageStatus.COMPLETED, kind = CouncilMessageKind.WITHDRAWN)
                        } else {
                            m
                        }
                    },
                    updatedAtMs = nowMs(),
                ))
            }
            return null
        }
        // ask_user sentinel → host wants to ask the user a question. Mark the
        // streaming placeholder withdrawn; the caller persists the question as
        // ASK_USER separately.
        if (CouncilRoomPrompts.ASK_USER_SENTINEL in text) {
            mutate(conversationId) { r ->
                CouncilRoomOpResult.Ok(r.copy(
                    messages = r.messages.map { m ->
                        if (m.id == reviewMessageId) {
                            m.copy(status = CouncilMessageStatus.COMPLETED, kind = CouncilMessageKind.WITHDRAWN)
                        } else {
                            m
                        }
                    },
                    updatedAtMs = nowMs(),
                ))
            }
            val question = text.substringAfter(CouncilRoomPrompts.ASK_USER_SENTINEL).trim()
            return question.ifBlank { "Please clarify your request." }
        }
        // Normal review: finalize the streaming message in place with the full text.
        mutate(conversationId) { r ->
            if (r.status.terminal) return@mutate CouncilRoomOpResult.Ok(r)
            CouncilRoomOpResult.Ok(r.copy(
                messages = r.messages.map { m ->
                    if (m.id == reviewMessageId) {
                        m.copy(text = text, status = CouncilMessageStatus.COMPLETED)
                    } else {
                        m
                    }
                },
                updatedAtMs = nowMs(),
            ))
        }
        return null
    }

}
