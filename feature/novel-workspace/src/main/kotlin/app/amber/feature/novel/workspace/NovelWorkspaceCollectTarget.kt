package app.amber.feature.novel.workspace

/** Where a collected draft lands in the manuscript. */
sealed interface NovelWorkspaceCollectTarget {
    /** Whole draft becomes a brand-new chapter appended at the end. */
    data object NewChapter : NovelWorkspaceCollectTarget

    /** Draft appended to the end of an existing chapter. */
    data class AppendToChapter(val chapterPath: String) : NovelWorkspaceCollectTarget

    /** Existing chapter body replaced by the draft. */
    data class ReplaceChapter(val chapterPath: String) : NovelWorkspaceCollectTarget
}
