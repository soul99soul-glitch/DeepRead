package app.amber.feature.ui.pages.extensions

import app.amber.feature.ui.components.ds.Hairline
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.SwitchSize
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.export.ModeInjectionSerializer
import app.amber.core.export.rememberExporter
import app.amber.core.export.rememberImporter
import app.amber.core.model.PromptInjection
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.ExportDialog
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
internal fun ModeInjectionTab(
    modeInjections: List<PromptInjection.ModeInjection>,
    onSave: (PromptInjection.ModeInjection) -> Unit,
    onToggle: (Uuid) -> Unit,
    onDelete: (Uuid) -> Unit,
    onMove: (Uuid, Uuid) -> Unit,
    onImport: (PromptInjection.ModeInjection) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val currentOnImport by rememberUpdatedState(onImport)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromId = from.key as? Uuid
        val toId = to.key as? Uuid
        if (fromId != null && toId != null) onMove(fromId, toId)
    }
    val editState = useEditState<PromptInjection.ModeInjection>(onSave)
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(ModeInjectionSerializer) { result ->
        result.onSuccess { imported ->
            currentOnImport(imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            state = lazyListState
        ) {
            item {
                SectionLabel(
                    text = stringResource(R.string.prompt_page_mode_injection_tab),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (modeInjections.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_mode_injection_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                itemsIndexed(modeInjections, key = { _, injection -> injection.id }) { index, injection ->
                    ReorderableItem(
                        state = reorderableState,
                        key = injection.id
                    ) { isDragging ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (index > 0) Hairline()
                            ModeInjectionCard(
                                injection = injection,
                                groupedFirst = index == 0,
                                groupedLast = index == modeInjections.lastIndex,
                                modifier = Modifier
                                    .longPressDraggableHandle()
                                    .graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        }
                                    },
                                onEdit = { editState.open(injection) },
                                onToggle = { onToggle(injection.id) },
                                onDelete = { onDelete(injection.id) }
                            )
                        }
                    }
                }
            }
        }

        PromptDock(
            label = stringResource(R.string.prompt_page_add_mode_injection),
            onImport = { importer.importFromFile() },
            onAdd = { editState.open(PromptInjection.ModeInjection()) },
        )
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            ModeInjectionEditSheet(
                injection = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun ModeInjectionCard(
    injection: PromptInjection.ModeInjection,
    modifier: Modifier = Modifier,
    groupedFirst: Boolean,
    groupedLast: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(injection, ModeInjectionSerializer)
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val rowShape = RoundedCornerShape(
        topStart = if (groupedFirst) 14.dp else 0.dp,
        topEnd = if (groupedFirst) 14.dp else 0.dp,
        bottomStart = if (groupedLast) 14.dp else 0.dp,
        bottomEnd = if (groupedLast) 14.dp else 0.dp,
    )

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(Lucide.X, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(Lucide.Trash2, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        Surface(
            shape = rowShape,
            border = BorderStroke(1.dp, tokens.line),
            color = tokens.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(tokens.surface2, RoundedCornerShape(9.dp))
                            .border(1.dp, tokens.line, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.GripVertical,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = tokens.ink3,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = injection.name.ifEmpty { stringResource(R.string.prompt_page_unnamed) },
                            modifier = Modifier.weight(1f),
                            style = type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = tokens.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!injection.enabled) {
                            WorkspaceStatusPill(
                                text = stringResource(R.string.prompt_page_disabled),
                                tone = WorkspaceTone.Neutral,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.prompt_page_priority_format, injection.priority) +
                            " · " + getPositionLabel(injection.position),
                        style = type.meta.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = tokens.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = injection.enabled,
                    onCheckedChange = { onToggle() },
                    size = SwitchSize.Small,
                    trackColor = tokens.accent,
                    trackColorUnchecked = tokens.surface2,
                    thumbColor = tokens.accentInk,
                    thumbColorUnchecked = tokens.ink2,
                )
                IconButton(onClick = { showExportDialog = true }) {
                    Icon(Lucide.Share2, stringResource(R.string.export_title))
                }
                IconButton(onClick = onEdit) {
                    Icon(Lucide.Pencil, stringResource(R.string.prompt_page_edit))
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}
