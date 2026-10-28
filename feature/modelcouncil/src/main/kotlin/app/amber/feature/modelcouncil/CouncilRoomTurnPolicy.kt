package app.amber.feature.modelcouncil

import kotlin.uuid.Uuid

/** Shared timeline identity, active-mode status and automatic-turn prompt policy. */
internal object CouncilRoomTurnPolicy {
    const val MAX_CONTEXT_CHARS = 40_000
    const val MAX_ROUNDS_CAP = 10

    fun nowMs(): Long = System.currentTimeMillis()

    // Messages are reference-graph targets, so retain full UUID identity across concurrent turns.
    fun msgId(): String = "cm-${Uuid.random()}"

    fun statusForMode(mode: CouncilRoomMode): CouncilRoomStatus = when (mode) {
        CouncilRoomMode.EXPLORE -> CouncilRoomStatus.EXPLORING
        CouncilRoomMode.DEBATE -> CouncilRoomStatus.DEBATING
        CouncilRoomMode.SYNTHESIZE -> CouncilRoomStatus.FINALIZING
    }

    /**
     * Pick the round/mode-appropriate guest prompt. This is where the EXPLORE vs
     * DEBATE difference is actually executed (wiring the previously-unused
     * exploreResponse / debateResponse / debateFinalPosition templates).
     */
    fun buildAutoPrompt(
        room: CouncilRoom,
        guest: CouncilParticipant,
        round: Int,
        totalRounds: Int,
    ): String {
        val priorGuestMessages = room.messages
            .filter {
                it.authorId != COUNCIL_ROOM_HOST_ID &&
                    it.authorId != COUNCIL_ROOM_USER_ID &&
                    it.authorId != guest.id &&
                    it.status == CouncilMessageStatus.COMPLETED
            }
            .takeLast(6)
            .reversed() // newest-first, matching the prompt templates' expectation
        val base = when (room.mode) {
            CouncilRoomMode.EXPLORE ->
                if (round <= 1 || priorGuestMessages.isEmpty()) {
                    CouncilRoomPrompts.exploreOpening(room, guest)
                } else {
                    CouncilRoomPrompts.exploreResponse(room, guest, priorGuestMessages)
                }

            CouncilRoomMode.DEBATE -> when {
                round <= 1 || priorGuestMessages.isEmpty() ->
                    CouncilRoomPrompts.debateOpening(room, guest)

                round >= totalRounds -> CouncilRoomPrompts.debateFinalPosition(
                    room = room,
                    guest = guest,
                    ownPriorMessages = room.messages.filter {
                        it.authorId == guest.id && it.status == CouncilMessageStatus.COMPLETED
                    },
                )

                else -> CouncilRoomPrompts.debateResponse(
                    room = room,
                    guest = guest,
                    priorMessages = priorGuestMessages,
                    referenceMessage = priorGuestMessages.firstOrNull(),
                )
            }

            // Not reached mid-loop (synthesis is handled separately), but keep total.
            CouncilRoomMode.SYNTHESIZE -> CouncilRoomPrompts.exploreOpening(room, guest)
        }
        return appendSteeringNote(room, base + userAttachmentContext(room) + userClarificationContext(room))
    }

    /** Accepted host-question answers remain constraints after newer host turns. */
    fun userClarificationContext(room: CouncilRoom): String {
        val questionIds = room.messages.filter { it.kind == CouncilMessageKind.ASK_USER }.map { it.id }.toSet()
        val answers = room.messages.filter { it.authorId == COUNCIL_ROOM_USER_ID && it.replyToMessageId in questionIds }
        if (answers.isEmpty()) return ""
        return "\n\nUser clarifications (preserve these constraints):\n" +
            answers.joinToString("\n") { it.text }.take(MAX_CONTEXT_CHARS)
    }

    /**
     * Inline the text extracted from user-attached documents so members can read
     * file contents (the council generation path can't see Document parts; images
     * ride multimodally instead). Empty when the user attached nothing / only
     * images. Bounded to keep per-turn prompt size sane.
     */
    private fun userAttachmentContext(room: CouncilRoom): String {
        val docs = room.messages
            .filter { it.authorId == COUNCIL_ROOM_USER_ID && it.attachmentText.isNotBlank() }
            .joinToString("\n\n") { it.attachmentText }
        if (docs.isBlank()) return ""
        return "\n\n---\nFile content provided by the user (use it when responding):\n" + docs.take(MAX_CONTEXT_CHARS)
    }

    /**
     * Append the latest user interjection or host redirect (anything newer than the
     * topic, authored by the user or host) so members actually adjust course. Both
     * stay public; the most recent steer wins.
     */
    private fun appendSteeringNote(room: CouncilRoom, base: String): String {
        val firstUserMessageId = room.messages.firstOrNull { it.authorId == COUNCIL_ROOM_USER_ID }?.id
        val steer = room.messages.lastOrNull {
            it.text.isNotBlank() &&
                it.status == CouncilMessageStatus.COMPLETED &&
                it.id != firstUserMessageId &&
                (it.authorId == COUNCIL_ROOM_USER_ID || it.authorId == COUNCIL_ROOM_HOST_ID)
        } ?: return base
        return buildString {
            append(base)
            append("\n\n---\nLatest guidance from ${steer.authorName} (prioritize it when adjusting your contribution):\n")
            append(steer.text.take(600))
        }
    }

    fun modeShortLabel(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "Explore"
        CouncilRoomMode.DEBATE -> "Debate"
        CouncilRoomMode.SYNTHESIZE -> "Synthesize"
    }

    /** Active guests whose `@name` appears in [text] at a word boundary. */
    fun mentionedGuests(room: CouncilRoom, text: String): List<CouncilParticipant> =
        room.activeGuests.filter { it.name.isNotBlank() && containsMention(text, it.name) }

    /**
     * True iff `@name` occurs in [text] not immediately followed by another name
     * character — so "@GPT-4" matches the guest "GPT-4" but NOT the guest "GPT".
     */
    private fun containsMention(text: String, name: String): Boolean {
        val token = "@$name"
        var idx = text.indexOf(token)
        while (idx >= 0) {
            val next = text.getOrNull(idx + token.length)
            val isBoundary = next == null || !(next.isLetterOrDigit() || next == '-' || next == '_')
            if (isBoundary) return true
            idx = text.indexOf(token, idx + 1)
        }
        return false
    }

}
