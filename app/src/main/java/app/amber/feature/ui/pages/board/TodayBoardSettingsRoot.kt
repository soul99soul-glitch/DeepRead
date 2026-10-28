package app.amber.feature.ui.pages.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.agent.StandaloneSurfaces
import app.amber.ai.provider.Model
import app.amber.core.utils.plus
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.LayoutDashboard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.ScanSearch
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.WandSparkles

internal fun LazyListScope.todayBoardRootSettings(
    board: TodayBoardSetting,
    boardModel: Model?,
    selectedCustomTemplateName: String?,
    enabledSearchCount: Int,
    searchServiceCount: Int,
    freeAggregateEnabled: Boolean,
    jinaEnabled: Boolean,
    googleWebViewEnabled: Boolean,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
    onOpenPane: (TodayBoardSettingsPane) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleFreeAggregate: () -> Unit,
    onToggleJina: () -> Unit,
    onToggleGoogleWebView: () -> Unit,
) {
    // Standalone deepread: iOS DeepReadSettingsView parity —
    // 外观 / 文章生成 / 多来源搜索 cards first, then the board
    // feature cards. The full app skips straight to features.
    if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
        item {
            val selectedTemplateName = DeepReadTemplateCatalog.name(
                board.deepReadTemplateId,
                selectedCustomTemplateName,
            )
            BoardSectionCard(
                title = stringResource(R.string.deepread_settings_appearance),
                footer = stringResource(R.string.deepread_settings_appearance_footer),
            ) {
                DeepReadAccentPicker(
                    selectedId = board.deepReadAccent,
                    onSelect = { choice -> update { it.copy(deepReadAccent = choice.id) } },
                )
                BoardDivider()
                DetailNavigationRow(
                    title = stringResource(R.string.deepread_settings_layout_style),
                    value = selectedTemplateName,
                    icon = Lucide.BookOpenText,
                    onClick = {
                        onOpenPane(TodayBoardSettingsPane.STYLE)
                    },
                )
            }
        }

        item {
            // Mirrors BoardModelRow semantics: null boardModelId
            // means "follow main chat model", not a pinned model.
            BoardSectionCard(
                title = stringResource(R.string.deepread_settings_generation),
                footer = stringResource(R.string.deepread_settings_gen_footer),
            ) {
                DetailNavigationRow(
                    title = stringResource(R.string.deepread_settings_gen_model),
                    value = boardModel?.displayName ?: stringResource(R.string.deepread_board_use_default_model),
                    icon = Lucide.WandSparkles,
                    onClick = {
                        onOpenPane(TodayBoardSettingsPane.MODEL)
                    },
                )
                BoardDivider()
                // 前台/后台 execution strategy belongs with
                // generation ops (folded from the old 通用 card).
                DetailNavigationRow(
                    title = stringResource(R.string.board_model_background),
                    icon = Lucide.RotateCw,
                    onClick = {
                        onOpenPane(TodayBoardSettingsPane.GENERAL)
                    },
                )
                BoardDivider()
                DetailNavigationRow(
                    title = stringResource(R.string.setting_page_providers),
                    icon = Lucide.Cpu,
                    onClick = { onOpenProviders() },
                )
                BoardDivider()
                // iOS "＋ 添加模型服务" accent action row.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProviders() }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Lucide.Plus,
                        contentDescription = null,
                        tint = LocalAmberTokens.current.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.deepread_settings_add_model),
                        style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                        color = LocalAmberTokens.current.accent,
                    )
                }
            }
        }

        // iOS "多来源搜索" — everything that feeds the digest
        // lives in one card: hotlist sources, the search service,
        // and personal signals with their weights.
        item {
            BoardSectionCard(
                title = stringResource(R.string.deepread_settings_search),
                footer = stringResource(R.string.deepread_settings_sources_footer),
            ) {
                DetailNavigationRow(
                    title = stringResource(R.string.deepread_board_sources_focus_title),
                    icon = Lucide.BookOpenText,
                    onClick = {
                        onOpenPane(TodayBoardSettingsPane.HOT_LIST)
                    },
                )
                BoardDivider()
                DetailNavigationRow(
                    title = stringResource(R.string.setting_page_search_service),
                    value = "$enabledSearchCount/$searchServiceCount",
                    icon = Lucide.ScanSearch,
                    onClick = { onOpenSearch() },
                )
                BoardDivider()
                DetailNavigationRow(
                    title = stringResource(R.string.deepread_board_signals_weights),
                    icon = Lucide.SlidersHorizontal,
                    onClick = {
                        onOpenPane(TodayBoardSettingsPane.REVIEW)
                    },
                )
            }
        }

        // iOS "免费聚合与正文读取" — the keyless built-in
        // sources. Android consumes them as three switches:
        // DuckDuckGo+Bing ride one aggregate, Jina reads page
        // text, Google WebView is the last-resort fallback.
        // (Wikipedia/HackerNews flags exist in prefs but have
        // no Android consumer — don't expose dead toggles.)
        item {
            BoardSectionCard(title = stringResource(R.string.deepread_settings_free_aggregate)) {
                SourceSwitch(
                    title = stringResource(R.string.setting_page_search_builtin_free_aggregate),
                    description = stringResource(R.string.setting_page_search_builtin_free_aggregate_desc),
                    checked = freeAggregateEnabled,
                    icon = Lucide.Globe,
                ) {
                    onToggleFreeAggregate()
                }
                BoardDivider()
                SourceSwitch(
                    title = stringResource(R.string.setting_page_search_builtin_jina),
                    description = stringResource(R.string.setting_page_search_builtin_jina_desc),
                    checked = jinaEnabled,
                    icon = Lucide.BookOpenText,
                ) {
                    onToggleJina()
                }
                BoardDivider()
                SourceSwitch(
                    title = stringResource(R.string.setting_page_search_google_webview_fallback),
                    description = stringResource(R.string.setting_page_search_google_webview_fallback_desc),
                    checked = googleWebViewEnabled,
                    icon = Lucide.Search,
                ) {
                    onToggleGoogleWebView()
                }
            }
        }

        // iOS-style 通用: the product switch plus refresh
        // scheduling — what was previously split across
        // 通用 / 热榜 / 今日复盘 shell cards.
        item {
            BoardSectionCard(title = stringResource(R.string.board_settings_general)) {
                SourceSwitch(
                    title = stringResource(R.string.deepread_board_enable_title),
                    description = stringResource(R.string.deepread_board_enable_description),
                    checked = board.enabled,
                    icon = Lucide.LayoutDashboard,
                ) {
                    update { it.copy(enabled = !it.enabled) }
                }
                BoardDivider()
                IntervalRow(board.hotListRefreshIntervalMinutes) { value ->
                    update { it.copy(hotListRefreshIntervalMinutes = value) }
                }
                BoardDivider()
                SourceSwitch(
                    title = stringResource(R.string.board_wifi_only),
                    description = stringResource(R.string.deepread_board_wifi_only_description),
                    checked = board.hotListWifiOnly,
                    icon = Lucide.Cloud,
                ) {
                    update { it.copy(hotListWifiOnly = !it.hotListWifiOnly) }
                }
            }
        }
    } else {
        // Full app keeps its original feature-shell cards.
        item {
            BoardSectionCard(title = stringResource(R.string.board_settings_general)) {
                SourceSwitch(
                    title = stringResource(R.string.board_enable_title),
                    description = stringResource(R.string.board_enable_description),
                    checked = board.enabled,
                    icon = Lucide.LayoutDashboard,
                ) {
                    update { it.copy(enabled = !it.enabled) }
                }
                BoardDivider()
                DetailNavigationRow(
                    title = stringResource(R.string.board_model_background),
                    description = null,
                    icon = Lucide.RotateCw,
                    onClick = {
                        onOpenPane(TodayBoardSettingsPane.GENERAL)
                    },
                )
            }
        }

        item {
            BoardSectionCard(title = stringResource(R.string.board_hotlist)) {
                IntervalRow(board.hotListRefreshIntervalMinutes) { value ->
                    update { it.copy(hotListRefreshIntervalMinutes = value) }
                }
                BoardDivider()
                SourceSwitch(
                    title = stringResource(R.string.board_wifi_only),
                    description = stringResource(R.string.board_wifi_only_description),
                    checked = board.hotListWifiOnly,
                    icon = Lucide.Cloud,
                ) {
                    update { it.copy(hotListWifiOnly = !it.hotListWifiOnly) }
                }
                BoardDivider()
                DetailNavigationRow(
                    title = stringResource(R.string.board_sources_focus_deep_read),
                    description = null,
                    icon = Lucide.BookOpenText,
                    onClick = {
                        onOpenPane(TodayBoardSettingsPane.HOT_LIST)
                    },
                )
            }
        }

        item {
            BoardSectionCard(title = stringResource(R.string.board_daily_review)) {
                DetailNavigationRow(
                    title = stringResource(R.string.board_signal_sources_focus),
                    description = null,
                    icon = Lucide.SlidersHorizontal,
                    onClick = {
                        onOpenPane(TodayBoardSettingsPane.REVIEW)
                    },
                )
            }
        }
    }


}
