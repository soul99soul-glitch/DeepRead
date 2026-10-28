package app.amber.feature.ui.pages.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Settings
import app.amber.agent.R
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.live.LiveMotion
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun LiveHeader(live: Boolean, onBack: () -> Unit, onSettings: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).pressable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = t.ink,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("COMPANION")
                    LiveDot(idle = !live, dotSize = 4.dp)
                }
                Text(stringResource(R.string.live_companion_title), style = type.screenTitle, color = t.ink)
            }
            Box(
                modifier = Modifier.size(48.dp).pressable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = t.ink2,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Hairline()
    }
}

// ───────────────────────────── master card ─────────────────────────────

@Composable
internal fun MasterCard(
    state: LiveModeUiState,
    onMaster: (Boolean) -> Unit,
    onPauseResume: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val live = state.active && !state.paused && state.error == null
            val eyeTint by animateColorAsState(
                targetValue = if (live) t.accent else t.ink3,
                animationSpec = tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut),
                label = "eyeTint",
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surface2),
                contentAlignment = Alignment.Center,
            ) {
                if (state.analyzing) {
                    // 分析中 halo：LiveDot 的呼吸光环挂到图标块上，强调"伴随正在阅读"。
                    val halo = rememberInfiniteTransition(label = "masterHalo")
                    val haloP by halo.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
                        label = "masterHaloP",
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .graphicsLayer {
                                val s = 1f + haloP * 1.3f
                                scaleX = s
                                scaleY = s
                                alpha = 0.35f * (1f - haloP)
                            }
                            .background(t.accent, CircleShape),
                    )
                }
                Icon(
                    Lucide.Eye,
                    contentDescription = null,
                    tint = eyeTint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = state.masterTitle(),
                        style = type.sessionTitle,
                        color = t.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.analyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = t.accent,
                        )
                    }
                }
                Text(
                    text = state.masterSubtitle(),
                    style = type.secondary,
                    color = t.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AmberToggle(checked = state.active, onCheckedChange = onMaster)
        }

        AnimatedVisibility(
            visible = state.active,
            enter = fadeIn(tween(LiveMotion.MediumMs)) + expandVertically(tween(LiveMotion.SlowMs, easing = LiveMotion.EaseOut)),
            exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
        ) {
            Column {
                Hairline()
                ToggleRow(
                    label = stringResource(R.string.live_pause_companion),
                    hint = if (state.paused) {
                        stringResource(R.string.live_paused_reading)
                    } else {
                        stringResource(R.string.live_pause_analysis_hint)
                    },
                    checked = state.paused,
                    onCheckedChange = { onPauseResume() },
                )
            }
        }
    }
}

@Composable
internal fun GuidanceCard(title: String, body: String, action: String, onAction: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(t.accent))
                Text(title, style = type.sessionTitle, color = t.ink)
            }
            Text(body, style = type.body, color = t.ink2)
            PillButton(text = action, accent = true, onClick = onAction)
        }
    }
}

@Composable
internal fun ErrorNote(title: String, error: String, retrying: Boolean) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface2)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = type.body.copy(fontWeight = FontWeight.Medium), color = t.ink)
        Text(text = error, style = type.secondary, color = t.ink2)
        if (retrying) {
            Text(
                text = stringResource(R.string.live_retry_unavailable_hint),
                style = type.secondary,
                color = t.ink3,
            )
        }
    }
}

// ───────────────────────────── DS controls ─────────────────────────────

/** Toggle row: cn label (+ optional mono hint) · accent toggle. design §6.2. */
@Composable
internal fun LiveModeUiState.masterTitle(): String = when {
    !active -> stringResource(R.string.live_master_not_enabled)
    paused -> stringResource(R.string.live_master_paused)
    error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> stringResource(R.string.live_master_model_busy)
    error != null -> stringResource(R.string.live_master_analysis_failed)
    analyzing -> stringResource(R.string.live_master_analyzing)
    card != null -> stringResource(R.string.live_master_companion_active)
    currentAppLabel.isNotBlank() -> stringResource(R.string.live_master_reading)
    else -> stringResource(R.string.live_master_enabled)
}

@Composable
internal fun LiveModeUiState.masterSubtitle(): String {
    val target = listOf(currentAppLabel, currentTitle).filter { it.isNotBlank() }.joinToString(" · ")
    val mode = stringResource(R.string.live_manual_analysis)
    val analyzingHint = stringResource(R.string.live_master_analyzing_hint)
    return when {
        !active -> stringResource(R.string.live_master_disabled_hint)
        paused -> stringResource(R.string.live_master_paused_hint)
        error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> stringResource(R.string.live_master_busy_hint)
        requestedAction.isNotBlank() -> stringResource(
            R.string.live_master_result_hint,
            requestedAction.localizedResultTitle(),
        )
        analyzing -> target.ifBlank { analyzingHint }
        target.isNotBlank() -> stringResource(R.string.live_master_target_mode, target, mode)
        else -> stringResource(R.string.live_master_status_mode, statusText, mode)
    }
}
