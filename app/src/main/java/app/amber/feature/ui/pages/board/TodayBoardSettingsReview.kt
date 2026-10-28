package app.amber.feature.ui.pages.board

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.amber.agent.R
import app.amber.agent.StandaloneSurfaces
import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import app.amber.feature.board.BoardSignalSourceType
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.ui.components.ui.NotionSlider
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Megaphone
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.MessagesSquare
import kotlin.math.roundToInt

internal fun LazyListScope.todayBoardReviewSettings(
    board: TodayBoardSetting,
    focusRules: List<BoardFocusRuleEntity>,
    sourceWeights: Map<String, Int>,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
    onAddRule: (String) -> Unit,
    onToggleRule: (BoardFocusRuleEntity, Boolean) -> Unit,
    onDeleteRule: (BoardFocusRuleEntity) -> Unit,
    onChangeWeight: (String, Int) -> Unit,
) {
    item {
        val context = LocalContext.current
        BoardSectionCard(title = stringResource(R.string.board_signal_sources)) {
            val notifPermissionOk = remember {
                runCatching {
                    android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        "enabled_notification_listeners"
                    )?.contains(context.packageName) == true
                }.getOrDefault(false)
            }
            val calendarPermissionOk = remember {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
            }
            SourceSwitch(
                stringResource(R.string.board_signal_notification),
                if (notifPermissionOk) stringResource(R.string.board_signal_notification_description)
                else stringResource(R.string.board_signal_notification_permission),
                BoardSignalSourceType.NOTIFICATION in board.enabledSources,
                icon = Lucide.Megaphone,
            ) { toggleSignalSource(BoardSignalSourceType.NOTIFICATION, update) }
            BoardDivider()
            SourceSwitch(
                stringResource(R.string.board_signal_calendar),
                if (calendarPermissionOk) stringResource(R.string.board_signal_calendar_description)
                else stringResource(R.string.board_signal_calendar_permission),
                BoardSignalSourceType.CALENDAR in board.enabledSources,
                icon = Lucide.AlarmClock,
            ) { toggleSignalSource(BoardSignalSourceType.CALENDAR, update) }
            // Feishu credentials and chat history only exist in the
            // full agent app; in standalone surfaces these toggles
            // could never produce data — don't offer them.
            if (StandaloneSurfaces.allowsAgentFeatures) {
                BoardDivider()
                SourceSwitch(stringResource(R.string.board_signal_feishu_messages), stringResource(R.string.board_signal_feishu_messages_description), BoardSignalSourceType.FEISHU_MSG in board.enabledSources, icon = Lucide.MessagesSquare) {
                    toggleSignalSource(BoardSignalSourceType.FEISHU_MSG, update)
                }
                BoardDivider()
                SourceSwitch(stringResource(R.string.board_signal_feishu_docs), stringResource(R.string.board_signal_feishu_docs_description), BoardSignalSourceType.FEISHU_DOC in board.enabledSources, icon = Lucide.FileText) {
                    toggleSignalSource(BoardSignalSourceType.FEISHU_DOC, update)
                }
                BoardDivider()
                SourceSwitch(stringResource(R.string.board_signal_chat_history), stringResource(R.string.board_signal_chat_history_description), BoardSignalSourceType.CHAT_HISTORY in board.enabledSources, icon = Lucide.MessageSquare) {
                    toggleSignalSource(BoardSignalSourceType.CHAT_HISTORY, update)
                }
            }
        }
    }
    item {
        BoardSectionCard(title = stringResource(R.string.board_generation_rules)) {
            IncrementalSlider(board.incrementalSignalThreshold) { value ->
                update { it.copy(incrementalSignalThreshold = value) }
            }
            BoardDivider()
            FocusRulesEditor(
                rules = focusRules,
                onAdd = onAddRule,
                onToggle = onToggleRule,
                onDelete = onDeleteRule,
            )
        }
    }
    item {
        BoardSectionCard(title = stringResource(R.string.board_source_weights)) {
            SourceWeightsEditor(weights = sourceWeights, onChange = onChangeWeight)
        }
    }
}

@Composable
private fun FocusRulesEditor(
    rules: List<BoardFocusRuleEntity>,
    onAdd: (String) -> Unit,
    onToggle: (BoardFocusRuleEntity, Boolean) -> Unit,
    onDelete: (BoardFocusRuleEntity) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.board_focus_points), style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.board_focus_example)) },
                maxLines = 2,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalAmberTokens.current.accent,
                    unfocusedBorderColor = LocalAmberTokens.current.line,
                    focusedContainerColor = LocalAmberTokens.current.raised,
                    unfocusedContainerColor = LocalAmberTokens.current.surface2,
                    cursorColor = LocalAmberTokens.current.accent,
                ),
            )
            TextButton(
                enabled = input.trim().isNotEmpty(),
                onClick = {
                    onAdd(input)
                    input = ""
                },
            ) {
                Text(stringResource(R.string.add))
            }
        }
        if (rules.isEmpty()) {
            Text(stringResource(R.string.board_focus_empty_list), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        } else {
            rules.forEach { rule ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = rule.active, onCheckedChange = { onToggle(rule, it) })
                    Text(rule.content, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onDelete(rule) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

@Composable
private fun SourceWeightsEditor(weights: Map<String, Int>, onChange: (sourceType: String, weight: Int) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BOARD_WEIGHT_SOURCES.forEach { source ->
            // Feishu/chat-history sources can never produce data outside the
            // full agent app — hide their weights there too.
            if (!StandaloneSurfaces.allowsAgentFeatures &&
                source.sourceType in AGENT_ONLY_SIGNAL_SOURCES) return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(source.localizedTitle(), style = MaterialTheme.typography.bodyMedium)
                        Text(source.localizedDescription(), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
                    }
                    val value = weights[source.sourceType] ?: 0
                    Text(if (value > 0) "+$value" else value.toString(), color = workspaceColors().muted)
                }
                NotionSlider(
                    value = (weights[source.sourceType] ?: 0).toFloat(),
                    onValueChangeFinished = { onChange(source.sourceType, it.roundToInt().coerceIn(-10, 10)) },
                    valueRange = -10f..10f,
                    snapStep = 1f,
                )
            }
        }
    }
}

@Composable
private fun IncrementalSlider(value: Int, onValueChange: (Int) -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.board_signal_threshold), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.board_signal_threshold_value, value), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        }
        NotionSlider(value = value.toFloat(), onValueChangeFinished = { onValueChange(it.toInt()) }, valueRange = 1f..30f)
    }
}

private fun toggleSignalSource(
    source: String,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    update {
        it.copy(enabledSources = if (source in it.enabledSources) it.enabledSources - source else it.enabledSources + source)
    }
}

private data class BoardWeightSource(
    val sourceType: String,
    val title: String,
    val description: String,
)

@Composable
private fun BoardWeightSource.localizedTitle(): String = when (sourceType) {
    BoardSignalSourceType.NOTIFICATION -> stringResource(R.string.board_signal_notification)
    BoardSignalSourceType.CALENDAR -> stringResource(R.string.board_signal_calendar)
    BoardSignalSourceType.FEISHU_MSG -> stringResource(R.string.board_signal_feishu_messages)
    BoardSignalSourceType.FEISHU_DOC -> stringResource(R.string.board_signal_feishu_docs)
    BoardSignalSourceType.CHAT_HISTORY -> stringResource(R.string.board_signal_chat_history)
    else -> title
}

@Composable
private fun BoardWeightSource.localizedDescription(): String = when (sourceType) {
    BoardSignalSourceType.NOTIFICATION -> stringResource(R.string.board_signal_notification_weight_description)
    BoardSignalSourceType.CALENDAR -> stringResource(R.string.board_signal_calendar_weight_description)
    BoardSignalSourceType.FEISHU_MSG -> stringResource(R.string.board_signal_feishu_messages_weight_description)
    BoardSignalSourceType.FEISHU_DOC -> stringResource(R.string.board_signal_feishu_docs_weight_description)
    BoardSignalSourceType.CHAT_HISTORY -> stringResource(R.string.board_signal_chat_history_weight_description)
    else -> description
}

private val BOARD_WEIGHT_SOURCES = listOf(
    BoardWeightSource(BoardSignalSourceType.NOTIFICATION, "Notifications", "Device notifications, app alerts, and important pushes"),
    BoardWeightSource(BoardSignalSourceType.CALENDAR, "Calendar", "Today's schedule, meetings, and time blocks"),
    BoardWeightSource(BoardSignalSourceType.FEISHU_MSG, "Feishu messages", "Unread messages, group chats, and work communication"),
    BoardWeightSource(BoardSignalSourceType.FEISHU_DOC, "Feishu docs", "Document updates, indexes, and change signals"),
    BoardWeightSource(BoardSignalSourceType.CHAT_HISTORY, "Chat history", "Real todos and project context from recent conversations"),
)

private val AGENT_ONLY_SIGNAL_SOURCES = setOf(
    BoardSignalSourceType.FEISHU_MSG,
    BoardSignalSourceType.FEISHU_DOC,
    BoardSignalSourceType.CHAT_HISTORY,
)
