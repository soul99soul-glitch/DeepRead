package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.core.model.AssistantMemory
import app.amber.core.model.MemoryKind
import app.amber.core.model.MemoryScope
import app.amber.core.memory.store.MemoryRepository
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import java.util.Locale

@Composable
internal fun MemoryEditorDialog(
    memory: AssistantMemory,
    mutation: MemoryMutationState,
    onChange: (AssistantMemory) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isSaving = mutation is MemoryMutationState.Saving
    val saveError = (mutation as? MemoryMutationState.Failed)
        ?.takeIf {
            it.operation != MemoryMutationOperation.DELETE &&
                it.draft.id == memory.id
        }
    AlertDialog(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = {
            if (!isSaving) {
                onDismiss()
            }
        },
        title = {
            Text(
                if (memory.id == 0) {
                    stringResource(R.string.setting_agent_memory_add_title)
                } else {
                    stringResource(R.string.setting_agent_memory_edit_title)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (memory.id != 0) {
                    MemoryEditMetadata(memory)
                }
                TextField(
                    value = memory.content,
                    onValueChange = { onChange(memory.copy(content = it)) },
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp),
                    label = { Text(stringResource(R.string.setting_agent_memory_content_label)) },
                    minLines = 5,
                    maxLines = 12,
                )
                saveError?.let { error ->
                    Text(
                        text = stringResource(
                            R.string.setting_agent_memory_write_error,
                            error.message,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = LocalAmberType.current.secondary,
                    )
                }
                if (isSaving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.setting_agent_memory_saving),
                            style = LocalAmberType.current.secondary,
                            color = workspaceColors().muted,
                        )
                    }
                }
                MemoryClassificationEditor(
                    memory = memory,
                    enabled = !isSaving,
                    onChange = onChange,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving && memory.content.isNotBlank(),
                onClick = onSave,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun MemoryDeleteDialog(
    memory: AssistantMemory,
    mutation: MemoryMutationState,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDeleting = mutation is MemoryMutationState.Deleting
    val deleteError = (mutation as? MemoryMutationState.Failed)
        ?.takeIf {
            it.operation == MemoryMutationOperation.DELETE && it.draft.id == memory.id
        }
    AlertDialog(
        onDismissRequest = {
            if (!isDeleting) {
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.confirm_delete)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = memory.content)
                deleteError?.let { error ->
                    Text(
                        text = stringResource(
                            R.string.setting_agent_memory_delete_error,
                            error.message,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = LocalAmberType.current.secondary,
                    )
                }
                if (isDeleting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.setting_agent_memory_deleting),
                            style = LocalAmberType.current.secondary,
                            color = workspaceColors().muted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isDeleting,
                onClick = onDelete,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isDeleting,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun MemoryConflictDialog(
    conflict: MemoryMutationState.Conflict,
    onDismiss: () -> Unit,
    onUseLatest: (AssistantMemory) -> Unit,
    onKeepDraft: (AssistantMemory) -> Unit,
) {
    val latest = conflict.latest
    val isUpdate = conflict.operation == MemoryMutationOperation.UPDATE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isUpdate) {
                    stringResource(R.string.setting_agent_memory_edit_conflict_title)
                } else {
                    stringResource(R.string.setting_agent_memory_delete_conflict_title)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (isUpdate) {
                        stringResource(R.string.setting_agent_memory_edit_conflict_message)
                    } else {
                        stringResource(R.string.setting_agent_memory_delete_conflict_message)
                    },
                )
                if (latest == null) {
                    Text(
                        text = stringResource(R.string.setting_agent_memory_conflict_deleted),
                        color = MaterialTheme.colorScheme.error,
                        style = LocalAmberType.current.secondary,
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.setting_agent_memory_conflict_latest_label,
                            latest.revision,
                        ),
                        style = LocalAmberType.current.secondary.copy(fontWeight = FontWeight.SemiBold),
                    )
                    SelectionContainer {
                        Text(
                            text = latest.content,
                            modifier = Modifier.fillMaxWidth(),
                            style = LocalAmberType.current.body,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (latest != null) {
                    TextButton(onClick = { onUseLatest(latest) }) {
                        Text(
                            if (isUpdate) {
                                stringResource(R.string.setting_agent_memory_use_latest)
                            } else {
                                stringResource(R.string.setting_agent_memory_retry_delete)
                            },
                        )
                    }
                    if (isUpdate) {
                        TextButton(onClick = { onKeepDraft(latest) }) {
                            Text(stringResource(R.string.setting_agent_memory_keep_draft))
                        }
                    }
                }
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
private fun MemoryEditMetadata(memory: AssistantMemory) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MemoryMetadataLine(
            label = stringResource(R.string.setting_agent_memory_metadata_source),
            value = memorySourceLabel(memory),
        )
        MemoryMetadataLine(
            label = stringResource(R.string.setting_agent_memory_metadata_kind),
            value = memoryKindLabel(memory.kind),
        )
        MemoryMetadataLine(
            label = stringResource(R.string.setting_agent_memory_metadata_confidence),
            value = String.format(Locale.getDefault(), "%.2f", memory.confidence),
        )
        if (memory.updatedAt > 0L) {
            MemoryMetadataLine(
                label = stringResource(R.string.setting_agent_memory_metadata_updated),
                value = formatMemoryDate(memory.updatedAt),
            )
        }
        if (memory.kind == MemoryKind.TOPIC && memory.memberIds.isNotEmpty()) {
            MemoryMetadataLine(
                label = stringResource(R.string.setting_agent_memory_metadata_members),
                value = memory.memberIds.joinToString(", ") { "#$it" },
            )
        }
        memory.lastUsedAt?.takeIf { it > 0L }?.let { lastUsedAt ->
            MemoryMetadataLine(
                label = stringResource(R.string.setting_agent_memory_metadata_last_used),
                value = formatMemoryDate(lastUsedAt),
            )
        }
        if (memory.useCount > 0) {
            MemoryMetadataLine(
                label = stringResource(R.string.setting_agent_memory_metadata_use_count),
                value = memory.useCount.toString(),
            )
        }
        memory.expiresAt?.takeIf { it > 0L }?.let { expiresAt ->
            MemoryMetadataLine(
                label = stringResource(R.string.setting_agent_memory_metadata_expires),
                value = formatMemoryDate(expiresAt),
            )
        }
    }
}

@Composable
private fun MemoryMetadataLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.8f),
            style = LocalAmberType.current.secondary,
            color = workspaceColors().muted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.2f),
            style = LocalAmberType.current.secondary,
            color = workspaceColors().ink,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MemoryClassificationEditor(
    memory: AssistantMemory,
    enabled: Boolean,
    onChange: (AssistantMemory) -> Unit,
) {
    // Topics stay in their dream-managed classification: pinning or re-scoping
    // one would defeat the recall rule that keeps topics out of always-eligible.
    val classificationEditable = enabled && memory.kind != MemoryKind.TOPIC
    var scopeMenuExpanded by remember(memory.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_agent_memory_classification),
            style = LocalAmberType.current.secondary.copy(fontWeight = FontWeight.SemiBold),
            color = LocalAmberTokens.current.accent,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = classificationEditable,
                onClick = { scopeMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_agent_memory_scope),
                            style = LocalAmberType.current.body,
                            color = workspaceColors().ink,
                        )
                        Text(
                            text = stringResource(R.string.setting_agent_memory_scope_desc),
                            style = LocalAmberType.current.secondary,
                            color = workspaceColors().muted,
                        )
                    }
                    Text(
                        text = memoryScopeLabel(memory.scope),
                        style = LocalAmberType.current.secondary,
                        color = workspaceColors().muted,
                        textAlign = TextAlign.End,
                    )
                }
            }
            DropdownMenu(
                expanded = scopeMenuExpanded,
                onDismissRequest = { scopeMenuExpanded = false },
            ) {
                MemoryScope.entries.forEach { scope ->
                    DropdownMenuItem(
                        text = { Text(memoryScopeLabel(scope)) },
                        onClick = {
                            scopeMenuExpanded = false
                            if (scope != memory.scope) {
                                onChange(memory.copy(scope = scope))
                            }
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = classificationEditable) {
                    onChange(memory.copy(pinned = !memory.pinned))
                }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_agent_memory_pinned),
                    style = LocalAmberType.current.body,
                    color = workspaceColors().ink,
                )
                Text(
                    text = stringResource(R.string.setting_agent_memory_pinned_desc),
                    style = LocalAmberType.current.secondary,
                    color = workspaceColors().muted,
                )
            }
            Switch(
                checked = memory.pinned,
                onCheckedChange = { onChange(memory.copy(pinned = it)) },
                enabled = classificationEditable,
            )
        }
    }
}

@Composable
private fun memorySourceLabel(memory: AssistantMemory): String {
    val parts = buildList {
        if (!memory.sourceConversationId.isNullOrBlank()) {
            add(stringResource(R.string.setting_agent_memory_source_chat))
        }
        if (memory.sourceMessageIds.isNotEmpty()) {
            add(
                stringResource(
                    R.string.setting_agent_memory_source_messages,
                    memory.sourceMessageIds.size,
                )
            )
        }
        if (memory.supersedesIds.isNotEmpty()) {
            add(
                stringResource(
                    R.string.setting_agent_memory_source_supersedes,
                    memory.supersedesIds.size,
                )
            )
        }
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return when (memory.sourceTrigger) {
        MemoryRepository.TRIGGER_AUTO_EXTRACTION ->
            stringResource(R.string.setting_agent_memory_source_auto_extraction)
        MemoryRepository.TRIGGER_TOOL -> stringResource(R.string.setting_agent_memory_source_tool)
        MemoryRepository.TRIGGER_DREAM -> stringResource(R.string.setting_agent_memory_source_dream)
        null -> if (memory.sourceRunId == null) {
            stringResource(R.string.setting_agent_memory_source_manual)
        } else {
            stringResource(R.string.setting_agent_memory_source_run)
        }
        else -> stringResource(R.string.setting_agent_memory_source_run)
    }
}
