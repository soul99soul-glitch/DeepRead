package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Owns only sequential batch progress, cooperative stop, retry and polish-pointer pairing. */
internal class NovelWorkspaceBatchRunner(
    private val generator: NovelWorkspaceChapterGenerator,
    private val writeWorkflow: NovelWorkspaceWriteChapterWorkflow,
    private val polisher: NovelWorkspacePolisher,
) {
    /**
     * Run a batch, committing each finished chapter. Version-bound write progress is
     * restored from durable receipts; legacy write and polish keep their existing
     * commit-derived cursor. One loop skeleton serves both modes:
     * - Write: private candidate -> joint review -> one host-owned canonical commit.
     * - Polish: next target = startOrdinal + progress (polish turn + host 剧情指针
     *   commit; see [NovelWorkspaceRuntime.commitPolishPointer] for why the pairing
     *   commit exists — the batch must end NOT plot-stale).
     * Sequential chapters, cooperative pause/cancel, executionId token rotation and
     * the single-retry policy are identical across modes on purpose.
     */
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
    ): NovelWorkspaceBatchResult {
        val store = NovelWorkspaceStore(projectDirectory)
        val polish = job.mode == NovelWorkspaceGhostwriteMode.Polish
        var written = NovelWorkspaceGhostwriteJobs.progress(job, store)
        var noProgressStreak = 0
        try {
            // Polish invariant repair: a crash (or pointer failure + restart) between a
            // 润色 commit and its 剧情指针 commit would leave chapters/ newer than plot/.
            // Re-emit the pointer for the last finished chapter before continuing.
            if (polish && written > 0 &&
                NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(projectDirectory), job.branchSlug)
            ) {
                try {
                    NovelWorkspaceGhostwriteJobs.withRunningOwner(projectDirectory, job.id, job.executionKey) {
                        NovelWorkspaceChapterCommits.repairDanglingPolishPointer(projectDirectory, job.branchSlug)
                        true
                    } ?: return NovelWorkspaceBatchResult.Stopped(written)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return NovelWorkspaceBatchResult.Failed(written, polishPointerCommitFailure(error))
                }
            }
            if (polish && NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(projectDirectory), job.branchSlug)) {
                return NovelWorkspaceBatchResult.Failed(written, ghostwriteLocalized(locale, "剧情落后于正文，请先更新剧情再继续润色", "The plot is behind the manuscript. Update it before continuing to polish."))
            }
            while (written < job.targetChapterCount) {
                if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) return NovelWorkspaceBatchResult.Stopped(written)
                // D-D: never touch chapters while a middle-chapter edit is unresolved.
                if (NovelWorkspaceUnresolvedStore.entryFor(projectDirectory, job.branchSlug) != null) {
                    return NovelWorkspaceBatchResult.Failed(
                        written,
                        if (polish) {
                            ghostwriteLocalized(
                                locale,
                                chinese = "存在未解决的中间章修改，请先处理再润色",
                                english = "Unresolved middle-chapter edits exist. Resolve them before polishing.",
                            )
                        } else {
                            ghostwriteLocalized(
                                locale,
                                chinese = "存在未解决的中间章修改，请先处理再代笔",
                                english = "Unresolved middle-chapter edits exist. Resolve them before ghostwriting.",
                            )
                        },
                    )
                }
                val before = written
                val nextOrdinal = if (polish) {
                    job.startOrdinal + written
                } else {
                    (NovelWorkspaceLedger.committedChapterOrdinals(
                        store,
                        NovelWorkspaceLedger.load(projectDirectory),
                        job.branchSlug,
                    ).maxOrNull() ?: 0) + 1
                }
                if (!polish && job.isVersionBound) {
                    when (val result = writeWorkflow.advanceChapter(
                        job, projectDirectory, branchId, settings, model, reviewModel,
                        written, nextOrdinal, isPaused, injection, locale, fallbackErrorMessage,
                    )) {
                        is NovelWorkspaceBatchResult.Completed -> {
                            written = result.chaptersWritten
                            onChapter(written)
                        }
                        is NovelWorkspaceBatchResult.Failed -> return result
                        is NovelWorkspaceBatchResult.Stopped -> return result
                    }
                    continue
                }
                var chapter = if (polish) {
                    polisher.runChapter(
                        maxSteps = 24,
                        reviewModel = reviewModel,
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
                    )
                } else {
                    generator.ghostwriteOneChapter(
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
                    )
                }
                // One retry absorbs transient provider blips (rate limit, dropped
                // connection); a second failure ends the batch with the error surfaced.
                if (chapter.error != null && chapter.retryable) {
                    if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) return NovelWorkspaceBatchResult.Stopped(written)
                    delay(CHAPTER_RETRY_DELAY_MS)
                    // Re-check after the backoff: pause during the wait must not
                    // still run a full turn (device-observed review finding).
                    if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) return NovelWorkspaceBatchResult.Stopped(written)
                    chapter = if (polish) {
                        polisher.runChapter(
                            maxSteps = 24,
                            reviewModel = reviewModel,
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
                        )
                    } else {
                        generator.ghostwriteOneChapter(
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
                        )
                    }
                }
                if (chapter.error != null) {
                    if (isPaused() || isGhostwriteCancelled(job, projectDirectory)) return NovelWorkspaceBatchResult.Stopped(written)
                    return NovelWorkspaceBatchResult.Failed(written, chapter.error)
                }
                // Polish freshness pairing: a round that actually landed a 润色 commit
                // (tool path or host-write fallback, chapter.commitId != null) is
                // immediately followed by its 剧情指针 commit so plot staleness never
                // opens mid-batch. A no-output round (模型寒暄：chapter.error == null 且
                // 没有任何润色落盘) must NOT emit a pointer — chapters/ 未动，剧情并未
                // 落后，补指针只会在账本里造出虚假的「第 N 章已润色」记录；该轮由下方
                // no-progress guard 计数并最终终止批次。润色 commit 与指针 commit 之间
                // 的暂停/取消/崩溃窗口不因本条件扩大：指针紧跟润色同步发出，悬挂形状
                // 由 runBatch 入口补发 + repairDanglingPolishPointer 自愈兜底。
                if (polish && chapter.commitId != null) {
                    try {
                        NovelWorkspaceGhostwriteJobs.withRunningOwner(projectDirectory, job.id, job.executionKey) {
                            check(NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.id == chapter.commitId) {
                                "润色收录后正文版本已变化，不能更新剧情指针"
                            }
                            NovelWorkspaceChapterCommits.commitPolishPointer(projectDirectory, branchId, job.branchSlug, nextOrdinal)
                        } ?: return NovelWorkspaceBatchResult.Stopped(written)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        return NovelWorkspaceBatchResult.Failed(written, polishPointerCommitFailure(error))
                    }
                }
                // No-progress guard: a turn that didn't finish a chapter must not spin forever.
                val after = NovelWorkspaceGhostwriteJobs.progress(job, store)
                if (after > before) {
                    noProgressStreak = 0
                } else {
                    noProgressStreak += 1
                    if (noProgressStreak >= MAX_NO_PROGRESS_TURNS) {
                        return NovelWorkspaceBatchResult.Failed(
                            written,
                            if (polish) {
                                ghostwriteLocalized(
                                    locale,
                                    chinese = "连续 $MAX_NO_PROGRESS_TURNS 轮未完成章节润色，已停止避免空转；请检查模型是否按要求写回润色正文",
                                    english = "No chapter was polished in $MAX_NO_PROGRESS_TURNS consecutive turns. Stopped to avoid spinning; check that the model writes the polished prose as requested.",
                                )
                            } else {
                                ghostwriteLocalized(
                                    locale,
                                    chinese = "连续 $MAX_NO_PROGRESS_TURNS 轮未产出新章节，已停止避免空转；请检查模型是否按要求写正文",
                                    english = "No new chapter was produced in $MAX_NO_PROGRESS_TURNS consecutive turns. Stopped to avoid spinning; check that the model writes the chapter as requested.",
                                )
                            },
                        )
                    }
                }
                written = after
                onChapter(written)
            }
        } catch (error: CancellationException) {
            throw error
        }
        return NovelWorkspaceBatchResult.Completed(written)
    }

}
