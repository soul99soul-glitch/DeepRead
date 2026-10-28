package app.amber.feature.ui.pages.sessionhome

import android.util.Log
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.findModelById
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.modelcouncil.CouncilRoomManager
import app.amber.feature.modelcouncil.CouncilRoomOpResult
import app.amber.feature.modelcouncil.toCouncilParticipant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Creates the conversation row required by Council's UPDATE-based room store. */
class HomeCouncilRoomCreator(
    private val settingsStore: SettingsAggregator,
    private val conversationRepo: ConversationRepository,
    private val roomManager: CouncilRoomManager,
    private val restoreWriteGate: SyncRestoreWriteGate,
) {
    suspend fun create(objective: String): CouncilRoomOpResult {
        val settings = settingsStore.settingsFlow.value
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = AMBER_AGENT_ID,
            newConversation = true,
        ).updateCurrentMessages(settings.presetMessages)
        return withHomeCouncilPlaceholder(
            restoreWriteGate = restoreWriteGate,
            hasRoom = { roomManager.peekRoom(conversation.id) != null },
            insertPlaceholder = { conversationRepo.insertConversation(conversation) },
            deletePlaceholder = { conversationRepo.deleteConversation(conversation) },
            openRoom = {
                roomManager.openRoom(
                    conversationId = conversation.id,
                    hostAssistantId = AMBER_AGENT_ID,
                    hostName = "Amber",
                    objective = objective,
                    initialGuests = settings.agentRuntime.modelCouncil.defaultSeats.map { seat ->
                        seat.toCouncilParticipant().copy(
                            modelName = settings.findModelById(seat.modelId)?.displayName.orEmpty(),
                        )
                    },
                    maxRounds = settings.agentRuntime.modelCouncil.defaultRounds.coerceIn(2, 6),
                    hostModelIdOverride = settings.agentRuntime.modelCouncil.hostModelId,
                )
            },
        )
    }
}

/**
 * The placeholder and its rollback belong to one restore generation. Cleanup
 * continues after UI cancellation, but may never delete a restored conversation.
 */
internal suspend fun withHomeCouncilPlaceholder(
    restoreWriteGate: SyncRestoreWriteGate,
    hasRoom: () -> Boolean,
    insertPlaceholder: suspend () -> Unit,
    deletePlaceholder: suspend () -> Unit,
    openRoom: suspend () -> CouncilRoomOpResult,
): CouncilRoomOpResult {
    val epoch = restoreWriteGate.currentEpoch()
    return withContext(SyncRestoreWriteEpoch(epoch)) {
        var inserted = false
        var opened = false
        try {
            restoreWriteGate.withCurrentWriterOrCancel {
                // Room may commit before cancellation discards the return to Main.
                // Keep the short insert and its ownership mark in the same interval.
                withContext(NonCancellable) {
                    insertPlaceholder()
                    inserted = true
                }
            }
            currentCoroutineContext().ensureActive()
            openRoom().also { opened = it is CouncilRoomOpResult.Ok }
        } finally {
            // Council may accept the row before a later registration is cancelled.
            if (inserted && !opened && !hasRoom()) {
                withContext(NonCancellable) {
                    // ConversationRepository rechecks this epoch at its durable
                    // delete boundary if a restore starts after this check.
                    if (restoreWriteGate.isWriteAllowed(epoch)) {
                        try {
                            deletePlaceholder()
                        } catch (_: SyncRestoreWriteRejectedException) {
                            // Restore can begin while the repository loads the row.
                            // Its durable gate has already protected the adopted snapshot.
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            Log.e("SessionHomeCouncil", "Failed to clean up placeholder conversation", error)
                        }
                    }
                }
            }
        }
    }
}
