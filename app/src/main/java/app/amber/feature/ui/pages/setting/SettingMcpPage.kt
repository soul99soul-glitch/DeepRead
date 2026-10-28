package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Plus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.runtime.CapabilityBackedCasLedger
import app.amber.core.ai.mcp.McpImportTransaction
import app.amber.core.ai.mcp.McpManager
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.ai.mcp.McpStatus
import app.amber.core.ai.mcp.RealMcpConnectPreflight
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.hooks.useEditState
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val workspace = workspaceColors()
    val capabilityPermissionStore = koinInject<CapabilityPermissionStore>()
    val transaction = remember {
        McpImportTransaction(
            preflight = RealMcpConnectPreflight(),
            approvalLedger = CapabilityBackedCasLedger(capabilityPermissionStore),
            existingServerNames = {
                vm.settings.value.mcpServers.map { it.commonOptions.name }.toSet()
            },
            publish = vm::importMcpServers,
        )
    }
    val creationState = useEditState<McpServerConfig> {
        vm.updateSettings { current -> current.copy(mcpServers = current.mcpServers + it) }
    }
    val editState = useEditState<McpServerConfig> { newConfig ->
        vm.updateSettings { current ->
            current.copy(
                mcpServers = current.mcpServers.map {
                    if (it.id == newConfig.id) {
                        newConfig
                    } else {
                        it
                    }
                }
            )
        }
    }
    var showImportDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_mcp_page_title),
                navigationIcon = { BackButton() },
                actions = {
                    ProviderGhostButton(
                        text = stringResource(R.string.setting_mcp_page_import_confirm),
                        imageVector = Lucide.Download,
                        modifier = Modifier.padding(end = 6.dp),
                        onClick = { showImportDialog = true },
                    )
                    ProviderGhostButton(
                        text = stringResource(R.string.add),
                        imageVector = Lucide.Plus,
                        onClick = { creationState.open(McpServerConfig.StreamableHTTPServer()) },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp)
            ) {
                item("mcp_servers_section") {
                    SettingSectionTitle(stringResource(R.string.setting_mcp_page_title))
                }
                items(mcpConfigs, key = { it.id }) { mcpConfig ->
                    McpServerItem(
                        item = mcpConfig,
                        status = status[mcpConfig.id] ?: McpStatus.Idle,
                        onEdit = {
                            editState.open(mcpConfig)
                        },
                        onDelete = {
                            vm.updateSettings { current ->
                                current.copy(mcpServers = current.mcpServers.filter { it.id != mcpConfig.id })
                            }
                        },
                        onStartAuthorization = {
                            mcpManager.startAuthorization(mcpConfig, context)
                        },
                        onClearAuthorization = {
                            scope.launch {
                                mcpManager.clearAuthorization(mcpConfig)
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (mcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = workspace.ink,
                    )
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                        color = workspace.muted,
                    )
                }
            }
        }
    }
    McpServerConfigModal(creationState)
    McpServerConfigModal(editState)
    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            transaction = transaction,
        )
    }
}
