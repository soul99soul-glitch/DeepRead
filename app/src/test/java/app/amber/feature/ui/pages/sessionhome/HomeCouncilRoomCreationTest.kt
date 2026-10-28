package app.amber.feature.ui.pages.sessionhome

import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomOpResult
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCouncilRoomCreationTest {
    @Test
    fun `successful room is opened after placeholder and retains its row`() = runTest {
        val gate = SyncRestoreWriteGate()
        val epoch = gate.currentEpoch()
        val calls = mutableListOf<String>()
        val room = CouncilRoomOpResult.Ok(room())
        val result = withHomeCouncilPlaceholder(
            restoreWriteGate = gate,
            hasRoom = { false },
            insertPlaceholder = {
                assertEquals(epoch, currentCoroutineContext()[SyncRestoreWriteEpoch]?.value)
                calls += "insert"
            },
            deletePlaceholder = { calls += "delete" },
            openRoom = {
                assertEquals(listOf("insert"), calls)
                assertEquals(epoch, currentCoroutineContext()[SyncRestoreWriteEpoch]?.value)
                calls += "open"
                room
            },
        )
        assertSame(room, result)
        assertEquals(listOf("insert", "open"), calls)
    }

    @Test
    fun `structured room error removes placeholder once`() = runTest {
        val calls = mutableListOf<String>()
        val error = CouncilRoomOpResult.Err("initial_roster_overflow", "Too many seats")
        val result = withHomeCouncilPlaceholder(
            restoreWriteGate = SyncRestoreWriteGate(),
            hasRoom = { false },
            insertPlaceholder = { calls += "insert" },
            deletePlaceholder = { calls += "delete" },
            openRoom = {
                calls += "open"
                error
            },
        )
        assertSame(error, result)
        assertEquals(listOf("insert", "open", "delete"), calls)
    }

    @Test
    fun `room exception cleans placeholder and propagates original failure`() = runTest {
        val original = IllegalStateException("Room store unavailable")
        var deletes = 0
        val failure = runCatching {
            withHomeCouncilPlaceholder(
                restoreWriteGate = SyncRestoreWriteGate(),
                hasRoom = { false },
                insertPlaceholder = {},
                deletePlaceholder = { deletes++ },
                openRoom = { throw original },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(original.message, failure?.message)
        // Coroutine stack-trace recovery may copy an exception at a context boundary.
        assertTrue(failure === original || failure?.cause === original)
        assertEquals(1, deletes)
    }

    @Test
    fun `cancellation waits for placeholder cleanup and remains cancelled`() = runTest {
        val opening = CompletableDeferred<Unit>()
        var cleanupFinished = false
        val creation = launch {
            withHomeCouncilPlaceholder(
                restoreWriteGate = SyncRestoreWriteGate(),
                hasRoom = { false },
                insertPlaceholder = {},
                deletePlaceholder = {
                    delay(10)
                    cleanupFinished = true
                },
                openRoom = {
                    opening.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        opening.await()
        creation.cancelAndJoin()
        assertTrue("cancelling the UI must not retain an empty conversation", cleanupFinished)
        assertTrue(creation.isCancelled)
    }

    @Test
    fun `restored snapshot is never removed by old placeholder cleanup`() = runTest {
        val gate = SyncRestoreWriteGate()
        var deletes = 0
        val error = CouncilRoomOpResult.Err("open_failed", "Failed")
        val result = withHomeCouncilPlaceholder(
            restoreWriteGate = gate,
            hasRoom = { false },
            insertPlaceholder = {},
            deletePlaceholder = { deletes++ },
            openRoom = {
                gate.withRestore { }
                error
            },
        )
        assertSame(error, result)
        assertEquals(0, deletes)
    }

    @Test
    fun `restore starting after cleanup check preserves the room error`() = runTest {
        val gate = SyncRestoreWriteGate()
        val error = CouncilRoomOpResult.Err("open_failed", "Failed")
        var deletes = 0
        val result = withHomeCouncilPlaceholder(
            restoreWriteGate = gate,
            hasRoom = { false },
            insertPlaceholder = {},
            deletePlaceholder = {
                // Repository reads the row before reaching its durable delete gate.
                gate.withRestore { }
                gate.withCurrentWriterOrCancel { deletes++ }
            },
            openRoom = { error },
        )
        assertSame(error, result)
        assertEquals(0, deletes)
    }

    @Test
    fun `restore invalidates queued creation before any placeholder is inserted`() = runTest {
        val gate = SyncRestoreWriteGate()
        val restoring = CompletableDeferred<Unit>()
        val finishRestore = CompletableDeferred<Unit>()
        val restore = launch {
            gate.withRestore {
                restoring.complete(Unit)
                finishRestore.await()
            }
        }
        restoring.await()
        var inserted = false
        var opened = false
        val creation = async {
            runCatching {
                withHomeCouncilPlaceholder(
                    restoreWriteGate = gate,
                    hasRoom = { false },
                    insertPlaceholder = { inserted = true },
                    deletePlaceholder = { error("no row belongs to this action") },
                    openRoom = {
                        opened = true
                        CouncilRoomOpResult.Ok(room())
                    },
                )
            }.exceptionOrNull()
        }
        runCurrent()
        finishRestore.complete(Unit)
        restore.join()
        assertTrue(creation.await() is SyncRestoreWriteRejectedException)
        assertFalse(inserted)
        assertFalse(opened)
    }

    private fun room(): CouncilRoom {
        val id = Uuid.random()
        return CouncilRoom(
            id = id.toString(),
            conversationId = id,
            hostAssistantId = AMBER_AGENT_ID,
            objective = "Test council",
            createdAtMs = 0,
        )
    }
}
