package app.amber.feature.ui.pages.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.agent.StandaloneSurfaces
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.feature.board.TodayBoardBackgroundStrategy
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.ui.components.ai.ProviderAccordionModelPicker
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.BotMessageSquare
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide

internal fun LazyListScope.todayBoardGenerationSettings(
    board: TodayBoardSetting,
    boardModel: Model?,
    providers: List<ProviderSetting>,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    item {
        BoardSectionCard(title = stringResource(R.string.board_settings_general)) {
            // Standalone deepread keeps the model picker under
            // 文章生成 → 生成模型 (iOS parity); duplicating it here
            // would give two entries to the same setting.
            if (StandaloneSurfaces.current != StandaloneSurfaces.DEEP_READ) {
                BoardModelRow(providers = providers, boardModel = boardModel, update = update)
                BoardDivider()
            }
            BackgroundStrategyRow(board.backgroundStrategy) { value ->
                update { it.copy(backgroundStrategy = value) }
            }
        }
    }
}

internal fun LazyListScope.todayBoardModelSettings(
    boardModel: Model?,
    providers: List<ProviderSetting>,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    item {
        BoardSectionCard(title = stringResource(R.string.deepread_settings_gen_model)) {
            BoardModelRow(
                providers = providers,
                boardModel = boardModel,
                update = update,
                initiallyExpanded = true,
                // The pane title and card header already say
                // 生成模型 — a third identical label inside the
                // card reads as a bug, not structure.
                showHeading = false,
            )
        }
    }
}

@Composable
private fun TodayBoardBackgroundStrategy.label(): String =
    when (this) {
        TodayBoardBackgroundStrategy.SMART -> stringResource(R.string.board_background_smart)
        TodayBoardBackgroundStrategy.WIFI_ONLY -> stringResource(R.string.board_background_wifi)
        TodayBoardBackgroundStrategy.FOREGROUND_ONLY -> stringResource(R.string.board_background_foreground)
    }

@Composable
private fun BoardModelRow(
    providers: List<ProviderSetting>,
    boardModel: Model?,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
    initiallyExpanded: Boolean = false,
    showHeading: Boolean = true,
) {
    val selectedProvider = boardModel?.findProviderForBoard(providers)
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showHeading) {
            Text(
                stringResource(
                    // "看板" is full-app vocabulary; standalone products name the role.
                    if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_settings_gen_model
                    else R.string.board_model_title,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(
                icon = Lucide.BotMessageSquare,
                size = 32.dp,
                iconSize = 17.dp,
                tone = if (boardModel == null) app.amber.feature.ui.components.ui.WorkspaceTone.Neutral
                else app.amber.feature.ui.components.ui.WorkspaceTone.Accent,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = boardModel?.displayName ?: stringResource(
                        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_board_use_default_model
                        else R.string.board_follow_main_model,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspaceColors().ink,
                )
                Text(
                    text = selectedProvider?.name ?: stringResource(
                        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_board_follow_default_model
                        else R.string.board_using_current_model,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                )
            }
            Text(
                text = if (expanded) "−" else "+",
                style = LocalAmberType.current.meta.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = workspaceColors().muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(42.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ProviderAccordionModelPicker(
                    currentModel = boardModel?.id,
                    providers = providers,
                    modelType = ModelType.CHAT,
                    clearLabel = stringResource(
                        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_board_use_default_model
                        else R.string.board_follow_main_model,
                    ),
                    onClear = { update { it.copy(boardModelId = null) } },
                    onSelect = { model -> update { it.copy(boardModelId = model.id.toString()) } },
                    dense = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IntervalRow(current: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkspaceLeadingIcon(icon = Lucide.Clock, size = 32.dp, iconSize = 17.dp)
            Text(
                stringResource(R.string.board_refresh_interval),
                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.Medium),
                color = LocalAmberTokens.current.ink,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                30 to stringResource(R.string.board_interval_30_minutes),
                60 to stringResource(R.string.board_interval_1_hour),
                120 to stringResource(R.string.board_interval_2_hours),
                240 to stringResource(R.string.board_interval_4_hours),
            ).forEach { (value, label) ->
                ChoiceChip(
                    selected = current == value,
                    label = label,
                    onClick = { onChange(value) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BackgroundStrategyRow(current: TodayBoardBackgroundStrategy, onChange: (TodayBoardBackgroundStrategy) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.board_background_strategy), style = MaterialTheme.typography.titleSmall)
        listOf(
            Triple(
                TodayBoardBackgroundStrategy.SMART,
                stringResource(R.string.board_background_smart),
                stringResource(R.string.board_background_smart_description),
            ),
            Triple(
                TodayBoardBackgroundStrategy.WIFI_ONLY,
                stringResource(R.string.board_background_wifi),
                stringResource(R.string.board_background_wifi_description),
            ),
            Triple(
                TodayBoardBackgroundStrategy.FOREGROUND_ONLY,
                stringResource(R.string.board_background_foreground),
                stringResource(R.string.board_background_foreground_description),
            ),
        ).forEach { (strategy, label, description) ->
            RadioRow(
                selected = current == strategy,
                label = label,
                description = description,
                onClick = { onChange(strategy) },
            )
        }
    }
}

private fun Model.findProviderForBoard(providers: List<ProviderSetting>): ProviderSetting? {
    providerOverwrite?.let { return it }
    return providers.firstOrNull { provider -> provider.models.any { it.id == id } }
}
