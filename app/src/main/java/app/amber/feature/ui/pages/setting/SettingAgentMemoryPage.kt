package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.core.memory.dream.PersistedMemoryDreamPlan
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryEvent
import app.amber.core.memory.model.MemoryWorkerDreamGate
import app.amber.core.model.AssistantMemory
import app.amber.core.memory.store.MemoryRepository
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Composable
fun SettingAgentMemoryPage(
    subpage: MemorySettingsSubpage = MemorySettingsSubpage.Overview,
) {
    val vm = koinViewModel<SettingAgentMemoryVM>()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val memoryCounts by if (subpage == MemorySettingsSubpage.Overview) {
        vm.memoryCounts.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    }
    val memories by if (
        subpage == MemorySettingsSubpage.Library ||
        subpage == MemorySettingsSubpage.Overview
    ) {
        vm.memories.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<AssistantMemory>()) }
    }
    val shortTermMemories by if (
        subpage == MemorySettingsSubpage.Library ||
        subpage == MemorySettingsSubpage.Overview
    ) {
        vm.shortTermMemories.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<AssistantMemory>()) }
    }
    val longTermMemories by if (
        subpage == MemorySettingsSubpage.Library ||
        subpage == MemorySettingsSubpage.Overview
    ) {
        vm.longTermMemories.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<AssistantMemory>()) }
    }
    val pendingCandidateCount by if (
        subpage == MemorySettingsSubpage.Overview ||
        subpage == MemorySettingsSubpage.Worker
    ) {
        vm.pendingCandidateCount.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(0) }
    }
    val pendingCandidates by if (subpage == MemorySettingsSubpage.Library) {
        vm.pendingCandidates.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<List<MemoryCandidate>>(emptyList()) }
    }
    val archivedMemories by if (subpage == MemorySettingsSubpage.Library) {
        vm.archivedMemories.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<AssistantMemory>()) }
    }
    val restoringMemoryIds by vm.restoringMemoryIds.collectAsStateWithLifecycle()
    val recentMemoryEvents by if (
        subpage == MemorySettingsSubpage.Worker ||
        subpage == MemorySettingsSubpage.Library
    ) {
        vm.recentMemoryEvents.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<List<MemoryEvent>>(emptyList()) }
    }
    val dreamPlan by if (
        subpage == MemorySettingsSubpage.Overview ||
        subpage == MemorySettingsSubpage.Worker
    ) {
        vm.dreamPlan.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<PersistedMemoryDreamPlan?>(null) }
    }
    val memoryTaskRunning by vm.memoryTaskRunning.collectAsStateWithLifecycle()
    val operationMessage by vm.operationMessage.collectAsStateWithLifecycle()
    val memoryMutation by vm.memoryMutation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editingMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var memoryInfoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    val pageTitle = when (subpage) {
        MemorySettingsSubpage.Overview -> stringResource(R.string.setting_agent_memory_title)
        MemorySettingsSubpage.Recall -> stringResource(R.string.memory_recall_title)
        MemorySettingsSubpage.Worker -> stringResource(R.string.memory_worker_title)
        MemorySettingsSubpage.Compaction -> stringResource(R.string.memory_compaction_title)
        MemorySettingsSubpage.Library -> stringResource(R.string.memory_library_title)
    }

    LaunchedEffect(operationMessage) {
        operationMessage?.let { message ->
            toaster.show(message, type = ToastType.Info)
            vm.consumeOperationMessage()
        }
    }

    LaunchedEffect(memoryMutation) {
        when (val mutation = memoryMutation) {
            is MemoryMutationState.Saved -> {
                if (editingMemory?.id == mutation.memoryId ||
                    mutation.operation == MemoryMutationOperation.CREATE
                ) {
                    editingMemory = null
                }
                vm.consumeMemoryMutation()
            }

            is MemoryMutationState.Deleted -> {
                if (pendingDeleteMemory?.id == mutation.memoryId) {
                    pendingDeleteMemory = null
                }
                vm.consumeMemoryMutation()
            }

            else -> Unit
        }
    }

    editingMemory?.let { memory ->
        MemoryEditorDialog(
            memory = memory,
            mutation = memoryMutation,
            onChange = { editingMemory = it },
            onSave = {
                if (memory.id == 0) {
                    vm.addMemory(memory)
                } else {
                    vm.updateMemory(memory)
                }
            },
            onDismiss = {
                editingMemory = null
                vm.consumeMemoryMutation()
            },
        )
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = pageTitle,
                navigationIcon = { BackButton() },
                actions = {
                    if (subpage == MemorySettingsSubpage.Worker) {
                        val worker = settings.agentRuntime.memoryWorker
                        val canRunDream = worker.enabled && MemoryWorkerDreamGate.isAnyDreamEnabled(worker)
                        IconButton(
                            enabled = canRunDream,
                            onClick = { vm.triggerDreamNow() },
                        ) {
                            Icon(
                                imageVector = Lucide.Play,
                                contentDescription = stringResource(R.string.memory_run_daydream_now),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        if (subpage == MemorySettingsSubpage.Library) {
            MemoryLibrarySubpage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = SettingPageHorizontalInset, vertical = 8.dp)
                    .imePadding(),
                memories = memories,
                shortTermMemories = shortTermMemories,
                longTermMemories = longTermMemories,
                archivedMemories = archivedMemories,
                pendingCandidates = pendingCandidates,
                recentMemoryEvents = recentMemoryEvents,
                running = memoryTaskRunning,
                onAcceptCandidate = vm::acceptCandidate,
                onIgnoreCandidate = vm::ignoreCandidate,
                onIgnoreLowConfidenceCandidates = vm::ignoreLowConfidenceCandidates,
                onExport = {
                    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                    vm.exportMemories(baseDir)
                },
                onImport = {
                    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                    vm.importMemories(File(baseDir, "AmberAgentMemory"))
                },
                onAddMemory = { editingMemory = AssistantMemory(0, "") },
                onEditMemory = { editingMemory = it },
                onDeleteMemory = { pendingDeleteMemory = it },
                restoringMemoryIds = restoringMemoryIds,
                onRestoreMemory = vm::restoreMemory,
                onInfoClick = { title, text -> memoryInfoDialog = title to text },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = SettingPageHorizontalInset, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (subpage) {
                    MemorySettingsSubpage.Overview -> {
                        MemoryOverviewSubpage(
                            soulMarkdown = settings.agentRuntime.agentSoulMarkdown,
                            onSaveSoul = { value ->
                                vm.updateAgentRuntime { it.copy(agentSoulMarkdown = value) }
                            },
                            pendingCandidateCount = pendingCandidateCount,
                            coreCount = memoryCounts[MemoryRepository.GLOBAL_MEMORY_ID] ?: 0,
                            shortCount = memoryCounts[MemoryRepository.SHORT_TERM_MEMORY_ID] ?: 0,
                            longCount = memoryCounts[MemoryRepository.LONG_TERM_MEMORY_ID] ?: 0,
                            hasPendingDreamPlan = dreamPlan != null,
                            coreMemories = memories,
                            shortTermMemories = shortTermMemories,
                            longTermMemories = longTermMemories,
                            onOpen = { target -> navController.navigate(target.toScreen()) },
                        )
                    }

                    MemorySettingsSubpage.Recall -> MemoryRecallSubpage(
                        settings = settings,
                        onUpdate = vm::updateAgentRuntime,
                    )

                    MemorySettingsSubpage.Worker -> MemoryWorkerSubpage(
                        settings = settings,
                        pendingCandidateCount = pendingCandidateCount,
                        eventCount = recentMemoryEvents.size,
                        dreamPlan = dreamPlan,
                        running = memoryTaskRunning,
                        onUpdate = vm::updateAgentRuntime,
                        onPlan = vm::planDream,
                        onApply = vm::applyDreamPlan,
                        onDismiss = vm::dismissDreamPlan,
                    )

                    MemorySettingsSubpage.Compaction -> MemoryCompactionSubpage(
                        settings = settings,
                        onUpdate = vm::updateAgentRuntime,
                    )

                    MemorySettingsSubpage.Library -> Unit
                }
            }
        }
    }

    pendingDeleteMemory?.let { memory ->
        MemoryDeleteDialog(
            memory = memory,
            mutation = memoryMutation,
            onDelete = { vm.deleteMemory(memory) },
            onDismiss = {
                pendingDeleteMemory = null
                vm.consumeMemoryMutation()
            },
        )
    }

    (memoryMutation as? MemoryMutationState.Conflict)?.let { conflict ->
        MemoryConflictDialog(
            conflict = conflict,
            onDismiss = {
                if (conflict.operation == MemoryMutationOperation.DELETE) {
                    pendingDeleteMemory = null
                }
                vm.consumeMemoryMutation()
            },
            onUseLatest = { latest ->
                if (conflict.operation == MemoryMutationOperation.UPDATE) {
                    editingMemory = latest
                } else {
                    pendingDeleteMemory = latest
                }
                vm.consumeMemoryMutation()
            },
            onKeepDraft = { latest ->
                editingMemory = conflict.draft.copy(revision = latest.revision)
                vm.consumeMemoryMutation()
            },
        )
    }

    memoryInfoDialog?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { memoryInfoDialog = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { memoryInfoDialog = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}

enum class MemorySettingsSubpage {
    Overview,
    Recall,
    Worker,
    Compaction,
    Library,
}

@Composable
fun SettingAgentMemoryRecallPage() {
    SettingAgentMemoryPage(subpage = MemorySettingsSubpage.Recall)
}

@Composable
fun SettingAgentMemoryWorkerPage() {
    SettingAgentMemoryPage(subpage = MemorySettingsSubpage.Worker)
}

@Composable
fun SettingAgentMemoryCompactionPage() {
    SettingAgentMemoryPage(subpage = MemorySettingsSubpage.Compaction)
}

@Composable
fun SettingAgentMemoryLibraryPage() {
    SettingAgentMemoryPage(subpage = MemorySettingsSubpage.Library)
}

private fun MemorySettingsSubpage.toScreen(): Screen = when (this) {
    MemorySettingsSubpage.Overview -> Screen.SettingAgentMemory
    MemorySettingsSubpage.Recall -> Screen.SettingAgentMemoryRecall
    MemorySettingsSubpage.Worker -> Screen.SettingAgentMemoryWorker
    MemorySettingsSubpage.Compaction -> Screen.SettingAgentMemoryCompaction
    MemorySettingsSubpage.Library -> Screen.SettingAgentMemoryLibrary
}
