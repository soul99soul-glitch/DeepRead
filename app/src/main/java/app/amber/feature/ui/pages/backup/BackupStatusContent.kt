package app.amber.feature.ui.pages.backup

import app.amber.agent.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.core.sync.core.SyncSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.appLocale

internal fun formatBackupDate(timestampMs: Long, locale: Locale): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date(timestampMs))

/**
 * Render the "上次备份" supporting line for the backup-status card item.
 * Falls back to "暂无备份" when none of upload / download / local-export has
 * happened yet (i.e. `lastBackupVersionName` is still its default blank).
 */
@Composable
private fun formatBackupStatus(syncSettings: SyncSettings, locale: Locale): String {
    if (syncSettings.lastBackupVersionName.isBlank()) {
        return stringResource(R.string.backup_status_none)
    }
    val latestAt = maxOf(
        syncSettings.lastUploadAt,
        syncSettings.lastDownloadAt,
        syncSettings.lastLocalExportAt,
    )
    val parts = mutableListOf<String>()
    if (latestAt > 0L) parts += formatBackupDate(latestAt, locale)
    parts += syncSettings.lastBackupVersionName
    if (syncSettings.lastBackupDeviceLabel.isNotBlank()) {
        parts += syncSettings.lastBackupDeviceLabel
    }
    return stringResource(R.string.backup_status_recent, parts.joinToString(separator = " · "))
}

@Composable
internal fun BackupMessage?.resolveBackupMessage(): String? {
    val message = this ?: return null
    return stringResource(message.resourceId, *message.formatArgs.toTypedArray())
}

@Composable
internal fun BackupStatusContent(
    syncSettings: SyncSettings,
    activity: BackupActivity?,
) {
    val appLocale = LocalContext.current.appLocale()
    if (activity == null) {
        // Graphite §3: backup status is timestamp + version + device — machine facts → MONO (meta), muted ink.
        Text(
            formatBackupStatus(syncSettings, appLocale),
            style = LocalAmberType.current.meta,
            color = LocalAmberTokens.current.ink3,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(activity.titleRes), style = LocalAmberType.current.body)
        val detailRes = activity.detailRes
        val detail = if (detailRes != null) {
            stringResource(detailRes)
        } else {
            activity.detail
        }
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        }
        val progress = activity.progress
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
