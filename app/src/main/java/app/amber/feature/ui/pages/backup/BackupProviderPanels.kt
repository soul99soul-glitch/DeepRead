package app.amber.feature.ui.pages.backup

import app.amber.agent.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.DatabaseZap
import com.composables.icons.lucide.FileInput
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Upload
import androidx.compose.ui.Alignment
import app.amber.core.sync.core.SyncSettings
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.settings.WebDavConfig
import app.amber.feature.ui.pages.setting.SettingTileIcon
import app.amber.feature.ui.pages.setting.SettingCardGroup
import app.amber.feature.ui.pages.setting.ExperimentActionButton
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

internal data class WebDavDraft(
    val url: String,
    val username: String,
    val password: String,
    val path: String,
    val urlDirty: Boolean = false,
    val usernameDirty: Boolean = false,
    val passwordDirty: Boolean = false,
    val pathDirty: Boolean = false,
)

internal fun WebDavDraft.syncFrom(config: WebDavConfig): WebDavDraft = copy(
    url = if (urlDirty) url else config.url,
    username = if (usernameDirty) username else config.username,
    password = if (passwordDirty) password else config.password,
    path = if (pathDirty) path else config.path,
)

@Composable
internal fun BackupCompactField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surface2)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier
                .testTag("backup-compact-field-label")
                .width(88.dp),
            style = type.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = tokens.ink3,
            softWrap = true,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 28.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(tokens.accent),
            textStyle = type.body.copy(color = tokens.ink),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun GoogleBackupPanel(
    googleAvailable: Boolean,
    credentialManagerAvailable: Boolean,
    googleSessionLabel: String?,
    hasGoogleConnection: Boolean,
    syncSettings: SyncSettings,
    googleMessageText: String?,
    backupActivity: BackupActivity?,
    onConnect: () -> Unit,
    onShowConfigDetails: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
) {
    SettingCardGroup(title = "Google Drive") {
        item(
            onClick = if (googleAvailable) {
                onConnect
            } else {
                null
            },
            leadingContent = { SettingTileIcon(Lucide.DatabaseZap) },
            headlineContent = { Text(stringResource(R.string.backup_google_account)) },
            supportingContent = {
                if (!googleAvailable) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowConfigDetails() },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.backup_google_not_configured))
                        Text(
                            stringResource(R.string.backup_google_config_details),
                            style = LocalAmberType.current.meta,
                            color = LocalAmberTokens.current.accent,
                        )
                    }
                } else {
                    val supportingText = when {
                        googleMessageText != null -> googleMessageText
                        googleSessionLabel != null -> stringResource(
                            R.string.backup_google_connected,
                            googleSessionLabel.orEmpty(),
                        )
                        hasGoogleConnection -> stringResource(
                            R.string.backup_google_last_connected,
                            syncSettings.googleAccountEmail,
                        )
                        syncSettings.googleAccountEmail.isNotBlank() -> stringResource(
                            R.string.backup_google_last_connected,
                            syncSettings.googleAccountEmail,
                        )
                        !credentialManagerAvailable ->
                            stringResource(R.string.backup_google_credential_manager_unavailable)
                        else -> stringResource(R.string.backup_google_login_hint)
                    }
                    Text(supportingText)
                }
            },
            trailingContent = {
                Text(
                    when {
                        !googleAvailable -> stringResource(R.string.backup_unavailable)
                        hasGoogleConnection -> stringResource(R.string.backup_connected)
                        else -> stringResource(R.string.backup_connect)
                    },
                    color = if (googleAvailable && hasGoogleConnection) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        )
        item(
            leadingContent = { SettingTileIcon(Lucide.Cloud) },
            headlineContent = { Text(stringResource(R.string.backup_status_title)) },
            supportingContent = {
                BackupStatusContent(
                    syncSettings = syncSettings,
                    activity = backupActivity,
                )
            },
            trailingContent = {
                backupActivity?.let { activity ->
                    val progress = activity.progress
                    if (progress == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        // Graphite §3: progress percent is a machine-fact number → MONO (meta).
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = LocalAmberType.current.meta,
                            color = LocalAmberTokens.current.ink2,
                        )
                    }
                }
            },
        )
        rawItem {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                ExperimentActionButton(
                    text = stringResource(R.string.backup_upload),
                    enabled = googleAvailable,
                    compact = false,
                    onClick = onUpload,
                )
                ExperimentActionButton(
                    text = stringResource(R.string.backup_download),
                    enabled = googleAvailable,
                    compact = false,
                    onClick = onDownload,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebDavBackupPanel(
    draft: WebDavDraft,
    webDavMessageText: String?,
    webDavSnapshots: List<SyncSnapshot>,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
    onUpload: () -> Unit,
    onRestore: (SyncSnapshot) -> Unit,
    onDelete: (SyncSnapshot) -> Unit,
) {
    SettingCardGroup(title = "WebDAV") {
        rawItem(dividerStartPadding = 50.dp) {
            val tokens = LocalAmberTokens.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingTileIcon(Lucide.DatabaseZap)
                    Text(
                        stringResource(R.string.backup_server_config),
                        style = LocalAmberType.current.body,
                        color = tokens.ink,
                    )
                }
                BackupCompactField(
                    label = stringResource(R.string.backup_server_address),
                    value = draft.url,
                    onValueChange = onUrlChange,
                    keyboardType = KeyboardType.Uri,
                )
                BackupCompactField(
                    label = stringResource(R.string.backup_username),
                    value = draft.username,
                    onValueChange = onUsernameChange,
                )
                BackupCompactField(
                    label = stringResource(R.string.backup_password),
                    keyboardType = KeyboardType.Password,
                    value = draft.password,
                    onValueChange = onPasswordChange,
                    visualTransformation = PasswordVisualTransformation(),
                )
                BackupCompactField(
                    label = stringResource(R.string.backup_directory),
                    value = draft.path,
                    onValueChange = onPathChange,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ExperimentActionButton(
                        text = stringResource(R.string.backup_save_config),
                        enabled = true,
                        primary = true,
                        onClick = onSave,
                    )
                    ExperimentActionButton(
                        text = stringResource(R.string.backup_read_snapshots),
                        enabled = true,
                        compact = true,
                        onClick = onRefresh,
                    )
                }
                if (webDavMessageText != null) {
                    Text(
                        webDavMessageText,
                        style = LocalAmberType.current.secondary,
                        color = tokens.ink3,
                    )
                }
            }
        }
        item(
            onClick = onUpload,
            leadingContent = { SettingTileIcon(Lucide.Upload) },
            headlineContent = { Text(stringResource(R.string.backup_upload)) },
            supportingContent = { Text(stringResource(R.string.backup_webdav_upload_desc)) },
        )
    }
    if (webDavSnapshots.isNotEmpty()) {
        SettingCardGroup(title = stringResource(R.string.backup_webdav_snapshots)) {
            webDavSnapshots.forEach { snapshot ->
                rawItem {
                    ProviderSnapshotRow(
                        snapshot = snapshot,
                        onRestore = { onRestore(snapshot) },
                        onDelete = { onDelete(snapshot) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun FolderBackupPanel(
    folderDisplayName: String?,
    folderMessageText: String?,
    localFolderSnapshots: List<SyncSnapshot>,
    onChooseFolder: () -> Unit,
    onRefresh: () -> Unit,
    onUpload: () -> Unit,
    onRestore: (SyncSnapshot) -> Unit,
    onDelete: (SyncSnapshot) -> Unit,
) {
    SettingCardGroup(title = stringResource(R.string.backup_local_folder)) {
        item(
            onClick = onChooseFolder,
            leadingContent = { SettingTileIcon(Lucide.FolderOpen) },
            headlineContent = {
                Text(
                    if (folderDisplayName != null) {
                        stringResource(R.string.backup_change_folder)
                    } else {
                        stringResource(R.string.backup_choose_folder)
                    }
                )
            },
            supportingContent = {
                Text(
                    folderMessageText ?: folderDisplayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.backup_folder_permission_hint)
                )
            },
        )
        item(
            onClick = onRefresh,
            leadingContent = { SettingTileIcon(Lucide.DatabaseZap) },
            headlineContent = { Text(stringResource(R.string.backup_read_snapshots)) },
            supportingContent = { Text(stringResource(R.string.backup_folder_list_desc)) },
        )
        item(
            onClick = onUpload,
            leadingContent = { SettingTileIcon(Lucide.Upload) },
            headlineContent = { Text(stringResource(R.string.backup_upload)) },
            supportingContent = { Text(stringResource(R.string.backup_folder_upload_desc)) },
        )
    }
    if (localFolderSnapshots.isNotEmpty()) {
        SettingCardGroup(title = stringResource(R.string.backup_folder_snapshots)) {
            localFolderSnapshots.forEach { snapshot ->
                rawItem {
                    ProviderSnapshotRow(
                        snapshot = snapshot,
                        onRestore = { onRestore(snapshot) },
                        onDelete = { onDelete(snapshot) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun LocalBackupPanel(
    localMessageText: String?,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    SettingCardGroup(title = stringResource(R.string.backup_local_backup)) {
        item(
            onClick = onExport,
            leadingContent = { SettingTileIcon(Lucide.Upload) },
            headlineContent = { Text(stringResource(R.string.backup_export)) },
            supportingContent = {
                Text(
                    localMessageText ?: stringResource(R.string.backup_local_export_desc)
                )
            }
        )
        item(
            onClick = onImport,
            leadingContent = { SettingTileIcon(Lucide.FileInput) },
            headlineContent = { Text(stringResource(R.string.backup_import)) },
            supportingContent = {
                Text(stringResource(R.string.backup_local_import_desc))
            }
        )
    }
}
