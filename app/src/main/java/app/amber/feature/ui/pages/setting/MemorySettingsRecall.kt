package app.amber.feature.ui.pages.setting

import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.amber.agent.R
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings

@Composable
internal fun MemoryRecallSubpage(
    settings: Settings,
    onUpdate: ((AgentRuntimeSetting) -> AgentRuntimeSetting) -> Unit,
) {
    SettingCardGroup(title = stringResource(R.string.chat_message_tool_kind_memory)) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_core_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_core_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableCoreMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableCoreMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_short_term_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_short_term_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableShortTermMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableShortTermMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_long_term_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_long_term_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableLongTermMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableLongTermMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_recent_chats_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_recent_chats_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableRecentChatsReference,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableRecentChatsReference = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_time_reminder_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_time_reminder_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableTimeReminder,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableTimeReminder = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.memory_selective_recall_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(
                        R.string.memory_selective_recall_desc,
                        settings.agentRuntime.memoryRecall.maxItems,
                        settings.agentRuntime.memoryRecall.maxPromptChars,
                    )
                )
            },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.memoryRecall.debug,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryRecall = it.memoryRecall.copy(debug = enabled)) }
                    },
                )
            },
        )
    }
}
