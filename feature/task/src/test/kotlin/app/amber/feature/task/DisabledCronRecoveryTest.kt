package app.amber.feature.task

import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisabledCronRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val json = Json { encodeDefaults = true }

    @Test
    fun `disabled cron remains cancelled in memory and disk after restart`() = runBlocking {
        val root = temporaryFolder.newFolder("disabled-cron")
        val disabled = snapshot(AgentTaskStatus.CANCELLED, AgentTaskQueueState.TERMINAL)
        store(root).register(disabled)

        val recovered = requireNotNull(store(root).read(disabled.taskId))

        assertEquals(AgentTaskStatus.CANCELLED, recovered.status)
        assertEquals(AgentTaskQueueState.TERMINAL, recovered.queueState)
        assertEquals(AgentTaskRecoveryState.CLEANUP_ONLY, recovered.recoveryState)
        val persisted = json.decodeFromString<AgentTaskSnapshot>(
            File(root, "amberagent/tasks/${disabled.taskId}.json").readText(),
        )
        assertEquals(recovered, persisted)
    }

    @Test
    fun `enabled running recurring cron still recovers as scheduled`() = runBlocking {
        val root = temporaryFolder.newFolder("enabled-cron")
        val running = snapshot(AgentTaskStatus.RUNNING, AgentTaskQueueState.ACTIVE)
        store(root).register(running)

        val recovered = requireNotNull(store(root).read(running.taskId))

        assertEquals(AgentTaskStatus.QUEUED, recovered.status)
        assertEquals(AgentTaskQueueState.SCHEDULED, recovered.queueState)
        assertEquals(AgentTaskRecoveryState.SCHEDULED, recovered.recoveryState)
    }

    private fun snapshot(status: AgentTaskStatus, queueState: AgentTaskQueueState) = AgentTaskSnapshot(
        taskId = "cron-plan", type = "cron", title = "Recurring plan", status = status,
        queueState = queueState, recoveryState = AgentTaskRecoveryState.CLEANUP_ONLY,
        cancelCapability = false, createdAtMs = 1_000L,
    )

    private fun store(root: File) = AgentTaskStore(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = root
    }, json)
}
