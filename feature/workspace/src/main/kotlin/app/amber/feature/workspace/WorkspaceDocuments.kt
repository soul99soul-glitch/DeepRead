package app.amber.feature.workspace

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** SAF file commands; workspace selection and permissions remain with WorkspaceManager. */
internal class WorkspaceDocuments(
    private val contentResolver: ContentResolver,
    private val requireRoot: () -> DocumentFile,
) {
    suspend fun list(relativePath: String = "."): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val dir = requireDocument(relativePath)
        require(dir.isDirectory) { "Not a directory: $relativePath" }
        dir.listFiles()
            .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
            .map { file ->
                WorkspaceEntry(
                    path = joinPath(normalizePath(relativePath), file.name.orEmpty()),
                    name = file.name.orEmpty(),
                    directory = file.isDirectory,
                    sizeBytes = if (file.isDirectory) null else file.length(),
                    mimeType = file.type
                )
            }
    }

    suspend fun readText(relativePath: String): String = withContext(Dispatchers.IO) {
        val file = requireDocument(relativePath)
        require(file.isFile) { "Not a file: $relativePath" }
        contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Unable to read file: $relativePath")
    }

    suspend fun readTextCapped(relativePath: String, maxChars: Int): WorkspaceTextRead = withContext(Dispatchers.IO) {
        require(maxChars > 0) { "maxChars must be positive" }
        val file = requireDocument(relativePath)
        require(file.isFile) { "Not a file: $relativePath" }
        val content = contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
            val buffer = CharArray(maxChars + 1)
            var total = 0
            while (total < buffer.size) {
                val read = reader.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            String(buffer, 0, total)
        } ?: error("Unable to read file: $relativePath")
        WorkspaceTextRead(
            content = content.take(maxChars),
            truncated = content.length > maxChars,
        )
    }

    suspend fun readBytes(relativePath: String): ByteArray = withContext(Dispatchers.IO) {
        val file = requireDocument(relativePath)
        require(file.isFile) { "Not a file: $relativePath" }
        contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: error("Unable to read file: $relativePath")
    }

    suspend fun readBytesCapped(relativePath: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val file = requireDocument(relativePath)
        require(file.isFile) { "Not a file: $relativePath" }
        if (file.length() > maxBytes) {
            error("File exceeds $maxBytes byte limit: $relativePath")
        }
        contentResolver.openInputStream(file.uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(total <= maxBytes - read) { "File exceeds $maxBytes byte limit: $relativePath" }
                output.write(buffer, 0, read)
                total += read
            }
            output.toByteArray()
        } ?: error("Unable to read file: $relativePath")
    }

    suspend fun writeText(relativePath: String, content: String, append: Boolean = false): WorkspaceEntry =
        withContext(Dispatchers.IO) {
            val normalized = requireFilePath(relativePath)
            val file = requireOrCreateFile(normalized, textMimeTypeForPath(normalized))
            val mode = if (append) "wa" else "wt"
            contentResolver.openOutputStream(file.uri, mode)?.bufferedWriter()?.use {
                it.write(content)
            } ?: error("Unable to write file: $relativePath")
            WorkspaceEntry(
                path = normalized,
                name = file.name.orEmpty(),
                directory = false,
                sizeBytes = file.length(),
                mimeType = file.type
            )
        }

    suspend fun writeBytes(
        relativePath: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
        append: Boolean = false,
    ): WorkspaceEntry = withContext(Dispatchers.IO) {
        val normalized = requireFilePath(relativePath)
        val file = requireOrCreateFile(relativePath, mimeType)
        val mode = if (append) "wa" else "wt"
        contentResolver.openOutputStream(file.uri, mode)?.use {
            it.write(bytes)
        } ?: error("Unable to write file: $relativePath")
        WorkspaceEntry(
            path = normalized,
            name = file.name.orEmpty(),
            directory = false,
            sizeBytes = file.length(),
            mimeType = file.type
        )
    }

    suspend fun editText(relativePath: String, oldText: String, newText: String, replaceAll: Boolean): EditResult =
        withContext(Dispatchers.IO) {
            require(oldText.isNotEmpty()) { "old_text must not be empty" }
            val current = readText(relativePath)
            val count = if (replaceAll) {
                current.split(oldText).size - 1
            } else if (current.contains(oldText)) {
                1
            } else {
                0
            }
            require(count > 0) { "Text not found in $relativePath" }
            val updated = if (replaceAll) current.replace(oldText, newText) else current.replaceFirst(oldText, newText)
            writeText(relativePath, updated)
            EditResult(path = normalizePath(relativePath), replaceCount = count)
        }

    suspend fun move(sourcePath: String, targetPath: String): WorkspaceEntry = withContext(Dispatchers.IO) {
        val sourceNormalized = normalizePath(sourcePath)
        val targetNormalized = normalizePath(targetPath)
        require(sourceNormalized != targetNormalized) { "Source and target are the same path: $sourceNormalized" }
        require(!targetNormalized.startsWith("$sourceNormalized/")) {
            "Moving a path into itself is not allowed: $sourceNormalized -> $targetNormalized"
        }
        require(findDocument(targetNormalized) == null) { "Target path already exists: $targetNormalized" }
        val source = requireDocument(sourcePath)
        val targetParent = requireOrCreateParent(targetPath)
        val targetName = targetNormalized.substringAfterLast("/")
        val sourceParent = sourceNormalized.substringBeforeLast("/", ".")
        val targetParentPath = targetNormalized.substringBeforeLast("/", ".")
        val renamed = if (sourceParent == targetParentPath) {
            val moved = source.renameTo(targetName)
            require(moved) { "Unable to rename $sourcePath to $targetPath" }
            if (source.name != targetName) {
                val actualName = source.name
                val originalName = sourceNormalized.substringAfterLast('/')
                val restored = source.renameTo(originalName) && source.name == originalName
                error(
                    "Workspace provider renamed '$sourcePath' to '$actualName' instead of '$targetName'. " +
                        if (restored) "Original path was restored." else
                            "Content remains at '${joinPath(sourceParent, source.name.orEmpty())}'."
                )
            }
            source
        } else {
            val copied = copyDocument(source, targetParent, targetName)
            require(source.delete()) { "Copied but failed to delete original path: $sourcePath" }
            copied
        }
        WorkspaceEntry(
            path = targetNormalized,
            name = renamed.name.orEmpty(),
            directory = renamed.isDirectory,
            sizeBytes = if (renamed.isDirectory) null else renamed.length(),
            mimeType = renamed.type
        )
    }

    suspend fun search(query: String, relativePath: String = ".", maxResults: Int = 50): List<SearchResult> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "query is required" }
            val start = requireDocument(relativePath)
            val results = mutableListOf<SearchResult>()
            var visitedFiles = 0
            var scannedChars = 0L
            suspend fun visit(file: DocumentFile, path: String) {
                currentCoroutineContext().ensureActive()
                if (results.size >= maxResults || visitedFiles >= SEARCH_MAX_FILES || scannedChars >= SEARCH_MAX_TOTAL_CHARS) return
                if (file.isDirectory) {
                    for (child in file.listFiles()) {
                        visit(child, joinPath(path, child.name.orEmpty()))
                        if (results.size >= maxResults || visitedFiles >= SEARCH_MAX_FILES || scannedChars >= SEARCH_MAX_TOTAL_CHARS) break
                    }
                    return
                }
                visitedFiles++
                runCatching {
                    contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                        var fileChars = 0L
                        var lineNumber = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val remaining = minOf(
                                SEARCH_MAX_CHARS_PER_FILE - fileChars,
                                SEARCH_MAX_TOTAL_CHARS - scannedChars,
                            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            if (remaining <= 0) break
                            val boundedLine = reader.readLineWithinLimit(remaining) ?: break
                            val line = boundedLine.text
                            lineNumber++
                            val consumed = boundedLine.consumedChars.toLong()
                            fileChars += consumed
                            scannedChars += consumed
                            if (line.contains(query, ignoreCase = true)) {
                                results.add(SearchResult(path, lineNumber, line.take(240)))
                                break
                            }
                            if (!boundedLine.terminated ||
                                fileChars >= SEARCH_MAX_CHARS_PER_FILE ||
                                scannedChars >= SEARCH_MAX_TOTAL_CHARS
                            ) break
                        }
                    }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                }
            }
            visit(start, normalizePath(relativePath))
            results
        }

    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizePath(relativePath)
        if (normalized == ".") return@withContext false
        val file = findDocument(normalized) ?: return@withContext false
        runCatching { file.delete() }.getOrDefault(false)
    }

    private fun requireDocument(relativePath: String): DocumentFile {
        val normalized = normalizePath(relativePath)
        return findDocument(normalized) ?: error("Path not found: $normalized")
    }

    private fun findDocument(relativePath: String): DocumentFile? {
        val normalized = normalizePath(relativePath)
        if (normalized == ".") return requireRoot()
        return normalized.split("/")
            .filter { it.isNotBlank() }
            .fold(requireRoot() as DocumentFile?) { parent, segment ->
                parent?.findFile(segment)
            }
    }

    private fun requireOrCreateFile(
        relativePath: String,
        mimeType: String = "application/octet-stream",
    ): DocumentFile {
        val normalized = requireFilePath(relativePath)
        val parent = requireOrCreateParent(relativePath)
        val name = normalized.substringAfterLast("/")
        parent.findFile(name)?.let { return it }
        return createFileWithExactName(parent, name, mimeType)
    }

    private fun createFileWithExactName(parent: DocumentFile, name: String, mimeType: String): DocumentFile {
        val created = parent.createFile(mimeType, name) ?: error("Unable to create workspace file: $name")
        if (created.name == name) return created

        val renamed = runCatching { created.renameTo(name) }.getOrDefault(false)
        if (renamed) {
            parent.findFile(name)?.let { return it }
        }
        error("Workspace provider created '${created.name.orEmpty()}' instead of '$name'")
    }

    private fun requireOrCreateParent(relativePath: String): DocumentFile {
        val normalized = normalizePath(relativePath)
        val parentPath = normalized.substringBeforeLast("/", ".")
        if (parentPath == "." || parentPath == normalized) return requireRoot()
        return parentPath.split("/")
            .filter { it.isNotBlank() }
            .fold(requireRoot()) { parent, segment ->
                parent.findFile(segment) ?: parent.createDirectory(segment)
                ?: error("Unable to create directory $segment")
            }
    }

    private fun copyDocument(source: DocumentFile, targetParent: DocumentFile, targetName: String): DocumentFile {
        if (source.isDirectory) {
            val targetDir = targetParent.createDirectory(targetName)
                ?: error("Unable to create directory $targetName")
            source.listFiles().forEach { child ->
                copyDocument(child, targetDir, child.name.orEmpty())
            }
            return targetDir
        }
        val target = createFileWithExactName(targetParent, targetName, source.type ?: "application/octet-stream")
        val input = contentResolver.openInputStream(source.uri)
            ?: error("Unable to read ${source.name}")
        val output = contentResolver.openOutputStream(target.uri, "wt")
            ?: error("Unable to write $targetName")
        input.use { sourceStream ->
            output.use { targetStream ->
                sourceStream.copyTo(targetStream)
            }
        }
        return target
    }

    private fun requireFilePath(path: String): String {
        val normalized = normalizePath(path)
        require(normalized != ".") { "A file path under /workspace is required: $path" }
        return normalized
    }

    private fun normalizePath(path: String): String = WorkspacePaths.normalize(path)

    private fun textMimeTypeForPath(path: String): String =
        when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "yaml", "yml" -> "application/yaml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "csv" -> "text/csv"
            "js", "mjs", "cjs" -> "text/javascript"
            "sh" -> "application/x-sh"
            else -> "text/plain"
        }

    private fun joinPath(parent: String, child: String): String = WorkspacePaths.join(parent, child)

    private companion object {
        private const val SEARCH_MAX_FILES = 1_000
        private const val SEARCH_MAX_CHARS_PER_FILE = 1_000_000L
        private const val SEARCH_MAX_TOTAL_CHARS = 8_000_000L
    }
}
