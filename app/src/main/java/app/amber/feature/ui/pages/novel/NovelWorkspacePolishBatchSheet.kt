package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Minus

/**
 * 批量润色 sheet：范围选择（默认第 1 章～最新章）与批次进度（进度条 + 暂停/继续/重试/
 * 取消），骨架与项目控制面板的批量进度区一致，文案区分润色/代笔。
 * 批次状态复用 state.ghostwriteJob 槽（分支同时只允许一个批次，代笔占用时此处只读提示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PolishBatchSheet(
    job: NovelMarkdownGhostwriteUi?,
    busy: Boolean,
    latestOrdinal: Int,
    onStart: (fromOrdinal: Int, toOrdinal: Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val polish = job?.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = tokens.raised,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "//",
                    style = type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = chatTheme.accent,
                )
                Text(
                    stringResource(R.string.novel_batch_polish),
                    style = type.sessionTitle.copy(fontWeight = FontWeight.Bold),
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.novel_polish_badge),
                    style = type.meta.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                    ),
                    color = tokens.ink4,
                )
            }
            if (errorMessage != null) {
                Text(errorMessage, style = type.meta, color = workspace.red)
            }

            PanelSection(title = stringResource(R.string.novel_polish_progress)) {
                when {
                    // 代笔批次占用：只读提示，不能从这里开新批次（两者互斥）。
                    job != null && !polish -> {
                        Text(
                            stringResource(R.string.novel_polish_blocked_by_ghostwrite),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                    job != null && job.status == "failed" -> {
                        Text(
                            stringResource(
                                R.string.novel_polish_batch_failed,
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
                    }
                    job != null -> {
                        val paused = job.status == "paused"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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
                                Text(
                                    stringResource(R.string.novel_number, job.written),
                                    style = type.screenTitle.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                                    ),
                                    color = workspace.ink,
                                )
                                Text(
                                    stringResource(R.string.novel_chapter_progress, job.target),
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
                            stringResource(R.string.novel_polish_progress_note),
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
                    else -> {
                        if (latestOrdinal <= 0) {
                            Text(
                                stringResource(R.string.novel_no_chapters_to_polish),
                                style = type.meta,
                                color = workspace.muted,
                            )
                        } else {
                            var fromOrdinal by remember { mutableStateOf(1) }
                            var toOrdinal by remember(latestOrdinal) { mutableStateOf(latestOrdinal) }
                            Text(
                                stringResource(R.string.novel_polish_range_desc),
                                style = type.meta,
                                color = workspace.muted,
                            )
                            RangeStepperRow(
                                label = stringResource(R.string.novel_start_chapter),
                                value = fromOrdinal,
                                bounds = 1..toOrdinal,
                                onChange = { fromOrdinal = it },
                            )
                            RangeStepperRow(
                                label = stringResource(R.string.novel_end_chapter),
                                value = toOrdinal,
                                bounds = fromOrdinal..latestOrdinal,
                                onChange = { toOrdinal = it },
                            )
                            PanelCtaButton(
                                text = stringResource(R.string.novel_start_polish),
                                enabled = !busy && fromOrdinal <= toOrdinal,
                                onClick = { onStart(fromOrdinal, toOrdinal) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 起/止章 stepper row（与代笔的章数步进同款几何）。 */
@Composable
private fun RangeStepperRow(
    label: String,
    value: Int,
    bounds: IntRange,
    onChange: (Int) -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PanelRoundIcon(
            icon = Lucide.Minus,
            contentDescription = stringResource(R.string.novel_decrease_chapter_named, label),
            enabled = value > bounds.first,
            onClick = { if (value > bounds.first) onChange(value - 1) },
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    style = type.meta,
                    color = workspace.muted,
                )
                Text(
                    stringResource(R.string.novel_chapter_number, value),
                    style = type.body.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                    ),
                    color = workspace.ink,
                )
            }
        }
        PanelRoundIcon(
            icon = Lucide.Plus,
            contentDescription = stringResource(R.string.novel_increase_chapter_named, label),
            enabled = value < bounds.last,
            onClick = { if (value < bounds.last) onChange(value + 1) },
        )
    }
}
