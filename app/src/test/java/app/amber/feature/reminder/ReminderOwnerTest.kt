package app.amber.feature.reminder

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderOwnerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `store round trip and atomic update keep durable snapshot`() = runTest {
        val root = Files.createTempDirectory("reminder-round-trip").toFile()
        val first = ReminderStore(root, json)
        val snapshot = reminder()
        first.upsert(snapshot)
        first.upsert(snapshot.copy(message = "updated"))

        val restored = ReminderStore(root, json).read(snapshot.id)
        assertEquals("updated", restored?.message)
        assertEquals(1L, restored?.nextFireAtEpochMs)
        assertEquals(1, root.resolve("amberagent/reminders/${snapshot.id}.json").parentFile.listFiles()?.size)

        first.update(snapshot.id) { it.copy(message = "cas-updated") }
        assertEquals("cas-updated", first.read(snapshot.id)?.message)
    }

    @Test
    fun `corrupt record remains visible and disabled`() {
        val root = Files.createTempDirectory("reminder-corrupt").toFile()
        val dir = root.resolve("amberagent/reminders").also { it.mkdirs() }
        dir.resolve("bad.json").writeText("not-json")

        val restored = ReminderStore(root, json).read("bad")
        assertNotNull(restored)
        assertTrue(restored!!.isCorrupt)
        assertFalse(restored.enabled)
        assertTrue(dir.resolve("bad.json").exists())
    }

    @Test
    fun `remove rejects path traversal id`() = runTest {
        val root = Files.createTempDirectory("reminder-path").toFile()
        val outside = root.resolve("outside.json").also { it.writeText("keep") }
        val store = ReminderStore(root, json)

        val error = runCatching { store.remove("../outside") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(outside.exists())
    }

    @Test
    fun `next fire uses epoch duration across daylight saving boundaries`() {
        val beforeTransition = 1_710_046_800_000L
        assertEquals(beforeTransition + 24L * 60L * 60L * 1000L, ReminderStore(Files.createTempDirectory("reminder-time").toFile(), json).nextFire(beforeTransition, ReminderRecurrence.DAILY))
        assertEquals(beforeTransition + 7L * 24L * 60L * 60L * 1000L, ReminderStore(Files.createTempDirectory("reminder-week").toFile(), json).nextFire(beforeTransition, ReminderRecurrence.WEEKLY))
        assertNull(ReminderStore(Files.createTempDirectory("reminder-once").toFile(), json).nextFire(beforeTransition, ReminderRecurrence.NONE))
    }

    @Test
    fun `scheduler chooses inexact and labels denial`() = runTest {
        val operations = RecordingAlarmOperations()
        val store = ReminderStore(Files.createTempDirectory("reminder-scheduler").toFile(), json)
        val scheduler = ReminderScheduler(store, operations, exactAlarmAllowed = { false })
        val snapshot = reminder()
        store.upsert(snapshot)

        val result = scheduler.schedule(snapshot)
        assertEquals(ReminderSchedulePrecision.APPROXIMATE, result.precision)
        assertTrue(result.userMessage.contains("近似"))
        assertEquals(1, operations.approximate.size)
        assertTrue(operations.exact.isEmpty())
    }

    @Test
    fun `scheduler fire key and reschedule all are idempotent`() = runTest {
        val operations = RecordingAlarmOperations()
        val store = ReminderStore(Files.createTempDirectory("reminder-idempotent").toFile(), json)
        val scheduler = ReminderScheduler(store, operations, exactAlarmAllowed = { true })
        store.upsert(reminder())

        scheduler.rescheduleAll()
        scheduler.rescheduleAll()
        assertEquals(1, operations.exact.size)
        assertEquals(reminder().fireKey(), operations.exact.single().fireKey)

        val commit = store.commitFire(reminder().id, reminder().fireKey()!!)
        assertNotNull(commit)
        assertNull(store.commitFire(reminder().id, reminder().fireKey()!!))
    }

    @Test
    fun `delayed older snapshot cannot replace the current durable alarm`() = runTest {
        val operations = RecordingAlarmOperations()
        val store = ReminderStore(Files.createTempDirectory("reminder-delayed-schedule").toFile(), json)
        val scheduler = ReminderScheduler(store, operations, exactAlarmAllowed = { true })
        val older = store.upsert(reminder())
        val current = store.upsert(older.copy(nextFireAtEpochMs = 2L))
        scheduler.schedule(current)

        val delayed = scheduler.schedule(older)

        assertEquals(current.fireKey(), operations.active[current.id]?.fireKey)
        assertEquals(current.fireKey(), delayed.fireKey)
        assertTrue(decideReminderDelivery(store.read(current.id), operations.active[current.id]?.fireKey).commit)
    }

    @Test
    fun `delayed disabled snapshot cannot cancel a newly enabled reminder`() = runTest {
        val operations = RecordingAlarmOperations()
        val store = ReminderStore(Files.createTempDirectory("reminder-delayed-disable").toFile(), json)
        val scheduler = ReminderScheduler(store, operations, exactAlarmAllowed = { true })
        val disabled = store.upsert(reminder().copy(enabled = false))
        val current = store.upsert(disabled.copy(enabled = true, nextFireAtEpochMs = 2L))
        scheduler.schedule(current)

        val delayed = scheduler.schedule(disabled)

        assertEquals(ReminderSchedulePrecision.EXACT, delayed.precision)
        assertEquals(current.fireKey(), operations.active[current.id]?.fireKey)
    }

    @Test
    fun `delayed schedule after deletion cannot recreate an alarm`() = runTest {
        val operations = RecordingAlarmOperations()
        val store = ReminderStore(Files.createTempDirectory("reminder-delayed-delete").toFile(), json)
        val scheduler = ReminderScheduler(store, operations, exactAlarmAllowed = { true })
        val deleted = store.upsert(reminder())
        scheduler.schedule(deleted)
        store.remove(deleted.id)

        val delayed = scheduler.schedule(deleted)

        assertEquals(ReminderSchedulePrecision.CANCELLED, delayed.precision)
        assertNull(operations.active[deleted.id])
    }

    @Test
    fun `receiver decision only accepts current fire key and requests notify plus reschedule`() {
        val snapshot = reminder()
        assertEquals(
            ReminderDeliveryDecision(commit = true, shouldNotify = true, shouldReschedule = true),
            decideReminderDelivery(snapshot, snapshot.fireKey()),
        )
        assertEquals(
            ReminderDeliveryDecision(commit = false, shouldNotify = false, shouldReschedule = false),
            decideReminderDelivery(snapshot, "stale"),
        )
    }

    @Test
    fun `store commit fire advances durable snapshot and rejects duplicate`() = runTest {
        val store = ReminderStore(Files.createTempDirectory("reminder-commit").toFile(), json)
        val snapshot = reminder().copy(recurrence = ReminderRecurrence.DAILY)
        store.upsert(snapshot)

        val commit = store.commitFire(snapshot.id, snapshot.fireKey()!!)
        assertEquals(1L + 86_400_000L, commit?.updated?.nextFireAtEpochMs)
        assertEquals(1L, commit?.updated?.firedCount)
        assertNull(store.commitFire(snapshot.id, snapshot.fireKey()!!))
    }

    private fun reminder() = ReminderSnapshot(
        id = "reminder-1",
        title = "测试提醒",
        message = "请查看",
        triggerAtEpochMs = 1L,
        nextFireAtEpochMs = 1L,
        enabled = true,
    )

    private class RecordingAlarmOperations : ReminderAlarmOperations {
        val exact = mutableListOf<ReminderAlarmRequest>()
        val approximate = mutableListOf<ReminderAlarmRequest>()
        val cancelled = mutableListOf<String>()
        val active = mutableMapOf<String, ReminderAlarmRequest>()
        override fun scheduleExact(request: ReminderAlarmRequest) {
            exact += request
            active[request.reminderId] = request
        }
        override fun scheduleApproximate(request: ReminderAlarmRequest) {
            approximate += request
            active[request.reminderId] = request
        }
        override fun cancel(reminderId: String) {
            cancelled += reminderId
            active.remove(reminderId)
        }
    }
}
