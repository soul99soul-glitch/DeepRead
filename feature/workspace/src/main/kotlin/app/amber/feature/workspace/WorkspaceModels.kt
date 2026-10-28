package app.amber.feature.workspace

import android.net.Uri
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class WorkspaceState(
    val treeUri: Uri?,
    val displayName: String?,
) {
    val configured: Boolean get() = treeUri != null
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long?,
    val mimeType: String?,
)

data class WorkspaceTextRead(
    val content: String,
    val truncated: Boolean,
)

data class EditResult(
    val path: String,
    val replaceCount: Int,
)

data class SearchResult(
    val path: String,
    val lineNumber: Int,
    val preview: String,
)

data class WorkspaceMirrorResult<T>(
    val value: T,
    val syncNote: String,
)

class WorkspaceMirrorLease internal constructor(
    val directory: File,
    val preparationNote: String,
    private val releaseBlock: suspend (syncBack: Boolean) -> String,
) {
    private val released = AtomicBoolean(false)

    suspend fun release(syncBack: Boolean): String {
        if (!released.compareAndSet(false, true)) return ""
        return releaseBlock(syncBack)
    }
}
