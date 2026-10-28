package app.amber.feature.ui.pages.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.core.font.FontPackCategory
import app.amber.core.font.FontPackState
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MAX
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MIN
import app.amber.feature.board.DEEP_READ_FONT_SCALE_STEP
import app.amber.feature.board.TodayBoardReadingFontMode
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplatePackage
import app.amber.feature.ui.components.ui.NotionSlider
import app.amber.feature.ui.components.ui.workspaceColors
import kotlin.math.roundToInt

internal fun LazyListScope.todayBoardReadingSettings(
    board: TodayBoardSetting,
    fontStates: List<FontPackState>,
    customDeepReadTemplates: List<DeepReadTemplatePackage>,
    invalidDeepReadTemplateCount: Int,
    templateFontCss: String,
    fontRepository: SlidesFontRepository,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
    onDeleteTemplate: (DeepReadTemplatePackage) -> Unit,
    onCreateTemplate: () -> Unit,
) {
    item {
        BoardSectionCard(title = stringResource(R.string.deep_read_title)) {
            ReadingFontRow(board = board, fontStates = fontStates, update = update)
            BoardDivider()
            DeepReadCacheTtlRow(board = board, update = update)
        }
    }
    item {
        DeepReadTemplateSettingsRow(
            board = board,
            customTemplates = customDeepReadTemplates,
            invalidTemplateCount = invalidDeepReadTemplateCount,
            fontCss = templateFontCss,
            fontRepository = fontRepository,
            onSelect = { templateId -> update { it.copy(deepReadTemplateId = templateId) } },
            onDelete = onDeleteTemplate,
            onCreateTemplate = onCreateTemplate,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeepReadCacheTtlRow(
    board: TodayBoardSetting,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    // TTL options: 1 / 3 / 7 (default) / 0 (never expire). 0 keeps expiresAt at Long.MAX_VALUE.
    val options = listOf(
        1 to stringResource(R.string.board_cache_24_hours),
        3 to stringResource(R.string.board_cache_3_days),
        7 to stringResource(R.string.board_cache_7_days),
        0 to stringResource(R.string.board_cache_forever),
    )
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.board_cache_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.board_cache_description),
            style = MaterialTheme.typography.bodySmall,
            color = workspaceColors().muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (days, label) ->
                ChoiceChip(
                    selected = board.deepReadCacheTtlDays == days,
                    label = label,
                    onClick = { update { it.copy(deepReadCacheTtlDays = days) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingFontRow(
    board: TodayBoardSetting,
    fontStates: List<FontPackState>,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    val installedFonts = fontStates.filter { it.installed && !it.installedPath.isNullOrBlank() }
    val selectedPackAvailable = installedFonts.any { it.pack.id == board.boardReadingFontPackId }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.board_reading_font_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(
                selected = board.boardReadingFontMode == TodayBoardReadingFontMode.SYSTEM,
                label = stringResource(R.string.board_font_system),
                onClick = { update { it.copy(boardReadingFontMode = TodayBoardReadingFontMode.SYSTEM) } },
            )
            ChoiceChip(
                selected = board.boardReadingFontMode == TodayBoardReadingFontMode.SERIF,
                label = stringResource(R.string.board_font_serif),
                onClick = {
                    update {
                        it.copy(
                            boardReadingFontMode = TodayBoardReadingFontMode.SERIF,
                            boardReadingFontPackId = null,
                        )
                    }
                },
            )
        }
        if (installedFonts.isEmpty()) {
            Text(stringResource(R.string.board_fonts_empty), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        } else {
            Text(stringResource(R.string.board_fonts_downloaded), style = MaterialTheme.typography.labelMedium, color = workspaceColors().muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installedFonts
                    .sortedWith(compareByDescending<FontPackState> { it.pack.category == FontPackCategory.SERIF }.thenBy { it.pack.displayName })
                    .take(12)
                    .forEach { state ->
                        ChoiceChip(
                            selected = board.boardReadingFontMode == TodayBoardReadingFontMode.SLIDES_PACK &&
                                board.boardReadingFontPackId == state.pack.id,
                            label = "${state.pack.displayName} · ${state.pack.category.label()}",
                            onClick = {
                                update {
                                    it.copy(
                                        boardReadingFontMode = TodayBoardReadingFontMode.SLIDES_PACK,
                                        boardReadingFontPackId = state.pack.id,
                                    )
                                }
                            },
                        )
                    }
            }
            if (board.boardReadingFontMode == TodayBoardReadingFontMode.SLIDES_PACK && !selectedPackAvailable) {
                Text(stringResource(R.string.board_font_unavailable), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.board_font_size), style = MaterialTheme.typography.bodyMedium)
                Text("${(board.deepReadFontScale * 100f).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
            }
            NotionSlider(
                value = board.deepReadFontScale.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX),
                onValueChangeFinished = { value ->
                    update { it.copy(deepReadFontScale = value.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX)) }
                },
                valueRange = DEEP_READ_FONT_SCALE_MIN..DEEP_READ_FONT_SCALE_MAX,
                snapStep = DEEP_READ_FONT_SCALE_STEP,
                valueLabel = { value ->
                    Text("${(value * 100f).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
                },
            )
        }
    }
}

@Composable
private fun FontPackCategory.label(): String =
    when (this) {
        FontPackCategory.SERIF -> stringResource(R.string.board_font_category_serif)
        FontPackCategory.SANS -> stringResource(R.string.board_font_category_sans)
        FontPackCategory.HANDWRITING -> stringResource(R.string.board_font_category_handwriting)
        FontPackCategory.MONO -> stringResource(R.string.board_font_category_mono)
    }
