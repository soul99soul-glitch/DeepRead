package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.ai.core.MessageRole
import app.amber.agent.R
import app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget
import app.amber.feature.novel.workspace.NovelWorkspaceWriteEntry
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BotMessageSquare
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.X
import com.composables.icons.lucide.PenLine

internal data class NovelWorkspaceChatState(
    val branchSlug: String?,
    val timeline: List<NovelMarkdownTimelineRow>,
    val chapters: List<NovelMarkdownChapterUi>,
    val ghostwriteJob: NovelMarkdownGhostwriteUi?,
    val busy: Boolean,
    val errorMessage: String?,
    val plotStale: Boolean,
    val unresolvedFromOrdinal: Int?,
    val streamingText: String,
    val toolActivity: String?,
    val composerMode: NovelMarkdownComposerMode,
)

@Composable
internal fun MarkdownWorkspaceChat(
    state: NovelWorkspaceChatState,
    draft: String,
    onDraftChange: (String) -> Unit,
    listState: LazyListState,
    onOpenGhostwrite: () -> Unit,
    readFileBody: suspend (String) -> String?,
    readFileRaw: suspend (String) -> String?,
    onSaveFileEdit: (path: String, body: String, onSaved: () -> Unit) -> Unit,
    onCollectDraft: (path: String, target: NovelWorkspaceCollectTarget, onCollected: () -> Unit) -> Unit,
    onArchiveDecision: (messageId: String, title: String, body: String, onSaved: () -> Unit) -> Unit,
    onEditProposal: (id: String, entries: List<NovelWorkspaceWriteEntry>, onSaved: () -> Unit) -> Unit,
    onApproveProposal: (id: String, onApproved: () -> Unit) -> Unit,
    onRejectProposal: (String) -> Unit,
    onSetComposerMode: (NovelMarkdownComposerMode) -> Unit,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onProposeCharacter: (name: String, sketch: String) -> Unit,
    onRewriteLaterChapters: () -> Unit,
    onResolveUnresolved: () -> Unit,
    onClearError: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val ghostwriteLabel = stringResource(R.string.novel_ghostwrite)
    val polishLabel = stringResource(R.string.novel_polish)
    var reviewDraft by remember(state.branchSlug) { mutableStateOf<NovelMarkdownDraftUi?>(null) }
    var reviewProposalId by remember(state.branchSlug) { mutableStateOf<String?>(null) }
    var archiveMessage by remember(state.branchSlug) { mutableStateOf<NovelMarkdownMessageUi?>(null) }
    val reviewLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed")
    archiveMessage?.let { message ->
        MarkdownMaterialCreateSheet(
            busy = state.busy, writeLocked = reviewLocked, errorMessage = state.errorMessage,
            decision = true, initialBody = message.content,
            onCreate = { _, title, body, done -> onArchiveDecision(message.id, title, body, done) },
            onDismiss = { archiveMessage = null },
        )
    }
    reviewDraft?.let { candidate ->
        MarkdownDraftReviewSheet(
            draft = candidate, chapters = state.chapters, busy = state.busy,
            writeLocked = reviewLocked, errorMessage = state.errorMessage,
            readBody = readFileBody,
            onSave = onSaveFileEdit,
            onCollect = { target, onCollected ->
                onCollectDraft(candidate.path, target, onCollected)
            },
            onDismiss = { reviewDraft = null },
        )
    }
    val reviewedProposal = state.timeline.find {
        it is NovelMarkdownTimelineRow.Proposal && it.proposal.id == reviewProposalId
    } as? NovelMarkdownTimelineRow.Proposal
    reviewedProposal?.proposal?.let { proposal ->
        MarkdownProposalReviewSheet(
            proposal = proposal, busy = state.busy, writeLocked = reviewLocked,
            errorMessage = state.errorMessage, readRaw = readFileRaw,
            onSave = { entries, onSaved -> onEditProposal(proposal.id, entries, onSaved) },
            onApprove = { onApproved -> onApproveProposal(proposal.id, onApproved) },
            onReject = { onRejectProposal(proposal.id) },
            onDismiss = { reviewProposalId = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // A running batch must be visible on the page itself (device-observed: the
        // user cannot tell whether anything is happening when this OEM blocks the
        // foreground-service notification).
        val activeJob = state.ghostwriteJob
        val batchActive = activeJob?.status == "running" || activeJob?.status == "paused"
        val branchOwned = batchActive || activeJob?.status == "failed"
        if (activeJob != null && batchActive) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenGhostwrite)
                    .background(workspace.canvas)
                    .border(1.dp, workspace.hairline, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (activeJob.status == "running") {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = workspace.blue,
                    )
                }
                val batchLabel = if (
                    activeJob.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                ) {
                    polishLabel
                } else {
                    ghostwriteLabel
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        ghostwriteStageLabel(activeJob),
                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (activeJob.status == "running") {
                            stringResource(
                                R.string.novel_batch_running,
                                batchLabel,
                                activeJob.written,
                                activeJob.target,
                            )
                        } else {
                            stringResource(
                                R.string.novel_batch_paused,
                                batchLabel,
                                activeJob.written,
                                activeJob.target,
                            )
                        },
                        style = type.tinyTag,
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth(0.28f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(workspace.hairline),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                if (activeJob.target == 0) 0f else activeJob.written.toFloat() / activeJob.target,
                            )
                            .fillMaxHeight()
                            .background(workspace.green),
                    )
                }
            }
        }
        // Chapters created by the current batch naturally make plot stale; resume is
        // allowed from that durable cursor. Show the repair instruction once it releases.
        if (state.plotStale && !branchOwned) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(workspace.canvas)
                    .drawBehind {
                        drawLine(workspace.hairline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                    }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.novel_plot_stale_summary),
                    style = type.secondary,
                    color = workspace.muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        onSetComposerMode(NovelMarkdownComposerMode.Discuss)
                        onSend("根据最新正文同步 plot/current.md")
                    },
                ) {
                    Text(stringResource(R.string.novel_sync_plot), style = type.secondary, color = workspace.amber)
                }
            }
        }
        state.unresolvedFromOrdinal?.let { fromOrdinal ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(workspace.canvas)
                    .drawBehind {
                        drawLine(workspace.hairline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                    }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.novel_unresolved_chapter_warning, fromOrdinal),
                    style = type.secondary,
                    color = workspace.muted,
                )
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(onClick = { onRewriteLaterChapters() }, enabled = !state.busy) {
                        Text(stringResource(R.string.novel_rewrite_later_chapters), style = type.secondary, color = workspace.amber)
                    }
                    TextButton(onClick = { onResolveUnresolved() }, enabled = !state.busy) {
                        Text(stringResource(R.string.novel_mark_resolved), style = type.secondary, color = workspace.muted)
                    }
                }
            }
        }
        // reverseLayout + newest-first ordering: fresh messages, the streaming bubble,
        // and errors are index 0-2, i.e. pinned at the visual bottom where the user is
        // looking — no scroll bookkeeping, no yank when history grows (device-observed:
        // long assistant answers overflowed the viewport with no auto-follow).
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.errorMessage?.let { message ->
                item(key = "error") {
                    Text(
                        message,
                        style = type.meta,
                        color = workspace.red,
                        modifier = Modifier.clickable { onClearError() },
                    )
                }
            }
            if (state.streamingText.isNotEmpty()) {
                item(key = "streaming") {
                    MarkdownChatBubble(isUser = false, content = state.streamingText, streaming = true)
                }
            }
            if (state.busy) {
                item(key = "activity") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = workspace.muted,
                        )
                        Text(
                            state.toolActivity ?: stringResource(R.string.novel_thinking),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                }
            }
            // Messages, drafts and approvals keep their real creation order in one timeline.
            items(state.timeline, key = { it.key }) { row ->
                Column(Modifier.fillMaxWidth().animateItem()) {
                when (row) {
                    is NovelMarkdownTimelineRow.Proposal -> {
                        val proposal = row.proposal
                        MarkdownProposalCard(
                            proposal = proposal,
                            busy = state.busy || branchOwned,
                            readRaw = readFileRaw,
                            onApprove = { onApproveProposal(proposal.id) {} },
                            onReview = { onClearError(); reviewProposalId = proposal.id },
                            onReject = { onRejectProposal(proposal.id) },
                        )
                    }
                    is NovelMarkdownTimelineRow.Draft -> MarkdownDraftCard(
                        draft = row.draft,
                        onReview = { onClearError(); reviewDraft = row.draft },
                    )
                    is NovelMarkdownTimelineRow.Message -> {
                        val message = row.message
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (message.kind == "interrupted") Text(
                                stringResource(R.string.novel_output_interrupted),
                                style = type.secondary, color = workspace.muted,
                            )
                            MarkdownChatBubble(isUser = message.role == MessageRole.USER, content = message.content)
                            if (message.role == MessageRole.ASSISTANT && message.kind != "interrupted") {
                                TextButton(
                                    onClick = { onClearError(); archiveMessage = message },
                                    enabled = !state.busy && !reviewLocked,
                                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.novel_archive_decision), style = type.secondary) }
                            }
                        }
                    }
                }
                }
            }
            if (state.timeline.isEmpty() && !state.busy) {
                item {
                    Text(
                        stringResource(R.string.novel_chat_empty),
                        style = type.secondary,
                        color = workspace.muted,
                    )
                }
            }
        }

        // 快捷动作：提案角色 —— 对话框收集角色名与一句话设想，交给模型把人物卡
        // 写入 setting/characters/（自由写路径，直存无需审批）。
        var showCharacterProposal by remember { mutableStateOf(false) }
        val branchLocked = state.ghostwriteJob?.status == "running" ||
            state.ghostwriteJob?.status == "paused" ||
            state.ghostwriteJob?.status == "failed"
        if (showCharacterProposal) {
            CharacterProposalDialog(
                busy = state.busy || branchLocked,
                onSubmit = { name, sketch ->
                    showCharacterProposal = false
                    onProposeCharacter(name, sketch)
                },
                onDismiss = { showCharacterProposal = false },
            )
        }

        // One compact input row; less frequent actions live in its existing mode menu.
        val tokens = LocalAmberTokens.current
        val accent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent
        val onAccent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.onAccent
        var modeMenu by remember { mutableStateOf(false) }
        // The tray shares the canvas and separates from the timeline with one hairline.
        Column(
            Modifier
                .fillMaxWidth()
                .background(workspace.canvas)
                .drawBehind {
                    drawLine(
                        color = workspace.hairline,
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(x = 0f, y = 0f),
                        end = Offset(x = size.width, y = 0f),
                    )
                },
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box {
                WorkspaceComposerButton(
                    icon = if (state.composerMode == NovelMarkdownComposerMode.WriteProse) Lucide.PenLine else Lucide.BotMessageSquare,
                    contentDescription = stringResource(R.string.novel_composer_mode),
                    enabled = !state.busy,
                    containerColor = workspace.paper,
                    tint = if (state.composerMode == NovelMarkdownComposerMode.WriteProse) accent else tokens.ink3,
                    onClick = { modeMenu = true },
                )
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    listOf(
                        NovelMarkdownComposerMode.Discuss to stringResource(R.string.novel_mode_discuss),
                        NovelMarkdownComposerMode.WriteProse to stringResource(R.string.novel_mode_write_prose),
                    ).forEach { (mode, label) ->
                        // Graphite: color is the only selection signal — no check marks.
                        val selected = state.composerMode == mode
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    style = type.body,
                                    color = if (selected) accent else workspace.ink,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                onSetComposerMode(mode)
                                modeMenu = false
                            },
                        )
                    }
                    HorizontalDivider(color = workspace.hairline)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_propose_character), style = type.body) },
                        enabled = !state.busy && !branchLocked,
                        onClick = { modeMenu = false; showCharacterProposal = true },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(workspace.paper)
                    .border(1.dp, workspace.hairline, RoundedCornerShape(24.dp))
                    .padding(start = 18.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = !state.busy,
                    textStyle = type.body.copy(color = workspace.ink),
                    cursorBrush = SolidColor(workspace.ink),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 120.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_input_compose_placeholder),
                                    style = type.body,
                                    color = workspace.faint,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            val hasDraft = draft.isNotBlank()
            WorkspaceComposerButton(
                icon = if (state.busy) Lucide.X else Lucide.ArrowUp,
                contentDescription = stringResource(if (state.busy) R.string.stop else R.string.send),
                enabled = state.busy || hasDraft,
                containerColor = if (state.busy || hasDraft) accent else workspace.paper,
                tint = if (state.busy || hasDraft) onAccent else tokens.ink3,
                onClick = {
                    if (state.busy) onStop()
                    else if (onSend(draft)) onDraftChange("")
                },
            )
        }
        }
    }
}

@Composable
private fun WorkspaceComposerButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    containerColor: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Box(
        Modifier.size(48.dp).novelPressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(containerColor)
                .border(1.dp, workspace.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** 提案角色对话框：角色名 + 一句话设想，提交后走 proposeCharacter 轮。 */
@Composable
private fun CharacterProposalDialog(
    busy: Boolean,
    onSubmit: (name: String, sketch: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var name by remember { mutableStateOf("") }
    var sketch by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = workspace.paper,
        title = {
            Text(
                stringResource(R.string.novel_propose_character),
                fontWeight = FontWeight.SemiBold,
                color = workspace.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.novel_character_name),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sketch,
                    onValueChange = { sketch = it },
                    enabled = !busy,
                    placeholder = {
                        Text(
                            stringResource(R.string.novel_character_idea_hint),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = { onSubmit(name, sketch) },
            ) {
                Text(stringResource(R.string.novel_generate_proposal), color = workspace.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = workspace.muted)
            }
        },
    )
}

@Composable
private fun MarkdownChatBubble(isUser: Boolean, content: String, streaming: Boolean = false) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    if (!isUser) {
        app.amber.feature.ui.components.richtext.MarkdownBlock(
            content = content,
            style = type.body.copy(color = workspace.ink),
            streaming = streaming,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier
                .widthIn(max = maxWidth * 0.82f)
                .clip(RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp))
                .background(workspace.ink)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(content, style = type.body, color = workspace.canvas)
        }
    }
}
