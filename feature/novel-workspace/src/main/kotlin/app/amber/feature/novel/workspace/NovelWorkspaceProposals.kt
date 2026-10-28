package app.amber.feature.novel.workspace

import java.io.File
import java.time.Instant

data class NovelWorkspaceProposalContext(
    val projectDirectory: File,
    val branchId: String,
    val branchSlug: String,
    val restoreEpoch: Long,
)

data class NovelWorkspaceWriteProposal(
    val id: String,
    val projectDirectory: File,
    val branchId: String,
    val branchSlug: String,
    val baseHeadId: String?,
    val baseTreeDigest: String,
    val planId: String? = null,
    val planDigest: String? = null,
    val entries: List<NovelWorkspaceWriteEntry>,
    val createdAt: Instant,
)
