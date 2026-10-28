package app.amber.feature.ui.pages.extensions

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.model.InjectionPosition
import app.amber.core.model.PromptInjection
import app.amber.feature.ui.components.ui.FormItem
import app.amber.feature.ui.theme.LocalAmberTokens

@Composable
internal fun ModeInjectionEditSheet(
    injection: PromptInjection.ModeInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.ModeInjection) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val tokens = LocalAmberTokens.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = tokens.raised,
        contentColor = tokens.ink,
        tonalElevation = 0.dp,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(Lucide.ArrowDown, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_mode_injection),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = injection.name,
                    onValueChange = { onEdit(injection.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = injection.enabled,
                            onCheckedChange = { onEdit(injection.copy(enabled = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = injection.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(injection.copy(priority = p)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    stringResource(R.string.prompt_page_injection_position),
                    style = MaterialTheme.typography.titleSmall
                )
                InjectionPositionSelector(
                    position = injection.position,
                    onSelect = { onEdit(injection.copy(position = it)) }
                )

                AnimatedVisibility(visible = injection.position == InjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = injection.injectDepth.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { d -> onEdit(injection.copy(injectDepth = d)) }
                        },
                        label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Text(
                    stringResource(R.string.prompt_page_injection_role),
                    style = MaterialTheme.typography.titleSmall
                )
                InjectionRoleSelector(
                    role = injection.role,
                    onSelect = { onEdit(injection.copy(role = it)) }
                )

                OutlinedTextField(
                    value = injection.content,
                    onValueChange = { onEdit(injection.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    minLines = 5
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}
