package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** Host-owned chapter writes and their paired plot-pointer commits. */
object NovelWorkspaceChapterCommits {
    private const val POLISH_POINTER_LINE_PREFIX = "（润色指针："

    /**
     * Ghostwrite host-write path: the model returned the chapter as plain text
     * (device-proven: several providers narrate instead of calling the write
     * tool), so the host files it into the manuscript and commits — same shape
     * as a collected draft. Undo captures pre-write state like collectDraft.
     */
    fun commitGhostwrittenChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterOrdinal: Int,
        title: String,
        body: String,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
    ): NovelWorkspaceCommit = NovelWorkspaceRestoreBoundary.write {
        val commitBlock = commitBlock@{
            val store = NovelWorkspaceStore(projectDirectory)
            val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
            val chaptersPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"
            val leafSlug = NovelWorkspaceSlug.slug(title).ifEmpty { "chapter" }
            val path = "$chaptersPrefix/" + NovelWorkspacePaths.chapterFileName(chapterOrdinal, leafSlug)
            val previous = mapOf(path to store.read(path))
            var ledgerPersisted = false
            var accountingPersisted = false
            try {
                store.write(
                    path,
                    NovelWorkspaceMarkdown.render(
                        fields = listOf(
                            "id" to UUID.randomUUID().toString().uppercase(),
                            "kind" to "chapter",
                            "title" to title,
                            "ordinal" to chapterOrdinal.toString(),
                        ),
                        body = body,
                    ),
                )
                val commit = NovelWorkspaceCommitter.commitTree(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    message = NovelWorkspaceLedger.Message.COLLECTION,
                    onLedgerPersisted = { ledgerPersisted = true },
                )
                if (ownerJobId != null) {
                    val currentJob = checkNotNull(NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId))
                    if (currentJob.isVersionBound) {
                        checkNotNull(
                            NovelWorkspaceGhostwriteJobs.recordWriteCommit(
                                projectDirectory = projectDirectory,
                                jobId = ownerJobId,
                                executionId = checkNotNull(ownerExecutionId),
                                commitId = commit.id,
                                chapterOrdinal = chapterOrdinal,
                                planId = currentJob.planId,
                                planDigest = currentJob.planDigest,
                            ),
                        ) { "代笔版本绑定已变化" }
                    }
                }
                accountingPersisted = true
                NovelWorkspaceUndo.save(
                    NovelWorkspaceUndoRecord(
                        commitId = commit.id,
                        parentCommitId = commit.parentId,
                        files = previous,
                        unresolvedBefore = unresolvedBefore,
                        branchSlug = branchSlug,
                    ),
                    projectDirectory,
                )
                commit
            } catch (error: Exception) {
                if (ledgerPersisted && accountingPersisted) {
                    NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.let {
                        return@commitBlock it
                    }
                }
                if (ledgerPersisted) throw error
                for ((changedPath, oldContent) in previous) {
                    if (oldContent == null) store.delete(changedPath) else store.write(changedPath, oldContent)
                }
                throw error
            }
        }
        if (ownerJobId == null) return@write commitBlock()
        return@write NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            checkNotNull(ownerExecutionId),
            commitBlock,
        ) ?: throw NovelWorkspaceIoError("代笔已暂停或取消")
    }

    /**
     * Polish host-write path: the model returned the polished chapter as plain text
     * instead of calling the write tool, so the host replaces the chapter body and
     * commits. Chapter identity (id/ordinal/title front matter) stays host-owned —
     * polish must not rename or renumber a chapter — unlike [commitGhostwrittenChapter],
     * which files a NEW chapter. Message 「润色」; exempt from the D-D unresolved gate
     * (facts unchanged). Owner-gated exactly like the ghostwrite host-write path.
     */
    fun commitPolishedChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
        polishedBody: String,
        expectedHeadId: String?,
        expectedTreeDigest: String,
        expectedChapterContent: String,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
    ): NovelWorkspaceCommit = NovelWorkspaceRestoreBoundary.write {
        val commitBlock = commitBlock@{
            val store = NovelWorkspaceStore(projectDirectory)
            if (!chapterPath.startsWith(NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters/") ||
                NovelWorkspacePaths.chapterOrdinalFromPath(chapterPath) == null
            ) throw NovelWorkspaceIoError("润色目标必须是当前分支已有章节")
            val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
            val existing = store.read(chapterPath)
                ?: throw NovelWorkspaceIoError("章节不存在：$chapterPath")
            val currentLedger = NovelWorkspaceLedger.load(projectDirectory)
            if (currentLedger.headOf(branchId)?.id != expectedHeadId ||
                NovelWorkspaceLedger.treeSHA256(store.fileTree()) != expectedTreeDigest ||
                existing != expectedChapterContent
            ) throw NovelWorkspaceIoError("正文版本已变化，请重新生成润色候选")
            val previous = mapOf(chapterPath to existing)
            var ledgerPersisted = false
            try {
                store.write(
                    chapterPath,
                    NovelWorkspaceMarkdown.withBody(existing, polishedBody),
                )
                val commit = NovelWorkspaceCommitter.commitTree(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    message = NovelWorkspaceLedger.Message.POLISH,
                    armsUnresolvedGate = false,
                    polishJobId = ownerJobId,
                    polishChapterOrdinal = NovelWorkspacePaths.chapterOrdinalFromPath(chapterPath),
                    onLedgerPersisted = { ledgerPersisted = true },
                )
                NovelWorkspaceUndo.save(
                    NovelWorkspaceUndoRecord(
                        commitId = commit.id,
                        parentCommitId = commit.parentId,
                        files = previous,
                        unresolvedBefore = unresolvedBefore,
                        branchSlug = branchSlug,
                    ),
                    projectDirectory,
                )
                commit
            } catch (error: Exception) {
                if (ledgerPersisted) {
                    NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.let {
                        return@commitBlock it
                    }
                    throw error
                }
                for ((changedPath, oldContent) in previous) {
                    if (oldContent == null) store.delete(changedPath) else store.write(changedPath, oldContent)
                }
                throw error
            }
        }
        if (ownerJobId == null) {
            NovelWorkspaceCommitter.assertNoGhostwriteOwner(projectDirectory, branchSlug)
            return@write commitBlock()
        }
        return@write NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            checkNotNull(ownerExecutionId),
            commitBlock,
        ) ?: throw NovelWorkspaceIoError("润色已暂停或取消")
    }

    /**
     * 剧情指针 commit after a polished chapter — the D-C freshness pairing that keeps a
     * polish batch from flipping `isPlotStale`.
     *
     * Why the host does this instead of the model: `isPlotStale` compares the newest
     * commit that CHANGED chapters/ against the newest commit that CHANGED plot/. A
     * 润色 commit changes only chapters/, so without a pairing commit every polish batch
     * would end plot-stale even though no story fact moved; relying on the model to
     * touch plot/current.md in the same turn (the write-mode D-C pattern) is not
     * something polish may ask for — the plot text itself must stay untouched. The host
     * therefore appends a single, self-replacing pointer line to plot/current.md and
     * commits it with the existing 「剧情指针」 convention (the same 收录→剧情指针 sequence
     * the legacy engine used for batch runs): after each chapter the commit chain reads
     * 润色 → 剧情指针, so plot freshness always trails or matches chapters/ and the batch
     * ends NOT stale. The line is plain prose (no front matter), documents that prose
     * moved without plot movement, and never accumulates.
     */
    fun commitPolishPointer(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterOrdinal: Int,
    ): NovelWorkspaceCommit = NovelWorkspaceRestoreBoundary.write {
        val store = NovelWorkspaceStore(projectDirectory)
        val plotPath = NovelWorkspacePaths.branchPrefix(branchSlug) + "/plot/current.md"
        val pointerLine = "（润色指针：第 $chapterOrdinal 章文字已润色，剧情与事实未变。）"
        val existing = store.read(plotPath)
        val rendered = if (existing == null) {
            // Plotless book: create the pointer file so freshness has a plot side at all.
            NovelWorkspaceMarkdown.render(
                fields = listOf(
                    "id" to UUID.randomUUID().toString().uppercase(),
                    "kind" to "plot",
                    "title" to "当前剧情",
                ),
                body = pointerLine,
            )
        } else {
            val parsed = NovelWorkspaceMarkdown.parseFile(existing)
            val kept = parsed.body
                .lines()
                .filterNot { it.trimStart().startsWith(POLISH_POINTER_LINE_PREFIX) }
                .joinToString("\n")
                .trimEnd()
            NovelWorkspaceMarkdown.render(
                fields = parsed.fields.toList(),
                aliases = parsed.lists["aliases"].orEmpty(),
                body = (if (kept.isEmpty()) "" else "$kept\n\n") + pointerLine,
            )
        }
        store.write(plotPath, rendered)
        return@write NovelWorkspaceCommitter.commitTree(
            projectDirectory = projectDirectory,
            branchId = branchId,
            branchSlug = branchSlug,
            message = NovelWorkspaceLedger.Message.PLOT_POINTER,
        )
    }

    /**
     * Crash-window self-heal for the polish freshness pairing（润色 → 剧情指针）. If the
     * process died between a 润色 commit and its pairing pointer commit, the branch reads
     * plot-stale while the only missing piece is that pointer — the stale message telling
     * the user to sync plot/current.md would be misleading and the gate a permanent
     * dead-end (the job behind the window is already terminal). Re-emits the pointer for
     * the dangling chapter. Returns the healed ordinal, or null when the staleness has
     * any other shape (manual edit / collection — a real plot gap) or the repair itself
     * failed; the caller's normal freshness gate then keeps refusing as before.
     */
    fun repairDanglingPolishPointer(projectDirectory: File, branchSlug: String): Int? {
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val dangling = NovelWorkspaceLedger.danglingPolishChapterOrdinal(
            NovelWorkspaceStore(projectDirectory),
            ledger,
            branchSlug,
        ) ?: return null
        val store = NovelWorkspaceStore(projectDirectory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, branchSlug) ?: return null
        return try {
            commitPolishPointer(projectDirectory, branchId, branchSlug, dangling)
            dangling
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }
}
