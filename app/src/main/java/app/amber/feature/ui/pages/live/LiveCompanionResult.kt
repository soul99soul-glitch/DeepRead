package app.amber.feature.ui.pages.live

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Sparkles
import app.amber.agent.R
import app.amber.core.utils.appLocale
import app.amber.feature.live.FILL_CONFIRM_WINDOW_MS
import app.amber.feature.live.LiveFillResult
import app.amber.feature.live.LiveModeCard
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.live.LiveMotion
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.StreamingTextWithCursor
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatLiveTime(timestampMs: Long, locale: Locale): String =
    SimpleDateFormat("HH:mm", locale).format(Date(timestampMs))

@Composable
internal fun LiveResultCard(
    card: LiveModeCard,
    state: LiveModeUiState,
    modelId: String?,
    actionKey: String,
    stale: Boolean,
    pendingAction: String,
    enabled: Boolean,
    highlight: Float,
    onInstruction: (String) -> Unit,
    onFillDraft: () -> LiveFillResult,
    onSaveCard: () -> Unit,
    onSendToChat: () -> Unit,
    onRemember: () -> Unit,
    onCopyCard: () -> Unit,
) {
    val appLocale = LocalContext.current.appLocale()
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val currentAppFallback = stringResource(R.string.live_current_app)
    val uncertainResultText = stringResource(R.string.live_result_uncertain)
    val screenUnclearText = stringResource(R.string.live_result_screen_unclear)
    val noClearRiskText = stringResource(R.string.live_result_no_clear_risk)
    // stale = 卡片脱离现场：整卡淡去 + hairline 变暗；highlight = 深链进入的 accent 边框脉冲。
    val staleAlpha by animateFloatAsState(
        targetValue = if (stale) 0.78f else 1f,
        animationSpec = tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut),
        label = "resultStaleAlpha",
    )
    val staleBorder by animateColorAsState(
        targetValue = if (stale) t.ink4 else t.line,
        animationSpec = tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut),
        label = "resultStaleBorder",
    )
    // 覆盖确认态提升到结果卡层（AnimatedContent 外）：新结果到达切换内容时
    // 不得静默丢弃确认窗口——Manager 侧 pendingFillConfirm 仍存活 5s。
    var fillConfirmOverwrite by remember { mutableStateOf(false) }
    var fillConfirmRound by remember { mutableStateOf(0) }
    AmberCard(
        modifier = Modifier.graphicsLayer { alpha = staleAlpha },
        borderColor = lerp(staleBorder, t.accent, highlight.coerceIn(0f, 1f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // source pill + timestamp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(999.dp))
                        .background(t.surface2)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Lucide.Eye, contentDescription = null, tint = t.ink3, modifier = Modifier.size(13.dp))
                    Text(
                        text = state.currentAppLabel.ifBlank { currentAppFallback },
                        style = type.tinyTag,
                        color = t.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.lastUpdatedAtMillis > 0L) {
                    Text(
                        text = formatLiveTime(state.lastUpdatedAtMillis, appLocale),
                        style = type.meta,
                        color = t.ink4,
                    )
                }
            }

            AnimatedVisibility(
                visible = stale,
                enter = fadeIn(tween(LiveMotion.MediumMs)) + expandVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
                exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
            ) {
                Text(
                    text = if (pendingAction.isNotBlank()) {
                        stringResource(
                            R.string.live_new_action_pending,
                            pendingAction.localizedActionLabel(),
                        )
                    } else {
                        stringResource(R.string.live_result_screen_changed)
                    },
                    style = type.secondary,
                    color = t.ink3,
                )
            }

            // 新结果到达 / 动作版本切换：内容交叉淡化，不再硬切文本。
            AnimatedContent(
                targetState = card to actionKey,
                transitionSpec = {
                    fadeIn(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)) togetherWith
                        fadeOut(tween(LiveMotion.FastMs))
                },
                label = "liveResultBody",
            ) { (targetCard, targetActionKey) ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (targetActionKey) {
                        "找重点" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_conclusion),
                                content = targetCard.watching.ifBlank { uncertainResultText },
                                prominent = true,
                            )
                            LiveSection(
                                title = stringResource(R.string.live_result_key_points),
                                items = targetCard.keyPoints,
                                emptyText = stringResource(R.string.live_result_no_key_points),
                            )
                        }
                        "总结" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_summary),
                                content = targetCard.watching.ifBlank { screenUnclearText },
                                prominent = true,
                            )
                            LiveSection(title = stringResource(R.string.live_result_key_information), items = targetCard.keyPoints)
                        }
                        "找下一步" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_next_steps),
                                items = targetCard.suggestions,
                                emptyText = stringResource(R.string.live_result_no_next_step_info),
                            )
                            LiveSection(title = stringResource(R.string.live_result_basis), items = targetCard.keyPoints)
                            LiveSection(
                                title = stringResource(R.string.live_result_what_is_visible),
                                content = targetCard.watching.ifBlank { screenUnclearText },
                            )
                        }
                        "查风险" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_conclusion),
                                content = targetCard.watching.ifBlank { noClearRiskText },
                                prominent = true,
                            )
                            LiveSection(
                                title = stringResource(R.string.live_result_risks),
                                items = targetCard.keyPoints,
                                emptyText = stringResource(R.string.live_result_no_risk_points),
                            )
                        }
                        "写回复" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_reply_draft),
                                content = targetCard.suggestions.firstOrNull() ?: targetCard.watching,
                                prominent = true,
                            )
                            LiveSection(title = stringResource(R.string.live_result_tone), items = targetCard.keyPoints)
                            FillDraftButton(
                                state = state,
                                onFillDraft = onFillDraft,
                                confirmOverwrite = fillConfirmOverwrite,
                                confirmRound = fillConfirmRound,
                                onConfirmOverwrite = { overwrite, replay ->
                                    fillConfirmOverwrite = overwrite
                                    if (replay) fillConfirmRound++
                                },
                            )
                        }
                        else -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_what_is_visible),
                                content = targetCard.watching.ifBlank { screenUnclearText },
                                prominent = true,
                            )
                            LiveSection(title = stringResource(R.string.live_result_key_content), items = targetCard.keyPoints)
                            LiveSection(title = stringResource(R.string.live_result_what_to_do), items = targetCard.suggestions)
                        }
                    }
                }
            }

            DynamicActionChips(
                // stale 且无 pending 时传空串：不匹配任何 chip 命令，同名动作保持可见
                // （UI 总检查 #1：stale 文案提示"点下方动作重新分析"，不得藏起同名 chip）。
                currentAction = if (stale) pendingAction else actionKey,
                enabled = enabled,
                onInstruction = onInstruction,
            )

            // 卡片资产化动作（蓝图 §7.3 P1-3/4/5）：保存 / 发到聊天 / 记住。
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardActionChip(text = stringResource(R.string.live_action_save), onClick = onSaveCard)
                CardActionChip(text = stringResource(R.string.live_action_send_to_chat), onClick = onSendToChat)
                CardActionChip(text = stringResource(R.string.live_action_remember), onClick = onRemember)
                CardActionChip(text = stringResource(R.string.live_action_copy), onClick = onCopyCard)
            }

            if (!modelId.isNullOrBlank()) {
                Hairline()
                Text(text = modelId, style = type.meta, color = t.ink3)
            }
        }
    }
}

@Composable
internal fun EmptyResultCard(state: LiveModeUiState) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (state.active) {
                    stringResource(R.string.live_empty_waiting_card)
                } else {
                    stringResource(R.string.live_empty_not_started)
                },
                style = type.sessionTitle,
                color = t.ink,
            )
            Text(
                text = when {
                    !state.active -> stringResource(R.string.live_empty_inactive_hint)
                    state.currentAppLabel.isNotBlank() -> stringResource(
                        R.string.live_empty_current_app,
                        listOf(state.currentAppLabel, state.currentTitle)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                    )
                    else -> state.statusText
                },
                style = type.body,
                color = t.ink2,
            )
        }
    }
}

@Composable
internal fun LiveSection(
    title: String,
    content: String? = null,
    items: List<String> = emptyList(),
    emptyText: String? = null,
    prominent: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionLabel(title)
        if (!content.isNullOrBlank()) {
            Text(
                text = content,
                style = if (prominent) type.sessionTitle else type.body,
                color = t.ink,
            )
        }
        items.take(4).forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("·", color = t.accent, style = type.body)
                Text(text = item, style = type.body, color = t.ink, modifier = Modifier.weight(1f))
            }
        }
        if (content.isNullOrBlank() && items.isEmpty() && !emptyText.isNullOrBlank()) {
            Text(text = emptyText, style = type.secondary, color = t.ink3)
        }
    }
}

@Composable
internal fun DynamicActionChips(
    currentAction: String,
    enabled: Boolean,
    onInstruction: (String) -> Unit,
) {
    val actions = listOf(
        "找重点" to stringResource(R.string.live_action_find_focus),
        "帮我写回复" to stringResource(R.string.live_action_write_reply),
        "有什么风险" to stringResource(R.string.live_action_check_risks),
        "下一步" to stringResource(R.string.live_action_find_next_step),
        "总结一下" to stringResource(R.string.live_action_summarize),
    ).filterNot { (command, _) -> command.liveActionKey() == currentAction.liveActionKey() }
        .take(3)
    if (actions.isEmpty()) return
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { (command, label) ->
            // chip 入场：动作版本变化时 fade+缩放 pop-in（ChatInput chip 先例）。
            var shown by remember(label) { mutableStateOf(false) }
            LaunchedEffect(label) { shown = true }
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(LiveMotion.FastMs)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(t.surface2)
                        .then(
                            Modifier.pressable(onClick = { if (enabled) onInstruction(command) }, enabled = enabled),
                        )
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = label,
                        style = type.secondary,
                        color = if (enabled) t.ink2 else t.ink4,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ───────────────────────────── alerts ─────────────────────────────

@Composable
internal fun ActionProgressCard(state: LiveModeUiState) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val action = state.requestedAction
    val actionLabel = action.localizedActionLabel()
    val resultTitle = action.localizedResultTitle()
    val retrying = state.nextAnalysisAfterMillis > System.currentTimeMillis()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface2)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.analyzing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = t.accent)
        } else {
            Icon(Lucide.Sparkles, contentDescription = null, tint = t.accent, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = when {
                    retrying -> stringResource(R.string.live_action_queued, actionLabel)
                    state.analyzing -> stringResource(R.string.live_action_running, actionLabel)
                    state.paused -> stringResource(R.string.live_action_paused, actionLabel)
                    else -> stringResource(R.string.live_action_received, actionLabel)
                },
                style = type.body.copy(fontWeight = FontWeight.Medium),
                color = t.ink,
            )
            Text(
                text = when {
                    retrying -> stringResource(R.string.live_action_retry_hint, resultTitle)
                    state.analyzing -> stringResource(R.string.live_action_running_hint)
                    state.paused -> stringResource(R.string.live_action_paused_hint)
                    else -> stringResource(R.string.live_action_received_hint, resultTitle)
                },
                style = type.secondary,
                color = t.ink3,
            )
            // 流式预览（蓝图 §7.3 P1-1）：仅分析中显示生成中的文本（截断两行），行尾带闪烁光标。
            // analyzing 门控保留：退避期 requestedAction 挂起时残文不得冒充排队进度（Phase 2 复审 P2）。
            state.streamingText?.takeIf { it.isNotBlank() && state.analyzing }?.let { streaming ->
                StreamingTextWithCursor(
                    text = streaming,
                    style = type.meta,
                    color = t.ink3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun StreamingPreviewCard(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface2)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = t.accent)
        StreamingTextWithCursor(text = text, style = type.meta, color = t.ink3)
    }
}

/** 卡片资产化动作 chip（与 DynamicActionChips 的 chip 同风格，surface2 pill）。 */
@Composable
internal fun CardActionChip(text: String, onClick: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(t.surface2)
            .pressable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(text = text, style = type.secondary, color = t.ink2, maxLines = 1)
    }
}

/** 已保存卡片行（历史区）：时间 · 应用 · 结论一行截断 + 删除；跨天显示日期。 */
@Composable
internal fun FillDraftButton(
    state: LiveModeUiState,
    onFillDraft: () -> LiveFillResult,
    confirmOverwrite: Boolean,
    confirmRound: Int,
    onConfirmOverwrite: (Boolean, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val t = LocalAmberTokens.current
    val fillAllowed = state.fillAllowed
    // 轮次号由持有方递增：窗口内二次确认时 confirmOverwrite 不变位，下划线据此重新起算。
    val confirmProgress = remember { Animatable(1f) }
    LaunchedEffect(confirmOverwrite, confirmRound) {
        if (confirmOverwrite) {
            val progressJob = launch {
                confirmProgress.snapTo(1f)
                confirmProgress.animateTo(0f, tween(FILL_CONFIRM_WINDOW_MS.toInt(), easing = LinearEasing))
            }
            kotlinx.coroutines.delay(FILL_CONFIRM_WINDOW_MS)
            progressJob.cancel()
            onConfirmOverwrite(false, false)
        }
    }
    val label = when {
        confirmOverwrite -> stringResource(R.string.live_fill_confirm_overwrite)
        fillAllowed -> stringResource(R.string.live_fill_action_fill)
        else -> stringResource(R.string.live_fill_other_input)
    }
    Column(modifier = Modifier.width(IntrinsicSize.Min), horizontalAlignment = Alignment.Start) {
        PillButton(
            text = label,
            accent = true,
            onClick = {
                val message = when (onFillDraft()) {
                    LiveFillResult.FILLED -> {
                        onConfirmOverwrite(false, false)
                        R.string.live_fill_result_filled
                    }
                    LiveFillResult.NEEDS_CONFIRM -> {
                        onConfirmOverwrite(true, true)
                        R.string.live_fill_toast_need_confirm
                    }
                    LiveFillResult.REJECTED_STALE -> {
                        onConfirmOverwrite(false, false)
                        R.string.live_fill_toast_stale_copied
                    }
                    LiveFillResult.COPIED -> {
                        onConfirmOverwrite(false, false)
                        R.string.live_fill_result_copied
                    }
                    LiveFillResult.NO_DRAFT -> R.string.live_fill_result_missing
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
        )
        // 覆盖确认 5s 倒计时：下划线线性收窄，替代原先的静默复位。
        AnimatedVisibility(
            visible = confirmOverwrite,
            enter = fadeIn(tween(LiveMotion.MicroMs)),
            exit = fadeOut(tween(LiveMotion.MicroMs)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(confirmProgress.value.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(t.accent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** Rounded pill action — accent fill or surface-2 (design §6.1 buttons, compact). */
internal fun LiveModeUiState.resultActionKey(): String = completedAction.ifBlank {
    currentFocus.liveActionKey().takeUnless { it == "屏幕分析" } ?: "屏幕分析"
}

internal fun String.liveActionKey(): String {
    val text = trim()
    return when {
        text.isBlank() -> "屏幕分析"
        "重点" in text -> "找重点"
        "总结" in text || "摘要" in text -> "总结"
        "下一步" in text || "怎么做" in text -> "找下一步"
        "风险" in text || "问题" in text -> "查风险"
        "回复" in text || "回话" in text -> "写回复"
        else -> text.take(12)
    }
}

@Composable
internal fun String.localizedActionLabel(): String = when (this) {
    "屏幕分析" -> stringResource(R.string.live_action_screen_analysis)
    "找重点" -> stringResource(R.string.live_action_find_focus)
    "总结" -> stringResource(R.string.live_action_summarize)
    "找下一步" -> stringResource(R.string.live_action_find_next_step)
    "查风险" -> stringResource(R.string.live_action_check_risks)
    "写回复" -> stringResource(R.string.live_action_write_reply)
    else -> this
}

@Composable
internal fun String.localizedResultTitle(): String = when (this) {
    "屏幕分析" -> stringResource(R.string.live_result_title_companion)
    "找重点" -> stringResource(R.string.live_result_title_focus)
    "总结" -> stringResource(R.string.live_result_title_summary)
    "找下一步" -> stringResource(R.string.live_result_title_next_step)
    "查风险" -> stringResource(R.string.live_result_title_risks)
    "写回复" -> stringResource(R.string.live_result_title_reply)
    else -> stringResource(R.string.live_result_title_custom, this)
}
