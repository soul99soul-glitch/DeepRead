package app.amber.feature.ui.pages.setting

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
import app.amber.core.memory.dream.PersistedMemoryDreamPlan
import app.amber.core.memory.model.MemoryWorkerDreamGate
import app.amber.feature.ui.components.ui.CardGroup

@Composable
internal fun MemoryWorkerSubpage(
    settings: Settings,
    pendingCandidateCount: Int,
    eventCount: Int,
    dreamPlan: PersistedMemoryDreamPlan?,
    running: Boolean,
    onUpdate: ((AgentRuntimeSetting) -> AgentRuntimeSetting) -> Unit,
    onPlan: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val worker = settings.agentRuntime.memoryWorker
    val canRunDream = worker.enabled && MemoryWorkerDreamGate.isAnyDreamEnabled(worker)
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.memory_local_maintenance_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(
                        R.string.memory_local_maintenance_desc,
                        pendingCandidateCount,
                        eventCount,
                    ),
                    mono = false,
                )
            },
            trailingContent = {
                Switch(
                    checked = worker.dreamMaintenanceEnabled,
                    size = SwitchSize.Small,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                memoryWorker = it.memoryWorker.copy(
                                    dreamMaintenanceEnabled = enabled,
                                    dreamEnabled = false,
                                )
                            )
                        }
                    },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.memory_auto_apply_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(R.string.memory_auto_apply_desc),
                    mono = false,
                )
            },
            trailingContent = {
                Switch(
                    checked = worker.autoApplyMaintenance && worker.dreamMaintenanceEnabled,
                    size = SwitchSize.Small,
                    enabled = worker.dreamMaintenanceEnabled,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                memoryWorker = it.memoryWorker.copy(autoApplyMaintenance = enabled)
                            )
                        }
                    },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.memory_llm_maintenance_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(R.string.memory_llm_maintenance_desc),
                    mono = false,
                )
            },
            trailingContent = {
                Switch(
                    checked = worker.dreamModelEnabled,
                    size = SwitchSize.Small,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                memoryWorker = it.memoryWorker.copy(
                                    dreamModelEnabled = enabled,
                                    dreamEnabled = false,
                                )
                            )
                        }
                    },
                )
            },
        )
        if (!canRunDream) {
            item(
                headlineContent = { Text(stringResource(R.string.memory_run_unavailable_title)) },
                supportingContent = {
                    MemoryRowSubtitle(
                        stringResource(R.string.memory_run_unavailable_desc),
                        mono = false,
                    )
                },
            )
        }
        // The following toggles were removed in favor of defaults:
        //   - 记忆后台任务 (worker.enabled)            → field kept ON
        //   - 对话结束后提取 (worker.extractionEnabled) → field kept ON
        //   - 跟随压缩模型 (worker.followCompressModel)  → moved to model settings page
        //   - 只在充电时运行 (worker.runOnlyOnCharging)  → kept ON; wired to the nightly
        //     WorkManager constraint, where it replaces the idle gate when enabled
        //   - 仅设备空闲时 (worker.runOnlyOnIdle)         → inert while charging-only is on
        //     (no UI for that pref), so the row was removed; the pref is still honored
        // Manual "立即运行一次" → moved to toolbar play icon.
    }

    Spacer(Modifier.height(12.dp))
    DreamReviewSection(
        plan = dreamPlan,
        running = running,
        canRun = canRunDream,
        onPlan = onPlan,
        onApply = onApply,
        onDismiss = onDismiss,
    )
}
