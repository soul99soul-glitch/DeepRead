package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.COUNCIL_ROOM_HOST_ID
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.COUNCIL_ROOM_USER_ID
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilMessageKind
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.agent.R
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun TimelineMessageRow(
    msg: CouncilMessage,
    modelLabel: String,
    referenceAuthors: CouncilReferenceAuthors,
    isTopic: Boolean,
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onStreamingVisualActiveChange: ((Boolean) -> Unit)? = null,
) {
    val isUser = msg.authorId == COUNCIL_ROOM_USER_ID
    val isHost = msg.authorId == COUNCIL_ROOM_HOST_ID
    val chatTheme = LocalChatTheme.current

    // Withdrawn host review → render a compact "主持人撤回发言" capsule instead
    // of a normal bubble. The host started a review (user saw it streaming) but
    // decided this round needs no commentary; showing a withdrawn notice keeps
    // the timeline continuous (no vanishing-jump) and explains the silence.
    if (msg.kind == CouncilMessageKind.WITHDRAWN) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = chatTheme.surface,
                border = BorderStroke(1.dp, chatTheme.surfaceEdge),
            ) {
                Text(
                    text = stringResource(R.string.council_room_host_withdrew),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = chatTheme.inkFaint,
                )
            }
        }
        return
    }

    if (isUser) {
        // Mirror the main chat user bubble: solid userBubble fill, light userBubbleInk
        // text, asymmetric 16/16/5/16 corners, RIGHT-aligned, wraps content up to 82%
        // of the row (fillWidth=false on the markdown is what makes it adaptive).
        val userBubbleShape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomEnd = 5.dp,
            bottomStart = 16.dp,
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd,
        ) {
            val userBubbleMaxWidth = maxWidth * 0.82f
            Surface(
                modifier = Modifier
                    .wrapContentWidth(Alignment.End)
                    .widthIn(max = userBubbleMaxWidth)
                    .clip(userBubbleShape),
                shape = userBubbleShape,
                color = chatTheme.userBubble,
                contentColor = chatTheme.userBubbleInk,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (isTopic) {
                        Text(
                            text = stringResource(R.string.council_room_topic_author),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = chatTheme.userBubbleInk.copy(alpha = 0.55f),
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    if (msg.attachments.isNotEmpty()) {
                        CouncilBubbleAttachments(
                            attachments = msg.attachments,
                            modifier = Modifier.padding(bottom = if (msg.text.isNotBlank()) 8.dp else 0.dp),
                        )
                    }
                    if (msg.text.isNotBlank()) {
                        MarkdownBlock(
                            content = msg.text,
                            fillWidth = false,
                        )
                    }
                }
            }
        }
    } else {
        // The host's final synthesis is surfaced inline as a host message
        // (mode == SYNTHESIZE). Give it its own look — a neutral surface card with
        // an accent frame + "综合结论" kicker — so it reads as the conclusion and
        // doesn't get confused with the green topic ("命题") or ordinary host turns.
        val isSynthesis = isHost && msg.mode == CouncilRoomMode.SYNTHESIZE
        // Member / host turn: keep the identity and bubble in a compact message
        // column beside the avatar. The reference room uses a 300dp message
        // measure so the timeline retains a readable editorial edge on phones.
        val bubbleShape = RoundedCornerShape(
            topStart = 5.dp,
            topEnd = 16.dp,
            bottomEnd = 16.dp,
            bottomStart = 16.dp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            CouncilMemberAvatar(msg = msg, isHost = isHost)
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CouncilMemberIdentityHeader(msg = msg, isHost = isHost, modelLabel = modelLabel)
                if (isSynthesis) {
                    Text(
                        text = stringResource(R.string.council_room_host_summary),
                        style = LocalAmberType.current.tinyTag,
                        fontWeight = FontWeight.SemiBold,
                        color = chatTheme.accent,
                    )
                }
                MessageBubble(
                    msg = msg,
                    container = when {
                        isSynthesis -> chatTheme.surface
                        isHost -> chatTheme.accentSoft
                        else -> chatTheme.surface
                    },
                    content = when {
                        isSynthesis -> chatTheme.ink
                        isHost -> chatTheme.accentDeep
                        else -> chatTheme.ink
                    },
                    borderColor = when {
                        isSynthesis -> chatTheme.accent
                        isHost -> chatTheme.accentTint
                        else -> chatTheme.surfaceEdge
                    },
                    shape = bubbleShape,
                    // Forwarded from CouncilTimelineTab: only the actively-streaming
                    // turn supplies non-null callbacks, so the renderer's frame
                    // release drives bottom-follow in phase with visible characters.
                    onStreamingVisibleFrame = onStreamingVisibleFrame,
                    onStreamingVisualActiveChange = onStreamingVisualActiveChange,
                )
                ReferenceFootnotes(msg, referenceAuthors, alignEnd = false)
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: CouncilMessage,
    container: Color,
    content: Color,
    borderColor: Color,
    shape: Shape = RoundedCornerShape(18.dp),
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onStreamingVisualActiveChange: ((Boolean) -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val streaming = msg.status == CouncilMessageStatus.STREAMING
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (msg.text.isNotBlank()) {
                MarkdownBlock(
                    content = msg.text,
                    streaming = streaming,
                    deferStreamingParse = streaming,
                    style = TextStyle(color = content),
                    // Forward the streaming frame callbacks so the timeline's
                    // bottom-follow stays in lockstep with what the renderer
                    // actually shows (each released frame → follow), instead of
                    // chasing raw text length which leads the display buffer.
                    onStreamingVisibleFrame = onStreamingVisibleFrame,
                    onStreamingVisualActiveChange = onStreamingVisualActiveChange,
                )
            } else if (streaming) {
                StreamingPlaceholder()
            }
            if (msg.error.isNotBlank()) {
                Text(
                    text = msg.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.red,
                )
            }
            if (msg.warnings.isNotEmpty()) {
                Text(
                    text = msg.warnings.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.amber,
                )
            }
        }
    }
}

@Composable
private fun StreamingPlaceholder() {
    val transition = rememberInfiniteTransition(label = "council-streaming")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "council-streaming-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(workspaceColors().muted, RoundedCornerShape(50)),
    )
}
