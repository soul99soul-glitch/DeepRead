package app.amber.feature.ui.components.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.SubAgentRun
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.Navigator
import app.amber.review.ReflectionFixture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SubAgentCitationInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var projection: KoinApplication
    private var globalBefore: Koin? = null

    @Before
    fun registerReadOnlySubAgentProjection() {
        globalBefore = GlobalContext.getOrNull()
        // Exercise the actual sheet and Markdown click wiring, while avoiding unrelated
        // model execution/storage dependencies. These are the manager's real UI flows.
        val manager = ReflectionFixture.allocate<SubAgentManager>()
        ReflectionFixture.set(manager, "runs", ConcurrentHashMap<String, Any>())
        ReflectionFixture.set(manager, "runStateFlows", ConcurrentHashMap<String, MutableStateFlow<SubAgentRun?>>())
        ReflectionFixture.set(manager, "liveTextFlows", ConcurrentHashMap<String, MutableStateFlow<String>>().apply {
            put(RUN_ID, MutableStateFlow("Verified answer [citation,legacy-source](abc123)."))
        })
        ReflectionFixture.set(manager, "livePartsFlows", ConcurrentHashMap<String, MutableStateFlow<List<UIMessagePart>>>().apply {
            put(RUN_ID, MutableStateFlow(listOf(UIMessagePart.Tool(
                toolCallId = "search-call",
                toolName = "search_web",
                input = "{}",
                output = listOf(UIMessagePart.Text(
                    """{"items":[{"id":"abc123","title":"Verified source","url":"$SOURCE_URL","text":"source evidence"}]}""",
                )),
            ))))
        })
        projection = koinApplication { modules(module { single { manager } }) }
    }

    @After
    fun releaseProjection() {
        if (::projection.isInitialized) projection.close()
        assertSame("The citation fixture must preserve any existing global Koin application", globalBefore, GlobalContext.getOrNull())
    }

    @Test
    fun legacyCitationInsideTheActualSubAgentSheetOpensItsSearchResult() {
        val tool = UIMessagePart.Tool(
            toolCallId = "subagent-call",
            toolName = "subagent_start",
            input = """{"subagent_id":"citation-probe"}""",
            output = listOf(UIMessagePart.Text("""{"run_id":"$RUN_ID","status":"completed"}""")),
        )
        compose.setContent {
            KoinIsolatedContext(projection) {
                CompositionLocalProvider(
                    LocalSettings provides Settings(),
                    LocalNavController provides Navigator(mutableListOf()),
                ) {
                    MaterialTheme {
                        SubAgentTaskStepView(ThinkingStep.SubAgentTaskStep(RUN_ID, listOf(tool)), loading = false)
                    }
                }
            }
        }

        compose.onNodeWithText("@citation-probe", substring = true).performClick()
        compose.onNodeWithText("legacy-source", useUnmergedTree = true).performClick()

        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("The visible citation must launch its actual search result", opened)
        assertEquals(SOURCE_URL, opened.dataString)
    }

    private companion object {
        const val RUN_ID = "citation-ui-fixture"
        const val SOURCE_URL = "https://example.com/subagent-source"
    }
}
