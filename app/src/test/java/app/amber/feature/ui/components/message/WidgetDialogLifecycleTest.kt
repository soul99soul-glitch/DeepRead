package app.amber.feature.ui.components.message

import android.app.Application
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import app.amber.core.ai.generative.GenerativeWidgetSegment
import app.amber.core.settings.GenerativeUiSetting
import app.amber.feature.ui.context.LocalToaster
import com.dokar.sonner.rememberToasterState
import java.util.IdentityHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowWebView

/** Counts destruction on the production dialog/runtime, without replacing their factories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [WidgetDialogLifecycleTest.CountingWebView::class])
class WidgetDialogLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun clearBefore() = CountingWebView.destroyCounts.clear()

    @After
    fun clearAfter() = CountingWebView.destroyCounts.clear()

    @Test
    fun `closing expanded widget destroys its actual webview exactly once`() {
        val visible = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                MaterialTheme {
                    if (visible.value) {
                        ExpandedGenerativeWidgetDialog(
                            widget = GenerativeWidgetSegment.Widget("Lifecycle", HTML, complete = true),
                            html = HTML,
                            setting = GenerativeUiSetting(),
                            onDismissRequest = { visible.value = false },
                        )
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(CountingWebView.destroyCounts.isEmpty()) }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle {
            assertEquals("The real dialog must have owned one WebView", 1, CountingWebView.destroyCounts.size)
            assertEquals("Only its concrete runtime may destroy the borrowed WebView", listOf(1), CountingWebView.destroyCounts.values.toList())
        }
    }

    @Implements(WebView::class)
    class CountingWebView : ShadowWebView() {
        @RealObject lateinit var actualWebView: WebView

        @Implementation
        protected override fun destroy() {
            destroyCounts[actualWebView] = (destroyCounts[actualWebView] ?: 0) + 1
            super.destroy()
        }

        companion object {
            val destroyCounts = IdentityHashMap<WebView, Int>()
        }
    }

    private companion object {
        const val HTML = "<svg viewBox=\"0 0 100 100\"><rect width=\"100\" height=\"100\" fill=\"#123456\"/></svg>"
    }
}
