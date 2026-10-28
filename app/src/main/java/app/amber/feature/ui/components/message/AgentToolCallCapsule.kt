package app.amber.feature.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Clock
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.modifier.shimmer

@Composable
internal fun AgentToolCallCapsule(
    title: String,
    toolName: String,
    icon: ImageVector,
    kind: AgentToolKind,
    status: AgentToolStatus,
    loading: Boolean,
    onClick: (() -> Unit)?,
    approvalActions: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // Tint the conversation paper, rather than the brighter card surface, for the muted
    // iOS-style capsule fill. Pre-compositing keeps the canvas texture out of the pill.
    val capsuleBackground = theme.accent
        .copy(alpha = if (theme.isDark) 0.16f else 0.08f)
        .compositeOver(theme.bg)
    val capsuleBorder = theme.accent
        .copy(alpha = if (theme.isDark) 0.24f else 0.14f)
        .compositeOver(theme.bg)
    // Compact tool result pill: opaque accent wash, readable ink, and a small semantic status
    // mark at the trailing edge. The status mark itself does not add a saturated color disk.
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        modifier = modifier
            // V3: 改 wrapContentWidth, 胶囊自适应内容长度而非顶满整行
            .wrapContentWidth(align = Alignment.Start)
            .widthIn(max = 460.dp)
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(shape)
                        .clickable { onClick() }
                        .minimumInteractiveComponentSize()
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start)
                .widthIn(max = 460.dp),
            // 不在胶囊上再加 animateContentSize: 外层 ChatMessageToolStep Column 已有
            // 一层 (input 流式增长/审批切换/output 回填时两层弹簧并行会弹跳)。
            // 见 ChatMessage.kt 中关于 message 渲染路径禁止叠加 animateContentSize 的注释。
            shape = shape,
            color = capsuleBackground,
            contentColor = workspace.ink,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, capsuleBorder),
        ) {
            val reserveStatusSlot = approvalActions == null
            Box {
                Row(
                    modifier = Modifier.padding(start = 7.dp, end = 3.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = if (reserveStatusSlot) {
                            Modifier.padding(end = 24.dp)
                        } else {
                            Modifier.padding(end = 7.dp)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (leadingContent != null) {
                            leadingContent()
                        } else {
                            V3ToolTypeIcon(
                                icon = icon,
                                kind = kind,
                            )
                        }

                        val displayText = if (subtitle.isNullOrBlank()) title else "$title $subtitle"
                        val amberType = app.amber.feature.ui.theme.LocalAmberType.current
                        Text(
                            text = displayText,
                            // Tool name + args stay compact and machine-readable, while the
                            // capsule text uses the theme ink for comfortable contrast.
                            style = amberType.meta.copy(fontSize = 11.sp),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                            color = theme.inkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = (if (approvalActions == null) {
                                Modifier.widthIn(max = 380.dp)
                            } else {
                                Modifier.weight(1f, fill = false)
                            })
                                .shimmer(isLoading = loading && status == AgentToolStatus.RUNNING),
                        )

                        if (approvalActions != null) {
                            approvalActions()
                        }
                    }
                }
                if (reserveStatusSlot) {
                    V3ToolStatusBadge(
                        status = status,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun V3ToolTypeIcon(
    icon: ImageVector,
    kind: AgentToolKind,
) {
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tint = when (kind) {
        AgentToolKind.FILE -> theme.toolIconInk
        AgentToolKind.TERMINAL -> theme.toolIconInk
        AgentToolKind.MCP -> theme.toolIconInk
        AgentToolKind.SCREEN -> theme.toolIconInk
        AgentToolKind.WEB -> theme.toolIconInk
        AgentToolKind.MEMORY -> theme.toolIconInk
        AgentToolKind.GENERIC -> theme.toolIconInk
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.requiredSize(16.dp),
    )
}

@Composable
private fun V3ToolStatusBadge(
    status: AgentToolStatus,
    modifier: Modifier = Modifier,
) {
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val statusDescription = toolStatusLabel(status)
    val successInk = theme.toolIconInk
    val ink = when (status) {
        AgentToolStatus.SUCCEEDED -> successInk
        AgentToolStatus.WAITING_FOR_PERMISSION -> theme.contextMid
        AgentToolStatus.UNKNOWN -> theme.inkSoft
        AgentToolStatus.FAILED,
        AgentToolStatus.TIMED_OUT -> theme.contextHigh
        AgentToolStatus.INTERRUPTED -> theme.contextMid
        AgentToolStatus.CANCELLED -> theme.accent
        AgentToolStatus.RUNNING -> theme.accent
    }
    if (status == AgentToolStatus.RUNNING) {
        Box(
            modifier = modifier
                .requiredSize(16.dp)
                .semantics { stateDescription = statusDescription },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = ink,
                strokeWidth = 1.5.dp,
            )
        }
        return
    }
    Box(
        modifier = modifier
            .requiredSize(16.dp)
            .semantics { stateDescription = statusDescription },
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            AgentToolStatus.SUCCEEDED -> Icon(
                imageVector = Lucide.Check,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(12.dp),
            )
            AgentToolStatus.FAILED,
            AgentToolStatus.TIMED_OUT,
            AgentToolStatus.CANCELLED -> Icon(
                imageVector = Lucide.X,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(12.dp),
            )
            AgentToolStatus.WAITING_FOR_PERMISSION,
            AgentToolStatus.UNKNOWN,
            AgentToolStatus.INTERRUPTED -> Icon(
                imageVector = Lucide.Clock,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(12.dp),
            )
            AgentToolStatus.RUNNING -> Unit
        }
    }
}
