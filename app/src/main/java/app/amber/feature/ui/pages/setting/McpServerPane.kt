package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.ai.mcp.McpStatus
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderSmallIconButton
import app.amber.feature.ui.theme.extendColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun McpServerItem(
    item: McpServerConfig,
    status: McpStatus,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onStartAuthorization: () -> Unit,
    onClearAuthorization: () -> Unit,
) {
    val dismissBoxState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val workspace = workspaceColors()
    SwipeToDismissBox(
        state = dismissBoxState,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ProviderSmallIconButton(
                    imageVector = Lucide.X,
                    contentDescription = stringResource(R.string.cancel),
                    onClick = { scope.launch { dismissBoxState.reset() } },
                )
                ProviderSmallIconButton(
                    imageVector = Lucide.Trash2,
                    contentDescription = stringResource(R.string.delete),
                    onClick = onDelete,
                    tint = workspace.muted,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        modifier = modifier
    ) {
        ProviderCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = workspace.row,
                        contentColor = workspace.muted,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Lucide.Server,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                text = item.commonOptions.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = workspace.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val dotColor =
                                if (item.commonOptions.enable) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .drawWithContent {
                                        drawCircle(
                                            color = dotColor
                                        )
                                    }
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            McpInlinePill(text = item.transportLabel())
                            McpInlinePill(
                                text = status.statusLabel(),
                                tone = when (status) {
                                    McpStatus.Connected -> WorkspaceTone.Success
                                    is McpStatus.Error -> WorkspaceTone.Danger
                                    McpStatus.Connecting,
                                    is McpStatus.Reconnecting,
                                    McpStatus.Authorizing,
                                    McpStatus.NeedsAuthorization,
                                    -> WorkspaceTone.Warning
                                    McpStatus.Idle -> WorkspaceTone.Neutral
                                },
                            )
                        }
                    }

                    ProviderIconButton(
                        imageVector = Lucide.Settings,
                        contentDescription = stringResource(R.string.setting_mcp_page_edit_server),
                        onClick = { onEdit(item) },
                        tint = workspace.muted,
                    )
                }
                McpOAuthSection(
                    item = item,
                    status = status,
                    onStartAuthorization = onStartAuthorization,
                    onClearAuthorization = onClearAuthorization,
                )
            }
        }
    }
}

@Composable
private fun McpOAuthSection(
    item: McpServerConfig,
    status: McpStatus,
    onStartAuthorization: () -> Unit,
    onClearAuthorization: () -> Unit,
) {
    val workspace = workspaceColors()
    val oauth = item.commonOptions.oauth
    val showSection = oauth != null || status == McpStatus.Authorizing || status == McpStatus.NeedsAuthorization
    if (!showSection) return
    val expiresAtText = remember(oauth?.expiresAt) {
        val expiresAt = oauth?.expiresAt ?: 0L
        if (expiresAt > 0) {
            Instant.ofEpochMilli(expiresAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } else {
            null
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = workspace.hairline,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            status == McpStatus.Authorizing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.setting_mcp_page_oauth_authorizing),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.muted,
                )
            }

            oauth != null && oauth.isAuthorized -> {
                Text(
                    text = stringResource(R.string.setting_mcp_page_oauth_authorized),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.muted,
                )
                expiresAtText?.let { expires ->
                    Text(
                        text = stringResource(R.string.setting_mcp_page_oauth_expires_at, expires),
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.muted,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onClearAuthorization,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.setting_mcp_page_oauth_cancel))
                }
            }

            else -> {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onStartAuthorization,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.setting_mcp_page_oauth_login))
                }
            }
        }
    }
}

@Composable
private fun McpInlinePill(
    text: String,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        WorkspaceTone.Neutral -> workspace.row to workspace.muted
        WorkspaceTone.Accent -> scheme.primaryContainer to scheme.primary
        WorkspaceTone.Success -> workspace.greenContainer to workspace.green
        WorkspaceTone.Warning -> workspace.amberContainer to workspace.amber
        WorkspaceTone.Danger -> workspace.redContainer to workspace.red
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, content.copy(alpha = 0.18f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun McpServerConfig.transportLabel(): String = when (this) {
    is McpServerConfig.SseTransportServer -> "SSE"
    is McpServerConfig.StreamableHTTPServer -> "Streamable HTTP"
}

@Composable
private fun McpStatus.statusLabel(): String = when (this) {
    McpStatus.Idle -> stringResource(R.string.setting_mcp_status_idle)
    McpStatus.Connecting -> stringResource(R.string.setting_mcp_status_connecting)
    McpStatus.Connected -> stringResource(R.string.setting_mcp_status_connected)
    is McpStatus.Reconnecting -> stringResource(R.string.setting_mcp_status_reconnecting)
    is McpStatus.Error -> stringResource(R.string.setting_mcp_status_error)
    McpStatus.NeedsAuthorization -> stringResource(R.string.setting_mcp_status_needs_authorization)
    McpStatus.Authorizing -> stringResource(R.string.setting_mcp_status_authorizing)
}
