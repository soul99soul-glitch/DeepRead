package app.amber.feature.modelcouncil

import kotlin.uuid.Uuid

/**
 * Host-action vocabulary — explicit runtime affordances for the room's host
 * (the conversation's canonical Amber identity). Each variant maps 1:1 to a host message
 * + a guest turn trigger. PR2 wires the generation side; PR1 only carries them
 * through the state machine and emits the host message.
 *
 * Mirrors the iOS design's "host control layer": Invite / Ask next / Let guests
 * respond / Switch to Debate / Synthesize / Stop.
 */
sealed interface HostAction {
    /** Invite an already-rostered guest to speak, optionally with a directive. */
    data class InviteNext(val participantId: String, val instruction: String = "") : HostAction

    /** Ask a guest to follow up on a specific prior message. */
    data class AskFollowUp(val toParticipantId: String, val aboutMessageId: String) : HostAction

    /** Let one or more guests respond to / continue a seed message. */
    data class LetGuestsRespond(val seedMessageId: String, val participantIds: List<String> = emptyList()) : HostAction

    /** Redirect a guest mid-discussion. */
    data class Redirect(val participantId: String, val newDirection: String) : HostAction

    data object SwitchToExplore : HostAction
    data object SwitchToDebate : HostAction
    data object Synthesize : HostAction
    data object Stop : HostAction
}

/** Result type for manager ops that can fail with a structured error. */
sealed interface CouncilRoomOpResult {
    data class Ok(val room: CouncilRoom) : CouncilRoomOpResult
    data class Err(val code: String, val message: String) : CouncilRoomOpResult
}

/**
 * Preflight result: either a structured error, or a [GuestTurnPlan] embedding
 * the next room state plus the generation parameters. Used by [CouncilRoomManager.mutatePreflight]
 * so host actions can persist the room under the lock, then launch generation
 * after releasing it (m3 fix — generation must not hold the room mutex).
 */
sealed interface PreflightResult {
    data class Err(val code: String, val message: String) : PreflightResult
    data class Plan(val plan: GuestTurnPlan) : PreflightResult
}

/**
 * Carries everything [CouncilRoomExecutor.generateGuestTurn] needs, captured
 * synchronously inside the room mutex. [extraGuests] supports multi-target
 * host actions (LetGuestsRespond) — each gets its own generation job.
 */
data class GuestTurnPlan(
    val room: CouncilRoom,
    val guest: CouncilParticipant,
    val userPrompt: String,
    val replyToMessageId: String? = null,
    val continuesFromMessageId: String? = null,
    val invitedBy: String? = null,
    val extraGuests: List<GuestTurnPlan> = emptyList(),
)
