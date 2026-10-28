package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Square
import app.amber.agent.R
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.TerminalJobSnapshot
import app.amber.feature.terminal.TerminalJobStatus
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun SshCommandPanel(
    profiles: List<SshProfile>,
    selectedProfile: SshProfile?,
    command: String,
    snapshot: TerminalJobSnapshot?,
    actionBusy: Boolean,
    onProfileSelected: (SshProfile) -> Unit,
    onCommandChanged: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    SandboxDisclosure(
        title = stringResource(R.string.setting_sandbox_ssh_command_title),
        summary = selectedProfile?.name,
        forceExpanded = snapshot?.running == true || actionBusy,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (profiles.isNotEmpty() && selectedProfile != null) {
                SandboxSelect(
                    options = profiles,
                    selectedOption = selectedProfile,
                    onOptionSelected = onProfileSelected,
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { it.name },
                    leading = { Icon(Lucide.Server, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }
            OutlinedTextField(
                value = command,
                onValueChange = onCommandChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_sandbox_ssh_command_label), style = LocalAmberType.current.secondary) },
                placeholder = { Text(stringResource(R.string.setting_sandbox_ssh_command_hint), style = LocalAmberType.current.secondary) },
                textStyle = LocalAmberType.current.body.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                enabled = selectedProfile != null && !actionBusy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SandboxAction(
                    label = stringResource(R.string.setting_sandbox_ssh_run),
                    onClick = onRun,
                    enabled = selectedProfile != null && command.isNotBlank() &&
                        !actionBusy && snapshot?.running != true,
                    primary = true,
                    icon = Lucide.Play,
                )
                SandboxAction(
                    label = stringResource(R.string.setting_sandbox_ssh_stop),
                    onClick = onStop,
                    enabled = snapshot?.running == true && !actionBusy,
                    icon = Lucide.Square,
                )
            }
            snapshot?.let { SshJobOutput(it) }
        }
    }
}

@Composable
private fun SshJobOutput(snapshot: TerminalJobSnapshot) {
    val colors = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.row,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.setting_sandbox_ssh_job_status, jobStatusLabel(snapshot.status)),
                style = LocalAmberType.current.secondary,
                color = colors.ink,
            )
            snapshot.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = colors.red, style = LocalAmberType.current.secondary)
            }
            Text(
                snapshot.outputTail.ifBlank { stringResource(R.string.setting_sandbox_ssh_job_no_output) },
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                style = LocalAmberType.current.meta,
                color = colors.ink,
            )
        }
    }
}

@Composable
private fun jobStatusLabel(status: TerminalJobStatus): String = when (status) {
    TerminalJobStatus.QUEUED -> stringResource(R.string.setting_sandbox_ssh_status_queued)
    TerminalJobStatus.RUNNING -> stringResource(R.string.setting_sandbox_ssh_status_running)
    TerminalJobStatus.COMPLETED -> stringResource(R.string.setting_sandbox_ssh_status_completed)
    TerminalJobStatus.FAILED -> stringResource(R.string.setting_sandbox_ssh_status_failed)
    TerminalJobStatus.CANCELLED -> stringResource(R.string.setting_sandbox_ssh_status_cancelled)
    TerminalJobStatus.TIMED_OUT -> stringResource(R.string.setting_sandbox_ssh_status_timed_out)
    TerminalJobStatus.INTERRUPTED -> stringResource(R.string.setting_sandbox_ssh_status_interrupted)
}
