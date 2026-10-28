package app.amber.feature.ui.pages.board

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import app.amber.agent.R
import app.amber.agent.StandaloneSurfaces
import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp")
class TodayBoardSettingsPaneTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rootRowsOpenTheirActualSettingsPanes() {
        val opened = mutableListOf<TodayBoardSettingsPane>()
        compose.setContent {
            CompositionLocalProvider(LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))) {
                MaterialTheme {
                    LazyColumn(Modifier.fillMaxSize()) {
                        todayBoardRootSettings(
                            board = TodayBoardSetting(),
                            boardModel = null,
                            selectedCustomTemplateName = null,
                            enabledSearchCount = 0,
                            searchServiceCount = 0,
                            freeAggregateEnabled = false,
                            jinaEnabled = false,
                            googleWebViewEnabled = false,
                            update = {},
                            onOpenPane = { opened += it },
                            onOpenProviders = {},
                            onOpenSearch = {},
                            onToggleFreeAggregate = {},
                            onToggleJina = {},
                            onToggleGoogleWebView = {},
                        )
                    }
                }
            }
        }
        val rows = if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
            listOf(
                R.string.deepread_settings_layout_style to TodayBoardSettingsPane.STYLE,
                R.string.deepread_settings_gen_model to TodayBoardSettingsPane.MODEL,
                R.string.deepread_board_sources_focus_title to TodayBoardSettingsPane.HOT_LIST,
                R.string.deepread_board_signals_weights to TodayBoardSettingsPane.REVIEW,
            )
        } else {
            listOf(
                R.string.board_model_background to TodayBoardSettingsPane.GENERAL,
                R.string.board_sources_focus_deep_read to TodayBoardSettingsPane.HOT_LIST,
                R.string.board_signal_sources_focus to TodayBoardSettingsPane.REVIEW,
            )
        }
        val context = RuntimeEnvironment.getApplication()
        for ((label, pane) in rows) {
            val text = context.getString(label)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).performClick()
            assertEquals(pane, opened.last())
        }
        assertEquals(rows.map { it.second }, opened)
    }

    @Test
    fun focusEditorKeepsAddToggleAndDeleteBoundToTheirOriginalRule() {
        val rule = BoardFocusRuleEntity("rule-fixture", "Existing focus rule", createdAt = 1L, updatedAt = 1L)
        val added = mutableListOf<String>()
        val toggled = mutableListOf<Pair<BoardFocusRuleEntity, Boolean>>()
        val deleted = mutableListOf<BoardFocusRuleEntity>()
        compose.setContent {
            CompositionLocalProvider(LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))) {
                MaterialTheme {
                    LazyColumn(Modifier.fillMaxSize()) {
                        todayBoardReviewSettings(
                            board = TodayBoardSetting(),
                            focusRules = listOf(rule),
                            sourceWeights = emptyMap(),
                            update = {},
                            onAddRule = { added += it },
                            onToggleRule = { value, enabled -> toggled += value to enabled },
                            onDeleteRule = { deleted += it },
                            onChangeWeight = { _, _ -> },
                        )
                    }
                }
            }
        }
        val context = RuntimeEnvironment.getApplication()
        compose.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("New focus rule")
        compose.onNodeWithText(context.getString(R.string.add)).performClick()
        assertEquals(listOf("New focus rule"), added)
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(rule.content))
        compose.onNode(
            hasAnySibling(hasText(rule.content)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
        ).performClick()
        assertEquals(listOf(rule to false), toggled)
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        assertEquals(listOf(rule), deleted)
    }
}
