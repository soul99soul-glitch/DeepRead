package app.amber.feature.ui.components.richtext

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class StreamingDisplayLifetimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retainedCompletedPresentationShowsEditsImmediatelyAndPacesTheNextGeneration() {
        var content by mutableStateOf("head")
        var streaming by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { Text(rememberStreamingDisplayText(content, streaming), Modifier.testTag("body")) }
        fun displayed() = compose.onNodeWithTag("body").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { content += " streamed".repeat(100) }
        compose.mainClock.advanceTimeBy(32)
        assertTrue("A live generation still uses the pacing buffer", displayed().length < content.length)
        compose.runOnIdle { streaming = false }
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(content, displayed())

        compose.runOnIdle { content += " edited".repeat(100) }
        // A drained renderer has no frame awaiter; flush the global snapshot
        // notification before advancing its newly requested composition frame.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        assertEquals("Editing a fully drained visible message must not retype it", content, displayed())
        compose.mainClock.advanceTimeBy(32)

        val completed = content
        compose.runOnIdle { streaming = true; content += " next".repeat(100) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        assertTrue(displayed().startsWith(completed))
        assertTrue("A subsequent generation gets a fresh pacing lifetime", displayed().length < content.length)
        compose.runOnIdle { streaming = false }
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(content, displayed())
    }
}
