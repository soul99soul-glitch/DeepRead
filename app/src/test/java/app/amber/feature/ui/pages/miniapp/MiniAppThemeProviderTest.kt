package app.amber.feature.ui.pages.miniapp

import android.app.Application
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import app.amber.feature.miniapp.bridge.MiniAppTheme
import app.amber.feature.ui.theme.LocalDarkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class MiniAppThemeProviderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hostDarkModeOverridesTheSystemPreferenceInTheBridgeReceipt() {
        val captured = AtomicReference<(() -> MiniAppTheme)?>(null)
        compose.setContent {
            val systemLight = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(
                LocalConfiguration provides systemLight,
                LocalDarkMode provides true,
            ) {
                MaterialTheme(colorScheme = darkColorScheme(
                    background = Color(0xFF112233),
                    onBackground = Color(0xFFDDEEFF),
                    primary = Color(0xFF88AACC),
                )) {
                    val provider = rememberMiniAppThemeProvider()
                    SideEffect { captured.compareAndSet(null, provider) }
                }
            }
        }

        compose.runOnIdle {
            assertEquals(
                MiniAppTheme(true, "#112233", "#ddeeff", "#88aacc"),
                requireNotNull(captured.get()).invoke(),
            )
        }
    }

    @Test
    fun theProviderRetainedByTheBridgeReadsTheCurrentThemeAfterRecomposition() {
        val captured = AtomicReference<(() -> MiniAppTheme)?>(null)
        val hostDark = mutableStateOf(false)
        val scheme = mutableStateOf(lightColorScheme(
            background = Color(0xFFF0E0D0),
            onBackground = Color(0xFF302010),
            primary = Color(0xFFAA6633),
        ))
        compose.setContent {
            CompositionLocalProvider(LocalDarkMode provides hostDark.value) {
                MaterialTheme(colorScheme = scheme.value) {
                    val provider = rememberMiniAppThemeProvider()
                    // AndroidView.factory installs its first callback in the native bridge.
                    SideEffect { captured.compareAndSet(null, provider) }
                }
            }
        }
        compose.runOnIdle {
            assertNotNull(captured.get())
            assertEquals("#f0e0d0", requireNotNull(captured.get()).invoke().background)
            hostDark.value = true
            scheme.value = darkColorScheme(
                background = Color(0xFF102030),
                onBackground = Color(0xFFE0D0C0),
                primary = Color(0xFF77AABB),
            )
        }

        compose.runOnIdle {
            assertEquals(
                MiniAppTheme(true, "#102030", "#e0d0c0", "#77aabb"),
                requireNotNull(captured.get()).invoke(),
            )
        }
    }
}
