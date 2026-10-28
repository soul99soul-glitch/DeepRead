package app.amber.feature.ui.pages.sessionhome

import app.amber.core.model.Conversation
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.repository.CouncilRoomRepository
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.modelcouncil.CouncilRoomOpResult
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.runtime.DurableRuntimeTestBase
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCouncilRoomPersistenceTest : DurableRuntimeTestBase() {
    @Test
    fun `cancelling after council takes ownership retains its conversation`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val repository = conversationRepository(gate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rooms = CouncilRoomRepository(database.conversationDao(), scope, gate)
        val placeholder = Conversation.ofId(Uuid.random())
        val room = CouncilRoom(
            id = placeholder.id.toString(),
            conversationId = placeholder.id,
            hostAssistantId = AMBER_AGENT_ID,
            objective = "Owned council",
            createdAtMs = 0,
        )
        val accepted = CompletableDeferred<Unit>()
        try {
            val creation = launch {
                withHomeCouncilPlaceholder(
                    restoreWriteGate = gate,
                    hasRoom = { rooms.peekRoom(placeholder.id) != null },
                    insertPlaceholder = { repository.insertConversation(placeholder) },
                    deletePlaceholder = { repository.deleteConversation(placeholder) },
                    openRoom = {
                        rooms.upsertRoom(room)
                        accepted.complete(Unit)
                        awaitCancellation()
                    },
                )
            }
            accepted.await()
            assertEquals(room, rooms.peekRoom(placeholder.id))
            creation.cancelAndJoin()
            assertTrue(creation.isCancelled)
            assertTrue(
                "a room already accepted by Council must retain its conversation",
                repository.existsConversationById(placeholder.id),
            )
            rooms.flush(placeholder.id)
            assertTrue(database.conversationDao().getCouncilState(placeholder.id.toString())?.isNotBlank() == true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancellation between durable commit and insert return removes placeholder`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val repository = conversationRepository(gate)
        val placeholder = Conversation.ofId(Uuid.random())
        var roomOpened = false
        lateinit var creation: Job
        creation = launch(start = CoroutineStart.LAZY) {
            withHomeCouncilPlaceholder(
                restoreWriteGate = gate,
                hasRoom = { false },
                insertPlaceholder = {
                    withContext(Dispatchers.IO) {
                        repository.insertConversation(placeholder)
                        creation.cancel()
                    }
                },
                deletePlaceholder = { repository.deleteConversation(placeholder) },
                openRoom = {
                    roomOpened = true
                    error("cancelled creation must not start a room")
                },
            )
        }
        creation.start()
        creation.join()
        assertTrue(creation.isCancelled)
        assertFalse(roomOpened)
        assertFalse(
            "a committed insert must remain owned when its return is cancelled",
            repository.existsConversationById(placeholder.id),
        )
    }

    @Test
    fun `cancelling creation after durable insert removes empty conversation`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val repository = conversationRepository(gate)
        val placeholder = Conversation.ofId(Uuid.random())
        val opening = CompletableDeferred<Unit>()
        val creation = launch {
            withHomeCouncilPlaceholder(
                restoreWriteGate = gate,
                hasRoom = { false },
                insertPlaceholder = { repository.insertConversation(placeholder) },
                deletePlaceholder = { repository.deleteConversation(placeholder) },
                openRoom = {
                    opening.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        opening.await()
        assertTrue(repository.existsConversationById(placeholder.id))
        creation.cancelAndJoin()
        assertTrue(creation.isCancelled)
        assertFalse(
            "cancelled Council creation must not leave a persisted empty conversation",
            repository.existsConversationById(placeholder.id),
        )
    }

    @Test
    fun `old creation cannot remove a row adopted by restore`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val repository = conversationRepository(gate)
        val placeholder = Conversation.ofId(Uuid.random())
        val error = CouncilRoomOpResult.Err("open_failed", "Failed")
        val result = withHomeCouncilPlaceholder(
            restoreWriteGate = gate,
            hasRoom = { false },
            insertPlaceholder = { repository.insertConversation(placeholder) },
            deletePlaceholder = { repository.deleteConversation(placeholder) },
            openRoom = {
                gate.withRestore {
                    repository.updateConversation(placeholder.copy(title = "Restored conversation"))
                }
                error
            },
        )
        assertSame(error, result)
        assertEquals("Restored conversation", repository.getConversationById(placeholder.id)?.title)
    }
}
