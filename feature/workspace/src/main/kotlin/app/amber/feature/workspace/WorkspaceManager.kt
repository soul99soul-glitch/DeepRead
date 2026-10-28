package app.amber.feature.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class WorkspaceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE)
    private val mirrorMutex = Mutex()
    private var activeTerminalMirrors = 0
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    val mirrorDir get() = context.filesDir.resolve("amberagent/workspace-mirror")
    private val documents = WorkspaceDocuments(context.contentResolver, ::requireRoot)
    private val mirrorCopy = WorkspaceMirrorCopy(context.contentResolver, mirrorDir, context.filesDir.resolve("amberagent"))

    suspend fun setWorkspace(uri: Uri) = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previousUri = mirrorMutex.withLock {
            val previous = _state.value.treeUri
            if (previous == uri) return@withLock previous
            check(activeTerminalMirrors == 0) {
                "Stop active terminal jobs and sessions before changing the workspace."
            }
            if (previous != null) {
                mirrorCopy.syncFromMirror(requireRoot())
                mirrorCopy.resetMirror()
            }
            context.contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit().putString(KEY_TREE_URI, uri.toString()).commit()
            _state.value = loadState()
            previous
        }
        if (previousUri != null && previousUri != uri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(previousUri, flags)
            }
        }
    }

    suspend fun clearWorkspace() = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previousUri = mirrorMutex.withLock {
            val previous = _state.value.treeUri
            if (previous != null) {
                check(activeTerminalMirrors == 0) {
                    "Stop active terminal jobs and sessions before clearing the workspace."
                }
                mirrorCopy.syncFromMirror(requireRoot())
                mirrorCopy.resetMirror()
            }
            prefs.edit().remove(KEY_TREE_URI).commit()
            _state.value = loadState()
            previous
        }
        previousUri?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            }
        }
    }

    suspend fun list(relativePath: String = "."): List<WorkspaceEntry> = documents.list(relativePath)

    suspend fun readText(relativePath: String): String = documents.readText(relativePath)

    suspend fun readTextCapped(relativePath: String, maxChars: Int): WorkspaceTextRead =
        documents.readTextCapped(relativePath, maxChars)

    suspend fun readBytes(relativePath: String): ByteArray = documents.readBytes(relativePath)

    suspend fun readBytesCapped(relativePath: String, maxBytes: Int): ByteArray =
        documents.readBytesCapped(relativePath, maxBytes)

    suspend fun writeText(relativePath: String, content: String, append: Boolean = false): WorkspaceEntry =
        withDocumentMutation {
            documents.writeText(relativePath, content, append).also { invalidateMirrorPaths(relativePath) }
        }

    suspend fun writeBytes(
        relativePath: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
        append: Boolean = false,
    ): WorkspaceEntry = withDocumentMutation {
        documents.writeBytes(relativePath, bytes, mimeType, append).also { invalidateMirrorPaths(relativePath) }
    }

    suspend fun editText(relativePath: String, oldText: String, newText: String, replaceAll: Boolean): EditResult =
        withDocumentMutation {
            documents.editText(relativePath, oldText, newText, replaceAll).also { invalidateMirrorPaths(relativePath) }
        }

    suspend fun move(sourcePath: String, targetPath: String): WorkspaceEntry = withDocumentMutation {
        val sourceMirror = mirrorPathInsideRoot(sourcePath)
        val targetMirror = mirrorPathInsideRoot(targetPath)
        require(targetMirror == null || !Files.exists(targetMirror.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Target path already exists in the local workspace mirror: $targetPath"
        }
        val mirroredDirectory = sourceMirror != null &&
            Files.isDirectory(sourceMirror.toPath(), LinkOption.NOFOLLOW_LINKS)
        require(!mirroredDirectory || targetMirror != null) {
            "Directory target is outside the local workspace mirror: $targetPath"
        }
        documents.move(sourcePath, targetPath).also { moved ->
            if (moved.directory && mirroredDirectory) {
                // A directory may contain terminal output that has never been flushed to SAF.
                // Keep that output under the new path instead of treating it as disposable cache.
                val destination = requireNotNull(targetMirror).toPath()
                Files.createDirectories(destination.parent)
                Files.move(requireNotNull(sourceMirror).toPath(), destination)
            } else {
                invalidateMirrorPaths(sourcePath, targetPath)
            }
        }
    }

    suspend fun search(query: String, relativePath: String = ".", maxResults: Int = 50): List<SearchResult> =
        documents.search(query, relativePath, maxResults)

    suspend fun syncToMirror(): String = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            mirrorCopy.syncToMirror(requireRoot())
        }
    }

    suspend fun refreshMirrorFromWorkspace(): String = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            check(activeTerminalMirrors == 0) {
                "Workspace refresh is unavailable while terminal jobs or sessions are active."
            }
            mirrorCopy.refreshFromWorkspace(requireRoot())
        }
    }

    suspend fun ensureMirrorWorkspace(): String = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            mirrorCopy.ensureMirrorWorkspace()
        }
    }

    suspend fun acquireTerminalMirror(refreshFromWorkspace: Boolean): WorkspaceMirrorLease =
        mirrorMutex.withLock {
            check(activeTerminalMirrors == 0) {
                "Another local terminal job or session is already using the workspace mirror."
            }
            val preparationNote = if (refreshFromWorkspace) {
                mirrorCopy.refreshFromWorkspace(requireRoot())
            } else {
                mirrorCopy.ensureMirrorWorkspace()
            }
            activeTerminalMirrors++
            WorkspaceMirrorLease(
                directory = mirrorDir,
                preparationNote = preparationNote,
            ) { syncBack ->
                withContext(NonCancellable + Dispatchers.IO) {
                    mirrorMutex.withLock {
                        try {
                            if (syncBack) mirrorCopy.syncFromMirror(requireRoot()) else ""
                        } finally {
                            activeTerminalMirrors--
                        }
                    }
                }
            }
        }

    /**
     * Stages a content URI (typically a file shared from the system Share menu) into the
     * POSIX mirror under `uploads/`, so the Agent's terminal tools can find it at
     * `/workspace/uploads/<displayName>` instead of the previously dead-end
     * `filesDir/upload/<uuid>` path.
     *
     * Filename is preserved so the user-visible name matches what `ls` reports. Collisions
     * append a `(1)`, `(2)` suffix before the extension. The mirror dir is created on
     * demand — works even when the SAF workspace has never been authorized; in that case
     * the file lives only in the private mirror, but the Agent still sees it via
     * `/workspace/uploads/`. (When SAF is configured, the next `withMirrorSync` round-trip
     * surfaces it back to the user-visible workspace too.)
     */
    suspend fun copyUriToUploads(sourceUri: Uri, displayName: String): Uri = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            val uploadsDir = mirrorDir.resolve("uploads").also { it.mkdirs() }
            val target = pickUniqueUploadFile(uploadsDir, sanitizeUploadName(displayName))
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open input stream for $sourceUri")
            Uri.fromFile(target)
        }
    }

    /**
     * Delete a single file in the SAF workspace by its relative path. Mirrors
     * [deleteWorkspaceFile] but for the SAF side (used by the artifact registry
     * to remove content files). Returns true only when a document was removed.
     */
    suspend fun deleteFile(relativePath: String): Boolean = withDocumentMutation {
        documents.deleteFile(relativePath).also { deleted ->
            if (deleted) invalidateMirrorPaths(relativePath)
        }
    }

    private suspend fun <T> withDocumentMutation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            check(activeTerminalMirrors == 0) {
                "Stop active terminal jobs and sessions before modifying workspace files."
            }
            block()
        }
    }

    private fun invalidateMirrorPaths(vararg relativePaths: String) {
        relativePaths.mapNotNull(::mirrorPathInsideRoot).distinct().forEach { target ->
            if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
            // The SAF mutation now owns this path; retaining its old mirror would replay
            // stale contents at the next flush, including workspace switching/clearing.
            Files.walk(target.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }

    private fun mirrorPathInsideRoot(relativePath: String): File? {
        val relative = WorkspacePaths.normalize(relativePath)
        if (relative == ".") return null
        val root = mirrorDir.canonicalFile
        val target = mirrorDir.resolve(relative)
        val parent = target.parentFile.canonicalFile
        // Terminal-created symlinks must not redirect mirror mutations outside its root.
        return target.takeIf { parent == root || parent.path.startsWith(root.path + File.separator) }
    }

    /**
     * Delete a single file under the workspace mirror by its relative path. Holds the
     * same `mirrorMutex` as `withMirrorSync` and `copyUriToUploads`, so a delete
     * triggered from the file sheet cannot race with an in-flight terminal-job sync
     * (which would otherwise either re-copy the file from SAF or leave a partial
     * write). Refuses paths that escape the mirror via `..`. Returns true only when an
     * actual file was removed.
     */
    suspend fun deleteWorkspaceFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        if (relativePath.isBlank()) return@withContext false
        mirrorMutex.withLock {
            val rootCanonical = mirrorDir.canonicalFile
            val target = mirrorDir.resolve(relativePath).canonicalFile
            if (!target.path.startsWith(rootCanonical.path + File.separator)) {
                return@withLock false
            }
            if (!target.exists() || !target.isFile) return@withLock false
            runCatching { target.delete() }.getOrDefault(false)
        }
    }

    // Display names come from external ContentProviders; strip path components so a
    // hostile "../../x" name cannot resolve outside the uploads dir.
    private fun sanitizeUploadName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        return if (name.isBlank() || name == "." || name == "..") "file" else name
    }

    private fun pickUniqueUploadFile(dir: File, name: String): File {
        val candidate = dir.resolve(name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val attempt = dir.resolve("$base ($index)$ext")
            if (!attempt.exists()) return attempt
            index++
        }
    }

    suspend fun syncFromMirror(): String = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            check(activeTerminalMirrors == 0) {
                "Workspace flush is unavailable while terminal jobs or sessions are active."
            }
            mirrorCopy.syncFromMirror(requireRoot())
        }
    }

    suspend fun <T> withMirrorSync(block: suspend (File) -> T): WorkspaceMirrorResult<T> = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            val syncIn = mirrorCopy.syncToMirror(requireRoot())
            val value = block(mirrorDir)
            val syncOut = mirrorCopy.syncFromMirror(requireRoot())
            WorkspaceMirrorResult(
                value = value,
                syncNote = "$syncIn\n$syncOut"
            )
        }
    }

    private fun requireRoot(): DocumentFile {
        val uri = _state.value.treeUri ?: error("No AmberAgent workspace selected")
        return DocumentFile.fromTreeUri(context, uri) ?: error("Unable to open AmberAgent workspace")
    }

    private fun loadState(): WorkspaceState {
        val uri = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
        val name = uri?.let { DocumentFile.fromTreeUri(context, it)?.name }
        return WorkspaceState(treeUri = uri, displayName = name)
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
    }
}
