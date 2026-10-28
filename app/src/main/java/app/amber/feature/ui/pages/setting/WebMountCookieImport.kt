package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.webmount.login.WebMountLoginController
import app.amber.feature.webmount.login.WebMountLoginTarget

@Composable
internal fun CookieImportFallback(
    target: WebMountLoginTarget,
    enabled: Boolean,
    onImport: (Map<String, String>, (Boolean) -> Unit) -> Unit,
) {
    var expanded by remember(target.id) { mutableStateOf(false) }
    var rawCookie by remember(target.id) { mutableStateOf("") }
    var fieldValues by remember(target.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var importing by remember(target.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                stringResource(
                    if (expanded) R.string.setting_webmount_cookie_import_collapse
                    else R.string.setting_webmount_cookie_import_expand
                )
            )
        }
        if (expanded) {
            OutlinedTextField(
                value = rawCookie,
                onValueChange = { rawCookie = it },
                label = { Text(stringResource(R.string.setting_webmount_cookie_line_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            target.manualCookieFields.forEach { field ->
                OutlinedTextField(
                    value = fieldValues[field.name].orEmpty(),
                    onValueChange = { value -> fieldValues = fieldValues + (field.name to value) },
                    label = { Text(field.name + if (field.required) " *" else "") },
                    supportingText = {
                        if (field.description.isNotBlank()) Text(field.description)
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    enabled = enabled && !importing,
                    onClick = {
                        val parsed = WebMountLoginController.parseCookieInput(rawCookie)
                        val keyed = fieldValues
                            .mapValues { it.value.trim() }
                            .filterValues { it.isNotBlank() }
                        val combined = parsed + keyed
                        if (combined.isEmpty()) return@TextButton
                        importing = true
                        onImport(combined) { success ->
                            importing = false
                            if (success) {
                                rawCookie = ""
                                fieldValues = emptyMap()
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (importing) R.string.setting_webmount_importing
                            else R.string.setting_webmount_import
                        )
                    )
                }
            }
        }
    }
}
