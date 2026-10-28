package app.amber.core.service

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.AppScope
import app.amber.agent.RouteActivity
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatRunPresenterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private lateinit var context: ContextWrapper
    private lateinit var scope: AppScope
    private lateinit var scheduler: AgentTaskScheduler
    private lateinit var presenter: ChatRunPresenter

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val taskDirectory = temporaryFolder.newFolder("tasks")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getFilesDir(): File = taskDirectory
        }
        scope = AppScope()
        scheduler = AgentTaskScheduler(AgentTaskStore(context, Json, loadDispatcher = Dispatchers.Unconfined))
        presenter = ChatRunPresenter(
            context = context,
            appScope = scope,
            activityStore = AgentToolActivityStore(),
            liveStatusNotifier = AgentLiveStatusNotifier(context, NotificationApprovalTokenRegistry()),
            agentTaskScheduler = scheduler,
        )
    }

    @After
    fun tearDown() {
        presenter.cleanup()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `notification intents retain their conversation and run after successor creation`() {
        val first = chatGenerationLaunchIntent(context, conversationId, "first-run")
        val same = chatGenerationLaunchIntent(context, conversationId, "first-run")
        val successor = chatGenerationLaunchIntent(context, conversationId, "next-run")
        val otherConversation = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val other = chatGenerationLaunchIntent(context, otherConversation, "first-run")

        assertEquals(first, same)
        assertNotEquals(first, successor)
        assertNotEquals(first, other)
        val original = shadowOf(first).savedIntent
        assertEquals(RouteActivity::class.java.name, original.component?.className)
        assertEquals(conversationId.toString(), original.getStringExtra("conversationId"))
        assertEquals("conversation", original.getStringExtra("focus"))
        assertEquals("first-run", original.getStringExtra("runId"))
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, original.flags)
        assertEquals("next-run", shadowOf(successor).savedIntent.getStringExtra("runId"))
        assertEquals(otherConversation.toString(), shadowOf(other).savedIntent.getStringExtra("conversationId"))
    }

    @Test
    fun `unscoped notification intent does not inherit a previous run`() {
        val scoped = chatGenerationLaunchIntent(context, conversationId, "scoped-run")
        val unscoped = chatGenerationLaunchIntent(context, conversationId)

        assertNotEquals(scoped, unscoped)
        assertNull(shadowOf(unscoped).savedIntent.getStringExtra("runId"))
        assertEquals("scoped-run", shadowOf(scoped).savedIntent.getStringExtra("runId"))
    }

    @Test
    fun `old run completion cannot settle successor task or its persisted snapshot`() = runBlocking {
        val taskId = startTask("first-run")
        assertEquals(taskId, startTask("next-run"))

        presenter.finishGenerationTask(taskId, null, "first-run")

        val task = scheduler.read(taskId)!!
        assertEquals(AgentTaskStatus.RUNNING, task.status)
        assertEquals("next-run", task.spec!!["run_id"]!!.jsonPrimitive.content)
        assertNull(task.summary)
        assertEquals(task, persistedTask(taskId))
    }

    @Test
    fun `accepted completion persists task outcome and clears cancel capability`() = runBlocking {
        val taskId = startTask("completed-run")

        presenter.finishGenerationTask(taskId, null, "completed-run")

        val task = scheduler.read(taskId)!!
        assertEquals(AgentTaskStatus.COMPLETED, task.status)
        assertEquals("Generation completed.", task.summary)
        assertFalse(task.cancelCapability)
        assertEquals(task, persistedTask(taskId))
    }

    @Test
    fun `cancelled and failed generation tasks retain their distinct error codes`() = runBlocking {
        val taskId = startTask("cancelled-run")
        presenter.finishGenerationTask(taskId, CancellationException("stop"), "cancelled-run")
        val cancelled = scheduler.read(taskId)!!
        assertEquals(AgentTaskStatus.FAILED, cancelled.status)
        assertEquals("cancelled", cancelled.lastErrorCode)
        assertEquals("Generation cancelled.", cancelled.error)

        startTask("failed-run")
        presenter.finishGenerationTask(taskId, IllegalStateException("provider unavailable"), "failed-run")
        val failed = scheduler.read(taskId)!!
        assertEquals(AgentTaskStatus.FAILED, failed.status)
        assertEquals("generation_failed", failed.lastErrorCode)
        assertEquals("provider unavailable", failed.error)
    }

    @Test
    fun `task registry invokes the explicit cancel command and preserves retry projection`() = runBlocking {
        var cancellations = 0
        val taskId = presenter.startGenerationTask(
            conversationId = conversationId,
            runId = "cancellable-run",
            senderName = "Sender",
            modelName = "Model",
            autoRetry = false,
            maxRetries = 3,
            cancel = { cancellations++; true },
        )
        val running = scheduler.read(taskId)!!
        val spec = running.spec!!
        assertEquals(conversationId.toString(), running.sourceConversationId)
        assertEquals("chat_generation", running.sourceToolName)
        assertFalse(spec["auto_retry"]!!.jsonPrimitive.boolean)
        assertEquals(3, spec["max_retries"]!!.jsonPrimitive.int)
        assertTrue(running.retryPolicy.retryable)
        assertFalse(running.retryPolicy.requiresApproval)
        assertEquals(3, running.retryPolicy.maxRetries)

        val cancelled = scheduler.cancel(taskId)

        assertEquals(1, cancellations)
        assertEquals(AgentTaskStatus.CANCELLED, cancelled.status)
    }

    // Opening another store performs startup recovery; inspect the actual commit
    // file to compare persistence without intentionally transforming the snapshot.
    private fun persistedTask(taskId: String): AgentTaskSnapshot = Json.decodeFromString(
        AgentTaskSnapshot.serializer(),
        File(context.filesDir, "amberagent/tasks/$taskId.json").readText(),
    )

    private suspend fun startTask(runId: String): String = presenter.startGenerationTask(
        conversationId = conversationId,
        runId = runId,
        senderName = "Sender",
        modelName = "Model",
        autoRetry = true,
        maxRetries = 2,
        cancel = { true },
    )
}
