package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteCandidate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One version-bound chapter: candidate -> review/rewrite -> optional plan -> host commit. */
internal class NovelWorkspaceWriteChapterWorkflow(
    private val generator: NovelWorkspaceChapterGenerator,
    private val reviewer: NovelWorkspaceChapterReview,
) {
    suspend fun advanceChapter(
        job: NovelWorkspaceGhostwriteJob,
        projectDirectory: File,
        branchId: String,
        settings: Settings,
        model: Model,
        reviewModel: Model,
        written: Int,
        nextOrdinal: Int,
        isPaused: () -> Boolean,
        injection: NovelWorkspaceInjectionFlags?,
        locale: Locale,
        fallbackErrorMessage: String,
    ): NovelWorkspaceBatchResult {
        val store = NovelWorkspaceStore(projectDirectory)
        val before = written
        suspend fun generateCandidate(repairInstructions: List<String>): NovelWorkspaceChapterResult {
            var result = generator.ghostwriteOneChapter(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = job.branchSlug,
                settings = settings,
                model = model,
                chapterOrdinal = nextOrdinal,
                injection = injection,
                locale = locale,
                fallbackErrorMessage = fallbackErrorMessage,
                ownerJobId = job.id,
                ownerExecutionId = job.executionKey,
                repairInstructions = repairInstructions,
            )
            if (result.error != null &&
                !isPaused() && !isGhostwriteCancelled(job, projectDirectory)
            ) {
                delay(CHAPTER_RETRY_DELAY_MS)
                if (!isPaused() && !isGhostwriteCancelled(job, projectDirectory)) {
                    result = generator.ghostwriteOneChapter(
                        projectDirectory = projectDirectory,
                        branchId = branchId,
                        branchSlug = job.branchSlug,
                        settings = settings,
                        model = model,
                        chapterOrdinal = nextOrdinal,
                        injection = injection,
                        locale = locale,
                        fallbackErrorMessage = fallbackErrorMessage,
                        ownerJobId = job.id,
                        ownerExecutionId = job.executionKey,
                        repairInstructions = repairInstructions,
                    )
                }
            }
            return result
        }

        while (true) {
            if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) {
                return NovelWorkspaceBatchResult.Stopped(written)
            }
            var latest = NovelWorkspaceGhostwriteJobs.load(projectDirectory, job.id)
                ?: return NovelWorkspaceBatchResult.Stopped(written)
            var candidate = latest.pendingCandidate
            if (candidate == null) {
                val generated = generateCandidate(emptyList())
                if (generated.error != null) {
                    if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) {
                        return NovelWorkspaceBatchResult.Stopped(written)
                    }
                    return NovelWorkspaceBatchResult.Failed(written, generated.error)
                }
                candidate = generated.candidate
                    ?: return NovelWorkspaceBatchResult.Failed(
                        written,
                        ghostwriteLocalized(
                            locale,
                            chinese = "本轮未生成可审核的章节候选",
                            english = "This turn did not generate a reviewable chapter candidate.",
                        ),
                    )
                latest = NovelWorkspaceGhostwriteJobs.load(projectDirectory, job.id)
                    ?: return NovelWorkspaceBatchResult.Stopped(written)
            }

            var reviewed = latest.pendingReview?.let { NovelWorkspaceJointReviewTurnResult(review = it) }
                ?: reviewer.reviewCandidate(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = job.branchSlug,
                    settings = settings,
                    model = reviewModel,
                    ownerJobId = job.id,
                    ownerExecutionId = job.executionKey,
                    injection = injection,
                    locale = locale,
                    fallbackErrorMessage = fallbackErrorMessage,
                )
            if (reviewed.error != null && reviewed.retryable &&
                !isPaused() && !isGhostwriteCancelled(job, projectDirectory)
            ) {
                delay(CHAPTER_RETRY_DELAY_MS)
                if (!isPaused() && !isGhostwriteCancelled(job, projectDirectory)) {
                    reviewed = reviewer.reviewCandidate(
                        projectDirectory = projectDirectory,
                        branchId = branchId,
                        branchSlug = job.branchSlug,
                        settings = settings,
                        model = reviewModel,
                        ownerJobId = job.id,
                        ownerExecutionId = job.executionKey,
                        injection = injection,
                        locale = locale,
                        fallbackErrorMessage = fallbackErrorMessage,
                    )
                }
            }
            if (reviewed.error != null) {
                if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) {
                    return NovelWorkspaceBatchResult.Stopped(written)
                }
                return NovelWorkspaceBatchResult.Failed(written, reviewed.error)
            }
            val review = reviewed.review ?: return NovelWorkspaceBatchResult.Failed(
                written,
                ghostwriteLocalized(
                    locale,
                    chinese = "联合审核没有可执行结论",
                    english = "The joint review returned no actionable decision.",
                ),
            )
            if (review.blocking) {
                return NovelWorkspaceBatchResult.Failed(
                    written,
                    review.findings.joinToString("；") { it.message }
                        .ifBlank {
                            ghostwriteLocalized(
                                locale,
                                chinese = "联合审核发现阻断问题",
                                english = "The joint review found a blocking issue.",
                            )
                        },
                )
            }
            if (review.rewriteRequired) {
                if (candidate.attempt >= NovelWorkspaceGhostwriteCandidate.MAX_REWRITE_ATTEMPTS) {
                    val finalFindings = review.findings
                        .map { it.message.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("；")
                    val exhausted = ghostwriteLocalized(
                        locale,
                        chinese = "本章定向重写两次后仍未通过联合审核",
                        english = "The chapter still failed joint review after two targeted rewrites.",
                    )
                    return NovelWorkspaceBatchResult.Failed(
                        written,
                        if (finalFindings.isBlank()) exhausted else "$exhausted：$finalFindings",
                    )
                }
                val rewritten = generateCandidate(review.repairInstructions)
                if (rewritten.error != null) {
                    if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) {
                        return NovelWorkspaceBatchResult.Stopped(written)
                    }
                    return NovelWorkspaceBatchResult.Failed(written, rewritten.error)
                }
                continue
            }
            latest = NovelWorkspaceGhostwriteJobs.load(projectDirectory, job.id)
                ?: return NovelWorkspaceBatchResult.Stopped(written)
            if (latest.stage == NovelWorkspaceGhostwriteStage.Planning) {
                val planned = reviewer.planNextChapter(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = job.branchSlug,
                    settings = settings,
                    model = reviewModel,
                    ownerJobId = job.id,
                    ownerExecutionId = job.executionKey,
                    injection = injection,
                    locale = locale,
                    fallbackErrorMessage = fallbackErrorMessage,
                )
                if (planned.error != null) {
                    if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) {
                        return NovelWorkspaceBatchResult.Stopped(written)
                    }
                    return NovelWorkspaceBatchResult.Failed(written, planned.error)
                }
            }
            try {
                NovelWorkspaceReviewedCommits.commitReviewedChapter(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = job.branchSlug,
                    ownerJobId = job.id,
                    ownerExecutionId = job.executionKey,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return NovelWorkspaceBatchResult.Failed(written, error.message ?: fallbackErrorMessage)
            }
            val after = NovelWorkspaceGhostwriteJobs.progress(job, store)
            if (after <= before) {
                return NovelWorkspaceBatchResult.Failed(
                    written,
                    ghostwriteLocalized(
                        locale,
                        chinese = "章节已审核收录，但批次进度未推进",
                        english = "The reviewed chapter was committed, but batch progress did not advance.",
                    ),
                )
            }
            return NovelWorkspaceBatchResult.Completed(after)
        }
    }
}
