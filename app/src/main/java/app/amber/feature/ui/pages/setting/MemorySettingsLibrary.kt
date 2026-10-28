package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import app.amber.agent.R
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryEvent
import app.amber.core.model.AssistantMemory
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MemoryLibrarySubpage(
    modifier: Modifier,
    memories: List<AssistantMemory>,
    shortTermMemories: List<AssistantMemory>,
    longTermMemories: List<AssistantMemory>,
    archivedMemories: List<AssistantMemory>,
    pendingCandidates: List<MemoryCandidate>,
    recentMemoryEvents: List<MemoryEvent>,
    running: Boolean,
    onAcceptCandidate: (String) -> Unit,
    onIgnoreCandidate: (String) -> Unit,
    onIgnoreLowConfidenceCandidates: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onAddMemory: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    restoringMemoryIds: Set<Int>,
    onRestoreMemory: (AssistantMemory) -> Unit,
    onInfoClick: (String, String) -> Unit,
) {
    var showPortabilityDialog by remember { mutableStateOf(false) }
    var showEventsDialog by remember { mutableStateOf(false) }
    val docsTitle = stringResource(R.string.memory_docs_title)
    val docsEmptyText = stringResource(R.string.memory_docs_empty)
    val docsInfoText = stringResource(R.string.memory_docs_info_body)

    // The library renders memories as documents: the core bucket is one file,
    // each dream-synthesized topic is one file, and every record not yet
    // grouped into a topic merges into its per-kind document.
    val docs by produceState<List<MemoryDoc>?>(null, memories, shortTermMemories, longTermMemories) {
        value = withContext(Dispatchers.Default) {
            buildMemoryDocs(memories, shortTermMemories, longTermMemories)
        }
    }
    val projectedDocs = docs.orEmpty()
    var openDocId by remember { mutableStateOf<String?>(null) }
    val openDoc = projectedDocs.firstOrNull { it.id == openDocId }
    BackHandler(enabled = openDoc != null) { openDocId = null }
    val listState = rememberLazyListState()

    if (openDoc != null) {
        MemoryDocDetail(
            doc = openDoc,
            modifier = modifier,
            onBack = { openDocId = null },
            onEditMemory = onEditMemory,
            onDeleteMemory = onDeleteMemory,
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item("memory_summary") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MemorySummarySection(
                    coreMemories = memories,
                    longTermMemories = longTermMemories,
                    shortTermMemories = shortTermMemories,
                    onEditMemory = onEditMemory,
                )
            }
        }

        item("memory_gap_candidates") { Spacer(Modifier.height(28.dp)) }
        memoryCandidatesSection(
            candidates = pendingCandidates,
            onAccept = onAcceptCandidate,
            onIgnore = onIgnoreCandidate,
            onIgnoreLowConfidence = onIgnoreLowConfidenceCandidates,
        )

        item("memory_gap_docs") { Spacer(Modifier.height(28.dp)) }
        memoryDocumentsSection(
            title = docsTitle,
            emptyText = docsEmptyText,
            docs = projectedDocs,
            isLoading = docs == null,
            infoText = docsInfoText,
            onInfoClick = onInfoClick,
            onAddMemory = onAddMemory,
            onOpenDoc = { openDocId = it },
        )

        item("memory_gap_archived") { Spacer(Modifier.height(28.dp)) }
        memoryArchivedSection(
            archivedMemories = archivedMemories,
            restoringIds = restoringMemoryIds,
            onRestore = onRestoreMemory,
        )

        item("memory_gap_maintenance") { Spacer(Modifier.height(28.dp)) }
        item("memory_maintenance") {
            MemoryMaintenanceSection(
                eventCount = recentMemoryEvents.size,
                onOpenPortability = { showPortabilityDialog = true },
                onOpenEvents = { showEventsDialog = true },
            )
        }
    }

    if (showPortabilityDialog) {
        AlertDialog(
            onDismissRequest = { showPortabilityDialog = false },
            title = { Text(stringResource(R.string.memory_import_export_title)) },
            text = {
                MemoryPortabilitySection(
                    running = running,
                    onExport = onExport,
                    onImport = onImport,
                )
            },
            confirmButton = {
                TextButton(onClick = { showPortabilityDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    if (showEventsDialog) {
        AlertDialog(
            onDismissRequest = { showEventsDialog = false },
            title = { Text(stringResource(R.string.memory_event_log_title)) },
            text = { MemoryEventsSection(events = recentMemoryEvents, showTitle = false) },
            confirmButton = {
                TextButton(onClick = { showEventsDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}

@Composable
private fun MemoryEventsSection(events: List<MemoryEvent>) {
    MemoryEventsSection(events = events, showTitle = true)
}

@Composable
private fun MemoryEventsSection(
    events: List<MemoryEvent>,
    showTitle: Boolean,
) {
    if (showTitle) {
        SectionLabel(
            text = stringResource(R.string.memory_event_log_title),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (events.isEmpty()) {
        Text(
            text = stringResource(R.string.memory_event_log_empty),
            style = LocalAmberType.current.secondary,
            color = workspaceColors().muted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        return
    }
    CardGroup {
        events.take(6).forEach { event ->
            item(
                headlineContent = { Text(event.type.wireName) },
                supportingContent = {
                    val message = event.message.ifBlank { "memory=${event.memoryId ?: "-"} candidate=${event.candidateId ?: "-"}" }
                    // Graphite §3: fallback shows raw memory/candidate ids → MONO.
                    val isIdFallback = event.message.isBlank()
                    Text(
                        message,
                        style = if (isIdFallback) LocalAmberType.current.meta else LocalAmberType.current.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun MemoryMaintenanceSection(
    eventCount: Int,
    onOpenPortability: () -> Unit,
    onOpenEvents: () -> Unit,
) {
    SettingCardGroup(title = stringResource(R.string.memory_maintenance_tools_title)) {
        item(
            onClick = onOpenPortability,
            headlineContent = { Text(stringResource(R.string.memory_import_export_title)) },
            supportingContent = { Text(stringResource(R.string.memory_import_export_desc)) },
        )
        item(
            onClick = onOpenEvents,
            headlineContent = { Text(stringResource(R.string.memory_event_log_title)) },
            supportingContent = { Text(stringResource(R.string.memory_event_log_desc, eventCount)) },
        )
    }
}

@Composable
private fun MemoryPortabilitySection(
    running: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.memory_frontmatter_export_title)) },
            supportingContent = { Text(stringResource(R.string.memory_frontmatter_export_desc)) },
            trailingContent = {
                TextButton(enabled = !running, onClick = onExport) {
                    Text(stringResource(R.string.memory_export))
                }
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.memory_frontmatter_import_title)) },
            supportingContent = { Text(stringResource(R.string.memory_frontmatter_import_desc)) },
            trailingContent = {
                TextButton(enabled = !running, onClick = onImport) {
                    Text(stringResource(R.string.memory_import))
                }
            },
        )
    }
}
