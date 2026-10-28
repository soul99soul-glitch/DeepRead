package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/** A guest's display model id, e.g. "gpt-5.1" / "deepseek-v3.2". */
internal fun CouncilParticipant.modelLabel(): String =
    modelName.ifBlank { externalModel }.ifBlank { providerName }

@Composable
internal fun CouncilMemberAvatar(msg: CouncilMessage, isHost: Boolean) {
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .background(
                if (isHost) chatTheme.accent.copy(alpha = 0.14f) else tokens.surface2,
            )
            .border(
                1.dp,
                if (isHost) chatTheme.accent.copy(alpha = 0.42f) else tokens.line,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SubAgentAvatar(
            id = msg.authorId,
            name = msg.authorName,
            avatarSize = 24.dp,
        )
    }
}

/** Identity tag keeps human names sans and model ids mono, as in the reference room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CouncilMemberIdentityHeader(
    msg: CouncilMessage,
    isHost: Boolean,
    modelLabel: String,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CouncilMemberTag(
            msg = msg,
            isHost = isHost,
            modelLabel = modelLabel,
            modifier = Modifier.widthIn(max = 240.dp),
        )
        if (msg.role.isNotBlank() && msg.role != msg.authorName) {
            CouncilRolePill(text = msg.role, isHost = isHost)
        }
    }
}

@Composable
private fun CouncilMemberTag(
    msg: CouncilMessage,
    isHost: Boolean,
    modelLabel: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(999.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (isHost) chatTheme.accent.copy(alpha = 0.12f) else tokens.surface2,
        border = BorderStroke(
            1.dp,
            if (isHost) chatTheme.accent.copy(alpha = 0.38f) else tokens.line,
        ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 22.dp)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = msg.authorName.ifBlank { msg.authorId },
                modifier = Modifier.weight(1f, fill = false),
                style = type.tinyTag.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (isHost) chatTheme.accent else tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (modelLabel.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .height(14.dp)
                        .width(1.dp)
                        .background(if (isHost) chatTheme.accent.copy(alpha = 0.34f) else tokens.line2),
                )
                Text(
                    text = modelLabel,
                    modifier = Modifier.widthIn(max = 180.dp),
                    style = type.meta.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                    color = if (isHost) chatTheme.accent.copy(alpha = 0.82f) else tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CouncilRolePill(text: String, isHost: Boolean) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isHost) chatTheme.accentTint else chatTheme.surfaceEdge),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isHost) chatTheme.accent else workspace.muted,
        )
    }
}
