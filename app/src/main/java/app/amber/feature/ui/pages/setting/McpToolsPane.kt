package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.SwitchSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.ai.core.InputSchema
import app.amber.agent.R
import app.amber.core.ai.mcp.McpManager
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.ai.mcp.McpTool
import app.amber.feature.ui.components.ui.Tag
import app.amber.feature.ui.components.ui.TagType
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.compose.koinInject

@Composable
internal fun McpToolsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mcpManager.getClient(config) == null) {
            item {
                Text(stringResource(R.string.setting_mcp_page_tools_unavailable_message))
            }
        }
        items(config.commonOptions.tools) { tool ->
            McpToolCard(
                tool = tool,
                onEnableChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions = config.commonOptions.copy(
                                tools = config.commonOptions.tools.map {
                                    if (tool.name == it.name) {
                                        it.copy(enable = newVal)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                },
                onNeedsApprovalChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions = config.commonOptions.copy(
                                tools = config.commonOptions.tools.map {
                                    if (tool.name == it.name) {
                                        it.copy(needsApproval = newVal)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun McpToolCard(
    tool: McpTool,
    onEnableChange: (Boolean) -> Unit,
    onNeedsApprovalChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val needsApprovalLabel = stringResource(R.string.setting_mcp_page_needs_approval)
    val enableLabel = stringResource(R.string.setting_mcp_page_enable)
    ProviderCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.name,
                    style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                    color = LocalAmberTokens.current.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.code_block_collapse else R.string.code_block_expand
                        ),
                        tint = LocalAmberTokens.current.ink3,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                McpToolToggle(
                    label = needsApprovalLabel,
                    checked = tool.needsApproval,
                    onCheckedChange = onNeedsApprovalChange,
                    modifier = Modifier.weight(1f),
                )
                McpToolToggle(
                    label = enableLabel,
                    checked = tool.enable,
                    onCheckedChange = onEnableChange,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                val toolDescription = tool.description
                if (!toolDescription.isNullOrBlank()) {
                    Text(
                        text = toolDescription,
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                tool.inputSchema?.let { it as? InputSchema.Obj }?.let { schema ->
                    if (schema.properties.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            schema.properties.forEach { (key, _) ->
                                Tag(
                                    type = if (schema.required?.contains(key) == true) TagType.INFO else TagType.DEFAULT
                                ) {
                                    Text(text = key, style = LocalAmberType.current.meta)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = LocalAmberType.current.meta,
            color = tokens.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            modifier = Modifier.semantics { contentDescription = label },
            onCheckedChange = onCheckedChange,
            size = SwitchSize.Small,
        )
    }
}
