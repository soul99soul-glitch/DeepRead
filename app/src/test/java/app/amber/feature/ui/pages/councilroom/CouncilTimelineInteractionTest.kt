package app.amber.feature.ui.pages.councilroom

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import app.amber.core.settings.Settings
import app.amber.feature.modelcouncil.COUNCIL_ROOM_HOST_ID
import app.amber.feature.modelcouncil.COUNCIL_ROOM_USER_ID
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilMessageKind
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.CouncilRoomStatus
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.pages.chat.toChatTheme
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w320dp-h812dp")
class CouncilTimelineInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun historicalUnansweredQuestionDoesNotOfferAnEditorWithoutAnAnswerReceiver() {
        val conversationId = Uuid.random()
        val question = CouncilMessage(
            id = "question-in-finished-room",
            authorId = COUNCIL_ROOM_HOST_ID,
            authorName = "主持人",
            role = "主持",
            round = 1,
            mode = CouncilRoomMode.EXPLORE,
            text = "这份已经停止的讨论还保留着当时的问题。",
            createdAtMs = 1L,
            status = CouncilMessageStatus.COMPLETED,
            kind = CouncilMessageKind.ASK_USER,
        )
        val room = CouncilRoom(
            id = conversationId.toString(),
            conversationId = conversationId,
            hostAssistantId = Uuid.random(),
            objective = "Historical room",
            status = CouncilRoomStatus.CANCELLED,
            messages = listOf(question),
            createdAtMs = 1L,
        )
        showTimeline(room, onAnswer = null)
        compose.onNodeWithText(question.text).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("→").assertDoesNotExist()
    }

    @Test
    fun activeQuestionDeliversTrimmedAnswerAndClearsItsEditorAfterSubmit() {
        val conversationId = Uuid.random()
        val question = CouncilMessage(
            id = "active-question",
            authorId = COUNCIL_ROOM_HOST_ID,
            authorName = "主持人",
            role = "主持",
            round = 1,
            mode = CouncilRoomMode.EXPLORE,
            text = "请明确本轮要采用哪个方案。",
            createdAtMs = 1L,
            status = CouncilMessageStatus.COMPLETED,
            kind = CouncilMessageKind.ASK_USER,
        )
        val room = CouncilRoom(
            id = conversationId.toString(),
            conversationId = conversationId,
            hostAssistantId = Uuid.random(),
            objective = "Active question",
            status = CouncilRoomStatus.INTERRUPTED,
            messages = listOf(
                question,
                CouncilMessage(
                    id = "unlinked-user-message",
                    authorId = COUNCIL_ROOM_USER_ID,
                    authorName = "You",
                    role = "user",
                    round = 1,
                    mode = CouncilRoomMode.EXPLORE,
                    text = "This ordinary message has not been accepted as the question answer.",
                    createdAtMs = 2L,
                    status = CouncilMessageStatus.COMPLETED,
                ),
            ),
            createdAtMs = 1L,
        )
        val receivedAnswers = mutableListOf<Pair<String, String>>()
        showTimeline(room, onAnswer = { questionId, answer -> receivedAnswers += questionId to answer })
        compose.onNodeWithText(question.text).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        val editor = compose.onNode(hasSetTextAction())
        editor.performTextInput("  采用第二个方案  ")
        compose.onNodeWithText("→").performClick()
        compose.runOnIdle {
            assertEquals(listOf(question.id to "采用第二个方案"), receivedAnswers)
        }
        assertEquals("", editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun showTimeline(room: CouncilRoom, onAnswer: ((String, String) -> Unit)?) {
        val tokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalAmberTokens provides tokens,
                LocalChatTheme provides tokens.toChatTheme(),
            ) {
                MaterialTheme {
                    CouncilTimelineTab(room = room, onAnswer = onAnswer, modifier = Modifier.size(320.dp, 600.dp))
                }
            }
        }
    }
}
