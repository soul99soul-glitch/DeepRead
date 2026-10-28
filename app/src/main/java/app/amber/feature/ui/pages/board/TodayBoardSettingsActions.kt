package app.amber.feature.ui.pages.board

import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import app.amber.agent.data.db.entity.BoardWeightEntity
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.core.settings.Settings
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.HotListScheduler
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import app.amber.feature.board.hotlist.providers.NewsNowPreset
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Uses the settings route's lifetime and its existing single settings writer. */
internal class TodayBoardSettingsActions(
    private val boardRepository: BoardRepository,
    private val hotListRepository: HotListRepository,
    private val hotListScheduler: HotListScheduler,
    private val templateRepository: DeepReadTemplateRepository,
    private val json: Json,
    private val scope: CoroutineScope,
    private val writeSettings: ((Settings) -> Settings) -> Unit,
) {
    fun updateBoard(board: TodayBoardSetting, block: (TodayBoardSetting) -> TodayBoardSetting) {
        writeSettings { current ->
            current.copy(
                agentRuntime = current.agentRuntime.copy(todayBoard = block(board))
            )
        }
    }

    fun addFocusRule(content: String, focusRules: List<BoardFocusRuleEntity>) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        scope.launch {
            boardRepository.upsertFocusRule(
                BoardFocusRuleEntity(
                    id = Uuid.random().toString(),
                    content = trimmed,
                    active = true,
                    sortOrder = (focusRules.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    fun updateFocusRule(rule: BoardFocusRuleEntity, active: Boolean) {
        scope.launch {
            boardRepository.upsertFocusRule(rule.copy(active = active, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteFocusRule(rule: BoardFocusRuleEntity) {
        scope.launch { boardRepository.deleteFocusRule(rule.id) }
    }

    fun updateSourceWeight(sourceType: String, weight: Int, onPersisted: () -> Unit) {
        scope.launch {
            boardRepository.upsertWeight(
                BoardWeightEntity(
                    sourceType = sourceType,
                    keyword = BoardWeightEntity.WHOLE_SOURCE,
                    weight = weight,
                    lastActionAt = System.currentTimeMillis(),
                )
            )
            onPersisted()
        }
    }

    fun saveCustomHotListSource(draft: CustomHotListSourceDraft, customHotListSources: List<HotListSourceEntity>) {
        val now = System.currentTimeMillis()
        scope.launch {
            hotListRepository.upsertSource(
                HotListSourceEntity(
                    id = "custom:${Uuid.random()}",
                    displayName = draft.name.trim(),
                    sourceType = draft.sourceType,
                    url = draft.url.trim(),
                    enabled = true,
                    fieldMappingJson = json.encodeToString(draft.mapping),
                    sortOrder = (customHotListSources.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            hotListScheduler.runOnce()
        }
    }

    fun addNewsNowPresets(presets: List<NewsNowPreset>, customHotListSources: List<HotListSourceEntity>) {
        if (presets.isEmpty()) return
        val now = System.currentTimeMillis()
        val baseOrder = (customHotListSources.maxOfOrNull { it.sortOrder } ?: 0) + 1
        scope.launch {
            presets.forEachIndexed { index, preset ->
                hotListRepository.upsertSource(
                    app.amber.feature.board.hotlist.providers.NewsNowPresets.entityFor(
                        preset = preset,
                        now = now,
                        sortOrder = baseOrder + index,
                    )
                )
            }
            hotListScheduler.runOnce()
        }
    }

    fun toggleCustomHotListSource(source: HotListSourceEntity) {
        scope.launch {
            hotListRepository.upsertSource(source.copy(enabled = !source.enabled, updatedAt = System.currentTimeMillis()))
            hotListScheduler.runOnce()
        }
    }

    fun deleteCustomHotListSource(source: HotListSourceEntity) {
        scope.launch {
            hotListRepository.deleteSource(source.id)
            hotListScheduler.runOnce()
        }
    }

    fun toggleBuiltInSource(board: TodayBoardSetting, source: String) {
        updateBoard(board) {
            it.copy(hotListEnabledSources = if (source in it.hotListEnabledSources) {
                it.hotListEnabledSources - source
            } else {
                it.hotListEnabledSources + source
            })
        }
        hotListScheduler.runOnce()
    }

    fun deleteTemplate(templateId: String, board: TodayBoardSetting) {
        scope.launch {
            templateRepository.deleteTemplate(templateId)
            if (board.deepReadTemplateId == templateId) {
                updateBoard(board) { it.copy(deepReadTemplateId = DeepReadTemplateIds.COMPOSE_MAGAZINE) }
            }
        }
    }

    fun toggleFreeAggregate() {
        writeSettings { current ->
            val target = !(current.searchBuiltinDuckDuckGoEnabled || current.searchBuiltinBingEnabled)
            current.copy(searchBuiltinDuckDuckGoEnabled = target, searchBuiltinBingEnabled = target)
        }
    }

    fun toggleJina() {
        writeSettings { it.copy(searchBuiltinJinaEnabled = !it.searchBuiltinJinaEnabled) }
    }

    fun toggleGoogleWebView() {
        writeSettings { it.copy(searchGoogleWebViewFallbackEnabled = !it.searchGoogleWebViewFallbackEnabled) }
    }
}
