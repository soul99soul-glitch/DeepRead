package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteCandidate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceJointReviewResult
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import app.amber.feature.novelworkspace.sha256Hex
import java.io.File
import java.util.UUID

/** Reviewed chapter, plot, plan and receipt settlement share their crash-recovery rules. */
object NovelWorkspaceReviewedCommits {
    /**
     * The only Phase-2 path from a private candidate into canon. Chapter prose and the
     * reviewed post-chapter plot state share one Ledger commit and one undo record.
     */
    fun commitReviewedChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        ownerJobId: String,
        ownerExecutionId: String,
    ): NovelWorkspaceCommit {
        val committed = NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            ownerExecutionId,
        ) {
            val job = checkNotNull(NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId))
            val candidate = checkNotNull(job.pendingCandidate)
            val review = checkNotNull(job.pendingReview)
            check(job.stage == NovelWorkspaceGhostwriteStage.Committing &&
                !review.blocking && !review.rewriteRequired
            ) { "联合审核尚未通过" }
            check(job.branchId == branchId && job.branchSlug == branchSlug) { "代笔分支绑定已变化" }

            val store = NovelWorkspaceStore(projectDirectory)
            val chaptersPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"
            check(store.list(chaptersPrefix).none {
                NovelWorkspacePaths.chapterOrdinalFromPath(it) == candidate.chapterOrdinal
            }) { "目标章节已经存在" }
            val chapterPath = reviewedChapterPath(branchSlug, candidate)
            val plotPath = NovelWorkspacePaths.branchPrefix(branchSlug) + "/plot/current.md"
            val planPath = NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md"
            val finalChapter = NovelWorkspaceGhostwriteJobs.progress(job, store) + 1 >=
                job.targetChapterCount
            val nextPlanContent = if (finalChapter) null else reviewedNextPlanContent(candidate, review)
            val previous = linkedMapOf(
                chapterPath to store.read(chapterPath),
                plotPath to store.read(plotPath),
                planPath to store.read(planPath),
            )
            val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
            val ledgerBefore = NovelWorkspaceLedger.load(projectDirectory)
            check(ledgerBefore.heads[branchId] == job.expectedHeadId) { "代笔分支版本已变化" }
            val commitId = UUID.randomUUID().toString().uppercase()
            val undo = NovelWorkspaceUndoRecord(
                commitId = commitId,
                parentCommitId = job.expectedHeadId,
                files = previous,
                unresolvedBefore = unresolvedBefore,
                branchSlug = branchSlug,
            )
            val previousUndo = NovelWorkspaceUndo.load(projectDirectory)
            var ledgerPersisted = false
            NovelWorkspaceUndo.save(undo, projectDirectory)
            try {
                store.write(chapterPath, reviewedChapterContent(candidate))
                store.write(plotPath, reviewedPlotContent(store, plotPath, review))
                if (nextPlanContent == null) {
                    store.delete(planPath)
                } else {
                    store.write(planPath, nextPlanContent)
                }
                val commit = NovelWorkspaceCommitter.commitTree(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    message = NovelWorkspaceLedger.Message.GHOSTWRITE_REVIEWED,
                    commitId = commitId,
                    onLedgerPersisted = { ledgerPersisted = true },
                )
                checkNotNull(
                    NovelWorkspaceGhostwriteJobs.recordReviewedCommit(
                        projectDirectory = projectDirectory,
                        jobId = ownerJobId,
                        executionId = ownerExecutionId,
                        commitId = commit.id,
                        candidateId = candidate.id,
                    ),
                ) { "章节已收录，但代笔进度记账失败" }
                commit
            } catch (error: Exception) {
                if (!ledgerPersisted) {
                    previous.forEach { (path, content) ->
                        if (content == null) store.delete(path) else store.write(path, content)
                    }
                    store.materializeCheckout()
                    if (previousUndo == null) {
                        NovelWorkspaceUndo.clear(projectDirectory)
                    } else {
                        NovelWorkspaceUndo.save(previousUndo, projectDirectory)
                    }
                }
                throw error
            }
        }
        return committed ?: throw NovelWorkspaceIoError("代笔已暂停、取消，或正文版本已变化")
    }

    /**
     * Repair only the reviewed-commit crash window. A canonical matching Ledger commit
     * gets its job receipt back; pre-Ledger candidate/plot writes are restored from the
     * already-durable undo preimage. No model call is repeated here.
     */
    fun reconcileReviewedChapter(
        projectDirectory: File,
        ownerJobId: String,
        ownerExecutionId: String,
    ): Boolean = NovelWorkspaceRestoreBoundary.write {
        val job = NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId) ?: return@write false
        val candidate = job.pendingCandidate ?: return@write false
        val review = job.pendingReview ?: return@write false
        if (job.executionKey != ownerExecutionId ||
            job.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            job.stage != NovelWorkspaceGhostwriteStage.Committing ||
            review.blocking || review.rewriteRequired
        ) return@write false
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val headId = ledger.heads[job.branchId]
        if (headId != job.expectedHeadId) {
            val canonicalCommit = headId?.let(ledger::commit)
                ?: throw NovelWorkspaceIoError("代笔提交后的账本来源无法确认")
            if (store.fileTree() != canonicalCommit.files) {
                throw NovelWorkspaceIoError("代笔提交后的工作树已被其他修改改变")
            }
            return@write NovelWorkspaceGhostwriteJobs.recordReviewedCommit(
                projectDirectory = projectDirectory,
                jobId = ownerJobId,
                executionId = ownerExecutionId,
                commitId = canonicalCommit.id,
                candidateId = candidate.id,
            ) != null || throw NovelWorkspaceIoError("代笔提交后的账本来源无法确认")
        }

        val undo = NovelWorkspaceUndo.load(projectDirectory)
        val orphanUndo = undo?.takeIf {
            it.parentCommitId == job.expectedHeadId &&
                it.branchSlug == job.branchSlug &&
                ledger.commit(it.commitId) == null
        }
        val treeDigest = NovelWorkspaceLedger.treeSHA256(store.fileTree())
        if (treeDigest == job.expectedTreeDigest) {
            if (orphanUndo != null) NovelWorkspaceUndo.clear(projectDirectory)
            return@write false
        }
        val chapterPath = reviewedChapterPath(job.branchSlug, candidate)
        val plotPath = NovelWorkspacePaths.branchPrefix(job.branchSlug) + "/plot/current.md"
        val planPath = NovelWorkspacePaths.branchPrefix(job.branchSlug) + "/plan/this-chapter.md"
        val finalChapter = NovelWorkspaceGhostwriteJobs.progress(job, store) + 1 >=
            job.targetChapterCount
        val nextPlanContent = if (finalChapter) null else reviewedNextPlanContent(candidate, review)
        if (orphanUndo == null || orphanUndo.files.keys != setOf(chapterPath, plotPath, planPath)) {
            throw NovelWorkspaceIoError("代笔提交前的工作树已被其他修改改变")
        }
        val expectedWrites = mapOf(
            chapterPath to reviewedChapterContent(candidate),
            plotPath to reviewedPlotContentFromPrevious(orphanUndo.files[plotPath], review),
            planPath to nextPlanContent,
        )
        val targetFilesAreRecoverable = expectedWrites.all { (path, expectedContent) ->
            val currentContent = store.read(path)
            currentContent == orphanUndo.files[path] || currentContent == expectedContent
        }
        val restoredTree = store.fileTree().toMutableMap().apply {
            orphanUndo.files.forEach { (path, content) ->
                if (content == null) remove(path) else put(path, sha256Hex(content))
            }
        }
        if (!targetFilesAreRecoverable ||
            NovelWorkspaceLedger.treeSHA256(restoredTree) != job.expectedTreeDigest
        ) {
            throw NovelWorkspaceIoError("代笔提交前的工作树已被其他修改改变")
        }
        orphanUndo.files.forEach { (path, content) ->
            if (content == null) store.delete(path) else store.write(path, content)
        }
        store.materializeCheckout()
        NovelWorkspaceUndo.clear(projectDirectory)
        check(NovelWorkspaceLedger.treeSHA256(store.fileTree()) == job.expectedTreeDigest) {
            "代笔提交前状态恢复失败"
        }
        return@write false
    }

    private fun reviewedChapterPath(
        branchSlug: String,
        candidate: NovelWorkspaceGhostwriteCandidate,
    ): String {
        val slug = NovelWorkspaceSlug.slug(candidate.title).ifEmpty { "chapter" }
        return NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters/" +
            NovelWorkspacePaths.chapterFileName(candidate.chapterOrdinal, slug)
    }

    private fun reviewedChapterContent(candidate: NovelWorkspaceGhostwriteCandidate): String =
        NovelWorkspaceMarkdown.render(
            fields = listOf(
                "id" to UUID.nameUUIDFromBytes(
                    ("reviewed-chapter:" + candidate.id).toByteArray(Charsets.UTF_8),
                ).toString().uppercase(),
                "kind" to "chapter",
                "title" to candidate.title,
                "ordinal" to candidate.chapterOrdinal.toString(),
                "sourceCandidateId" to candidate.id,
                "sourcePlanId" to candidate.planId,
                "sourcePlanDigest" to candidate.planDigest,
            ),
            body = candidate.body,
        )

    private fun reviewedNextPlanContent(
        candidate: NovelWorkspaceGhostwriteCandidate,
        review: NovelWorkspaceJointReviewResult,
    ): String {
        val body = review.nextPlan?.trim().orEmpty()
        check(body.isNotEmpty()) { "联合审核没有提供下一章计划" }
        return NovelWorkspaceMarkdown.render(
            fields = listOf(
                "id" to NovelWorkspaceGhostwriteJobs.reviewedNextPlanId(candidate.id),
                "kind" to "plan",
                "title" to "本章计划",
                "status" to "confirmed",
            ),
            body = body,
        )
    }

    private fun reviewedPlotContent(
        store: NovelWorkspaceStore,
        plotPath: String,
        review: NovelWorkspaceJointReviewResult,
    ): String = reviewedPlotContentFromPrevious(store.read(plotPath), review)

    private fun reviewedPlotContentFromPrevious(
        previous: String?,
        review: NovelWorkspaceJointReviewResult,
    ): String {
        val parsed = previous?.let(NovelWorkspaceMarkdown::parseFile)
        val highlights = parsed?.body
            ?.let(NovelWorkspaceMarkdown::splitHighlights)
            ?.second
            .orEmpty() + review.chapterHighlight.trim()
        val body = buildString {
            append(review.plotState.trim())
            append("\n\n## 近期已写\n")
            highlights.filter { it.isNotBlank() }.forEach { append("- ").append(it).append('\n') }
        }.trim()
        return NovelWorkspaceMarkdown.render(
            fields = parsed?.fields?.toList() ?: listOf(
                "id" to UUID.nameUUIDFromBytes(
                    ("reviewed-plot:" + review.candidateId).toByteArray(Charsets.UTF_8),
                ).toString().uppercase(),
                "kind" to "plot",
                "title" to "当前剧情",
            ),
            aliases = parsed?.lists?.get("aliases").orEmpty(),
            body = body,
        )
    }

}
