package app.amber.feature.ui.pages.novel

import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteController
import app.amber.feature.novel.workspace.NovelWorkspaceUndoActions
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import app.amber.feature.novelworkspace.NovelWorkspaceFocus
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceLedgerStore
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSettingsStore
import app.amber.feature.novelworkspace.NovelWorkspaceProjectTitle
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal data class NovelGhostwriteRefresh(
    val job: NovelMarkdownGhostwriteUi?,
    val unreadableJobFiles: List<String>,
    val chapters: List<NovelMarkdownChapterUi>,
    val drafts: List<NovelMarkdownDraftUi>,
    val catalog: NovelWorkspaceCatalog.NovelWorkspaceCatalogData?,
    val plotStale: Boolean,
    val unresolvedFromOrdinal: Int?,
    val canUndo: Boolean,
    val chapterCache: Map<String, CachedNovelUi<NovelMarkdownChapterUi>>,
    val draftCache: Map<String, CachedNovelUi<NovelMarkdownDraftUi>>,
)

internal data class CachedNovelUi<T>(
    val stamp: NovelFileStamp,
    val value: T,
)

internal data class NovelFileStamp(
    val fileKey: Any,
    val size: Long,
    val lastModifiedTime: FileTime,
)

private fun novelFileStamp(file: File): NovelFileStamp? = try {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    if (!attributes.isRegularFile) null else attributes.fileKey()?.let { key ->
        NovelFileStamp(key, attributes.size(), attributes.lastModifiedTime())
    }
} catch (_: IOException) {
    null
}

internal data class NovelWorkspaceReloadSnapshot(
    val projectDirectory: File? = null,
    val branchId: String? = null,
    val branchSlug: String? = null,
    val title: String = "",
    val branches: List<NovelWorkspaceBranches.NovelWorkspaceBranchInfo> = emptyList(),
    val messages: List<NovelMarkdownMessageUi> = emptyList(),
    val chapters: List<NovelMarkdownChapterUi> = emptyList(),
    val drafts: List<NovelMarkdownDraftUi> = emptyList(),
    val catalog: NovelWorkspaceCatalog.NovelWorkspaceCatalogData? = null,
    val proposals: List<NovelWorkspaceWriteProposal> = emptyList(),
    val plotStale: Boolean = false,
    val unresolvedFromOrdinal: Int? = null,
    val writingModelId: String? = null,
    val reviewModelId: String? = null,
    val ghostwriteMode: Boolean = false,
    val injection: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags =
        app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
    val canUndo: Boolean = false,
)

internal data class NovelWorkspaceContentSnapshot(
    val messages: List<NovelMarkdownMessageUi>,
    val chapters: List<NovelMarkdownChapterUi>,
    val drafts: List<NovelMarkdownDraftUi>,
    val catalog: NovelWorkspaceCatalog.NovelWorkspaceCatalogData?,
    val proposals: List<NovelWorkspaceWriteProposal>,
    val plotStale: Boolean,
    val unresolvedFromOrdinal: Int?,
    val canUndo: Boolean,
)

/** Filesystem projections and polling caches; publication remains the ViewModel's decision. */
internal class NovelWorkspaceQueries(private val repository: NovelWorkspaceProjectRepository) {
    private var chapterCache: Map<String, CachedNovelUi<NovelMarkdownChapterUi>> = emptyMap()
    private var draftCache: Map<String, CachedNovelUi<NovelMarkdownDraftUi>> = emptyMap()

    fun clearCache() {
        chapterCache = emptyMap()
        draftCache = emptyMap()
    }

    fun cachedChapters(): Map<String, CachedNovelUi<NovelMarkdownChapterUi>> = chapterCache
    fun cachedDrafts(): Map<String, CachedNovelUi<NovelMarkdownDraftUi>> = draftCache

    fun acceptRefresh(refresh: NovelGhostwriteRefresh) {
        chapterCache = refresh.chapterCache
        draftCache = refresh.draftCache
    }

    /** Read one coherent workspace projection on IO before publishing it to the UI. */
    fun readWorkspaceSnapshot(
        projectId: String,
        proposals: List<NovelWorkspaceWriteProposal>,
        focusRequest: NovelWorkspaceFocus?,
    ): NovelWorkspaceReloadSnapshot {
        if (!repository.exists(projectId)) return NovelWorkspaceReloadSnapshot()

        val directory = repository.projectDirectory(projectId)
        val store = NovelWorkspaceStore(directory)
        val ledger = NovelWorkspaceLedger.load(directory)
        // Notification/deep-link focus is view-only: resolve it against durable state without
        // changing the app's active branch or implicitly resuming a Worker.
        val focus = focusRequest?.resolve(store)
        val slug = focus?.branchSlug ?: NovelWorkspaceBranches.activeSlug(directory)
        val resolvedBranchId = focus?.branchId ?: NovelWorkspaceLedger.branchId(store, ledger, slug)
        val projectSettings = NovelWorkspaceProjectSettingsStore.load(directory)
        val chapters = chapters(store, slug)
        val drafts = drafts(store, resolvedBranchId, ledger = ledger)
        val catalog = catalog(directory, ledger, slug)
        return NovelWorkspaceReloadSnapshot(
            projectDirectory = directory,
            branchId = resolvedBranchId,
            branchSlug = slug,
            title = NovelWorkspaceProjectTitle.read(store),
            branches = NovelWorkspaceBranches.list(directory, slug),
            messages = NovelWorkspaceSessionHistory.recoverAndLoad(directory, resolvedBranchId),
            chapters = chapters,
            drafts = drafts,
            catalog = catalog,
            proposals = proposals.filter {
                it.projectDirectory == directory && it.branchId == resolvedBranchId && it.branchSlug == slug
            },
            plotStale = NovelWorkspaceLedger.isPlotStale(store, ledger, slug),
            unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
            writingModelId = projectSettings.writingModelId,
            reviewModelId = projectSettings.reviewModelId,
            ghostwriteMode = projectSettings.ghostwriteMode,
            injection = projectSettings.injection
                ?: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
            canUndo = NovelWorkspaceUndoActions.canUndo(directory, slug),
        )
    }

    fun contentSnapshot(
        directory: File,
        branch: String,
        slug: String,
        proposals: List<NovelWorkspaceWriteProposal>,
        includeMessages: Boolean = true,
    ): NovelWorkspaceContentSnapshot {
        val store = NovelWorkspaceStore(directory)
        val ledger = NovelWorkspaceLedger.load(directory)
        return NovelWorkspaceContentSnapshot(
            messages = if (includeMessages) NovelWorkspaceSessionHistory.recoverAndLoad(directory, branch) else emptyList(),
            chapters = chapters(store, slug),
            drafts = drafts(store, branch, ledger = ledger),
            catalog = catalog(directory, ledger, slug),
            proposals = proposals.filter { it.projectDirectory == directory && it.branchId == branch && it.branchSlug == slug },
            plotStale = NovelWorkspaceLedger.isPlotStale(store, ledger, slug),
            unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
            canUndo = NovelWorkspaceUndoActions.canUndo(directory, slug),
        )
    }

    fun catalog(directory: File, slug: String): NovelWorkspaceCatalog.NovelWorkspaceCatalogData =
        catalog(directory, NovelWorkspaceLedger.load(directory), slug)

    fun catalog(
        directory: File,
        ledger: NovelWorkspaceLedgerStore,
        slug: String,
    ): NovelWorkspaceCatalog.NovelWorkspaceCatalogData = runCatching {
        NovelWorkspaceCatalog.load(NovelWorkspaceStore(directory), ledger, slug)
    }.getOrDefault(NovelWorkspaceCatalog.NovelWorkspaceCatalogData(emptyList(), emptyList(), emptyList()))

    fun chapters(
        store: NovelWorkspaceStore,
        slug: String?,
        cache: MutableMap<String, CachedNovelUi<NovelMarkdownChapterUi>>? = null,
    ): List<NovelMarkdownChapterUi> {
        slug ?: return emptyList()
        val prefix = NovelWorkspacePaths.branchPrefix(slug) + "/chapters"
        val paths = store.list(prefix)
        cache?.keys?.retainAll(paths.toSet())
        return paths.mapNotNull { path ->
            val file = cache?.let { File(store.rootDirectory, path) }
            val stamp = file?.let(::novelFileStamp)
            cache?.get(path)?.takeIf {
                stamp != null && it.stamp == stamp
            }?.let { return@mapNotNull it.value }
            val content = store.read(path) ?: run {
                cache?.remove(path)
                return@mapNotNull null
            }
            val parsed = NovelWorkspaceMarkdown.parseFile(content)
            val chapter = NovelMarkdownChapterUi(
                path = path,
                title = parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(path),
                ordinal = parsed.fields["ordinal"]?.toIntOrNull()
                    ?: NovelWorkspacePaths.chapterOrdinalFromPath(path)
                    ?: 0,
                charCount = parsed.body.length,
            )
            if (cache != null && file != null && stamp != null && novelFileStamp(file) == stamp) {
                cache[path] = CachedNovelUi(stamp, chapter)
            } else {
                cache?.remove(path)
            }
            chapter
        }.sortedBy { it.ordinal }
    }

    fun drafts(
        store: NovelWorkspaceStore,
        forBranchId: String?,
        cache: MutableMap<String, CachedNovelUi<NovelMarkdownDraftUi>>? = null,
        ledger: NovelWorkspaceLedgerStore = NovelWorkspaceLedger.load(store.rootDirectory),
    ): List<NovelMarkdownDraftUi> {
        val ancestry = ledger.ancestry(forBranchId?.let { ledger.heads[it] } ?: ledger.head)
        val paths = store.list(NovelWorkspacePaths.DRAFTS_DIR)
        cache?.keys?.retainAll(paths.toSet())
        return paths.mapNotNull { path ->
            val file = File(store.rootDirectory, path)
            val stamp = cache?.let { novelFileStamp(file) }
            val createdAt = novelMarkdownDraftCreatedAt(
                ancestry, path, Instant.ofEpochMilli(file.lastModified()),
            )
            cache?.get(path)?.takeIf {
                stamp != null && it.stamp == stamp
            }?.let { cached ->
                val draft = if (cached.value.createdAt == createdAt) cached.value
                    else cached.value.copy(createdAt = createdAt)
                return@mapNotNull draft
            }
            val content = store.read(path) ?: run {
                cache?.remove(path)
                return@mapNotNull null
            }
            val parsed = NovelWorkspaceMarkdown.parseFile(content)
            val draft = NovelMarkdownDraftUi(
                path = path,
                title = parsed.fields["title"]?.takeIf { it.isNotBlank() }
                    ?: NovelWorkspacePaths.fileNameTitle(path),
                excerpt = parsed.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(80),
                createdAt = createdAt,
            )
            if (cache != null && stamp != null && novelFileStamp(file) == stamp) {
                cache[path] = CachedNovelUi(stamp, draft)
            } else {
                cache?.remove(path)
            }
            draft
        }
    }

    suspend fun ghostwriteRefresh(
        directory: File,
        branch: String?,
        slug: String,
        focusedJobId: String?,
        expectedRestoreEpoch: Long,
        ghostwriteController: NovelWorkspaceGhostwriteController,
        cachedChapters: Map<String, CachedNovelUi<NovelMarkdownChapterUi>>,
        cachedDrafts: Map<String, CachedNovelUi<NovelMarkdownDraftUi>>,
        localizeJobReason: (String?) -> String?,
    ): NovelGhostwriteRefresh {
        // Reconcile the durable job and WorkManager identity before projecting
        // it. A WorkManager cancellation racing the UI is handled by the job CAS.
        try {
            ghostwriteController.reconcile(directory, expectedRestoreEpoch = expectedRestoreEpoch)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A cold UI may be projected before WorkManager is initialized;
            // durable job files remain authoritative for this refresh.
        }
        val store = NovelWorkspaceStore(directory)
        val snapshot = NovelWorkspaceGhostwriteJobs.snapshot(directory)
        val selectedJob = if (focusedJobId != null) {
            snapshot.jobs.firstOrNull { it.id == focusedJobId && it.branchSlug == slug }
        } else {
            snapshot.jobs.firstOrNull { !it.isTerminal && it.branchSlug == slug }
                ?: NovelWorkspaceGhostwriteJobs.latestFailed(directory, slug)
        }
        // A terminal job is written after its final commit. Read the ledger
        // after choosing that job so its one-shot progress cannot lag behind.
        val ledger = NovelWorkspaceLedger.load(directory)
        val running = selectedJob?.status == NovelWorkspaceGhostwriteJob.STATUS_RUNNING
        val chapterCache = if (running) cachedChapters.toMutableMap() else null
        val draftCache = if (running) cachedDrafts.toMutableMap() else null
        val jobUi = selectedJob
            ?.let { job ->
                novelMarkdownGhostwriteUi(job, NovelWorkspaceGhostwriteJobs.progress(job, store, ledger), localizeJobReason(job.reason))
            }
        return NovelGhostwriteRefresh(
            job = jobUi,
            unreadableJobFiles = snapshot.unreadableFiles,
            chapters = chapters(store, slug, chapterCache),
            drafts = drafts(store, branch, draftCache, ledger),
            catalog = catalog(directory, ledger, slug),
            plotStale = NovelWorkspaceLedger.isPlotStale(store, ledger, slug),
            unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
            canUndo = NovelWorkspaceUndoActions.canUndo(directory, slug),
            chapterCache = chapterCache.orEmpty(),
            draftCache = draftCache.orEmpty(),
        )
    }
    fun fileBody(directory: File, path: String): String? = runCatching {
        val store = NovelWorkspaceStore(directory)
        val content = store.read(path) ?: return@runCatching null
        NovelWorkspaceMarkdown.parseFile(content).body
    }.getOrNull()

    fun discarded(directory: File, branch: String, slug: String): NovelMarkdownDiscardedSnapshotUi? =
        app.amber.feature.novel.workspace.NovelWorkspaceDiscardedChapters.snapshot(directory, branch, slug)
            ?.let { snapshot ->
                NovelMarkdownDiscardedSnapshotUi(snapshot.headId, snapshot.treeDigest, snapshot.entries.map {
                    NovelMarkdownDiscardedUi(it.path, it.title, it.ordinal, it.restoreBlockedReason)
                })
            }

    fun writingPreference(directory: File, slug: String, forGeneration: Boolean): String {
        val store = NovelWorkspaceStore(directory)
        if (forGeneration) return app.amber.feature.novelworkspace.NovelWorkspaceEffectiveMaterials
            .writingPreferenceForPrompt(store, slug)
        val entry = app.amber.feature.novelworkspace.NovelWorkspaceEffectiveMaterials
            .writingPreference(store, slug) ?: return ""
        return entry.parsed.body
    }

    fun briefPreview(directory: File, slug: String, flags: NovelWorkspaceInjectionFlags, locale: java.util.Locale): String =
        runCatching {
            app.amber.feature.novelworkspace.NovelWorkspaceContextAssembler.assemble(
                NovelWorkspaceStore(directory), slug,
                flags = flags,
                locale = locale,
            )
        }.getOrDefault("")

    fun pathBody(directory: File, path: String?): String? {
        if (path == null) return null
        return runCatching {
            NovelWorkspaceMarkdown.parseFile(NovelWorkspaceStore(directory).read(path) ?: "").body
        }.getOrNull()
    }
}
