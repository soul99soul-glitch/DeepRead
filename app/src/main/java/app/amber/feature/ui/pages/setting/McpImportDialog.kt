package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.ai.mcp.McpImportApplyResult
import app.amber.core.ai.mcp.McpImportPreparation
import app.amber.core.ai.mcp.McpImportPreview
import app.amber.core.ai.mcp.McpImportTransaction
import app.amber.core.ai.mcp.McpImportTransport
import java.util.UUID

@Composable
internal fun McpImportModal(
    onDismiss: () -> Unit,
    transaction: McpImportTransaction,
) {
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<McpImportPreview?>(null) }
    var sessionId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.setting_mcp_page_import_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.setting_mcp_page_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    errorMessage = null
                    preview = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("{ \"mcpServers\": { ... } }") },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } }
            )
            preview?.let { McpImportPreviewCard(it) }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                OutlinedButton(onClick = {
                    when (val preparation = transaction.prepare(jsonText.trim())) {
                        is McpImportPreparation.Ready -> {
                            preview = preparation.preview
                            errorMessage = null
                        }
                        is McpImportPreparation.Rejected -> {
                            preview = null
                            errorMessage = preparation.errors.joinToString("\n")
                        }
                    }
                }) {
                    Text(stringResource(R.string.setting_mcp_page_import_preview))
                }
                Button(
                    enabled = preview != null && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val currentPreview = preview
                                if (currentPreview != null) {
                                    transaction.approve(currentPreview, sessionId)
                                    when (val result = transaction.apply(jsonText.trim(), sessionId)) {
                                        is McpImportApplyResult.Applied -> onDismiss()
                                        is McpImportApplyResult.Stale -> {
                                            preview = null
                                            errorMessage = result.reason
                                            sessionId = UUID.randomUUID().toString()
                                        }
                                        is McpImportApplyResult.Rejected -> {
                                            errorMessage = result.errors.joinToString("\n")
                                        }
                                    }
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (busy) {
                                R.string.setting_mcp_page_importing
                            } else {
                                R.string.setting_mcp_page_import_approve
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun McpImportPreviewCard(preview: McpImportPreview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(
                        R.string.setting_mcp_page_import_preview_summary,
                        preview.serverCount,
                        preview.headerNameCount,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        if (preview.risk == "high") {
                            R.string.setting_mcp_page_import_risk_high
                        } else {
                            R.string.setting_mcp_page_import_risk_normal
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (preview.risk == "high") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            preview.servers.forEachIndexed { index, server ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        server.serverName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "transport: ${if (server.transport == McpImportTransport.SSE) "sse" else "streamable_http"} · origin: ${server.origin}" +
                            if (server.risk == "high") {
                                stringResource(R.string.setting_mcp_page_import_risk_high_suffix)
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (server.headerNames.isNotEmpty()) {
                        Text(
                            "headers: ${server.headerNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    server.note?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (index < preview.servers.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}
