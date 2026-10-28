package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceNodes
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Local ledger/head and host-identity rules shared by every workspace writer. */
object NovelWorkspaceCommitter {
    /**
     * Commit the current tree for a branch: append commit, advance the branch head,
     * keep the global head mirroring only the previously-mirrored branch, run the
     * unresolved (middle-edit) hook, refresh the checkout, and persist the ledger.
     *
     * [armsUnresolvedGate]=false is reserved for polish commits: D-D invalidates the
     * chapters after an edited middle chapter because their content may no longer
     * follow from it — a polish commit keeps every fact, so nothing after it becomes
     * invalid and the gate must stay silent.
     */
    fun commitTree(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        message: String,
        armsUnresolvedGate: Boolean = true,
        metadataOnlyPaths: Set<String> = emptySet(),
        polishJobId: String? = null,
        polishChapterOrdinal: Int? = null,
        commitId: String = UUID.randomUUID().toString().uppercase(),
        onLedgerPersisted: () -> Unit = {},
    ): NovelWorkspaceCommit {
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val parentId = ledger.heads[branchId] ?: ledger.head
        val commit = NovelWorkspaceLedger.makeCommit(
            id = commitId,
            parentId = parentId,
            files = store.fileTree(),
            message = message,
            createdAt = Instant.now(),
            metadataOnlyPaths = metadataOnlyPaths,
            polishJobId = polishJobId,
            polishChapterOrdinal = polishChapterOrdinal,
        )
        val withCommit = NovelWorkspaceLedger.appending(commit, ledger).copy(
            heads = ledger.heads + (branchId to commitId),
        )
        // head mirrors the branch that was mirrored before this commit; approving or
        // collecting on a side branch must not drag the global pointer away from it.
        val mirrorsBranch = ledger.head == null || ledger.heads[branchId] == ledger.head
        val updated = if (mirrorsBranch) withCommit else withCommit.copy(head = ledger.head)
        NovelWorkspaceLedger.save(updated, projectDirectory)
        onLedgerPersisted()
        // D-D: a middle-chapter edit invalidates everything after it — record the range.
        // Polish commits (armsUnresolvedGate=false) keep all facts and are exempt.
        if (armsUnresolvedGate) {
            NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(
                store,
                updated,
                branchSlug,
                commit,
            )?.let { fromOrdinal ->
                NovelWorkspaceUnresolvedStore.set(projectDirectory, branchSlug, fromOrdinal, commitId)
            }
        }
        store.materializeCheckout()
        return commit
    }

    /**
     * Identity stays host-owned: chapters/plot keep their front matter, while a
     * foreshadowing node may update its open/resolved state through the author gate.
     * New protected files get host-synthesized identity from the path.
     */
    internal fun mergedContent(store: NovelWorkspaceStore, entry: NovelWorkspaceWriteEntry): String {
        val parsedNew = NovelWorkspaceMarkdown.parseFile(entry.content)
        // fields empty → bare body (a leading `---` thematic break is not front matter).
        val body = parsedNew.body
        val existing = store.read(entry.path)
        val segments = entry.path.split('/')
        if (segments.getOrNull(2) == "plot" && segments.getOrNull(3) == "foreshadowing") {
            return mergedForeshadowingContent(entry.path, existing, parsedNew)
        }
        if (existing != null) return NovelWorkspaceMarkdown.withBody(existing, body)
        return NovelWorkspaceMarkdown.render(
            fields = synthesizedFields(entry.path, parsedNew.fields["title"]),
            body = body,
        )
    }

    private fun mergedForeshadowingContent(
        path: String,
        existing: String?,
        incoming: NovelWorkspaceMarkdown.ParsedFile,
    ): String {
        val currentStatus = existing?.let(NovelWorkspaceMarkdown::parseFile)?.fields?.get("status")
            ?.trim()?.takeIf { it.isNotEmpty() }
        val requestedStatus = incoming.fields["status"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
        if (requestedStatus != null && requestedStatus != NovelWorkspaceNodes.STATUS_OPEN &&
            requestedStatus != NovelWorkspaceNodes.STATUS_RESOLVED &&
            !requestedStatus.equals(currentStatus, ignoreCase = true)
        ) throw NovelWorkspaceIoError("伏笔状态只能更新为 open 或 resolved")
        val content = if (existing == null) {
            NovelWorkspaceMarkdown.render(synthesizedFields(path, incoming.fields["title"]), body = incoming.body)
        } else {
            NovelWorkspaceMarkdown.withBody(existing, incoming.body)
        }
        return NovelWorkspaceMarkdown.withFields(content, mapOf(
            "kind" to NovelWorkspaceNodes.KIND_FORESHADOWING,
            "status" to (requestedStatus ?: currentStatus ?: NovelWorkspaceNodes.STATUS_OPEN),
        ))
    }

    private fun synthesizedFields(path: String, modelTitle: String?): List<Pair<String, String>> {
        val segments = path.split('/')
        val section = segments.getOrNull(2)
        return when (section) {
            "chapters" -> listOf(
                "id" to UUID.randomUUID().toString().uppercase(),
                "kind" to "chapter",
                "title" to (modelTitle ?: NovelWorkspacePaths.fileNameTitle(path)),
                "ordinal" to (NovelWorkspacePaths.chapterOrdinalFromPath(path)?.toString() ?: "1"),
            )
            "plot" -> listOf(
                "id" to UUID.randomUUID().toString().uppercase(),
                "kind" to "plot",
                "title" to (modelTitle ?: NovelWorkspacePaths.fileNameTitle(path)),
            )
            else -> listOf(
                "id" to UUID.randomUUID().toString().uppercase(),
                "kind" to "chapter",
                "title" to (modelTitle ?: NovelWorkspacePaths.fileNameTitle(path)),
            )
        }
    }

    fun assertNoGhostwriteOwner(projectDirectory: File, branchSlug: String? = null) {
        val owner = if (branchSlug == null) {
            NovelWorkspaceGhostwriteJobs.listActive(projectDirectory).firstOrNull()
        } else {
            NovelWorkspaceGhostwriteJobs.activeFor(projectDirectory, branchSlug)
        }
        if (owner != null) {
            throw NovelWorkspaceIoError("当前分支仍被代笔批次占用，请先让批次完成或取消后再修改正文")
        }
    }

    internal fun commitMessageFor(paths: List<String>): String = when {
        paths.any { "/chapters/" in it } -> NovelWorkspaceLedger.Message.COLLECTION
        paths.any { "/plot/" in it } -> NovelWorkspaceLedger.Message.PLOT_POINTER
        else -> NovelWorkspaceLedger.Message.GENERIC
    }
}
