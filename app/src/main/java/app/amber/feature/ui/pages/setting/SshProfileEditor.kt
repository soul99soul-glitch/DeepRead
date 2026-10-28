package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import app.amber.agent.R
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshProfile
import kotlin.uuid.Uuid

@Composable
internal fun SshProfileEditorDialog(
    request: SshProfileEditorRequest,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (SshProfile, String?, String?, String?) -> Unit,
) {
    val existing = request.profile
    var name by remember(request.id) { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember(request.id) { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember(request.id) { mutableStateOf(existing?.port?.toString() ?: "22") }
    var username by remember(request.id) { mutableStateOf(existing?.username.orEmpty()) }
    var authMethod by remember(request.id) { mutableStateOf(existing?.authMethod ?: SshAuthMethod.PASSWORD) }
    // Credentials stay in ordinary remember state and are cleared on either button path.
    var password by remember(request.id) { mutableStateOf("") }
    var privateKey by remember(request.id) { mutableStateOf("") }
    var passphrase by remember(request.id) { mutableStateOf("") }
    var clearPassphrase by remember(request.id) { mutableStateOf(false) }
    var showPassword by remember(request.id) { mutableStateOf(false) }
    var showPassphrase by remember(request.id) { mutableStateOf(false) }
    var formError by remember(request.id) { mutableStateOf<String?>(null) }
    val invalidProfileMessage = stringResource(R.string.setting_sandbox_ssh_invalid_profile)
    val parsedPort = port.toIntOrNull()
    val authChanged = existing != null && existing.authMethod != authMethod
    val secretRequired = existing == null || authChanged
    val validationMessage = when {
        name.isBlank() -> stringResource(R.string.setting_sandbox_ssh_name_required)
        host.isBlank() -> stringResource(R.string.setting_sandbox_ssh_host_required)
        username.isBlank() -> stringResource(R.string.setting_sandbox_ssh_username_required)
        parsedPort == null || parsedPort !in 1..65535 -> stringResource(R.string.setting_sandbox_ssh_port_invalid)
        authMethod == SshAuthMethod.PASSWORD && secretRequired && password.isBlank() ->
            stringResource(R.string.setting_sandbox_ssh_password_required)
        authMethod == SshAuthMethod.PRIVATE_KEY && secretRequired && privateKey.isBlank() ->
            stringResource(R.string.setting_sandbox_ssh_private_key_required)
        else -> null
    }

    fun clearSecrets() {
        password = ""
        privateKey = ""
        passphrase = ""
        clearPassphrase = false
        showPassword = false
        showPassphrase = false
    }

    AlertDialog(
        onDismissRequest = {
            if (!saving) {
                clearSecrets()
                onDismiss()
            }
        },
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.setting_sandbox_ssh_editor_new
                    else R.string.setting_sandbox_ssh_editor_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_name)) },
                    singleLine = true,
                    enabled = !saving,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_host)) },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { value -> if (value.all(Char::isDigit)) port = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_port)) },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_username)) },
                    singleLine = true,
                    enabled = !saving,
                )
                Text(stringResource(R.string.setting_sandbox_ssh_auth_method), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authMethod == SshAuthMethod.PASSWORD,
                        onClick = { authMethod = SshAuthMethod.PASSWORD },
                        enabled = !saving,
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_auth_password)) },
                    )
                    FilterChip(
                        selected = authMethod == SshAuthMethod.PRIVATE_KEY,
                        onClick = { authMethod = SshAuthMethod.PRIVATE_KEY },
                        enabled = !saving,
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_auth_private_key)) },
                    )
                }
                if (authMethod == SshAuthMethod.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_password)) },
                        supportingText = { Text(stringResource(R.string.setting_sandbox_ssh_secret_hint)) },
                        singleLine = true,
                        enabled = !saving,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Lucide.EyeOff else Lucide.Eye,
                                    stringResource(if (showPassword) R.string.setting_sandbox_ssh_hide_secret else R.string.setting_sandbox_ssh_show_secret),
                                )
                            }
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_private_key)) },
                        supportingText = { Text(stringResource(R.string.setting_sandbox_ssh_private_key_hint)) },
                        enabled = !saving,
                        minLines = 4,
                        maxLines = 8,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_passphrase)) },
                        supportingText = { Text(stringResource(R.string.setting_sandbox_ssh_passphrase_hint)) },
                        singleLine = true,
                        enabled = !saving && !clearPassphrase,
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    if (showPassphrase) Lucide.EyeOff else Lucide.Eye,
                                    stringResource(if (showPassphrase) R.string.setting_sandbox_ssh_hide_secret else R.string.setting_sandbox_ssh_show_secret),
                                )
                            }
                        },
                    )
                    if (existing != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = clearPassphrase,
                                onCheckedChange = {
                                    clearPassphrase = it
                                    if (it) passphrase = ""
                                },
                                enabled = !saving,
                            )
                            Text(stringResource(R.string.setting_sandbox_ssh_passphrase_clear))
                        }
                    }
                }
                (validationMessage ?: formError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            SandboxAction(
                label = stringResource(R.string.setting_sandbox_ssh_save),
                onClick = {
                    val portNumber = parsedPort ?: return@SandboxAction
                    val now = System.currentTimeMillis()
                    val endpointChanged = existing != null &&
                        (existing.host != host.trim() || existing.port != portNumber)
                    val saved = runCatching { (existing ?: SshProfile(
                        id = request.id,
                        name = name.trim(),
                        host = host.trim(),
                        port = portNumber,
                        username = username.trim(),
                        authMethod = authMethod,
                        createdAtMs = now,
                        updatedAtMs = now,
                    )).copy(
                        name = name.trim(),
                        host = host.trim(),
                        port = portNumber,
                        username = username.trim(),
                        authMethod = authMethod,
                        acceptedHostKeyFingerprint = if (endpointChanged) null else existing?.acceptedHostKeyFingerprint,
                        updatedAtMs = now,
                    )
                    }.getOrElse {
                        formError = invalidProfileMessage
                        return@SandboxAction
                    }
                    val newPassword = password.takeIf { it.isNotEmpty() }
                    val newPrivateKey = privateKey.takeIf { it.isNotEmpty() }
                    val newPassphrase = when {
                        clearPassphrase -> ""
                        passphrase.isNotEmpty() -> passphrase
                        else -> null
                    }
                    clearSecrets()
                    onSave(saved, newPassword, newPrivateKey, newPassphrase)
                },
                enabled = validationMessage == null && !saving,
                primary = true,
            )
        },
        dismissButton = {
            SandboxAction(
                label = stringResource(R.string.setting_sandbox_ssh_cancel),
                onClick = {
                    if (!saving) {
                        clearSecrets()
                        onDismiss()
                    }
                },
                enabled = !saving,
            )
        },
    )
}

internal data class SshProfileEditorRequest(val id: String, val profile: SshProfile?) {
    companion object {
        fun new() = SshProfileEditorRequest(Uuid.random().toString(), null)
        fun existing(profile: SshProfile) = SshProfileEditorRequest(profile.id, profile)
    }
}
