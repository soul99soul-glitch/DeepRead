package app.amber.core.ai.mcp

import app.amber.core.settings.prefs.SettingsAggregator

/** The batch was valid at preview time, but a name was published before its final write. */
internal class McpImportNameConflict(val serverNames: List<String>) : IllegalStateException()

internal suspend fun SettingsAggregator.publishMcpImport(configs: List<McpServerConfig>) {
    update { current ->
        val existingNames = current.mcpServers.map { it.commonOptions.name }.toSet()
        val conflicts = configs.map { it.commonOptions.name }.filter { it in existingNames }
        if (conflicts.isNotEmpty()) throw McpImportNameConflict(conflicts)
        current.copy(mcpServers = current.mcpServers + configs)
    }
}
