package app.amber.feature.ui.pages.novel

import app.amber.ai.core.MessageRole
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import java.time.Instant

data class NovelMarkdownMessageUi(
    val id: String,
    val role: MessageRole,
    val content: String,
    val kind: String = "discussion",
    val createdAt: Instant = Instant.EPOCH,
)

data class NovelMarkdownChapterUi(
    val path: String,
    val title: String,
    val ordinal: Int,
    val charCount: Int,
)

data class NovelMarkdownDraftUi(
    val path: String,
    val title: String,
    val excerpt: String,
    val createdAt: Instant = Instant.EPOCH,
)

data class NovelMarkdownDiscardedUi(
    val path: String,
    val title: String,
    val ordinal: Int,
    val restoreBlockedReason: String?,
)

data class NovelMarkdownDiscardedSnapshotUi(
    val headId: String?,
    val treeDigest: String,
    val entries: List<NovelMarkdownDiscardedUi>,
)

data class NovelMarkdownWorkspaceUiState(
    val restoreVersion: Long = 0L,
    val loading: Boolean = true,
    val exists: Boolean = false,
    val title: String = "",
    /** 活跃分支 slug（.amber/branch.json 标记，缺失回退 manifest.mainBranch）。 */
    val branchSlug: String? = null,
    /** 分支列表（ledger heads + branches/ 目录，标当前），分支 sheet 数据源。 */
    val branches: List<NovelWorkspaceBranches.NovelWorkspaceBranchInfo> = emptyList(),
    val messages: List<NovelMarkdownMessageUi> = emptyList(),
    val chapters: List<NovelMarkdownChapterUi> = emptyList(),
    val drafts: List<NovelMarkdownDraftUi> = emptyList(),
    /** 设定 tab：设定文件分组 + 伏笔 + 决定（每次 commit 后与切分支后刷新）。 */
    val catalog: NovelWorkspaceCatalog.NovelWorkspaceCatalogData? = null,
    val streamingText: String = "",
    val reasoningText: String = "",
    val toolActivity: String? = null,
    val busy: Boolean = false,
    val proposals: List<NovelWorkspaceWriteProposal> = emptyList(),
    val plotStale: Boolean = false,
    /** D-D: editing a middle chapter invalidates this ordinal and after, until resolved. */
    val unresolvedFromOrdinal: Int? = null,
    /** Running/paused ghostwrite batch for this project, if any. */
    val ghostwriteJob: NovelMarkdownGhostwriteUi? = null,
    val unreadableJobFiles: List<String> = emptyList(),
    /** Per-project writing model override (null = follow global chat model). */
    val writingModelId: String? = null,
    /** Per-project review model override (null = follow the writing model). */
    val reviewModelId: String? = null,
    val ghostwriteMode: Boolean = false,
    /** Composer intent: 讨论 plans/world; 写正文 produces a collectable draft. */
    val composerMode: NovelMarkdownComposerMode = NovelMarkdownComposerMode.Discuss,
    /** Consistency review running / last report. */
    val consistencyChecking: Boolean = false,
    val consistencyCheckedChapters: Int = 0,
    val consistencyTotalChapters: Int = 0,
    val consistencyReport: String? = null,
    /** One-level undo available (the last canon commit can be rolled back). */
    val canUndo: Boolean = false,
    /** Which constraint sections the per-turn brief injects (ghostwrite panel). */
    val injection: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags =
        app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
    /** Bumped when the chapter plan changes outside the editor (auto-draft) so the sheet reloads. */
    val planAutoTick: Int = 0,
    val errorMessage: String? = null,
)

enum class NovelMarkdownComposerMode { Discuss, WriteProse }

data class NovelMarkdownGhostwriteUi(
    val jobId: String,
    val executionId: String,
    val branchSlug: String,
    val target: Int,
    val written: Int,
    /** First chapter ordinal owned by this batch (used by polish ranges). */
    val startOrdinal: Int,
    val status: String,
    /** Durable stage for the current chapter (writing/reviewing/rewriting/committing/planning). */
    val stage: NovelWorkspaceGhostwriteStage = NovelWorkspaceGhostwriteStage.Idle,
    /** 1-based chapter ordinal currently owned by the batch. */
    val currentChapterOrdinal: Int = 0,
    /** Targeted rewrite attempt for the current candidate (0..2). */
    val rewriteAttempt: Int = 0,
    /** Terminal-failure reason surfaced when status == failed. */
    val reason: String? = null,
    /** Batch kind: 代笔 writes new chapters, 润色 re-proses an existing range. */
    val mode: NovelWorkspaceGhostwriteMode = NovelWorkspaceGhostwriteMode.Write,
)


internal fun novelMarkdownGhostwriteUi(
    job: app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob,
    written: Int,
    reason: String? = job.reason,
) = NovelMarkdownGhostwriteUi(
    jobId = job.id,
    executionId = job.executionKey,
    branchSlug = job.branchSlug,
    target = job.targetChapterCount,
    written = written,
    startOrdinal = job.startOrdinal,
    status = job.status,
    stage = job.stage,
    currentChapterOrdinal = job.currentChapterOrdinal,
    rewriteAttempt = job.rewriteAttempt,
    reason = reason,
    mode = job.mode,
)
