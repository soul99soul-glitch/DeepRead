package app.amber.feature.ui.components.message

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import app.amber.core.ai.generative.IntelligentUiCardState
import app.amber.core.ai.generative.IntelligentUiComponentKind
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.core.ai.generative.IntelligentUiDocument
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens

/**
 * Recursive dispatcher that renders all 80 native `task-ui` component kinds in Jetpack Compose.
 */
@Composable
internal fun IntelligentUiComponentView(
    node: IntelligentUiComponentNode,
    document: IntelligentUiDocument,
    state: IntelligentUiCardState,
    onActionPrompt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent

    when (node.kind) {
        // 1. Layout (10)
        IntelligentUiComponentKind.SECTION -> LayoutSectionView(node, document, state, onActionPrompt, modifier)
        IntelligentUiComponentKind.ROW -> LayoutRowView(node, document, state, onActionPrompt, modifier)
        IntelligentUiComponentKind.COLUMN -> LayoutColumnView(node, document, state, onActionPrompt, modifier)
        IntelligentUiComponentKind.GRID -> LayoutGridView(node, document, state, onActionPrompt, modifier)
        IntelligentUiComponentKind.CARD -> LayoutCardView(node, document, state, onActionPrompt, modifier)
        IntelligentUiComponentKind.SPLIT -> LayoutSplitView(node, document, state, onActionPrompt, modifier)
        IntelligentUiComponentKind.TABS -> LayoutTabsView(node, document, state, onActionPrompt, modifier)
        IntelligentUiComponentKind.ACCORDION,
        IntelligentUiComponentKind.DISCLOSURE -> LayoutDisclosureView(node, document, state, onActionPrompt, modifier)
        IntelligentUiComponentKind.DIVIDER -> HorizontalDivider(
            modifier = modifier.padding(vertical = 4.dp),
            thickness = 0.5.dp,
            color = tokens.line2,
        )

        // 2. Text (12)
        IntelligentUiComponentKind.HEADING -> TextHeadingView(node, isSubheading = false, modifier = modifier)
        IntelligentUiComponentKind.SUBHEADING -> TextHeadingView(node, isSubheading = true, modifier = modifier)
        IntelligentUiComponentKind.TEXT -> TextBodyView(node, isCaption = false, modifier = modifier)
        IntelligentUiComponentKind.CAPTION -> TextBodyView(node, isCaption = true, modifier = modifier)
        IntelligentUiComponentKind.EYEBROW -> TextEyebrowView(node, accent, modifier)
        IntelligentUiComponentKind.CALLOUT -> TextCalloutView(node, isQuote = false, accent = accent, modifier = modifier)
        IntelligentUiComponentKind.QUOTE -> TextCalloutView(node, isQuote = true, accent = accent, modifier = modifier)
        IntelligentUiComponentKind.CODE_BLOCK,
        IntelligentUiComponentKind.CODE_PREVIEW -> CodeBlockNodeView(node, modifier)
        IntelligentUiComponentKind.BULLET_LIST -> ListItemsView(node, numbered = false, modifier = modifier)
        IntelligentUiComponentKind.NUMBERED_LIST -> ListItemsView(node, numbered = true, modifier = modifier)
        IntelligentUiComponentKind.KEY_VALUE -> KeyValueSingleView(node, modifier)
        IntelligentUiComponentKind.DEFINITION_LIST -> DefinitionListView(node, modifier)

        // 3. Status (10)
        IntelligentUiComponentKind.BADGE,
        IntelligentUiComponentKind.TAG,
        IntelligentUiComponentKind.PILL -> StatusPillNodeView(node, accent, modifier)
        IntelligentUiComponentKind.STATUS_DOT -> StatusDotNodeView(node, accent, modifier)
        IntelligentUiComponentKind.ALERT,
        IntelligentUiComponentKind.BANNER -> StatusAlertBannerView(node, accent, modifier)
        IntelligentUiComponentKind.EMPTY_STATE,
        IntelligentUiComponentKind.SKELETON_NOTE -> StatusEmptyOrSkeletonView(node, modifier)
        IntelligentUiComponentKind.TIMELINE_ITEM,
        IntelligentUiComponentKind.STEP_ITEM -> TimelineStepItemView(node, accent, modifier)

        // 4. Input (14)
        IntelligentUiComponentKind.TEXT_FIELD -> InputTextFieldNodeView(node, state, multiline = false, numeric = false, modifier = modifier)
        IntelligentUiComponentKind.TEXT_AREA -> InputTextFieldNodeView(node, state, multiline = true, numeric = false, modifier = modifier)
        IntelligentUiComponentKind.NUMBER_FIELD -> InputTextFieldNodeView(node, state, multiline = false, numeric = true, modifier = modifier)
        IntelligentUiComponentKind.DATE_FIELD -> InputTextFieldNodeView(node, state, multiline = false, numeric = false, isDate = true, modifier = modifier)
        IntelligentUiComponentKind.SLIDER -> InputSliderNodeView(node, state, accent, modifier)
        IntelligentUiComponentKind.STEPPER -> InputStepperNodeView(node, state, accent, modifier)
        IntelligentUiComponentKind.RATING -> InputRatingNodeView(node, state, accent, modifier)
        IntelligentUiComponentKind.TOGGLE -> InputToggleCheckboxView(node, state, useSwitch = true, accent = accent, modifier = modifier)
        IntelligentUiComponentKind.CHECKBOX,
        IntelligentUiComponentKind.CHECKLIST_ITEM -> InputToggleCheckboxView(node, state, useSwitch = false, accent = accent, modifier = modifier)
        IntelligentUiComponentKind.RADIO_GROUP,
        IntelligentUiComponentKind.SELECT -> InputSingleChoiceView(node, state, segmented = false, accent = accent, modifier = modifier)
        IntelligentUiComponentKind.SEGMENTED_CONTROL,
        IntelligentUiComponentKind.FILTER_BAR -> InputSingleChoiceView(node, state, segmented = true, accent = accent, modifier = modifier)
        IntelligentUiComponentKind.MULTI_SELECT,
        IntelligentUiComponentKind.CHIP_GROUP -> InputMultiChoiceView(node, state, accent, modifier)

        // 5. Data (10)
        IntelligentUiComponentKind.METRIC -> DataMetricNodeView(node, document, state, accent, modifier)
        IntelligentUiComponentKind.METRIC_GRID -> DataMetricGridView(node, document, state, onActionPrompt, accent, modifier)
        IntelligentUiComponentKind.PROGRESS -> DataProgressNodeView(node, document, state, accent, modifier)
        IntelligentUiComponentKind.STEP_PROGRESS -> DataStepProgressNodeView(node, document, state, accent, modifier)
        IntelligentUiComponentKind.COMPARISON_ROW -> DataComparisonRowView(node, modifier)
        IntelligentUiComponentKind.PROS_CONS -> DataProsConsView(node, modifier)
        IntelligentUiComponentKind.TABLE -> DataTableView(node, modifier)
        IntelligentUiComponentKind.RANKING_LIST -> DataRankingListView(node, accent, modifier)
        IntelligentUiComponentKind.BAR_CHART -> DataBarChartView(node, document, state, accent, modifier)
        IntelligentUiComponentKind.LINE_CHART -> DataLineChartView(node, document, state, accent, modifier)

        // 6. Media (8) — CODE_PREVIEW handled above with CODE_BLOCK
        IntelligentUiComponentKind.ICON_LABEL -> MediaIconLabelView(node, accent, modifier)
        IntelligentUiComponentKind.AVATAR_ITEM -> MediaAvatarItemView(node, accent, modifier)
        IntelligentUiComponentKind.IMAGE_CARD -> MediaImageCardView(node, modifier)
        IntelligentUiComponentKind.GALLERY_STRIP -> MediaGalleryStripView(node, modifier)
        IntelligentUiComponentKind.LINK_CARD -> MediaLinkOrFileCardView(node, isFile = false, accent = accent, modifier = modifier)
        IntelligentUiComponentKind.FILE_CARD -> MediaLinkOrFileCardView(node, isFile = true, accent = accent, modifier = modifier)
        IntelligentUiComponentKind.MAP_PIN -> MediaMapPinView(node, accent, modifier)

        // 7. Task Semantic (8) — CHECKLIST_ITEM handled above
        IntelligentUiComponentKind.OPTION_CARD -> SemanticOptionCardView(node, state, accent, modifier)
        IntelligentUiComponentKind.RECOMMENDATION_HERO -> SemanticRecommendationHeroView(node, accent, modifier)
        IntelligentUiComponentKind.CONSTRAINT_ITEM -> SemanticConstraintOrRiskView(node, isRisk = false, modifier = modifier)
        IntelligentUiComponentKind.RISK_ITEM -> SemanticConstraintOrRiskView(node, isRisk = true, modifier = modifier)
        IntelligentUiComponentKind.FLASHCARD -> IntelligentUiSingleFlashcardNode(node = node, state = state, modifier = modifier)
        IntelligentUiComponentKind.QUIZ_ITEM -> IntelligentUiQuizItemNode(node = node, indexLabel = node.badge, state = state, modifier = modifier)
        IntelligentUiComponentKind.SCORE_SUMMARY -> IntelligentUiScoreSummarySection(
            flashcards = document.flashcardNodes(),
            quizNodes = document.quizNodes(),
            state = state,
            modifier = modifier,
        )

        // 8. Action & Container (8) — FILTER_BAR handled above
        IntelligentUiComponentKind.SEARCH_FILTER -> InputTextFieldNodeView(node, state, multiline = false, numeric = false, modifier = modifier)
        IntelligentUiComponentKind.ACTION_BUTTON -> ActionButtonNodeView(node, document, state, onActionPrompt, accent, modifier)
        IntelligentUiComponentKind.ACTION_BAR,
        IntelligentUiComponentKind.CONFIRM_BAR -> ActionBarNodeView(node, document, state, onActionPrompt, accent, modifier)
        IntelligentUiComponentKind.FOOTNOTE -> TextBodyView(node, isCaption = true, modifier = modifier)
        IntelligentUiComponentKind.SUMMARY_BOX,
        IntelligentUiComponentKind.CTA_CARD -> SummaryOrCtaCardView(node, document, state, onActionPrompt, accent, modifier)
    }
}
