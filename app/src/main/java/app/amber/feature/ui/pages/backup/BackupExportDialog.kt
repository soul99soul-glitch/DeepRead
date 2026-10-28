package app.amber.feature.ui.pages.backup

import app.amber.agent.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import app.amber.core.sync.core.NO_PASSPHRASE_FALLBACK
import app.amber.core.sync.core.SyncEncryptionMode
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/**
 * P7-02 导出加密方式：口令加密（输入 + 确认 + 强度提示）或设备绑定加密，
 * 二选一的明确 UI。新备份不再允许“无口令”导出。
 */
@Composable
internal fun ExportEncryptionDialog(
    seed: ExportRequestSeed,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String, mode: SyncEncryptionMode) -> Unit,
) {
    var mode by remember { mutableStateOf(SyncEncryptionMode.PASSPHRASE) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val error = when {
        mode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank() ->
            stringResource(R.string.backup_passphrase_required)
        mode == SyncEncryptionMode.PASSPHRASE && passphrase != confirm ->
            stringResource(R.string.backup_passphrase_mismatch)
        passphrase == NO_PASSPHRASE_FALLBACK ->
            stringResource(R.string.backup_reserved_passphrase)
        else -> null
    }
    AlertDialog(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_encrypted_backup)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == SyncEncryptionMode.PASSPHRASE,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { mode = SyncEncryptionMode.PASSPHRASE },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = mode == SyncEncryptionMode.PASSPHRASE,
                        onClick = null,
                    )
                    Text(
                        stringResource(R.string.backup_custom_passphrase_recommended),
                        style = LocalAmberType.current.body,
                    )
                }
                if (mode == SyncEncryptionMode.PASSPHRASE) {
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
                    Text(
                        stringResource(R.string.backup_passphrase_strength, passphraseStrength(passphrase)),
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == SyncEncryptionMode.DEVICE_BOUND,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { mode = SyncEncryptionMode.DEVICE_BOUND },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = mode == SyncEncryptionMode.DEVICE_BOUND,
                        onClick = null,
                    )
                    Text(stringResource(R.string.backup_device_bound_encryption), style = LocalAmberType.current.body)
                }
                if (mode == SyncEncryptionMode.DEVICE_BOUND) {
                    Text(
                        stringResource(R.string.backup_device_bound_encryption_desc),
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = LocalAmberType.current.secondary,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passphrase, mode) },
                enabled = error == null,
            ) {
                Text(stringResource(R.string.backup_start_export))
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
private fun passphraseStrength(passphrase: String): String {
    if (passphrase.length < 8) return stringResource(R.string.backup_strength_weak)
    val classes = listOf(
        Regex("[a-z]"),
        Regex("[A-Z]"),
        Regex("[0-9]"),
        Regex("[^A-Za-z0-9]"),
    ).count { it.containsMatchIn(passphrase) }
    return when {
        passphrase.length >= 12 && classes >= 3 -> stringResource(R.string.backup_strength_strong)
        classes >= 2 -> stringResource(R.string.backup_strength_medium)
        else -> stringResource(R.string.backup_strength_weak)
    }
}
