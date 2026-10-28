package app.amber.feature.board.hotlist.deepread

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.workDataOf
import app.amber.agent.DEEP_READ_NOTIFICATION_CHANNEL_ID
import app.amber.agent.data.db.AppDatabase
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.review.ReflectionFixture
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeepReadWorkerCacheOwnershipTest {
    private lateinit var context: Application
    private lateinit var database: AppDatabase
    private lateinit var repository: HotListRepository
    private val title = "The same title"
    private val oldTopic = "old-topic"
    private val newTopic = "new-url-topic"
    private val newUrl = "https://example.com/new-source"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = HotListRepository(database.hotListDao(), Json { ignoreUnknownKeys = true })
        val settings = ReflectionFixture.allocate<SettingsAggregator>().also {
            ReflectionFixture.set(it, "_settingsFlow", MutableStateFlow(Settings(providers = emptyList())))
        }
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(DEEP_READ_NOTIFICATION_CHANNEL_ID, "Deep read", NotificationManager.IMPORTANCE_LOW),
        )
        startKoin {
            modules(module {
                single { repository }
                single { settings }
                single { SyncRestoreWriteGate() }
                single { DeepReadNotifier(context) }
                single<AgentRunner> { AdmissionFailureRunner() }
            })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        database.close()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test
    fun `generation admission failure cannot adopt another topics same title article`() = runBlocking {
        seedOtherArticle()

        assertEquals(ListenableWorker.Result.failure(), worker().doWork())

        assertFailureKeepsSourceIdentity()
    }

    @Test
    fun `exhausted foreground failure cannot adopt another topics same title article`() = runBlocking {
        seedOtherArticle()

        assertEquals(ListenableWorker.Result.failure(), worker(foregroundFailure = true).doWork())

        assertFailureKeepsSourceIdentity()
    }

    @Test
    fun `generation admission failure retains the current topics ready sections`() = runBlocking {
        seedOtherArticle()
        repository.saveDeepRead(
            newTopic, title,
            DeepReadOutput(summary = "Current source partial overview")
                .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY),
            ttlDays = 0, sourceUrl = newUrl,
        )

        assertEquals(ListenableWorker.Result.failure(), worker().doWork())

        val current = requireNotNull(repository.getFreshDeepRead(newTopic))
        assertEquals("Current source partial overview", current.summary)
        assertEquals(DeepReadSectionStatus.READY, current.statusOf(DeepReadGenerationStage.OVERVIEW))
        assertEquals(DeepReadSectionStatus.FAILED, current.statusOf(DeepReadGenerationStage.NARRATIVE))
        assertEquals("https://example.com/old-source", database.hotListDao().getDeepRead(oldTopic)?.sourceUrl)
    }

    private suspend fun seedOtherArticle() {
        repository.saveDeepRead(
            oldTopic, title,
            DeepReadOutput(
                summary = "Other source article that must not become the new URL result",
                generationComplete = true,
                sectionStates = DeepReadGenerationStage.entries.associateWith {
                    DeepReadSectionState(DeepReadSectionStatus.READY)
                },
            ),
            ttlDays = 0,
            sourceUrl = "https://example.com/old-source",
            templateId = "old-template",
            structuredJson = "{\"old\":true}",
        )
    }

    private suspend fun assertFailureKeepsSourceIdentity() {
        val entry = requireNotNull(database.hotListDao().getDeepRead(newTopic))
        val current = requireNotNull(repository.getFreshDeepRead(newTopic))
        assertEquals("", current.summary)
        assertFalse(current.sectionsReady())
        assertEquals(newUrl, entry.sourceUrl)
        assertNull(entry.templateId)
        assertNull(entry.structuredJson)
        assertNotNull(repository.getFreshDeepRead(oldTopic))
    }

    private fun worker(foregroundFailure: Boolean = false): DeepReadWorker {
        // Drive doWork directly with only the WorkManager fields this path reads;
        // no worker database or background executor is involved in this regression.
        val params = ReflectionFixture.allocate<WorkerParameters>().also {
            ReflectionFixture.set(it, "mId", UUID.randomUUID())
            ReflectionFixture.set(it, "mInputData", workDataOf(
                DeepReadWorker.KEY_TOPIC_ID to newTopic,
                DeepReadWorker.KEY_TITLE to title,
                DeepReadWorker.KEY_SOURCE_URL to newUrl,
            ))
            ReflectionFixture.set(it, "mRunAttemptCount", if (foregroundFailure) 3 else 0)
            ReflectionFixture.set(it, "mForegroundUpdater", ForegroundUpdater { _, _, _ ->
                SettableFuture.create<Void>().apply {
                    if (foregroundFailure) setException(IllegalStateException("Foreground service rejected")) else set(null)
                }
            })
        }
        return DeepReadWorker(context, params)
    }

    private class AdmissionFailureRunner : AgentRunner {
        override fun <I : AgentInput> launch(
            descriptorId: AgentDescriptorId, input: I, requestedRunId: AgentRunId?,
        ): Result<AgentRunHandle> = Result.failure(IllegalStateException("请先配置今日看板模型"))

        override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> = error("Admission did not launch a run")
        override fun cancel(runId: AgentRunId) = Unit
        override suspend fun awaitRunSettled(runId: AgentRunId) = Unit
        override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> = emptyList()
    }
}
