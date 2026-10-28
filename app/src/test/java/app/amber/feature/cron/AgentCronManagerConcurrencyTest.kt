package app.amber.feature.cron

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentCronManagerConcurrencyTest {
    private lateinit var context: Context
    private lateinit var manager: AgentCronManager
    private lateinit var workManager: WorkManager
    private lateinit var taskStore: AgentTaskStore
    private var taskId: String? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val workerExecutor = Executor { /* Keep the manual request ENQUEUED for this interleaving. */ }
        val taskExecutor = Executor { runnable ->
            Thread(runnable, "CronWorkManagerTest").apply { isDaemon = true }.start()
        }
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(
                context,
                Configuration.Builder()
                    .setExecutor(workerExecutor)
                    .setTaskExecutor(taskExecutor)
                    .build(),
            )
        }
        workManager = WorkManager.getInstance(context)
        taskStore = AgentTaskStore(context, Json)
        manager = AgentCronManager(context, Json, taskStore)
    }

    @After
    fun tearDown() {
        runBlocking {
            taskId?.let { manager.deleteTask(it) }
        }
    }

    @Test
    fun `cancelled worker finally cannot replace the manual request`() {
        runBlocking {
            val task = manager.createTask(
                title = "Concurrency test",
                prompt = "Test prompt",
                cronExpression = "*/5 * * * *",
                timezoneId = "UTC",
                enabled = true,
            )
            taskId = task.id
            val oldRequest = activeWork(task.id)

            assertTrue(manager.runTaskNow(task.id))
            val manualRequest = activeWork(task.id)
            assertNotEquals(oldRequest.id, manualRequest.id)
            assertTrue(
                workManager.getWorkInfosForUniqueWork("amberagent_cron_${task.id}").get()
                    .none { it.id == oldRequest.id && it.state in ACTIVE_STATES },
            )

            val beforeOldWorkerCallback = manager.listTasks().single()
            assertEquals(null, manager.prepareTriggeredRun(task.id, oldRequest.id))
            assertEquals(beforeOldWorkerCallback, manager.listTasks().single())

            // This is the old worker's NonCancellable finally block after the manual
            // REPLACE has committed. It must not enqueue another REPLACE.
            manager.scheduleNextRun(task.id, oldRequest.id)

            assertEquals(manualRequest.id, activeWork(task.id).id)
        }
    }

    @Test
    fun `startup reschedule keeps a manual run for a disabled task`() {
        runBlocking {
            val task = manager.createTask(
                title = "Disabled concurrency test",
                prompt = "Test prompt",
                cronExpression = "*/5 * * * *",
                timezoneId = "UTC",
                enabled = false,
            )
            taskId = task.id

            assertTrue(manager.runTaskNow(task.id))
            val manualRequest = activeWork(task.id)

            manager.rescheduleAll()

            assertEquals(manualRequest.id, activeWork(task.id).id)
            assertEquals(AgentTaskStatus.QUEUED, taskStore.read(task.id)?.status)
        }
    }

    @Test
    fun `startup reprojects completed manual run from the disabled cron owner`() = runBlocking {
        val task = manager.createTask(
            title = "Disabled manual completion", prompt = "Test prompt", cronExpression = "*/5 * * * *",
            timezoneId = "UTC", enabled = false,
        )
        taskId = task.id
        assertTrue(manager.runTaskNow(task.id))
        val manualRequest = activeWork(task.id)
        val workSpecs = (workManager as WorkManagerImpl).workDatabase.workSpecDao()
        // The inert executor lets this test drive the real worker-owned state transitions.
        withContext(Dispatchers.IO) { workSpecs.setState(WorkInfo.State.RUNNING, manualRequest.id.toString()) }
        assertTrue(manager.prepareTriggeredRun(task.id, manualRequest.id, manual = true) != null)
        assertTrue(manager.markRunStarted(task.id, manualRequest.id))
        assertTrue(manager.markRunCompleted(task.id, manualRequest.id))
        assertEquals(AgentTaskStatus.COMPLETED, taskStore.read(task.id)?.status)
        withContext(Dispatchers.IO) { workSpecs.setState(WorkInfo.State.SUCCEEDED, manualRequest.id.toString()) }

        val recoveredTasks = AgentTaskStore(context, Json)
        val startupManager = AgentCronManager(context, Json, recoveredTasks)
        startupManager.rescheduleAll()

        assertEquals(AgentTaskStatus.CANCELLED, recoveredTasks.read(task.id)?.status)
        assertEquals(false, startupManager.listTasks().single().enabled)
        assertTrue(workManager.getWorkInfosForUniqueWork("amberagent_cron_${task.id}").get()
            .none { it.state in ACTIVE_STATES })
    }

    @Test
    fun `replacement execution waits for the stopped task cleanup before preparing`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val previous = launch {
            manager.withTaskExecution("same-task") {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
        }
        entered.await()
        previous.cancel()
        cleanupEntered.await()
        var replacementPrepared = false
        val replacement = launch {
            manager.withTaskExecution("same-task") { replacementPrepared = true }
        }

        runCurrent()
        val preparedBeforeCleanupFinished = replacementPrepared
        releaseCleanup.complete(Unit)
        previous.join()
        replacement.join()

        assertFalse("A manual REPLACE must wait for its previous owner to stop", preparedBeforeCleanupFinished)
        assertTrue(replacementPrepared)
    }

    @Test
    fun `unrelated tasks may execute while another task is running`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            manager.withTaskExecution("first-task") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var otherTaskPrepared = false
        val other = launch {
            manager.withTaskExecution("other-task") { otherTaskPrepared = true }
        }

        runCurrent()
        val preparedIndependently = otherTaskPrepared
        release.complete(Unit)
        first.join()
        other.join()

        assertTrue(preparedIndependently)
    }

    @Test
    fun `cancelled replacement waiting for its task never prepares a run`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            manager.withTaskExecution("same-task") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var replacementPrepared = false
        val replacement = launch {
            manager.withTaskExecution("same-task") { replacementPrepared = true }
        }

        runCurrent()
        replacement.cancelAndJoin()
        release.complete(Unit)
        first.join()

        assertFalse(replacementPrepared)
    }

    private fun activeWork(taskId: String): WorkInfo {
        return workManager.getWorkInfosForUniqueWork("amberagent_cron_$taskId").get()
            .single { it.state in ACTIVE_STATES }
    }

    private companion object {
        val ACTIVE_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
        )
    }
}
