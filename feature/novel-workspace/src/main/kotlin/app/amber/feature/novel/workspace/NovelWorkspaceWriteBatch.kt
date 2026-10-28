package app.amber.feature.novel.workspace

/** Buffered owner-gated writes of one turn; the runtime applies them as a single commit. */
class NovelWorkspaceWriteBatch {
    private val entries = mutableListOf<NovelWorkspaceWriteEntry>()
    private var freeWrites = 0

    /**
     * Pre-turn content of auto-approved writes (null = file did not exist).
     * A failed turn restores these: an uncommitted orphan chapter would later join
     * a fresh chapter in one commit and trip the D-D unresolved gate.
     */
    private val previousContents = mutableMapOf<String, String?>()

    fun add(entry: NovelWorkspaceWriteEntry) {
        // Last write to the same path within a turn wins.
        entries.removeAll { it.path == entry.path }
        entries.add(entry)
    }

    fun rememberPrevious(path: String, content: String?) {
        if (!previousContents.containsKey(path)) previousContents[path] = content
    }

    fun previousSnapshot(): Map<String, String?> = previousContents.toMap()

    fun snapshot(): List<NovelWorkspaceWriteEntry> = entries.toList()

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Free writes (setting/inbox/drafts) land on disk immediately; tracked so the
     *  runtime can commit them on success or restore their pre-turn content on failure. */
    fun noteFreeWrite() {
        freeWrites += 1
    }

    fun hasFreeWrites(): Boolean = freeWrites > 0
}

data class NovelWorkspaceWriteEntry(
    val path: String,
    val content: String,
    val reason: String?,
)
