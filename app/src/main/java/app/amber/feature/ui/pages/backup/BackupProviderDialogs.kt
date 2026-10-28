package app.amber.feature.ui.pages.backup

import app.amber.agent.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.sync.provider.UploadConflictPolicy
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun BackupDeleteSnapshotDialog(
    snapshot: SyncSnapshot,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_delete_remote_snapshot)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.backup_delete_remote_snapshot_message,
                        snapshot.name,
                        snapshot.manifest.deviceLabel.ifBlank {
                            stringResource(R.string.backup_unknown_device)
                        },
                        snapshot.manifest.appVersionName.ifBlank {
                            stringResource(R.string.backup_unknown_version)
                        },
                    ),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun BackupUploadConflictDialog(
    choice: UploadConflictChoice,
    onDismiss: () -> Unit,
    onResolve: (UploadConflictPolicy) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_upload_conflict)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.backup_upload_conflict_message,
                        choice.snapshot.manifest.createdAt,
                    ),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onResolve(UploadConflictPolicy.OVERWRITE) }) {
                Text(stringResource(R.string.backup_overwrite_old_snapshot))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(UploadConflictPolicy.CREATE_COPY) }) {
                Text(stringResource(R.string.backup_create_copy))
            }
        },
    )
}

@Composable
internal fun GoogleBackupConfigDialog(
    packageName: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_google_not_configured)) },
        text = {
            Text(
                stringResource(
                    R.string.backup_google_oauth_missing_client,
                    packageName,
                ),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}

@Composable
internal fun GoogleBackupConflictDialog(
    conflict: GoogleCloudConflict,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_cloud_snapshot_conflict)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.backup_cloud_revision_conflict),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
                // Graphite §3: remote modified time + local revision are machine facts → MONO (meta).
                Text(
                    stringResource(
                        R.string.backup_remote_modified_time,
                        conflict.remoteFile.modifiedTime ?: stringResource(R.string.backup_unknown_time),
                    ),
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                Text(
                    stringResource(
                        R.string.backup_local_revision,
                        conflict.localRevision.ifBlank { stringResource(R.string.backup_no_revision) },
                    ),
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                Text(
                    stringResource(R.string.backup_cloud_overwrite_warning),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.backup_overwrite_cloud))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
