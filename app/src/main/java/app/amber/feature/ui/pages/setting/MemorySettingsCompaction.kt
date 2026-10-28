package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.SwitchSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun MemoryCompactionSubpage(
    settings: Settings,
    onUpdate: ((AgentRuntimeSetting) -> AgentRuntimeSetting) -> Unit,
) {
    val compaction = settings.agentRuntime.contextCompaction
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_compaction_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MemoryRowSubtitle(
                        stringResource(R.string.setting_agent_memory_context_compaction_desc)
                    )
                    MemoryRowSubtitle(
                        stringResource(R.string.setting_agent_memory_context_compaction_defaults)
                    )
                }
            },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.contextCompaction.enabled,
                    size = SwitchSize.Small,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                contextCompaction = it.contextCompaction.copy(
                                    enabled = enabled,
                                    notifyOnly = if (enabled) false else it.contextCompaction.notifyOnly,
                                )
                            )
                        }
                    },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_compaction_notify_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(R.string.setting_agent_memory_context_compaction_notify_desc)
                )
            },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.contextCompaction.notifyOnly,
                    size = SwitchSize.Small,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                contextCompaction = it.contextCompaction.copy(
                                    notifyOnly = enabled,
                                    enabled = if (enabled) false else it.contextCompaction.enabled,
                                )
                            )
                        }
                    },
                )
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    SettingCardGroup(title = stringResource(R.string.setting_agent_memory_context_threshold_section)) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_force_threshold_title)) },
            trailingContent = {
                Text(
                    text = "${(compaction.forceRatio * 100f).toInt()}%",
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_precompact_threshold_title)) },
            trailingContent = {
                Text(
                    text = "${(compaction.precompactRatio * 100f).toInt()}%",
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    SettingCardGroup(title = stringResource(R.string.setting_agent_memory_context_protect_section)) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_protect_turns_title)) },
            trailingContent = {
                Text(
                    text = stringResource(
                        R.string.setting_agent_memory_context_protect_turns_value,
                        compaction.keepRecentTurns,
                    ),
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_protect_messages_title)) },
            trailingContent = {
                Text(
                    text = stringResource(
                        R.string.setting_agent_memory_context_protect_messages_value,
                        compaction.keepRecentTurns * 2,
                    ),
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            },
        )
    }
}
