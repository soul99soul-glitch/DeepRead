package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.Ellipsis

internal data class NovelWorkspaceManuscriptState(
    val branchSlug: String?,
    val chapters: List<NovelMarkdownChapterUi>,
    val ghostwriteJob: NovelMarkdownGhostwriteUi?,
    val busy: Boolean,
    val errorMessage: String?,
    val canUndo: Boolean,
    val consistencyChecking: Boolean,
)

@Composable
internal fun MarkdownWorkspaceManuscript(
    state: NovelWorkspaceManuscriptState,
    onOpenPolish: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onHistory: (NovelMarkdownChapterUi) -> Unit,
    onDiscard: (NovelMarkdownChapterUi) -> Unit,
    listState: LazyListState,
    onManageProjects: () -> Unit,
    loadDiscardedChapters: suspend () -> NovelMarkdownDiscardedSnapshotUi?,
    readFileBody: suspend (String) -> String?,
    onRestoreDiscardedChapter: (path: String, expectedHead: String?, expectedTree: String, onSaved: () -> Unit) -> Unit,
    onRewriteChapter: (Int) -> Boolean,
    onUndoLast: () -> Unit,
    onRunConsistencyCheck: () -> Unit,
    onClearError: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var showDiscarded by remember(state.branchSlug) { mutableStateOf(false) }
    var rewritingOrdinal by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.branchSlug) { rewritingOrdinal = null }
    LaunchedEffect(state.busy) { if (!state.busy) rewritingOrdinal = null }
    val branchLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed")
    if (showDiscarded) MarkdownDiscardedSheet(
        busy = state.busy, writeLocked = branchLocked, errorMessage = state.errorMessage,
        loadSnapshot = loadDiscardedChapters, readBody = readFileBody,
        onRestore = onRestoreDiscardedChapter,
        onDismiss = { showDiscarded = false },
    )

    var directoryMenu by remember { mutableStateOf(false) }
    val activeJob = state.ghostwriteJob
    val batchOwnsBranch = activeJob?.status in setOf("running", "paused", "failed")
    val polishOwned = batchOwnsBranch && activeJob?.mode ==
        app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
    val writeOwned = batchOwnsBranch && !polishOwned
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.novel_manuscript_directory), style = type.sessionTitle,
                color = workspace.ink, modifier = Modifier.weight(1f))
            // Keep directory actions together; move the group below the title when needed.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !writeOwned && (!state.busy || polishOwned), onClick = onOpenPolish) {
                    Icon(Lucide.WandSparkles, contentDescription = null, tint = workspace.blue, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(if (polishOwned) R.string.novel_polish_in_progress else R.string.novel_batch_polish),
                        style = type.secondary, color = workspace.blue,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Text(stringResource(R.string.novel_directory_count, state.chapters.size),
                    style = type.secondary, color = workspace.muted)
                Box {
                    IconButton(onClick = { directoryMenu = true }) {
                        Icon(Lucide.Ellipsis, contentDescription = stringResource(R.string.novel_more_actions),
                            tint = workspace.muted, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = directoryMenu, onDismissRequest = { directoryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_discarded_chapters)) },
                            onClick = { directoryMenu = false; onClearError(); showDiscarded = true },
                        )
                        if (state.canUndo) DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_undo_last)) },
                            enabled = !state.busy && !branchLocked,
                            onClick = { directoryMenu = false; onUndoLast() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_consistency_check)) },
                            enabled = !state.busy && !state.consistencyChecking,
                            onClick = { directoryMenu = false; onRunConsistencyCheck() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_manage_projects)) },
                            onClick = { directoryMenu = false; onManageProjects() },
                        )
                    }
                }
            }
        }
        if (writeOwned) Text(stringResource(R.string.novel_batch_in_use), style = type.secondary,
            color = workspace.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (state.chapters.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.novel_no_chapters), style = type.secondary, color = workspace.muted)
            }
        } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
        // Paper ends with the actual group, even when a short book leaves the viewport empty.
        itemsIndexed(state.chapters, key = { _, chapter -> chapter.path }) { index, chapterItem ->
            val rowShape = RoundedCornerShape(
                topStart = if (index == 0) 14.dp else 0.dp,
                topEnd = if (index == 0) 14.dp else 0.dp,
                bottomStart = if (index == state.chapters.lastIndex) 14.dp else 0.dp,
                bottomEnd = if (index == state.chapters.lastIndex) 14.dp else 0.dp,
            )
            Column(Modifier.fillMaxWidth().clip(rowShape).background(workspace.paper)) {
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 60.dp)
                        .height(1.dp)
                        .background(workspace.hairline),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .novelPressable(onClick = { onOpenChapter(chapterItem.path) })
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(workspace.blue.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = chapterItem.ordinal.toString(),
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.blue,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = chapterItem.title,
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.novel_character_count, chapterItem.charCount),
                        style = type.secondary,
                        color = workspace.muted,
                    )
                }
                var chapterMenu by remember(chapterItem.path) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { chapterMenu = true }) {
                        Icon(Lucide.Ellipsis, contentDescription = stringResource(R.string.novel_more_actions),
                            tint = workspace.faint, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = chapterMenu, onDismissRequest = { chapterMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (rewritingOrdinal == chapterItem.ordinal)
                                R.string.novel_rewriting_chapter else R.string.novel_rewrite_chapter)) },
                            enabled = !state.busy && !branchLocked,
                            onClick = {
                                chapterMenu = false
                                if (onRewriteChapter(chapterItem.ordinal)) rewritingOrdinal = chapterItem.ordinal
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_chapter_history)) },
                            onClick = { chapterMenu = false; onHistory(chapterItem) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_discard_chapter), color = workspace.red) },
                            enabled = !state.busy && !branchLocked,
                            onClick = { chapterMenu = false; onDiscard(chapterItem) },
                        )
                    }
                }
            }
            }
        }
        }
        }
    }
}
