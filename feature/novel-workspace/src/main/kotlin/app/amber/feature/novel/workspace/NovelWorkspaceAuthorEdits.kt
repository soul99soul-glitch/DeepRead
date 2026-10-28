package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifest
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.util.UUID
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.sha256Hex

/** Explicit author-confirmed file changes; model tools cannot invoke this owner. */
object NovelWorkspaceAuthorEdits {
    fun apply(
        directory: File,
        branchId: String,
        branchSlug: String,
        changes: Map<String, String?>,
        expectedHeadId: String?,
        expectedTreeDigest: String,
        message: String = NovelWorkspaceLedger.Message.MANUAL_EDIT,
    ): NovelWorkspaceCommit = NovelWorkspaceGhostwriteJobs.withNoActiveBranch(directory, branchSlug) {
        require(changes.isNotEmpty()) { "没有需要保存的改动" }
        val store = NovelWorkspaceStore(directory)
        val ledger = NovelWorkspaceLedger.load(directory)
        if (NovelWorkspaceBranches.activeSlug(directory) != branchSlug ||
            NovelWorkspaceLedger.branchId(store, ledger, branchSlug) != branchId
        ) throw NovelWorkspaceIoError("当前分支已变化，请重新打开后再操作")
        if (ledger.headOf(branchId)?.id != expectedHeadId || NovelWorkspaceLedger.treeSHA256(store.fileTree()) != expectedTreeDigest) {
            throw NovelWorkspaceIoError("工作区版本已变化，请重新打开后再操作")
        }
        val mainBranch = NovelWorkspaceManifest.parse(store.read(NovelWorkspacePaths.MANIFEST).orEmpty()).mainBranch
        changes.forEach { (path, content) ->
            NovelWorkspacePaths.validate(path)
            val currentPrefix = NovelWorkspacePaths.branchPrefix(branchSlug)
            val allowed = path.endsWith(".md") && (
                path.startsWith("setting/") || path.startsWith("$currentPrefix/setting/") ||
                    path.startsWith("$currentPrefix/chapters/") || path.startsWith("$currentPrefix/discarded/") ||
                    path.startsWith("discarded/$branchSlug/") ||
                    (content == null && branchSlug == mainBranch && path.split('/').size == 2 && path.startsWith("discarded/"))
                )
            if (!allowed) throw NovelWorkspaceIoError("该文件由宿主管理，不能通过作者资料操作修改：$path")
        }
        val previous = changes.keys.associateWith(store::read)
        val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(directory)
        var persisted = false
        val committed = try {
            changes.forEach { (path, content) ->
                if (content == null) {
                    if (previous[path] != null && !store.delete(path)) throw NovelWorkspaceIoError("无法删除文件：$path")
                } else {
                    store.write(path, content)
                }
            }
            NovelWorkspaceCommitter.commitTree(directory, branchId, branchSlug, message, onLedgerPersisted = { persisted = true })
        } catch (error: Exception) {
            if (!persisted) previous.forEach { (path, content) ->
                try {
                    if (content == null) {
                        if (store.exists(path) && !store.delete(path)) throw NovelWorkspaceIoError("无法撤回新文件：$path")
                    } else {
                        store.write(path, content)
                    }
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        }
        NovelWorkspaceUndo.save(
            NovelWorkspaceUndoRecord(committed.id, committed.parentId, previous, unresolvedBefore, branchSlug), directory,
        )
        committed
    } ?: throw NovelWorkspaceIoError("当前分支仍被代笔批次占用，请先让批次完成或取消后再修改")

    /**
     * Author manual edit: save a chapter's title/body and commit. Host identity
     * (id/ordinal) is preserved; only title and body change. Editing a middle chapter
     * records the unresolved gate via the shared commitTree hook.
     */
    fun saveChapterEdit(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
        title: String,
        body: String,
    ): NovelWorkspaceCommit = NovelWorkspaceRestoreBoundary.write {
        NovelWorkspaceCommitter.assertNoGhostwriteOwner(projectDirectory, branchSlug)
        requirePathUnderActiveBranch(projectDirectory, chapterPath)
        val store = NovelWorkspaceStore(projectDirectory)
        val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
        val previous = mapOf(chapterPath to store.read(chapterPath))
        val existing = store.read(chapterPath)
            ?: throw NovelWorkspaceIoError("章节不存在：$chapterPath")
        val parsed = NovelWorkspaceMarkdown.parseFile(existing)
        val committedHash = NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.files?.get(chapterPath)
        val metadataOnly = parsed.body == body.trim() && committedHash == sha256Hex(existing)
        store.write(
            chapterPath,
            NovelWorkspaceMarkdown.withBody(NovelWorkspaceMarkdown.withFields(existing, mapOf("title" to title)), body),
        )
        val commit = NovelWorkspaceCommitter.commitTree(
            projectDirectory = projectDirectory,
            branchId = branchId,
            branchSlug = branchSlug,
            message = NovelWorkspaceLedger.Message.MANUAL_EDIT,
            metadataOnlyPaths = if (metadataOnly) setOf(chapterPath) else emptySet(),
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
        return@write commit
    }

    /**
     * Author manual edit of a NON-chapter file from the 设定 tab: setting cards (free
     * paths) and foreshadowing nodes (protected plot/ paths). Only the body changes —
     * front matter (identity, status, relations) is host-owned and re-rendered verbatim,
     * same rule as [saveChapterEdit]. Saving commits 「手改」 (the click is the author's
     * approval) and captures undo, so the tab keeps the 保存/撤销 conventions of the
     * chapter editor. manifest/project/branch.md and other host-owned paths are refused.
     */
    fun saveFileEdit(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        path: String,
        body: String,
    ): NovelWorkspaceCommit = NovelWorkspaceRestoreBoundary.write {
        NovelWorkspaceCommitter.assertNoGhostwriteOwner(projectDirectory, branchSlug)
        requirePathUnderActiveBranch(projectDirectory, path)
        val isProtectedNonChapter = NovelWorkspacePaths.isProtectedPath(path) &&
            path.split('/').getOrNull(2) == "plot"
        if (!NovelWorkspacePaths.isFreeWritePath(path) && !isProtectedNonChapter) {
            throw NovelWorkspaceIoError("该文件由宿主管理，不能编辑：$path")
        }
        val store = NovelWorkspaceStore(projectDirectory)
        val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
        val previous = mapOf(path to store.read(path))
        val existing = store.read(path)
        val rendered = existing?.let { NovelWorkspaceMarkdown.withBody(it, body) } ?: body
        store.write(path, rendered)
        val commit = NovelWorkspaceCommitter.commitTree(
            projectDirectory = projectDirectory,
            branchId = branchId,
            branchSlug = branchSlug,
            message = NovelWorkspaceLedger.Message.MANUAL_EDIT,
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
        return@write commit
    }

    /**
     * 防御层：作者手改保存（saveChapterEdit/saveFileEdit）的目标若带分支前缀，该前缀
     * 必须等于磁盘上的当前活跃分支（.amber/branch.json 标记，同 [NovelWorkspaceBranches.activeSlug]
     * 的解析口径）。切分支后残留的明细视图（上一分支打开的章节/设定编辑器）会把旧分支
     * 路径递进保存入口；不校验时旧分支文件被写盘、却以新分支的 branchId 推进 head 并记
     * undo——账本污染。UI 层在切分支时已重置明细视图，这里是 runtime 的第二道。
     * 不带分支前缀的书级路径（setting/、drafts/、inbox/）跨分支共享，不在此约束内。
     */
    private fun requirePathUnderActiveBranch(projectDirectory: File, path: String) {
        val segments = path.split('/')
        val pathBranch = segments
            .takeIf { it.size >= 3 && it[0] == NovelWorkspacePaths.BRANCHES_DIR }
            ?.get(1)
            ?: return
        val active = NovelWorkspaceBranches.activeSlug(projectDirectory)
        if (pathBranch != active) {
            throw NovelWorkspaceIoError(
                "该文件属于分支「$pathBranch」，当前活跃分支是「$active」；请切回该分支后再保存",
            )
        }
    }


    /**
     * Author-initiated collect: promote a draft into the manuscript and commit.
     * The author's click IS the approval, so this commits directly (no proposal card).
     * The draft is consumed. ReplaceChapter on a middle chapter records the unresolved gate.
     */
    fun collectDraft(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        draftPath: String,
        target: NovelWorkspaceCollectTarget,
        chapterTitle: String? = null,
    ): NovelWorkspaceCommit = NovelWorkspaceRestoreBoundary.write {
        NovelWorkspaceCommitter.assertNoGhostwriteOwner(projectDirectory, branchSlug)
        val store = NovelWorkspaceStore(projectDirectory)
        val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
        val draftRaw = store.read(draftPath)
            ?: throw NovelWorkspaceIoError("草稿不存在：$draftPath")
        val draftBody = NovelWorkspaceMarkdown.parseFile(draftRaw).body
        val chaptersPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"

        // Compute the new chapter path up front so undo captures it pre-write.
        var newChapterPath: String? = null
        val targetChapterPath = when (target) {
            is NovelWorkspaceCollectTarget.NewChapter -> {
                val nextOrdinal =
                    (NovelWorkspaceLedger.workingChapterOrdinals(store, branchSlug).maxOrNull() ?: 0) + 1
                val title = chapterTitle?.takeIf { it.isNotBlank() }
                    ?: NovelWorkspacePaths.fileNameTitle(draftPath).ifBlank { "第 $nextOrdinal 章" }
                val leafSlug = NovelWorkspaceSlug.slug(title).ifEmpty { "chapter" }
                "$chaptersPrefix/" + NovelWorkspacePaths.chapterFileName(nextOrdinal, leafSlug)
            }
            is NovelWorkspaceCollectTarget.AppendToChapter -> target.chapterPath
            is NovelWorkspaceCollectTarget.ReplaceChapter -> target.chapterPath
        }
        // Capture pre-write contents for 撤销最近一笔 (draft is deleted, chapter written).
        val previous = mapOf(
            draftPath to store.read(draftPath),
            targetChapterPath to store.read(targetChapterPath),
        )

        when (target) {
            is NovelWorkspaceCollectTarget.NewChapter -> {
                val path = targetChapterPath
                val nextOrdinal =
                    NovelWorkspacePaths.chapterOrdinalFromPath(path) ?: 1
                val title = chapterTitle?.takeIf { it.isNotBlank() }
                    ?: NovelWorkspacePaths.fileNameTitle(draftPath).ifBlank { "第 $nextOrdinal 章" }
                newChapterPath = path
                store.write(
                    path,
                    NovelWorkspaceMarkdown.render(
                        fields = listOf(
                            "id" to UUID.randomUUID().toString().uppercase(),
                            "kind" to "chapter",
                            "title" to title,
                            "ordinal" to nextOrdinal.toString(),
                        ),
                        body = draftBody,
                    ),
                )
            }
            is NovelWorkspaceCollectTarget.AppendToChapter -> {
                val existing = store.read(target.chapterPath)
                    ?: throw NovelWorkspaceIoError("目标章节不存在：${target.chapterPath}")
                val parsed = NovelWorkspaceMarkdown.parseFile(existing)
                store.write(
                    target.chapterPath,
                    NovelWorkspaceMarkdown.render(
                        fields = parsed.fields.toList(),
                        aliases = parsed.lists["aliases"].orEmpty(),
                        body = (parsed.body + "\n\n" + draftBody).trim(),
                    ),
                )
            }
            is NovelWorkspaceCollectTarget.ReplaceChapter -> {
                val existing = store.read(target.chapterPath)
                    ?: throw NovelWorkspaceIoError("目标章节不存在：${target.chapterPath}")
                val parsed = NovelWorkspaceMarkdown.parseFile(existing)
                store.write(
                    target.chapterPath,
                    NovelWorkspaceMarkdown.render(
                        fields = parsed.fields.toList(),
                        aliases = parsed.lists["aliases"].orEmpty(),
                        body = draftBody,
                    ),
                )
            }
        }

        // Collecting consumes the draft.
        store.delete(draftPath)
        val commit = NovelWorkspaceCommitter.commitTree(
            projectDirectory = projectDirectory,
            branchId = branchId,
            branchSlug = branchSlug,
            message = NovelWorkspaceLedger.Message.COLLECTION,
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
        return@write commit
    }


}
