package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import app.amber.feature.novelworkspace.sha256Hex
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Concrete batch creation and freshness preflight; Jobs remains the durable CAS owner. */
object NovelWorkspaceBatchJobs {
    const val MAX_GHOSTWRITE_CHAPTERS = 10

    /**
     * Validate + heal + claim a polish batch — everything
     * the Android batch controller does before enqueueing work. The same local owner
     * is used by the worker and JVM fixtures. Order matters: the dangling-pointer
     * self-heal runs FIRST, because a crash between 润色 and its pairing commit is the
     * one stale-plot shape a polish batch can cure by itself; every other stale shape
     * (and a failed repair) still hits the unchanged freshness refusal below.
     */
    fun preparePolishBatch(
        projectDirectory: File,
        branchSlug: String,
        fromOrdinal: Int,
        toOrdinal: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob = NovelWorkspaceGhostwriteJobs.withNoActiveBranch(projectDirectory, branchSlug) {
        require(fromOrdinal in 1..toOrdinal) {
            localized(locale, "润色范围无效", "The polishing range is invalid.")
        }
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        if (NovelWorkspaceLedger.isPlotStale(store, ledger, branchSlug)) {
            NovelWorkspaceChapterCommits.repairDanglingPolishPointer(projectDirectory, branchSlug)
        }
        val healed = NovelWorkspaceLedger.load(projectDirectory)
        check(!NovelWorkspaceLedger.isPlotStale(store, healed, branchSlug)) {
            localized(
                locale,
                "剧情落后于正文。请切到“讨论”，发送“根据最新正文同步 plot/current.md”，并批准剧情修改后再润色",
                "The plot is behind the manuscript. Switch to Discussion, send “Sync plot/current.md from the latest manuscript”, approve the plot changes, then polish again.",
            )
        }
        check(NovelWorkspaceUnresolvedStore.entryFor(projectDirectory, branchSlug) == null) {
            localized(
                locale,
                "存在未解决的中间章修改，请先处理（确认无碍/重写后章）再润色",
                "Unresolved middle-chapter edits exist. Resolve them (confirm safe or rewrite later chapters) before polishing.",
            )
        }
        val existing = NovelWorkspaceLedger.workingChapterOrdinals(store, branchSlug).toSet()
        val missing = (fromOrdinal..toOrdinal).firstOrNull { it !in existing }
        check(missing == null) {
            localized(locale, "第 $missing 章不存在，无法润色", "Chapter $missing does not exist and cannot be polished.")
        }
        newPolishJob(projectDirectory, branchSlug, fromOrdinal, toOrdinal, locale)
    } ?: error(localized(locale, "已有批次占用当前分支，请先继续或取消该批次", "A batch already occupies this branch. Resume or cancel it first."))

    /** Create a new batch job (cursor = current manuscript state). */
    fun newJob(
        projectDirectory: File,
        branchSlug: String,
        targetChapterCount: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob {
        require(targetChapterCount in 1..MAX_GHOSTWRITE_CHAPTERS) {
            localized(
                locale,
                "代笔章数必须在 1 到 $MAX_GHOSTWRITE_CHAPTERS 之间",
                "The ghostwrite target must be between 1 and $MAX_GHOSTWRITE_CHAPTERS chapters.",
            )
        }
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val branchId = checkNotNull(NovelWorkspaceLedger.branchId(store, ledger, branchSlug)) {
            localized(locale, "当前分支不存在", "The current branch does not exist.")
        }
        val planRaw = store.read(NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md")
            ?: ""
        val parsedPlan = NovelWorkspaceMarkdown.parseFile(planRaw)
        val confirmedPlan = parsedPlan.body.trim()
        check(confirmedPlan.isNotBlank()) {
            localized(
                locale,
                "请先填写或生成本章计划，再开始代笔",
                "Create or enter the chapter plan before starting ghostwriting.",
            )
        }
        val planDigest = sha256Hex(confirmedPlan)
        val planId = parsedPlan.fields["id"]?.takeIf { it.isNotBlank() }
            ?: "PLAN-${planDigest.take(16).uppercase()}"
        val startOrdinal = NovelWorkspaceLedger.committedChapterOrdinals(
            store,
            ledger,
            branchSlug,
        ).maxOrNull() ?: 0
        val headId = ledger.heads[branchId]
        val treeDigest = NovelWorkspaceLedger.treeSHA256(store.fileTree())
        val jobId = UUID.randomUUID().toString().uppercase()
        val job = NovelWorkspaceGhostwriteJob(
            id = jobId,
            executionId = jobId,
            branchSlug = branchSlug,
            targetChapterCount = targetChapterCount,
            startOrdinal = startOrdinal,
            branchId = branchId,
            baseHeadId = headId,
            expectedHeadId = headId,
            baseTreeDigest = treeDigest,
            expectedTreeDigest = treeDigest,
            planId = planId,
            planDigest = planDigest,
            confirmedPlan = confirmedPlan,
            currentChapterOrdinal = startOrdinal + 1,
            stage = app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage.Writing,
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        check(NovelWorkspaceGhostwriteJobs.saveIfNoActive(job, projectDirectory)) {
            localized(locale, "已有代笔批次占用当前分支，请先继续或取消该批次", "A ghostwrite batch already occupies this branch. Resume or cancel it first.")
        }
        return job
    }

    /**
     * Create a new batch polish job over the inclusive ordinal range
     * [fromOrdinal, toOrdinal]. Range validation (chapters exist, freshness gates,
     * dangling-pointer self-heal) happens in [preparePolishBatch]; the branch claim is
     * shared with Write mode, so a polish batch and a ghostwrite batch can never own
     * the same branch at the same time.
     */
    fun newPolishJob(
        projectDirectory: File,
        branchSlug: String,
        fromOrdinal: Int,
        toOrdinal: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob {
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, branchSlug)
            ?: error(localized(locale, "当前分支没有可用的账本绑定", "The current branch has no ledger binding."))
        val jobId = UUID.randomUUID().toString().uppercase()
        val job = NovelWorkspaceGhostwriteJob(
            id = jobId,
            executionId = jobId,
            branchSlug = branchSlug,
            targetChapterCount = toOrdinal - fromOrdinal + 1,
            startOrdinal = fromOrdinal,
            endOrdinal = toOrdinal,
            mode = NovelWorkspaceGhostwriteMode.Polish,
            polishUsesCommitProvenance = true,
            branchId = branchId,
            currentChapterOrdinal = fromOrdinal,
            stage = NovelWorkspaceGhostwriteStage.Writing,
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        check(NovelWorkspaceGhostwriteJobs.saveIfNoActive(job, projectDirectory)) {
            localized(locale, "已有批次占用当前分支，请先继续或取消该批次", "A batch already occupies this branch. Resume or cancel it first.")
        }
        return job
    }

    fun saveJob(job: NovelWorkspaceGhostwriteJob, projectDirectory: File) {
        NovelWorkspaceGhostwriteJobs.save(job.copy(updatedAt = Instant.now()), projectDirectory)
    }

    private fun localized(locale: Locale, chinese: String, english: String): String =
        if (locale.language.equals("zh", ignoreCase = true)) chinese else english
}
