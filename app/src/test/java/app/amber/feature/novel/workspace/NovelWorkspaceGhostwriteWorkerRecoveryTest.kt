package app.amber.feature.novel.workspace

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.agent.runtime.impl.InMemoryAgentRegistry
import app.amber.core.agent.runtime.impl.InProcessAgentRunner
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.review.ReflectionFixture
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NovelWorkspaceGhostwriteWorkerRecoveryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `worker repairs final polished chapter pointer before completing without another provider call`() = runTest {
        val repository = NovelWorkspaceProjectRepository(tempFolder.root)
        val projectId = UUID.randomUUID().toString().uppercase()
        val directory = repository.install(projectId, projectFiles(projectId)).projectDirectory
        val kernel = NoProviderKernel()
        val runtime = NovelWorkspaceRuntime(kernel)
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(directory), NovelWorkspaceLedger.load(directory), "主线"))
        val coordinator = coordinator(runtime, backgroundScope)
        val createdJob = coordinator.newPolishJob(directory, "主线", fromOrdinal = 1, toOrdinal = 1)
        NovelWorkspaceGhostwriteJobs.save(createdJob, directory)
        // Use the persisted job, including its serialized timestamp precision, as a restarted Worker does.
        val job = checkNotNull(NovelWorkspaceGhostwriteJobs.load(directory, createdJob.id))
        val store = NovelWorkspaceStore(directory)
        runtime.commitPolishedChapter(
            directory,
            "B-1",
            "主线",
            CHAPTER_PATH,
            "陈桥驿的风先到了，他仍守在原地。",
            expectedHeadId = NovelWorkspaceLedger.load(directory).headOf("B-1")?.id,
            expectedTreeDigest = NovelWorkspaceLedger.treeSHA256(store.fileTree()),
            expectedChapterContent = checkNotNull(store.read(CHAPTER_PATH)),
            ownerJobId = job.id,
            ownerExecutionId = job.executionKey,
        )
        val committedChapter = checkNotNull(store.read(CHAPTER_PATH))
        val before = NovelWorkspaceLedger.load(directory)
        assertEquals(1, job.targetChapterCount)
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, store))
        assertEquals(NovelWorkspaceLedger.Message.POLISH, before.commits.last().message)
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, before, "主线"))
        startKoin {
            modules(module {
                single { repository }
                single { coordinator }
            })
        }

        val result = worker(projectId, job).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, kernel.calls)
        assertEquals(committedChapter, store.read(CHAPTER_PATH))
        val after = NovelWorkspaceLedger.load(directory)
        assertEquals("Recovery must append only the missing pointer", before.commits.size + 1, after.commits.size)
        assertEquals(before.commits, after.commits.dropLast(1))
        assertEquals(NovelWorkspaceLedger.Message.PLOT_POINTER, after.commits.last().message)
        assertFalse(NovelWorkspaceLedger.isPlotStale(store, after, "主线"))
        assertEquals(
            NovelWorkspaceGhostwriteJob.STATUS_COMPLETED,
            checkNotNull(NovelWorkspaceGhostwriteJobs.load(directory, job.id)).status,
        )
    }

    private fun worker(projectId: String, job: NovelWorkspaceGhostwriteJob): NovelWorkspaceGhostwriteWorker {
        // These are the WorkManager inputs read by the public recovery path; no fake worker bypasses doWork.
        val params = ReflectionFixture.allocate<WorkerParameters>().also {
            ReflectionFixture.set(it, "mId", UUID.randomUUID())
            ReflectionFixture.set(it, "mInputData", workDataOf(
                NovelWorkspaceGhostwriteWorker.KEY_PROJECT_ID to projectId,
                NovelWorkspaceGhostwriteWorker.KEY_JOB_ID to job.id,
                NovelWorkspaceGhostwriteWorker.KEY_EXECUTION_ID to job.executionKey,
            ))
            ReflectionFixture.set(it, "mRunAttemptCount", 0)
        }
        return NovelWorkspaceGhostwriteWorker(RuntimeEnvironment.getApplication(), params)
    }

    private fun coordinator(runtime: NovelWorkspaceRuntime, scope: CoroutineScope): NovelWorkspaceGhostwriteCoordinator {
        val payloads = NovelTurnPayloads()
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = NovelTurnDescriptor.value,
                inputClass = NovelTurnInput::class,
                inputSerializer = NovelTurnInput.serializer(),
                artifactSerializer = NovelTurnArtifact.serializer(),
                factory = { NovelTurnAgent(payloads) },
            )
        }
        val runner = InProcessAgentRunner(
            registry,
            InMemoryAgentEventStore(),
            runScopeFactory = { id, _ -> LegacyRunScope(runId = id) },
            scope = scope,
        )
        return NovelWorkspaceGhostwriteCoordinator(runtime, NovelTurnLauncher(runner, payloads, scope))
    }

    private class NoProviderKernel : RunKernel {
        var calls = 0
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            calls += 1
            error("A durable final polish needs no provider call during recovery")
        }
    }

    private fun projectFiles(projectId: String): List<NovelWorkspaceFile> = listOf(
        NovelWorkspaceFile(
            "manifest.yaml",
            NovelWorkspaceManifestRenderer.render(
                exportedAt = Instant.parse("2026-08-19T00:00:00Z"),
                sourceProjectID = projectId,
                sourceProjectRevision = 1,
                sourceSchemaVersion = 1,
                mainBranch = "主线",
            ),
        ),
        NovelWorkspaceFile(
            "project.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to projectId, "kind" to "project", "title" to "赵大来了"),
                body = "",
            ),
        ),
        NovelWorkspaceFile(
            "branches/主线/branch.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "B-1", "kind" to "branch", "title" to "主线"),
                body = "",
            ),
        ),
        NovelWorkspaceFile(
            CHAPTER_PATH,
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "C-1", "kind" to "chapter", "title" to "山呼", "ordinal" to "1"),
                body = "陈桥驿的风先到。",
            ),
        ),
        NovelWorkspaceFile(
            "branches/主线/plot/current.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "PLOT-1", "kind" to "plot", "title" to "当前剧情"),
                body = "他仍守在陈桥驿。",
            ),
        ),
    )

    private companion object {
        const val CHAPTER_PATH = "branches/主线/chapters/001-山呼.md"
    }
}
