package app.amber.feature.ui.pages.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.agent.StandaloneSurfaces
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.feature.board.DEFAULT_HOT_LIST_FOCUS_KEYWORDS
import app.amber.feature.board.TodayBoardHotListFilterMode
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.hotlist.normalizeHotListFocusKeywords
import app.amber.feature.board.hotlist.providers.NewsNowPreset
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens

internal fun LazyListScope.todayBoardSourceSettings(
    board: TodayBoardSetting,
    customHotListSources: List<HotListSourceEntity>,
    enabledSearchCount: Int,
    searchServiceCount: Int,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
    onToggleBuiltIn: (String) -> Unit,
    onToggleCustom: (HotListSourceEntity) -> Unit,
    onDeleteCustom: (HotListSourceEntity) -> Unit,
    onAddNewsNowPresets: (List<NewsNowPreset>) -> Unit,
    onSaveCustom: (CustomHotListSourceDraft) -> Unit,
    readingSettings: LazyListScope.() -> Unit,
) {
    item {
        HotListSourceSettings(
            enabledBuiltIns = board.hotListEnabledSources,
            customSources = customHotListSources,
            onToggleBuiltIn = onToggleBuiltIn,
            onToggleCustom = onToggleCustom,
            onDeleteCustom = onDeleteCustom,
            onAddNewsNowPresets = onAddNewsNowPresets,
            onSaveCustom = onSaveCustom,
        )
    }
    item {
        BoardSectionCard(title = stringResource(R.string.board_search_title)) {
            SearchServiceSummary(
                enabledCount = enabledSearchCount,
                totalCount = searchServiceCount,
            )
        }
    }
    item {
        BoardSectionCard(title = stringResource(R.string.board_focus_filter)) {
            HotListFocusKeywordEditor(
                keywords = board.hotListFocusKeywords,
                mode = board.hotListFilterMode,
                onKeywordsChange = { keywords -> update { it.copy(hotListFocusKeywords = keywords) } },
                onModeChange = { mode -> update { it.copy(hotListFilterMode = mode) } },
            )
        }
    }
    // Standalone deepread: reading typography + templates live in
    // the dedicated STYLE pane (iOS 版式与样式). Rendering them
    // here too would give two entries to the same settings.
    if (StandaloneSurfaces.current != StandaloneSurfaces.DEEP_READ) {
        readingSettings()
    }
}

@Composable
private fun TodayBoardHotListFilterMode.label(): String =
    when (this) {
        TodayBoardHotListFilterMode.ALL -> stringResource(R.string.board_filter_all)
        TodayBoardHotListFilterMode.FOCUS_FIRST -> stringResource(R.string.board_filter_focus_first)
        TodayBoardHotListFilterMode.FOCUS_ONLY -> stringResource(R.string.board_filter_focus_only)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HotListFocusKeywordEditor(
    keywords: List<String>,
    mode: TodayBoardHotListFilterMode,
    onKeywordsChange: (List<String>) -> Unit,
    onModeChange: (TodayBoardHotListFilterMode) -> Unit,
) {
    var draft by rememberSaveable(keywords.joinToString("|")) { mutableStateOf(keywords.joinToString("、")) }
    val parsed = normalizeHotListFocusKeywords(listOf(draft))
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.board_focus_filter), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                TodayBoardHotListFilterMode.ALL to stringResource(R.string.board_filter_all),
                TodayBoardHotListFilterMode.FOCUS_FIRST to stringResource(R.string.board_filter_focus_first),
                TodayBoardHotListFilterMode.FOCUS_ONLY to stringResource(R.string.board_filter_focus_only),
            ).forEach { (value, label) ->
                ChoiceChip(selected = mode == value, label = label, onClick = { onModeChange(value) })
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.board_focus_placeholder)) },
            minLines = 2,
            maxLines = 4,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LocalAmberTokens.current.accent,
                unfocusedBorderColor = LocalAmberTokens.current.line,
                focusedContainerColor = LocalAmberTokens.current.raised,
                unfocusedContainerColor = LocalAmberTokens.current.surface2,
                cursorColor = LocalAmberTokens.current.accent,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = parsed.isNotEmpty(),
                onClick = { onKeywordsChange(parsed) },
            ) {
                Text(stringResource(R.string.board_apply))
            }
            TextButton(
                onClick = {
                    draft = DEFAULT_HOT_LIST_FOCUS_KEYWORDS.joinToString("、")
                    onKeywordsChange(DEFAULT_HOT_LIST_FOCUS_KEYWORDS)
                },
            ) {
                Text(stringResource(R.string.board_focus_restore))
            }
        }
        if (keywords.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                keywords.take(16).forEach { keyword ->
                    ChoiceChip(selected = false, label = keyword, onClick = {})
                }
                if (keywords.size > 16) {
                    Text("+${keywords.size - 16}", style = MaterialTheme.typography.labelSmall, color = workspaceColors().muted)
                }
            }
        }
    }
}

@Composable
private fun SearchServiceSummary(enabledCount: Int, totalCount: Int) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.board_search_title), style = MaterialTheme.typography.titleSmall)
        Text(
            if (enabledCount > 0) {
                stringResource(R.string.board_search_enabled_summary, enabledCount, totalCount)
            } else {
                stringResource(R.string.board_search_required)
            },
            style = MaterialTheme.typography.bodySmall,
            color = workspaceColors().muted,
        )
    }
}
