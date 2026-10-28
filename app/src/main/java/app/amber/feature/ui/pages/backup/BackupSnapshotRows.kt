package app.amber.feature.ui.pages.backup

import app.amber.agent.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash
import androidx.compose.ui.Alignment
import app.amber.core.sync.core.CURRENT_ARCHIVE_VERSION
import app.amber.core.sync.provider.CURRENT_SNAPSHOT_SCHEMA_VERSION
import app.amber.core.sync.google.GoogleDriveFile
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.sync.provider.checkSnapshotCompatibility
import java.util.Locale
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.appLocale

@Composable
internal fun CloudSnapshotPickerDialog(
    snapshots: List<GoogleDriveFile>,
    onDismiss: () -> Unit,
    onSelect: (GoogleDriveFile) -> Unit,
    onDelete: (GoogleDriveFile) -> Unit,
) {
    val appLocale = LocalContext.current.appLocale()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_choose_cloud_snapshot)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                snapshots.forEachIndexed { index, snapshot ->
                    // P7-02：v1 旧格式允许只读恢复（迁移）。
                    val unsupported = snapshot.archiveVersion?.let {
                        it != CURRENT_ARCHIVE_VERSION && it != app.amber.core.sync.core.LEGACY_ARCHIVE_VERSION
                    } == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !unsupported) { onSelect(snapshot) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Graphite §3: snapshot title (timestamp · version) and detail
                            // (device · size · archive format) are machine facts → MONO (meta).
                            Text(
                                formatCloudSnapshotTitle(snapshot, appLocale),
                                style = LocalAmberType.current.meta,
                                color = LocalAmberTokens.current.ink,
                            )
                            Text(
                                formatCloudSnapshotDetail(snapshot, unsupported, appLocale),
                                style = LocalAmberType.current.meta,
                                color = LocalAmberTokens.current.ink3,
                            )
                        }
                        TextButton(onClick = { onDelete(snapshot) }) {
                            Icon(
                                Lucide.Trash,
                                contentDescription = stringResource(R.string.delete),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (unsupported) {
                                stringResource(R.string.backup_unsupported)
                            } else {
                                stringResource(R.string.backup_select)
                            },
                            color = if (unsupported) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    if (index != snapshots.lastIndex) {
                        Hairline()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun formatCloudSnapshotTitle(snapshot: GoogleDriveFile, locale: Locale): String {
    val createdAt = snapshot.backupCreatedAt?.let {
        formatBackupDate(it, locale)
    } ?: snapshot.modifiedTime?.take(16)?.replace('T', ' ') ?: stringResource(R.string.backup_unknown_time)
    val version = snapshot.backupVersionName.ifBlank { stringResource(R.string.backup_unknown_version) }
    return "$createdAt · $version"
}

@Composable
private fun formatCloudSnapshotDetail(
    snapshot: GoogleDriveFile,
    unsupported: Boolean,
    locale: Locale,
): String {
    val parts = mutableListOf<String>()
    if (snapshot.backupDeviceLabel.isNotBlank()) parts += snapshot.backupDeviceLabel
    formatDriveSize(snapshot.size, locale)?.let { parts += it }
    snapshot.archiveVersion?.let { archiveVersion ->
        parts += if (unsupported) {
            stringResource(R.string.backup_archive_incompatible, archiveVersion)
        } else {
            stringResource(R.string.backup_archive_format, archiveVersion)
        }
    }
    if (parts.isEmpty()) parts += snapshot.name
    return parts.joinToString(separator = " · ")
}

private fun formatDriveSize(size: String?, locale: Locale): String? {
    val bytes = size?.toLongOrNull() ?: return null
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 1.0) {
        String.format(locale, "%.1f MB", mib)
    } else {
        "${bytes / 1024} KB"
    }
}

@Composable
internal fun ProviderSnapshotRow(
    snapshot: SyncSnapshot,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val appLocale = LocalContext.current.appLocale()
    val compatibility = checkSnapshotCompatibility(snapshot.manifest)
    val unsupported = compatibility !is app.amber.core.sync.provider.SnapshotCompatibility.Compatible
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !unsupported) { onRestore() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Graphite §3: snapshot title (timestamp · version) and detail
            // (device · size · domains) are machine facts → MONO (meta).
            Text(
                formatProviderSnapshotTitle(snapshot, appLocale),
                style = LocalAmberType.current.meta,
                color = LocalAmberTokens.current.ink,
            )
            Text(
                formatProviderSnapshotDetail(snapshot, unsupported, appLocale),
                style = LocalAmberType.current.meta,
                color = LocalAmberTokens.current.ink3,
            )
        }
        TextButton(onClick = onDelete) {
            Icon(
                Lucide.Trash,
                contentDescription = stringResource(R.string.delete),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (unsupported) {
                stringResource(R.string.backup_incompatible)
            } else {
                stringResource(R.string.backup_restore)
            },
            color = if (unsupported) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun formatProviderSnapshotTitle(snapshot: SyncSnapshot, locale: Locale): String {
    val createdAt = snapshot.manifest.createdAt.let {
        if (it > 0L) {
            formatBackupDate(it, locale)
        } else {
            stringResource(R.string.backup_unknown_time)
        }
    }
    val version = snapshot.manifest.appVersionName.ifBlank {
        stringResource(R.string.backup_unknown_version)
    }
    return "$createdAt · $version"
}

@Composable
private fun formatProviderSnapshotDetail(
    snapshot: SyncSnapshot,
    unsupported: Boolean,
    locale: Locale,
): String {
    val parts = mutableListOf<String>()
    if (snapshot.manifest.deviceLabel.isNotBlank()) parts += snapshot.manifest.deviceLabel
    if (snapshot.sizeBytes > 0L) {
        parts += if (snapshot.sizeBytes >= 1024 * 1024) {
            String.format(locale, "%.1f MB", snapshot.sizeBytes / (1024.0 * 1024.0))
        } else {
            "${snapshot.sizeBytes / 1024} KB"
        }
    }
    parts += if (unsupported) {
        stringResource(
            R.string.backup_format_incompatible,
            if (snapshot.manifest.schemaVersion > CURRENT_SNAPSHOT_SCHEMA_VERSION) {
                "schema v${snapshot.manifest.schemaVersion}"
            } else {
                stringResource(R.string.backup_archive_incompatible, snapshot.manifest.archiveVersion)
            },
        )
    } else {
        stringResource(
            R.string.backup_encryption_domains,
            stringResource(
                if (snapshot.manifest.encrypted) R.string.backup_yes else R.string.backup_no,
            ),
            snapshot.manifest.includedDomains.joinToString("/").ifBlank {
                stringResource(R.string.backup_none)
            },
        )
    }
    return parts.joinToString(separator = " · ")
}
