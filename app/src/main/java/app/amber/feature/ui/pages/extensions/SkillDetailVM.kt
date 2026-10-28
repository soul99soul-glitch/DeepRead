package app.amber.feature.ui.pages.extensions

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.agent.R
import app.amber.core.ai.mcp.McpImportApplyResult
import app.amber.core.ai.mcp.McpImportPreparation
import app.amber.core.ai.mcp.McpImportPreview
import app.amber.core.ai.mcp.McpImportTransaction
import app.amber.core.ai.mcp.publishMcpImport
import app.amber.core.ai.mcp.RealMcpConnectPreflight
import app.amber.core.ai.mcp.parseMcpServersFromJson
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.files.SkillFrontmatterParser
import app.amber.core.files.SkillManager
import app.amber.feature.runtime.CapabilityBackedCasLedger
import app.amber.feature.runtime.CapabilityPermissionStore
import java.io.File
import java.util.UUID

data class SkillFile(
    val file: File,
    val relativePath: String,
    val sizeBytes: Long,
)

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

data class SkillMcpConfigState(
    val serverCount: Int,
    val error: String? = null,
)

internal data class SkillFileTreeRow(
    val node: SkillFileNode,
    val depth: Int,
) {
    val key: String
        get() = when (node) {
            is SkillFileNode.FileNode -> "file:${node.skillFile.relativePath}"
            is SkillFileNode.DirNode -> "dir:${node.relativePath}"
        }
}

internal fun flattenSkillFileTree(
    nodes: List<SkillFileNode>,
    expandedPaths: Set<String>,
): List<SkillFileTreeRow> = buildList {
    fun appendNodes(current: List<SkillFileNode>, depth: Int) {
        current.forEach { node ->
            add(SkillFileTreeRow(node, depth))
            if (node is SkillFileNode.DirNode && node.relativePath in expandedPaths) {
                appendNodes(node.children, depth + 1)
            }
        }
    }
    appendNodes(nodes, depth = 0)
}

class SkillDetailVM(
    private val context: Application,
    private val skillManager: SkillManager,
    private val settingsStore: SettingsAggregator,
    private val capabilityPermissionStore: CapabilityPermissionStore,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private val _fileCount = MutableStateFlow(0)
    val fileCount = _fileCount.asStateFlow()

    private val _mcpConfig = MutableStateFlow<SkillMcpConfigState?>(null)
    val mcpConfig = _mcpConfig.asStateFlow()

    private val _mcpImportPreview = MutableStateFlow<McpImportPreview?>(null)
    val mcpImportPreview = _mcpImportPreview.asStateFlow()

    private data class PendingMcpImport(
        val file: File,
        val digest: String,
    )

    private var pendingMcpImport: PendingMcpImport? = null

    private var skillName = ""

    fun init(name: String) {
        if (skillName == name) return
        clearMcpImportPreview()
        skillName = name
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = skillManager.getSkillDir(skillName) ?: return@launch
            val tree = buildTree(dir, dir)
            _tree.value = tree
            _fileCount.value = tree.countFiles()
            _mcpConfig.value = readMcpConfigState(dir)
        }
    }

    private fun readMcpConfigState(dir: File): SkillMcpConfigState? {
        val mcpFile = dir.resolve(MCP_CONFIG_FILE)
        if (!mcpFile.exists()) return null
        return runCatching {
            val configs = parseMcpServersFromJson(mcpFile.readText())
            if (configs.isEmpty()) {
                SkillMcpConfigState(
                    serverCount = 0,
                    error = context.getString(R.string.skill_detail_page_mcp_config_empty),
                )
            } else {
                SkillMcpConfigState(serverCount = configs.size)
            }
        }.getOrElse { error ->
            SkillMcpConfigState(
                serverCount = 0,
                error = context.getString(
                    R.string.skill_detail_page_mcp_config_parse_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun buildTree(root: File, dir: File): List<SkillFileNode> {
        val items = dir.listFiles()?.toList() ?: return emptyList()
        val files = items
            .filter { it.isFile }
            .sortedWith(compareBy({ it.name != "SKILL.md" }, { it.name }))
            .map { f -> SkillFileNode.FileNode(SkillFile(f, f.relativeTo(root).path, f.length())) }
        val dirs = items
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { d -> SkillFileNode.DirNode(d.name, d.relativeTo(root).path, buildTree(root, d)) }
        return dirs + files
    }

    private fun List<SkillFileNode>.countFiles(): Int = sumOf { node ->
        when (node) {
            is SkillFileNode.FileNode -> 1
            is SkillFileNode.DirNode -> node.children.countFiles()
        }
    }

    suspend fun readFile(skillFile: SkillFile): String = withContext(Dispatchers.IO) {
        skillFile.file.readText()
    }

    // Returns null on success, error message on failure
    fun saveFile(relativePath: String, content: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (relativePath == "SKILL.md") {
                val name = SkillFrontmatterParser.parse(content)["name"]
                if (name != skillName) {
                    withContext(Dispatchers.Main) {
                        onResult(
                            context.getString(
                                R.string.skill_detail_page_name_immutable,
                                skillName,
                            )
                        )
                    }
                    return@launch
                }
            }
            val success = skillManager.saveSkillFile(skillName, relativePath, content)
            loadFiles()
            withContext(Dispatchers.Main) {
                onResult(if (success) null else context.getString(R.string.skill_detail_page_save_failed))
            }
        }
    }

    fun deleteFile(skillFile: SkillFile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = skillManager.deleteSkillFile(skillName, skillFile.relativePath)
            if (success) loadFiles()
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    fun previewMcpConfig(onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            clearMcpImportPreview()
            val skillDir = skillManager.getSkillDir(skillName)
            val mcpFile = skillDir?.resolve(MCP_CONFIG_FILE)
            if (mcpFile == null || !mcpFile.exists()) {
                withContext(Dispatchers.Main) {
                    onResult(context.getString(R.string.skill_detail_page_mcp_config_missing))
                }
                return@launch
            }

            val rawJson = try {
                mcpFile.readText()
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(
                        context.getString(
                            R.string.skill_detail_page_mcp_config_read_failed,
                            error.message ?: error.javaClass.simpleName,
                        )
                    )
                }
                return@launch
            }
            val transaction = createMcpImportTransaction()
            val preparation = transaction.prepare(rawJson)
            val ready = when (preparation) {
                is McpImportPreparation.Ready -> preparation
                is McpImportPreparation.Rejected -> {
                    withContext(Dispatchers.Main) {
                        onResult(preparation.errors.joinToString("\n"))
                    }
                    return@launch
                }
            }
            pendingMcpImport = PendingMcpImport(mcpFile, ready.preview.digest)
            _mcpImportPreview.value = ready.preview
            withContext(Dispatchers.Main) {
                onResult(null)
            }
        }
    }

    fun confirmMcpConfig(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val pending = pendingMcpImport
            if (pending == null) {
                withContext(Dispatchers.Main) {
                    onResult(context.getString(R.string.skill_detail_page_mcp_import_preview_required))
                }
                return@launch
            }

            val rawJson = try {
                pending.file.readText()
            } catch (error: Exception) {
                clearMcpImportPreview()
                withContext(Dispatchers.Main) {
                    onResult(
                        context.getString(
                            R.string.skill_detail_page_mcp_config_read_failed,
                            error.message ?: error.javaClass.simpleName,
                        )
                    )
                }
                return@launch
            }
            val transaction = createMcpImportTransaction()
            val preparation = transaction.prepare(rawJson)
            val ready = when (preparation) {
                is McpImportPreparation.Ready -> preparation
                is McpImportPreparation.Rejected -> {
                    clearMcpImportPreview()
                    withContext(Dispatchers.Main) {
                        onResult(preparation.errors.joinToString("\n"))
                    }
                    return@launch
                }
            }
            if (ready.preview.digest != pending.digest) {
                clearMcpImportPreview()
                withContext(Dispatchers.Main) {
                    onResult(context.getString(R.string.skill_detail_page_mcp_import_changed))
                }
                return@launch
            }

            // This approval is reached only from the user's confirm action;
            // apply then re-prepares the same freshly-read candidate.
            val sessionId = UUID.randomUUID().toString()
            transaction.approve(ready.preview, sessionId, source = "skill_ui")
            val result = transaction.apply(rawJson, sessionId)
            clearMcpImportPreview()
            withContext(Dispatchers.Main) {
                onResult(
                    when (result) {
                        is McpImportApplyResult.Applied -> context.getString(
                            R.string.skill_detail_page_mcp_imported,
                            result.serverCount,
                        )
                        is McpImportApplyResult.Stale -> result.reason
                        is McpImportApplyResult.Rejected -> result.errors.joinToString("\n")
                    }
                )
            }
        }
    }

    fun clearMcpImportPreview() {
        pendingMcpImport = null
        _mcpImportPreview.value = null
    }

    private fun createMcpImportTransaction() = McpImportTransaction(
        preflight = RealMcpConnectPreflight(),
        approvalLedger = CapabilityBackedCasLedger(capabilityPermissionStore),
        existingServerNames = {
            settingsStore.settingsFlow.value.mcpServers
                .map { it.commonOptions.name }
                .toSet()
        },
        publish = settingsStore::publishMcpImport,
    )

    private companion object {
        const val MCP_CONFIG_FILE = "mcp.json"
    }
}
