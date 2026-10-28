package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteCandidate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceJointReviewResult
import java.io.File
import java.util.Locale

/** Composition boundary for the existing Controller/Worker and interactive chapter callers. */
class NovelWorkspaceGhostwriteCoordinator(
    runtime: NovelWorkspaceRuntime,
    turnLauncher: NovelTurnLauncher,
) {
    private val generator = NovelWorkspaceChapterGenerator(runtime, turnLauncher)
    private val reviewer = NovelWorkspaceChapterReview(runtime, turnLauncher)
    private val polisher = NovelWorkspacePolisher(runtime, turnLauncher)
    private val batchRunner = NovelWorkspaceBatchRunner(
        generator, NovelWorkspaceWriteChapterWorkflow(generator, reviewer), polisher,
    )

    suspend fun ghostwriteOneChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        chapterOrdinal: Int,
        maxSteps: Int = 24,
        injection: NovelWorkspaceInjectionFlags? = null,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
        repairInstructions: List<String> = emptyList(),
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
    ): NovelWorkspaceChapterResult = generator.ghostwriteOneChapter(
        projectDirectory, branchId, branchSlug, settings,
        model, chapterOrdinal, maxSteps, injection,
        ownerJobId, ownerExecutionId, repairInstructions, locale,
        fallbackErrorMessage,
    )

    suspend fun reviewCandidate(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        ownerJobId: String,
        ownerExecutionId: String,
        injection: NovelWorkspaceInjectionFlags? = null,
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
    ): NovelWorkspaceJointReviewTurnResult = reviewer.reviewCandidate(
        projectDirectory, branchId, branchSlug, settings,
        model, ownerJobId, ownerExecutionId, injection,
        locale, fallbackErrorMessage,
    )

    suspend fun planNextChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        ownerJobId: String,
        ownerExecutionId: String,
        injection: NovelWorkspaceInjectionFlags? = null,
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
    ): NovelWorkspaceNextPlanTurnResult = reviewer.planNextChapter(
        projectDirectory, branchId, branchSlug, settings,
        model, ownerJobId, ownerExecutionId, injection,
        locale, fallbackErrorMessage,
    )

    suspend fun runBatch(
        job: NovelWorkspaceGhostwriteJob,
        projectDirectory: File,
        branchId: String,
        settings: Settings,
        model: Model,
        reviewModel: Model = model,
        isPaused: () -> Boolean,
        injection: NovelWorkspaceInjectionFlags? = null,
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
        onChapter: suspend (Int) -> Unit,
    ): NovelWorkspaceBatchResult = batchRunner.runBatch(
        job, projectDirectory, branchId, settings,
        model, reviewModel, isPaused, injection,
        locale, fallbackErrorMessage, onChapter,
    )

    fun preparePolishBatch(
        projectDirectory: File,
        branchSlug: String,
        fromOrdinal: Int,
        toOrdinal: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob = NovelWorkspaceBatchJobs.preparePolishBatch(
        projectDirectory, branchSlug, fromOrdinal, toOrdinal, locale,
    )

    fun newJob(
        projectDirectory: File,
        branchSlug: String,
        targetChapterCount: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob = NovelWorkspaceBatchJobs.newJob(
        projectDirectory, branchSlug, targetChapterCount, locale,
    )

    fun newPolishJob(
        projectDirectory: File,
        branchSlug: String,
        fromOrdinal: Int,
        toOrdinal: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob = NovelWorkspaceBatchJobs.newPolishJob(
        projectDirectory, branchSlug, fromOrdinal, toOrdinal, locale,
    )

    /** Read-only polish generation and independent factual review precede the host commit. */
    suspend fun polishOneChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        chapterOrdinal: Int,
        maxSteps: Int = 24,
        injection: NovelWorkspaceInjectionFlags? = null,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
        reviewModel: Model = model,
    ): NovelWorkspaceChapterResult = polisher.runChapter(
        projectDirectory, branchId, branchSlug, settings, model, reviewModel,
        chapterOrdinal, maxSteps, injection, ownerJobId, ownerExecutionId,
        locale, fallbackErrorMessage,
    )

    internal fun parseJointReview(text: String): NovelWorkspaceJointReviewResult? = reviewer.parseJointReview(text)

    internal fun parseNextPlan(text: String): String? = reviewer.parseNextPlan(text)

    fun reconcileReviewedChapter(projectDirectory: File, jobId: String, executionId: String): Boolean =
        NovelWorkspaceReviewedCommits.reconcileReviewedChapter(projectDirectory, jobId, executionId)

    fun repairDanglingPolishPointer(projectDirectory: File, branchSlug: String): Int? =
        NovelWorkspaceChapterCommits.repairDanglingPolishPointer(projectDirectory, branchSlug)

    fun saveJob(job: NovelWorkspaceGhostwriteJob, projectDirectory: File) =
        NovelWorkspaceBatchJobs.saveJob(job, projectDirectory)

    companion object {
        const val REASON_POLISH_POINTER_COMMIT_FAILED = GHOSTWRITE_POINTER_COMMIT_FAILED
        const val MIN_REVIEW_OUTPUT_TOKENS = GHOSTWRITE_MIN_REVIEW_OUTPUT_TOKENS
        const val MAX_GHOSTWRITE_CHAPTERS = NovelWorkspaceBatchJobs.MAX_GHOSTWRITE_CHAPTERS

        fun maximumBatchRuntimeMs(job: NovelWorkspaceGhostwriteJob): Long {
            val perChapter = if (job.isVersionBound) {
                val candidateVersions = NovelWorkspaceGhostwriteCandidate.MAX_REWRITE_ATTEMPTS + 1L
                candidateVersions * (
                    2L * CHAPTER_TURN_TIMEOUT_MS +
                        2L * REVIEW_TURN_TIMEOUT_MS +
                        2L * CHAPTER_RETRY_DELAY_MS
                    )
            } else if (job.mode == NovelWorkspaceGhostwriteMode.Polish) {
                2L * (CHAPTER_TURN_TIMEOUT_MS + REVIEW_TURN_TIMEOUT_MS) + CHAPTER_RETRY_DELAY_MS
            } else {
                2L * CHAPTER_TURN_TIMEOUT_MS + CHAPTER_RETRY_DELAY_MS
            }
            val planning = if (job.isVersionBound) {
                (job.targetChapterCount - 1).coerceAtLeast(0) * PLANNING_TURN_TIMEOUT_MS
            } else {
                0L
            }
            return job.targetChapterCount * perChapter + planning + 60_000L
        }
    }
}
