package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteCandidate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceJointReviewResult
import java.io.File
import java.util.Locale

data class NovelWorkspaceChapterResult(
    val commitId: String?,
    val candidate: NovelWorkspaceGhostwriteCandidate? = null,
    val error: String? = null,
    val retryable: Boolean = true,
)

data class NovelWorkspaceJointReviewTurnResult(
    val review: NovelWorkspaceJointReviewResult? = null,
    val error: String? = null,
    val retryable: Boolean = false,
)

data class NovelWorkspaceNextPlanTurnResult(
    val nextPlan: String? = null,
    val error: String? = null,
)

sealed interface NovelWorkspaceBatchResult {
    data class Completed(val chaptersWritten: Int) : NovelWorkspaceBatchResult
    data class Failed(val chaptersWritten: Int, val error: String) : NovelWorkspaceBatchResult
    data class Stopped(val chaptersWritten: Int) : NovelWorkspaceBatchResult
}

internal const val MAX_NO_PROGRESS_TURNS = 2
internal const val CHAPTER_RETRY_DELAY_MS = 15_000L
internal const val CHAPTER_TURN_TIMEOUT_MS = 8 * 60_000L
internal const val REVIEW_TURN_TIMEOUT_MS = 8 * 60_000L
internal const val PLANNING_TURN_TIMEOUT_MS = 8 * 60_000L
internal const val GHOSTWRITE_MIN_REVIEW_OUTPUT_TOKENS = 10_000
internal const val MIN_HOSTWRITE_CHARS = 500
internal const val GHOSTWRITE_POINTER_COMMIT_FAILED = "润色指针提交失败"

internal fun isGhostwriteCancelled(job: NovelWorkspaceGhostwriteJob, projectDirectory: File): Boolean =
    NovelWorkspaceGhostwriteJobs.load(projectDirectory, job.id)?.let {
        it.status == NovelWorkspaceGhostwriteJob.STATUS_CANCELLED ||
            it.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED ||
            it.executionKey != job.executionKey
    } ?: true

internal fun ghostwriteLocalized(locale: Locale, chinese: String, english: String): String =
    if (locale.language.equals("zh", ignoreCase = true)) chinese else english

internal fun ghostwriteOwnerError(
    error: Exception,
    locale: Locale,
    fallbackErrorMessage: String,
    chinese: String,
    english: String,
): String = if (error.message == chinese) {
    ghostwriteLocalized(locale, chinese, english)
} else {
    error.message ?: fallbackErrorMessage
}

internal fun polishPointerCommitFailure(error: Exception): String =
    "$GHOSTWRITE_POINTER_COMMIT_FAILED：${error.message ?: "未知错误"}"
