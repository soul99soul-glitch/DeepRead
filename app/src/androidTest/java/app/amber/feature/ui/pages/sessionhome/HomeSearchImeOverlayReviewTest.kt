package app.amber.feature.ui.pages.sessionhome

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.agent.LAUNCH_START_MODE_PREF
import app.amber.agent.LaunchStartMode
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSearchImeOverlayReviewTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun landscapeKeyboardMustNotCoverSearchClearWithNewConversation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val old = prefs.getString(LAUNCH_START_MODE_PREF, null)
        prefs.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).commit()
        try {
            ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription(context.getString(R.string.history_page_search)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription(context.getString(R.string.history_page_search)).performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("OVERLAY REVIEW NO RESULTS")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(10_000) {
                    var ready = false
                    scenario.onActivity {
                        val view = it.window.decorView
                        ready = view.width > view.height &&
                            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    ready
                }
                android.os.SystemClock.sleep(1_000) // Android IME animation is outside Compose's idling clock.
                compose.waitForIdle()
                val newConversation = hasClickAction() and hasAnyDescendant(
                    hasText(context.getString(R.string.history_page_new_conversation)),
                )
                assertFalse(
                    "Hide the new-conversation button while the IME can move it over search controls",
                    compose.onAllNodes(newConversation, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                )
                compose.onNodeWithContentDescription(context.getString(R.string.parity_home_search_clear))
                    .assertIsDisplayed()
                    .performClick()
                compose.onNodeWithContentDescription(context.getString(R.string.parity_home_search_cancel))
                    .assertIsDisplayed()
            }
        } finally {
            prefs.edit().apply { if (old == null) remove(LAUNCH_START_MODE_PREF) else putString(LAUNCH_START_MODE_PREF, old) }.commit()
        }
    }
}
