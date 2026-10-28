package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme

/** Live "X 正在发言…" strip with a breathing signal dot + model label. */
@Composable
internal fun CouncilSpeakingStrip(participant: CouncilParticipant, onClick: () -> Unit) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        shape = RoundedCornerShape(0.dp),
        color = chatTheme.accent.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, chatTheme.surfaceEdge),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CouncilBreathingDot(color = workspace.green)
            Text(
                text = stringResource(
                    R.string.council_room_speaking,
                    participant.name.ifBlank { participant.id },
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val modelLabel = participant.modelLabel()
            if (modelLabel.isNotBlank()) {
                Text(
                    text = modelLabel,
                    modifier = Modifier.widthIn(max = 140.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Pulsing signal dot — a fading, expanding ring around a solid core (design `.dot::after`). */
@Composable
private fun CouncilBreathingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "council-dot")
    val ringScale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 2.1f,
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "council-dot-scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "council-dot-alpha",
    )
    Box(modifier = Modifier.size(15.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                    alpha = ringAlpha
                }
                .background(color, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
    }
}
