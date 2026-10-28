package app.amber.feature.workspace

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.file.Files

/** Performs concrete SAF / mirror copies under the caller's existing mirror lock. */
internal class WorkspaceMirrorCopy(
    private val contentResolver: ContentResolver,
    private val mirrorDir: File,
    private val privateRoot: File,
) {
    fun syncToMirror(root: DocumentFile): String {
        val stats = MirrorSyncStats()
        resetMirror()
        root.listFiles().forEach { child ->
            copyDocumentToMirror(child, mirrorDir, stats)
        }
        return "Synced SAF workspace to POSIX mirror at ${mirrorDir.absolutePath}. " +
            stats.summary()
    }

    fun refreshFromWorkspace(root: DocumentFile): String {
        val stats = MirrorSyncStats()
        ensureMirrorRoot().mkdirs()
        root.listFiles().forEach { child ->
            copyDocumentToMirror(child, mirrorDir, stats)
        }
        return "Refreshed POSIX mirror from SAF workspace at ${mirrorDir.absolutePath}. " +
            "Existing mirror-only files were preserved. " + stats.summary()
    }

    fun ensureMirrorWorkspace(): String {
        val root = ensureMirrorRoot()
        require(root.mkdirs() || root.isDirectory) { "Unable to create workspace mirror: ${root.absolutePath}" }
        return "Using POSIX mirror workspace at ${root.absolutePath}. SAF sync was not required for this terminal job."
    }

    fun syncFromMirror(root: DocumentFile): String {
        val stats = MirrorSyncStats()
        ensureMirrorRoot().mkdirs()
        mirrorDir.listFiles().orEmpty().forEach { child ->
            copyMirrorToDocument(child, root, stats)
        }
        return "Synced POSIX mirror back to SAF workspace. Policy: mirror files overwrite matching SAF files; " +
            "files deleted from mirror are not deleted from SAF in stage1. " + stats.summary()
    }

    fun resetMirror() {
        val root = ensureMirrorRoot()
        if (root.exists()) {
            require(root.deleteRecursively()) { "Unable to clear workspace mirror: ${root.absolutePath}" }
        }
        require(root.mkdirs() || root.isDirectory) { "Unable to create workspace mirror: ${root.absolutePath}" }
    }

    private fun ensureMirrorRoot(): File {
        val amberRoot = privateRoot.canonicalFile
        val root = mirrorDir.canonicalFile
        require(root.path.startsWith(amberRoot.path + File.separator)) {
            "Workspace mirror must stay under ${amberRoot.absolutePath}"
        }
        return root
    }

    private fun copyDocumentToMirror(source: DocumentFile, targetParent: File, stats: MirrorSyncStats) {
        val targetName = safeMirrorName(source.name ?: return stats.skip())
        if (isWorkspaceSyncExcluded(targetName)) return stats.skip()
        val target = safeMirrorChild(targetParent, targetName)
        if (source.isDirectory) {
            if (target.exists() && !target.isDirectory) target.deleteRecursively()
            require(target.mkdirs() || target.isDirectory) { "Unable to create mirror directory: ${target.path}" }
            stats.directories++
            source.listFiles().forEach { child -> copyDocumentToMirror(child, target, stats) }
            return
        }
        if (!source.isFile) {
            stats.skip()
            return
        }
        if (target.exists() && target.isDirectory) {
            require(target.deleteRecursively()) { "Unable to replace mirror directory: ${target.path}" }
        }
        target.parentFile?.mkdirs()
        val input = contentResolver.openInputStream(source.uri) ?: return stats.skip()
        input.use { sourceStream ->
            target.outputStream().use { targetStream ->
                stats.bytes += sourceStream.copyTo(targetStream)
            }
        }
        stats.files++
    }

    private fun copyMirrorToDocument(source: File, targetParent: DocumentFile, stats: MirrorSyncStats) {
        if (Files.isSymbolicLink(source.toPath())) {
            stats.skip()
            return
        }
        requireInsideMirror(source)
        val targetName = safeMirrorName(source.name)
        if (isWorkspaceSyncExcluded(targetName)) return stats.skip()
        if (source.isDirectory) {
            val targetDir = ensureDocumentDirectory(targetParent, targetName)
            stats.directories++
            source.listFiles().orEmpty().forEach { child -> copyMirrorToDocument(child, targetDir, stats) }
            return
        }
        if (!source.isFile) {
            stats.skip()
            return
        }
        val target = ensureDocumentFile(targetParent, targetName)
        contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            source.inputStream().use { input ->
                stats.bytes += input.copyTo(output)
            }
        } ?: error("Unable to write workspace file: $targetName")
        stats.files++
    }

    private fun ensureDocumentDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing?.isDirectory == true) return existing
        existing?.delete()
        return parent.createDirectory(name) ?: error("Unable to create workspace directory: $name")
    }

    private fun ensureDocumentFile(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing?.isFile == true) return existing
        existing?.delete()
        return parent.createFile("application/octet-stream", name)
            ?: error("Unable to create workspace file: $name")
    }

    private fun safeMirrorChild(parent: File, name: String): File {
        val parentCanonical = parent.canonicalFile
        val child = File(parentCanonical, safeMirrorName(name)).canonicalFile
        require(child.path.startsWith(parentCanonical.path + File.separator)) {
            "Mirror path escaped parent: $name"
        }
        return child
    }

    private fun safeMirrorName(name: String): String {
        require(name.isNotBlank()) { "Workspace file name is blank" }
        require(name != "." && name != "..") { "Unsafe workspace file name: $name" }
        require(!name.contains("/") && !name.contains("\\")) { "Unsafe workspace file name: $name" }
        return name
    }

    private fun isWorkspaceSyncExcluded(name: String): Boolean =
        name == ".amberagent-external-cli-home" ||
            name.startsWith(".amberagent-council-cli-")

    private fun requireInsideMirror(file: File) {
        val root = ensureMirrorRoot()
        val filePath = file.canonicalPath
        require(filePath == root.canonicalPath || filePath.startsWith(root.canonicalPath + File.separator)) {
            "Mirror entry escaped workspace mirror: ${file.path}"
        }
    }

}

private class MirrorSyncStats {
    var files: Int = 0
    var directories: Int = 0
    var bytes: Long = 0
    var skipped: Int = 0

    fun skip() {
        skipped++
    }

    fun summary(): String =
        "files=$files, directories=$directories, bytes=$bytes, skipped=$skipped."
}
