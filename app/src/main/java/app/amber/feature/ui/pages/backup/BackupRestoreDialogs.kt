package app.amber.feature.ui.pages.backup

import app.amber.agent.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import app.amber.core.sync.core.SyncEncryptionMode
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.core.SyncSettings
import java.util.Locale
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.appLocale

@Composable
internal fun ImportPreviewDialog(
    preview: SyncPreview,
    payloadPreview: app.amber.core.sync.core.SyncPayloadPreview? = null,
    restoreConversations: Boolean,
    restoreGenMedia: Boolean,
    restoreActivity: BackupActivity?,
    restoring: Boolean,
    onRestoreConversationsChange: (Boolean) -> Unit,
    onRestoreGenMediaChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    val appLocale = LocalContext.current.appLocale()
    AlertDialog(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = {
            if (!restoring) onDismiss()
        },
        title = { Text(stringResource(R.string.backup_confirm_overwrite)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (restoring) {
                    Text(
                        stringResource(R.string.restore_committing),
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                    BackupStatusContent(
                        syncSettings = SyncSettings(),
                        activity = restoreActivity ?: BackupActivity(
                            titleRes = R.string.backup_activity_restoring_backup,
                        ),
                    )
                    Hairline()
                }
                // Graphite §3: backup createdAt + version strings are machine facts → MONO (meta).
                Text(
                    stringResource(R.string.backup_created_at, preview.createdAt),
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                Text(
                    stringResource(
                        R.string.backup_version,
                        preview.manifest.appVersionName,
                        preview.manifest.appVersionCode,
                    ),
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                if (preview.legacyFormat) {
                    // P7-02 迁移引导：旧格式只读恢复，恢复后建议重存为新格式。
                    Text(
                        stringResource(
                            R.string.backup_legacy_restore_notice,
                            preview.manifest.archiveVersion,
                        ),
                        style = LocalAmberType.current.secondary,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                payloadPreview?.let { payload ->
                    Hairline()
                    Text(
                        stringResource(
                            R.string.backup_contents_summary,
                            payload.conversationCount,
                            payload.messageNodeCount,
                            payload.attachmentCount,
                            payload.fileCount,
                        ) + if (payload.estimatedBytes > 0L) {
                                stringResource(
                                    R.string.backup_approximate_size,
                                    formatBytes(payload.estimatedBytes, appLocale),
                                )
                        } else {
                            ""
                        },
                        style = LocalAmberType.current.meta,
                        color = LocalAmberTokens.current.ink,
                    )
                    if (payload.includesSecrets) {
                        Text(
                            stringResource(R.string.backup_contains_oauth_tokens),
                            style = LocalAmberType.current.secondary,
                            color = LocalAmberTokens.current.ink3,
                        )
                    }
                }
                Hairline()
                Text(
                    stringResource(R.string.backup_restore_scope_warning),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
                IncludeToggleRow(
                    checked = restoreConversations,
                    title = stringResource(R.string.backup_restore_conversations),
                    description = stringResource(R.string.backup_restore_conversations_desc),
                    enabled = !restoring,
                    onCheckedChange = onRestoreConversationsChange,
                )
                IncludeToggleRow(
                    checked = restoreGenMedia,
                    title = stringResource(R.string.backup_restore_generated_images),
                    description = stringResource(R.string.backup_restore_generated_images_desc),
                    enabled = !restoring,
                    onCheckedChange = onRestoreGenMediaChange,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onRestore,
                enabled = !restoring,
            ) {
                if (restoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    if (restoring) {
                        stringResource(R.string.backup_restoring)
                    } else {
                        stringResource(R.string.backup_overwrite)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !restoring,
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun IncludeToggleRow(
    checked: Boolean,
    title: String,
    description: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = checked,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,  // handled by selectable() on the row
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = LocalAmberType.current.body)
            Text(
                description,
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        }
    }
}

/**
 * P7-02 恢复第一步：输入口令（或确认设备绑定/旧无口令格式）并触发验证。
 * 验证只做头部 + 认证标签 + 解密，不写入；解密成功后由 ImportPreviewDialog
 * 展示恢复 preview，用户确认后才覆盖。
 */
@Composable
internal fun RestorePassphraseDialog(
    preview: SyncPreview,
    verifying: Boolean,
    onDismiss: () -> Unit,
    onVerify: (String) -> Unit,
    retryHint: String? = null,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val needsPassphrase = restoreNeedsPassphrase(preview)
    val mismatch = needsPassphrase && passphrase != confirm
    AlertDialog(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = { if (!verifying) onDismiss() },
        title = { Text(stringResource(R.string.backup_restore_backup)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                retryHint?.let {
                    Text(
                        it,
                        style = LocalAmberType.current.secondary,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.backup_created_at, preview.createdAt),
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                Text(
                    stringResource(
                        R.string.backup_version,
                        preview.manifest.appVersionName,
                        preview.manifest.appVersionCode,
                    ),
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                if (preview.legacyFormat) {
                    Text(
                        stringResource(
                            R.string.backup_legacy_restore_compatible,
                            preview.manifest.archiveVersion,
                        ),
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                if (needsPassphrase) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text(stringResource(R.string.backup_confirm_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mismatch) {
                        Text(
                            stringResource(R.string.backup_passphrase_mismatch),
                            style = LocalAmberType.current.secondary,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        if (preview.manifest.encryptionMode == SyncEncryptionMode.DEVICE_BOUND) {
                            stringResource(R.string.backup_device_bound_restore_hint)
                        } else {
                            stringResource(R.string.backup_legacy_no_passphrase_hint)
                        },
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                if (verifying) {
                    BackupStatusContent(
                        syncSettings = SyncSettings(),
                        activity = BackupActivity(
                            titleRes = R.string.backup_activity_verifying_backup,
                            detailRes = R.string.backup_activity_verifying_encryption_header,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onVerify(passphrase) },
                enabled = !verifying && (!needsPassphrase || (passphrase.isNotBlank() && !mismatch)),
            ) {
                Text(
                    if (verifying) {
                        stringResource(R.string.backup_verifying)
                    } else {
                        stringResource(R.string.backup_verify_and_preview)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun formatBytes(bytes: Long, locale: Locale): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(locale, "%.1fKB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(locale, "%.1fMB", bytes / (1024.0 * 1024.0))
    else -> String.format(locale, "%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
}
