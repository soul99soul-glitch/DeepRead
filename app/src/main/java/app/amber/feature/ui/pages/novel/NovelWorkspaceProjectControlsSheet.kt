package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteCoordinator
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.ChevronRight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelWorkspaceProjectControlsSheet(
    job: NovelMarkdownGhostwriteUi?,
    ghostwriteMode: Boolean,
    branchLabel: String,
    plotStale: Boolean,
    unresolvedFromOrdinal: Int?,
    onGhostwriteModeChange: (Boolean) -> Unit,
    onBranch: () -> Unit,
    onOpenPolish: () -> Unit,
    busy: Boolean,
    onStart: (Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onInjectionChange: (app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags) -> Unit,
    onGeneratePlan: () -> Unit,
    readChapterPlan: suspend () -> String,
    saveChapterPlan: (String) -> Boolean,
    readUpcomingArc: suspend () -> String,
    saveUpcomingArc: (String) -> Boolean,
    readWritingPreference: suspend () -> String,
    saveWritingPreference: (String, () -> Unit) -> Unit,
    briefPreview: suspend () -> String,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    injection: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags =
        app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
    planAutoTick: Int = 0,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val branchOwned = job?.status == "running" ||
        job?.status == "paused" ||
        job?.status == "failed"
    val ghostwriteLabel = stringResource(R.string.novel_ghostwrite)
    val polishLabel = stringResource(R.string.novel_polish)
    val canStartBatch = job == null || job.status in setOf("completed", "cancelled")
    var controlsTab by remember { mutableStateOf(0) }
    var ghostwriteTarget by remember { mutableStateOf(1) }
    val chapterPlanInitial by produceState<String?>(initialValue = null, planAutoTick) {
        value = readChapterPlan()
    }
    val loadedChapterPlan = chapterPlanInitial
    var chapterPlanText by remember(planAutoTick, chapterPlanInitial) {
        mutableStateOf(chapterPlanInitial.orEmpty())
    }
    val chapterPlanDirty = chapterPlanText != chapterPlanInitial.orEmpty()
    val futureArcInitial by produceState<String?>(initialValue = null, planAutoTick) {
        value = readUpcomingArc()
    }
    var futureArcText by remember(futureArcInitial) { mutableStateOf(futureArcInitial.orEmpty()) }
    val writingPreferenceInitial by produceState<String?>(initialValue = null) {
        value = readWritingPreference()
    }
    var writingPreferenceText by remember(writingPreferenceInitial) {
        mutableStateOf(writingPreferenceInitial.orEmpty())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = workspace.canvas,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_page_close), color = workspace.muted)
                }
                Text(
                    stringResource(R.string.novel_project_controls),
                    style = type.screenTitle,
                    color = workspace.ink,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    enabled = ghostwriteMode && canStartBatch && !busy && !plotStale &&
                        unresolvedFromOrdinal == null && loadedChapterPlan != null && chapterPlanText.isNotBlank(),
                    onClick = {
                        if (!chapterPlanDirty || saveChapterPlan(chapterPlanText)) onStart(ghostwriteTarget)
                    },
                ) { Text(stringResource(R.string.novel_start)) }
            }
            NovelWorkspaceOptions(
                labels = listOf(stringResource(R.string.novel_mode_preferences), stringResource(R.string.novel_context_injection)),
                selected = controlsTab,
                onSelect = { controlsTab = it },
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (errorMessage != null) {
                    Text(errorMessage, style = type.meta, color = workspace.red)
                }

            if (controlsTab == 0) {
                PanelSection(title = stringResource(R.string.novel_creation_mode)) {
                    NovelWorkspaceOptions(
                        labels = listOf(
                            stringResource(R.string.novel_collaboration_co_create),
                            stringResource(R.string.novel_collaboration_ghostwrite),
                        ),
                        selected = if (ghostwriteMode) 1 else 0,
                        enabled = !busy && !branchOwned,
                        onSelect = { onGhostwriteModeChange(it == 1) },
                    )
                    Text(
                        stringResource(if (ghostwriteMode) R.string.novel_ghostwrite_mode_description else R.string.novel_co_creation_description),
                        style = type.secondary, color = workspace.muted,
                    )
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(enabled = !busy && !branchOwned, role = Role.Button, onClick = onBranch),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.novel_branches_title), style = type.body, color = workspace.ink,
                            modifier = Modifier.weight(1f))
                        Text(branchLabel, style = type.secondary, color = workspace.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        Icon(Lucide.ChevronRight, contentDescription = stringResource(R.string.novel_switch_branch),
                            modifier = Modifier.padding(start = 8.dp).size(16.dp), tint = workspace.faint)
                    }
                }
                if (ghostwriteMode || branchOwned) {
            PanelSection(title = stringResource(R.string.novel_batch_progress)) {
                if (job == null || job.status == "completed" || job.status == "cancelled") {
                    Text(
                        stringResource(R.string.novel_ghostwrite_target_desc),
                        style = type.meta,
                        color = workspace.muted,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        PanelRoundIcon(
                            icon = Lucide.Minus,
                            contentDescription = stringResource(R.string.novel_decrease_chapter),
                            enabled = ghostwriteTarget > 1,
                            onClick = { if (ghostwriteTarget > 1) ghostwriteTarget -= 1 },
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.novel_number, ghostwriteTarget),
                                    style = type.screenTitle.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                                    ),
                                    color = workspace.ink,
                                )
                                Text(
                                    stringResource(R.string.novel_chapter_unit),
                                    style = type.meta,
                                    color = workspace.muted,
                                )
                            }
                        }
                        PanelRoundIcon(
                            icon = Lucide.Plus,
                            contentDescription = stringResource(R.string.novel_increase_chapter),
                            enabled = ghostwriteTarget < NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS,
                            onClick = {
                                if (ghostwriteTarget < NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS) {
                                    ghostwriteTarget += 1
                                }
                            },
                        )
                    }
                } else if (job.status == "failed") {
                    val failedLabel = if (
                        job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                    ) {
                        polishLabel
                    } else {
                        ghostwriteLabel
                    }
                    Text(
                        stringResource(
                            R.string.novel_batch_failed,
                            failedLabel,
                            job.written,
                            job.target,
                        ),
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.red,
                    )
                    Text(
                        ghostwriteStageLabel(job),
                        style = type.meta,
                        color = workspace.red,
                    )
                    Text(
                        job.reason ?: stringResource(R.string.novel_unknown_reason),
                        style = type.meta,
                        color = workspace.red,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelPill(
                            text = stringResource(R.string.novel_dismiss),
                            onClick = onDismissFailure,
                        )
                        PanelPill(
                            text = stringResource(R.string.novel_continue_remaining),
                            tone = PanelTone.Accent,
                            enabled = !busy,
                            onClick = onRetryFailed,
                        )
                    }
                } else {
                    val paused = job.status == "paused"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Graphite: signal green = liveness (running); paused stays quiet.
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (paused) workspace.row else workspace.greenContainer)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (!paused) {
                                    // Signal live-dot (spec: green dot = liveness).
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(workspace.green),
                                    )
                                }
                                Text(
                                    if (paused) {
                                        stringResource(R.string.novel_paused)
                                    } else {
                                        stringResource(R.string.novel_in_progress)
                                    },
                                    style = type.tinyTag,
                                    color = if (paused) workspace.muted else workspace.green,
                                )
                            }
                        }
                        Text(
                            ghostwriteStageLabel(job),
                            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            val runningLabel = if (
                                job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                            ) {
                                polishLabel
                            } else {
                                ghostwriteLabel
                            }
                            Text(
                                stringResource(R.string.novel_number, job.written),
                                style = type.screenTitle.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = app.amber.feature.ui.theme.AmberMono,
                                ),
                                color = workspace.ink,
                            )
                            Text(
                                stringResource(R.string.novel_batch_progress_detail, job.target, runningLabel),
                                style = type.secondary,
                                color = workspace.muted,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(workspace.hairline),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(if (job.target == 0) 0f else job.written.toFloat() / job.target)
                                .fillMaxHeight()
                                .background(chatTheme.accent),
                        )
                    }
                    Text(
                        stringResource(R.string.novel_ghostwrite_progress_note),
                        style = type.meta,
                        color = workspace.muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelPill(
                            text = if (paused) {
                                stringResource(R.string.novel_continue)
                            } else {
                                stringResource(R.string.novel_pause)
                            },
                            onClick = if (paused) onResume else onPause,
                        )
                        PanelPill(
                            text = stringResource(R.string.novel_cancel_batch),
                            tone = PanelTone.Danger,
                            onClick = onCancel,
                        )
                    }
                }
            }

            }

            // ── editable control files (one frame: the card; editor is flat) ──
            if (ghostwriteMode) {
            PanelSection(
                title = stringResource(R.string.novel_chapter_plan),
                action = {
                    PanelPill(
                        text = if (busy) {
                            stringResource(R.string.novel_generating)
                        } else {
                            stringResource(R.string.novel_generate_once)
                        },
                        tone = PanelTone.Accent,
                        enabled = !busy && !branchOwned,
                        onClick = onGeneratePlan,
                    )
                },
            ) {
                Text(
                    stringResource(R.string.novel_chapter_plan_desc),
                    style = type.meta,
                    color = workspace.muted,
                )
                if (loadedChapterPlan == null) {
                    PanelLoading()
                } else {
                    PanelEditor(
                        placeholder = stringResource(R.string.novel_chapter_plan_placeholder),
                        initial = loadedChapterPlan,
                        text = chapterPlanText,
                        enabled = !busy && !branchOwned,
                        onTextChange = { chapterPlanText = it },
                        onSave = { body, onSaved ->
                            if (saveChapterPlan(body)) onSaved()
                        },
                    )
                }
            }

            }
            PanelSection(title = stringResource(R.string.novel_future_arc)) {
                Text(
                    stringResource(R.string.novel_future_arc_desc),
                    style = type.meta,
                    color = workspace.muted,
                )
                val loadedFutureArc = futureArcInitial
                if (loadedFutureArc == null) {
                    PanelLoading()
                } else {
                    PanelEditor(
                        placeholder = stringResource(R.string.novel_future_arc_placeholder),
                        initial = loadedFutureArc,
                        text = futureArcText,
                        onTextChange = { futureArcText = it },
                        enabled = !busy && !branchOwned,
                        onSave = { body, onSaved ->
                            if (saveUpcomingArc(body)) onSaved()
                        },
                    )
                }
            }

            PanelSection(title = stringResource(R.string.novel_writing_preferences)) {
                Text(
                    stringResource(R.string.novel_writing_preferences_desc),
                    style = type.meta,
                    color = workspace.muted,
                )
                val loadedWritingPreference = writingPreferenceInitial
                if (loadedWritingPreference == null) {
                    PanelLoading()
                } else {
                    PanelEditor(
                        placeholder = stringResource(R.string.novel_writing_preferences_placeholder),
                        initial = loadedWritingPreference,
                        text = writingPreferenceText,
                        onTextChange = { writingPreferenceText = it },
                        enabled = !busy && !branchOwned,
                        onSave = saveWritingPreference,
                    )
                }
            }

                TextButton(
                    onClick = onOpenPolish,
                    enabled = !busy && !branchOwned,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.novel_batch_polish),
                        style = type.body,
                        color = if (!busy && !branchOwned) workspace.blue else workspace.faint,
                    )
                }
                if (plotStale && !branchOwned) {
                    Text(stringResource(R.string.novel_plot_stale_warning), style = type.secondary, color = workspace.amber)
                }
                unresolvedFromOrdinal?.let {
                    Text(stringResource(R.string.novel_unresolved_chapter_warning, it), style = type.secondary, color = workspace.amber)
                }
            } else {
            // ── injection selection (hairline rows, no nested fills) ────────
            PanelSection(title = stringResource(R.string.novel_context_injection)) {
                val rows = listOf(
                    Triple(
                        stringResource(R.string.novel_injection_plot_title),
                        stringResource(R.string.novel_injection_plot_desc),
                        injection.plot,
                    ),
                    Triple(
                        stringResource(R.string.novel_injection_foreshadowing_title),
                        stringResource(R.string.novel_injection_foreshadowing_desc),
                        injection.foreshadowing,
                    ),
                    Triple(
                        stringResource(R.string.novel_injection_neighborhood_title),
                        stringResource(R.string.novel_injection_neighborhood_desc),
                        injection.neighborhood,
                    ),
                    Triple(
                        stringResource(R.string.novel_injection_decisions_title),
                        stringResource(R.string.novel_injection_decisions_desc),
                        injection.decisions,
                    ),
                )
                rows.forEachIndexed { index, (label, desc, checked) ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .height(1.dp)
                                .background(workspace.hairline),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy && !branchOwned) {
                                onInjectionChange(
                                    when (index) {
                                        0 -> injection.copy(plot = !checked)
                                        1 -> injection.copy(foreshadowing = !checked)
                                        2 -> injection.copy(neighborhood = !checked)
                                        else -> injection.copy(decisions = !checked)
                                    },
                                )
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = type.body, color = workspace.ink)
                            Text(desc, style = type.tinyTag, color = workspace.faint)
                        }
                        Switch(
                            checked = checked,
                            enabled = !busy && !branchOwned,
                            onCheckedChange = {
                                onInjectionChange(
                                    when (index) {
                                        0 -> injection.copy(plot = it)
                                        1 -> injection.copy(foreshadowing = it)
                                        2 -> injection.copy(neighborhood = it)
                                        else -> injection.copy(decisions = it)
                                    },
                                )
                            },
                            modifier = Modifier.height(28.dp),
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = chatTheme.accent,
                                uncheckedTrackColor = workspace.hairline,
                            ),
                        )
                    }
                }
            }

            // ── injected-brief preview (flat text, no inner box) ────────────
            PanelSection(title = stringResource(R.string.novel_injected_brief_title)) {
                val brief by produceState<String?>(initialValue = null, injection, planAutoTick) {
                    value = briefPreview()
                }
                if (brief == null) {
                    PanelLoading()
                } else {
                    Text(
                        if (brief.orEmpty().isBlank()) {
                            stringResource(R.string.novel_injected_brief_empty)
                        } else {
                            brief.orEmpty()
                        },
                        style = type.meta,
                        color = workspace.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }

            }
            }
        }
    }
}
