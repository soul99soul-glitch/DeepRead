package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceJointReviewResult
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Candidate-bound joint review and its optional next-plan turn share strict parsing rules. */
internal class NovelWorkspaceChapterReview(
    private val runtime: NovelWorkspaceRuntime,
    private val turnLauncher: NovelTurnLauncher,
) {
    @Serializable
    private data class NextPlanPayload(val nextPlan: String)

    /** Read-only, candidate-bound joint review with a hard 10k output-token floor. */
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
    ): NovelWorkspaceJointReviewTurnResult {
        val owned = NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            ownerExecutionId,
        ) { NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId) }
            ?: return NovelWorkspaceJointReviewTurnResult(
                error = ghostwriteLocalized(
                    locale,
                    chinese = "代笔已暂停、取消，或正文与确认计划的版本绑定已变化",
                    english = "The ghostwrite batch was paused or cancelled, or its manuscript/plan binding changed.",
                ),
            )
        val candidate = owned.pendingCandidate ?: return NovelWorkspaceJointReviewTurnResult(
            error = ghostwriteLocalized(
                locale,
                chinese = "没有可审核的章节候选",
                english = "There is no chapter candidate to review.",
            ),
        )
        owned.pendingReview?.takeIf { it.candidateId == candidate.id }?.let {
            return NovelWorkspaceJointReviewTurnResult(review = it)
        }
        val reviewSettings = settings.copy(maxTokens = maxOf(settings.maxTokens ?: 0, GHOSTWRITE_MIN_REVIEW_OUTPUT_TOKENS))
        val handle = turnLauncher.launch(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = branchSlug,
                userText = ghostwriteLocalized(
                    locale,
                    chinese = "请联合审核候选 ${candidate.id}，只返回严格 JSON。",
                    english = "Joint-review candidate ${candidate.id}; return strict JSON only.",
                ),
                systemPrompt = NovelWorkspacePrompts.jointGhostwriteReview(
                    candidate = candidate,
                    confirmedPlan = owned.confirmedPlan,
                    requiresNextPlan = NovelWorkspaceGhostwriteJobs.progress(
                        owned,
                        NovelWorkspaceStore(projectDirectory),
                    ) + 1 < owned.targetChapterCount,
                    locale = locale,
                ),
                settings = reviewSettings,
                model = model,
                maxSteps = 12,
                readOnlyTools = true,
                autoApproveCanon = false,
                injection = injection,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
                ghostwriteChapterOrdinal = candidate.chapterOrdinal,
                ghostwritePlanId = candidate.planId,
                ghostwritePlanDigest = candidate.planDigest,
                fallbackErrorMessage = fallbackErrorMessage,
                locale = locale,
            ),
            runtime,
        )
        val terminal = try {
            kotlinx.coroutines.withTimeout(REVIEW_TURN_TIMEOUT_MS) {
                handle.events.first { event ->
                    event is NovelWorkspaceRuntime.TurnEvent.Completed ||
                        event is NovelWorkspaceRuntime.TurnEvent.Failed
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            runCatching { handle.awaitTerminal() }
            return NovelWorkspaceJointReviewTurnResult(
                error = ghostwriteLocalized(
                    locale,
                    chinese = "联合审核超时，已中止本轮",
                    english = "The joint review timed out and was aborted.",
                ),
                retryable = true,
            )
        }
        (terminal as? NovelWorkspaceRuntime.TurnEvent.Failed)?.let {
            return NovelWorkspaceJointReviewTurnResult(error = it.message, retryable = true)
        }
        val raw = (terminal as NovelWorkspaceRuntime.TurnEvent.Completed).finalText.trim()
        val review = parseJointReview(raw) ?: return NovelWorkspaceJointReviewTurnResult(
            error = ghostwriteLocalized(
                locale,
                chinese = "联合审核未返回符合契约的严格 JSON",
                english = "The joint review did not return strict JSON matching the contract.",
            ),
        )
        val persisted = NovelWorkspaceGhostwriteJobs.recordReview(
            projectDirectory = projectDirectory,
            jobId = ownerJobId,
            executionId = ownerExecutionId,
            review = review,
        ) ?: return NovelWorkspaceJointReviewTurnResult(
            error = ghostwriteLocalized(
                locale,
                chinese = "联合审核关联字段无效，或批次版本绑定已变化",
                english = "The joint review correlation is invalid, or the batch binding changed.",
            ),
        )
        return NovelWorkspaceJointReviewTurnResult(review = persisted.pendingReview)
    }

    internal fun parseJointReview(text: String): NovelWorkspaceJointReviewResult? = runCatching {
        strictReviewJson.decodeFromString(
            NovelWorkspaceJointReviewResult.serializer(),
            text.trim(),
        )
    }.getOrNull()

    /** One read-only fallback planning turn when a passing review omitted nextPlan. */
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
    ): NovelWorkspaceNextPlanTurnResult {
        val owned = NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            ownerExecutionId,
        ) { NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId) }
            ?: return NovelWorkspaceNextPlanTurnResult(
                error = ghostwriteLocalized(
                    locale,
                    chinese = "代笔已暂停、取消，或正文与确认计划的版本绑定已变化",
                    english = "The ghostwrite batch was paused or cancelled, or its manuscript/plan binding changed.",
                ),
            )
        val candidate = owned.pendingCandidate
            ?: return NovelWorkspaceNextPlanTurnResult(error = ghostwriteLocalized(locale, "没有可规划后续的章节候选", "There is no chapter candidate to plan from."))
        val review = owned.pendingReview
            ?: return NovelWorkspaceNextPlanTurnResult(error = ghostwriteLocalized(locale, "没有可规划后续的联合审核结果", "There is no joint-review result to plan from."))
        review.nextPlan?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return NovelWorkspaceNextPlanTurnResult(nextPlan = it)
        }
        if (owned.stage != NovelWorkspaceGhostwriteStage.Planning ||
            NovelWorkspaceGhostwriteJobs.progress(owned, NovelWorkspaceStore(projectDirectory)) + 1 >=
            owned.targetChapterCount
        ) {
            return NovelWorkspaceNextPlanTurnResult(
                error = ghostwriteLocalized(locale, "当前批次不需要生成下一章计划", "This batch does not need a next-chapter plan."),
            )
        }
        val handle = turnLauncher.launch(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = branchSlug,
                userText = ghostwriteLocalized(
                    locale,
                    chinese = "请为第 ${candidate.chapterOrdinal + 1} 章生成计划，只返回严格 JSON。",
                    english = "Plan chapter ${candidate.chapterOrdinal + 1}; return strict JSON only.",
                ),
                systemPrompt = NovelWorkspacePrompts.nextGhostwritePlan(candidate, review, locale),
                settings = settings,
                model = model,
                maxSteps = 8,
                readOnlyTools = true,
                autoApproveCanon = false,
                injection = injection,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
                ghostwriteChapterOrdinal = candidate.chapterOrdinal,
                ghostwritePlanId = candidate.planId,
                ghostwritePlanDigest = candidate.planDigest,
                fallbackErrorMessage = fallbackErrorMessage,
                locale = locale,
            ),
            runtime,
        )
        val terminal = try {
            kotlinx.coroutines.withTimeout(PLANNING_TURN_TIMEOUT_MS) {
                handle.events.first { event ->
                    event is NovelWorkspaceRuntime.TurnEvent.Completed ||
                        event is NovelWorkspaceRuntime.TurnEvent.Failed
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            runCatching { handle.awaitTerminal() }
            return NovelWorkspaceNextPlanTurnResult(
                error = ghostwriteLocalized(locale, "下一章规划超时，已中止本轮", "Next-chapter planning timed out and was aborted."),
            )
        }
        (terminal as? NovelWorkspaceRuntime.TurnEvent.Failed)?.let {
            return NovelWorkspaceNextPlanTurnResult(error = it.message)
        }
        val nextPlan = parseNextPlan((terminal as NovelWorkspaceRuntime.TurnEvent.Completed).finalText)
            ?: return NovelWorkspaceNextPlanTurnResult(
                error = ghostwriteLocalized(
                    locale,
                    chinese = "下一章规划未返回符合契约的严格 JSON",
                    english = "Next-chapter planning did not return strict JSON matching the contract.",
                ),
            )
        val persisted = NovelWorkspaceGhostwriteJobs.recordPlannedNextPlan(
            projectDirectory = projectDirectory,
            jobId = ownerJobId,
            executionId = ownerExecutionId,
            candidateId = candidate.id,
            nextPlan = nextPlan,
        ) ?: return NovelWorkspaceNextPlanTurnResult(
            error = ghostwriteLocalized(
                locale,
                chinese = "下一章计划生成完成，但批次版本绑定已变化",
                english = "The next plan was generated, but the batch binding changed.",
            ),
        )
        return NovelWorkspaceNextPlanTurnResult(nextPlan = persisted.pendingReview?.nextPlan)
    }

    internal fun parseNextPlan(text: String): String? = runCatching {
        strictReviewJson.decodeFromString(NextPlanPayload.serializer(), text.trim()).nextPlan.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private val strictReviewJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }
}
