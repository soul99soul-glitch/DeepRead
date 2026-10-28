package app.amber.feature.ui.pages.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.agent.StandaloneSurfaces
import app.amber.agent.data.db.entity.BoardWeightEntity
import app.amber.core.ai.tools.SearchOrchestrator
import app.amber.core.font.SlidesFontRepository
import app.amber.core.settings.findModelById
import app.amber.core.utils.plus
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.HotListScheduler
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.setting.ExperimentalSettingsScaffold
import app.amber.feature.ui.pages.setting.SettingVM
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingTodayBoardPage(
    paneRoute: String? = null,
    vm: SettingVM = koinViewModel(),
) {
    val boardRepository: BoardRepository = koinInject()
    val hotListRepository: HotListRepository = koinInject()
    val hotListScheduler: HotListScheduler = koinInject()
    val deepReadTemplateRepository: DeepReadTemplateRepository = koinInject()
    val fontRepository: SlidesFontRepository = koinInject()
    val json: Json = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val focusRules by remember(boardRepository) { boardRepository.observeFocusRules() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val customHotListSources by remember(hotListRepository) { hotListRepository.observeSources() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val customDeepReadTemplates by deepReadTemplateRepository.observeTemplates().collectAsStateWithLifecycle()
    val invalidDeepReadTemplateCount by deepReadTemplateRepository.observeInvalidTemplateCount().collectAsStateWithLifecycle()
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val board = settings.agentRuntime.todayBoard
    var sourceWeights by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var weightReloadKey by remember { mutableIntStateOf(0) }
    val pane = TodayBoardSettingsPane.fromRoute(paneRoute)
    val templateFontCss = rememberDeepReadTemplateFontCss(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
        fontScale = board.deepReadFontScale,
    )

    LaunchedEffect(boardRepository, pane, weightReloadKey) {
        if (pane != TodayBoardSettingsPane.REVIEW) return@LaunchedEffect
        sourceWeights = boardRepository.getAllWeights()
            .filter { it.keyword == BoardWeightEntity.WHOLE_SOURCE }
            .associate { it.sourceType to it.weight }
    }

    LaunchedEffect(deepReadTemplateRepository, pane) {
        if (pane == TodayBoardSettingsPane.ROOT ||
            pane == TodayBoardSettingsPane.STYLE ||
            pane == TodayBoardSettingsPane.HOT_LIST
        ) {
            deepReadTemplateRepository.reload()
        }
    }

    val actions = remember(
        vm, boardRepository, hotListRepository, hotListScheduler,
        deepReadTemplateRepository, json, scope,
    ) {
        TodayBoardSettingsActions(
            boardRepository = boardRepository,
            hotListRepository = hotListRepository,
            hotListScheduler = hotListScheduler,
            templateRepository = deepReadTemplateRepository,
            json = json,
            scope = scope,
            writeSettings = vm::updateSettings,
        )
    }
    val boardModel = board.boardModelId
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?.let { settings.findModelById(it) }
    fun update(block: (TodayBoardSetting) -> TodayBoardSetting) {
        actions.updateBoard(board, block)
    }
    fun updateSourceWeight(sourceType: String, weight: Int) {
        sourceWeights = sourceWeights + (sourceType to weight)
        actions.updateSourceWeight(sourceType, weight) { weightReloadKey++ }
    }
    fun LazyListScope.addReadingSettings() {
        todayBoardReadingSettings(
            board = board,
            fontStates = fontStates,
            customDeepReadTemplates = customDeepReadTemplates,
            invalidDeepReadTemplateCount = invalidDeepReadTemplateCount,
            templateFontCss = templateFontCss,
            fontRepository = fontRepository,
            update = ::update,
            onDeleteTemplate = { actions.deleteTemplate(it.id, board) },
            onCreateTemplate = { navController.navigate(Screen.DeepReadTemplateWorkbench) },
        )
    }

    ExperimentalSettingsScaffold(
        title = pane.localizedTitle(),
        titleStyle = deepReadEditorialSerif?.let { serif ->
            LocalAmberType.current.screenTitle.copy(fontFamily = serif, fontWeight = FontWeight.Bold)
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .amberCanvas()
                .let {
                    if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                        it.deepReadPaper(night = DeepReadMoments.isNight(), tokens = LocalAmberTokens.current)
                    } else it
                },
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (pane) {
                TodayBoardSettingsPane.ROOT -> todayBoardRootSettings(
                    board = board,
                    boardModel = boardModel,
                    selectedCustomTemplateName = customDeepReadTemplates.firstOrNull { it.id == board.deepReadTemplateId }?.name,
                    enabledSearchCount = settings.searchEnabledServiceIds.size,
                    searchServiceCount = settings.searchServices.size,
                    freeAggregateEnabled = SearchOrchestrator.freeAggregateEnabled(settings),
                    jinaEnabled = settings.searchBuiltinJinaEnabled,
                    googleWebViewEnabled = settings.searchGoogleWebViewFallbackEnabled,
                    update = ::update,
                    onOpenPane = { navController.navigate(Screen.SettingTodayBoardDetail(it.route)) },
                    onOpenProviders = { navController.navigate(Screen.SettingProvider) },
                    onOpenSearch = { navController.navigate(Screen.SettingSearch) },
                    onToggleFreeAggregate = actions::toggleFreeAggregate,
                    onToggleJina = actions::toggleJina,
                    onToggleGoogleWebView = actions::toggleGoogleWebView,
                )
                TodayBoardSettingsPane.GENERAL -> todayBoardGenerationSettings(board, boardModel, settings.providers, ::update)
                TodayBoardSettingsPane.MODEL -> todayBoardModelSettings(boardModel, settings.providers, ::update)
                TodayBoardSettingsPane.HOT_LIST -> todayBoardSourceSettings(
                    board = board,
                    customHotListSources = customHotListSources,
                    enabledSearchCount = settings.searchEnabledServiceIds.size,
                    searchServiceCount = settings.searchServices.size,
                    update = ::update,
                    onToggleBuiltIn = { actions.toggleBuiltInSource(board, it) },
                    onToggleCustom = actions::toggleCustomHotListSource,
                    onDeleteCustom = actions::deleteCustomHotListSource,
                    onAddNewsNowPresets = { actions.addNewsNowPresets(it, customHotListSources) },
                    onSaveCustom = { actions.saveCustomHotListSource(it, customHotListSources) },
                    readingSettings = { addReadingSettings() },
                )
                TodayBoardSettingsPane.STYLE -> addReadingSettings()
                TodayBoardSettingsPane.REVIEW -> todayBoardReviewSettings(
                    board = board,
                    focusRules = focusRules,
                    sourceWeights = sourceWeights,
                    update = ::update,
                    onAddRule = { actions.addFocusRule(it, focusRules) },
                    onToggleRule = actions::updateFocusRule,
                    onDeleteRule = actions::deleteFocusRule,
                    onChangeWeight = ::updateSourceWeight,
                )
            }
        }
    }
}

internal enum class TodayBoardSettingsPane(val route: String) {
    ROOT("root"),
    GENERAL("general"),
    HOT_LIST("hot_list"),
    REVIEW("review"),
    // Standalone-only "版式与样式" page — iOS DeepReadTemplatesView parity.
    STYLE("style"),
    // Standalone-only "生成模型" page — iOS DeepReadSettingsView's single
    // model picker parity (the full app's multi-role SettingModelPage is
    // agent-only and must not surface in standalone products).
    MODEL("model"),
    ;

    companion object {
        fun fromRoute(route: String?): TodayBoardSettingsPane =
            entries.firstOrNull { it.route == route } ?: ROOT
    }
}

@Composable
private fun TodayBoardSettingsPane.localizedTitle(): String = when (this) {
    // In the standalone deepread app this page IS the product settings —
    // "今日看板" is internal full-app vocabulary the user never sees (iOS tab
    // title is just "设置").
    TodayBoardSettingsPane.ROOT -> stringResource(
        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.settings else R.string.board_settings_title,
    )
    TodayBoardSettingsPane.GENERAL -> stringResource(R.string.board_settings_general_title)
    TodayBoardSettingsPane.HOT_LIST -> stringResource(R.string.board_settings_hotlist_title)
    TodayBoardSettingsPane.REVIEW -> stringResource(
        // Standalone pane only holds signal sources + weights — "复盘" is
        // full-app feature vocabulary with no surface here.
        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_board_signals_weights
        else R.string.board_settings_review_title,
    )
    TodayBoardSettingsPane.STYLE -> stringResource(R.string.deepread_settings_layout_style)
    TodayBoardSettingsPane.MODEL -> stringResource(R.string.deepread_settings_gen_model)
}
