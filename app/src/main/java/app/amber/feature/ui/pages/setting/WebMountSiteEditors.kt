package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.X
import app.amber.agent.R

@Composable
internal fun OAuthEditDialog(
    siteName: String,
    initialAppId: String,
    initialAppSecret: String,
    initialScope: String,
    providerHint: String,
    onDismiss: () -> Unit,
    onSave: (appId: String, appSecret: String, scope: String) -> Unit,
) {
    var appId by remember { mutableStateOf(initialAppId) }
    var appSecret by remember { mutableStateOf(initialAppSecret) }
    var scopeText by remember { mutableStateOf(initialScope) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_webmount_oauth_edit_title, siteName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    providerHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = appId,
                    onValueChange = { appId = it },
                    label = { Text(stringResource(R.string.setting_webmount_oauth_app_id)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = appSecret,
                    onValueChange = { appSecret = it },
                    label = { Text(stringResource(R.string.setting_webmount_oauth_app_secret)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = scopeText,
                    onValueChange = { scopeText = it },
                    label = { Text(stringResource(R.string.setting_webmount_oauth_scope)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(appId, appSecret, scopeText) }) {
                Text(stringResource(R.string.setting_webmount_oauth_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_webmount_profile_import_dialog_cancel))
            }
        },
    )
}

@Composable
internal fun AddCustomSiteDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, needsLogin: Boolean, cookieName: String?) -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var needsLogin by remember { mutableStateOf(true) }
    var cookieInput by remember { mutableStateOf("") }
    val urlValid = urlInput.startsWith("http://") || urlInput.startsWith("https://")
    val canSubmit = nameInput.isNotBlank() && urlValid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_webmount_custom_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.setting_webmount_custom_dialog_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.setting_webmount_custom_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.setting_webmount_custom_url_label)) },
                    placeholder = { Text("https://weibo.com") },
                    singleLine = true,
                    isError = urlInput.isNotBlank() && !urlValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Per-user feedback: most user-added sites need login (微博, X,
                // 小红书 etc.). The old "leave blank for public sites"
                // helper text confused users who didn't know their site's
                // cookie name but did know it needed login — they ended up
                // with no Sign-in button. Make it an explicit toggle.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.setting_webmount_custom_needs_login_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.setting_webmount_custom_needs_login_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = needsLogin,
                        onCheckedChange = { needsLogin = it },
                    )
                }
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    enabled = needsLogin,
                    label = { Text(stringResource(R.string.setting_webmount_custom_cookie_label)) },
                    placeholder = { Text("SUB") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            stringResource(R.string.setting_webmount_custom_cookie_supporting),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onAdd(
                        nameInput.trim(),
                        urlInput.trim(),
                        needsLogin,
                        cookieInput.trim().ifBlank { null }.takeIf { needsLogin },
                    )
                },
            ) {
                Text(stringResource(R.string.setting_webmount_custom_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_webmount_profile_import_dialog_cancel))
            }
        },
    )
}
