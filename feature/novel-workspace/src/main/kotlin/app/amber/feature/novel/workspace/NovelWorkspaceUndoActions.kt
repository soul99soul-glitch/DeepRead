package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifest
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File

/** Branch-bound single-level undo; the durable record stays in NovelWorkspaceUndo. */
object NovelWorkspaceUndoActions {
    /**
     * 兼容旧单参调用：按当前活跃分支（.amber/branch.json 标记，回退主线）判定。
     * 多分支代码路径应使用 [canUndo] 的双参形式显式传入分支。
     */
    fun canUndo(projectDirectory: File): Boolean =
        canUndo(projectDirectory, app.amber.feature.novelworkspace.NovelWorkspaceBranches.activeSlug(projectDirectory))

    /**
     * True when a one-level undo is available for THIS branch: the undo record must
     * belong to [branchSlug]（书级 undo.json 的分支绑定——分支不匹配一律视为无 undo，
     * 防 cross-branch 恢复写坏数据；历史 null 记录按主线解释，见 NovelWorkspaceUndoRecord）
     * and match the branch's own head commit.
     */
    fun canUndo(projectDirectory: File, branchSlug: String): Boolean {
        val undo = NovelWorkspaceUndo.load(projectDirectory) ?: return false
        if (!undoBelongsToBranch(undo, projectDirectory, branchSlug)) return false
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val store = NovelWorkspaceStore(projectDirectory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, branchSlug) ?: return false
        return ledger.heads[branchId] == undo.commitId
    }

    /** Legacy (null-slug) records can only have come from the main branch. */
    private fun undoBelongsToBranch(
        undo: NovelWorkspaceUndoRecord,
        projectDirectory: File,
        branchSlug: String,
    ): Boolean {
        val recordBranch = undo.branchSlug ?: NovelWorkspaceManifest.parse(
            NovelWorkspaceStore(projectDirectory).read(NovelWorkspacePaths.MANIFEST) ?: "",
        ).mainBranch
        return recordBranch == branchSlug
    }

    /** 兼容旧单参调用：对当前活跃分支执行撤销（见 [undoLast] 双参形式）。 */
    fun undoLast(projectDirectory: File): Boolean =
        undoLast(projectDirectory, app.amber.feature.novelworkspace.NovelWorkspaceBranches.activeSlug(projectDirectory))

    /**
     * 撤销最近一笔: restore the previous contents captured at the last commit, move the
     * BRANCH head back, drop the undone commit, clear the undo record, refresh checkout.
     * Only the undo record's branch head commit can be undone (single level) and the
     * record must belong to [branchSlug] — an undo saved on another branch is invisible
     * here (mismatch = no undo; see [canUndo]).
     *
     * 多分支 fork 安全：被撤销 commit 若仍是其他分支的 head（或更后 commit 的 parent，
     * 即分叉点），则只把本分支 head 退回、commit 本体保留在链上；仅当无任何引用时才
     * 从 commits 中删除。全局 head 只在恰好镜像本分支时随动。
     */
    fun undoLast(projectDirectory: File, branchSlug: String): Boolean = NovelWorkspaceRestoreBoundary.write {
        NovelWorkspaceCommitter.assertNoGhostwriteOwner(projectDirectory)
        val undo = NovelWorkspaceUndo.load(projectDirectory) ?: return@write false
        if (!undoBelongsToBranch(undo, projectDirectory, branchSlug)) return@write false
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, branchSlug) ?: return@write false
        if (ledger.heads[branchId] != undo.commitId) return@write false
        for ((path, previous) in undo.files) {
            if (previous == null) store.delete(path) else store.write(path, previous)
        }
        val stillReferenced = ledger.commits.any { it.parentId == undo.commitId } ||
            ledger.heads.any { (branch, headId) -> branch != branchId && headId == undo.commitId }
        // The undone branch head moves to the commit's parent; a branch-root undo
        // removes that head entry entirely.
        val updatedHeads = if (undo.parentCommitId != null) {
            ledger.heads + (branchId to undo.parentCommitId!!)
        } else {
            ledger.heads - branchId
        }
        val updated = ledger.copy(
            head = if (ledger.head == undo.commitId) undo.parentCommitId else ledger.head,
            heads = updatedHeads,
            commits = if (stillReferenced) {
                ledger.commits
            } else {
                ledger.commits.filterNot { it.id == undo.commitId }
            },
        )
        NovelWorkspaceLedger.save(updated, projectDirectory)
        store.materializeCheckout()
        val unresolvedBefore = undo.unresolvedBefore
        if (unresolvedBefore != null) {
            NovelWorkspaceUnresolvedStore.save(unresolvedBefore, projectDirectory)
        } else {
            // Legacy undo records predate unresolvedBefore. Only remove the gate
            // created by the commit being undone; preserve unrelated older gates.
            val current = NovelWorkspaceUnresolvedStore.load(projectDirectory)
            val restored = current.copy(
                branches = current.branches.filterValues { it.sinceCommitId != undo.commitId },
            )
            if (restored != current) NovelWorkspaceUnresolvedStore.save(restored, projectDirectory)
        }
        NovelWorkspaceUndo.clear(projectDirectory)
        return@write true
    }

}
