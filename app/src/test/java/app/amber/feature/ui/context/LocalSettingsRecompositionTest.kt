package app.amber.feature.ui.context

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.amber.core.settings.Settings
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalSettingsRecompositionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsPublicationUpdatesReadersWithoutRecomposingUnrelatedContent() {
        val settings = mutableStateOf(Settings.dummy())
        val unrelatedCompositions = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides settings.value) {
                Column {
                    DeviceLabel()
                    NavigationChrome { unrelatedCompositions.incrementAndGet() }
                }
            }
        }
        val before = compose.runOnIdle { unrelatedCompositions.get() }

        compose.runOnIdle {
            settings.value = settings.value.copy(
                syncSettings = settings.value.syncSettings.copy(deviceId = "updated-device"),
            )
        }

        compose.onNodeWithText("device:updated-device").assertExists()
        compose.runOnIdle { assertEquals(before, unrelatedCompositions.get()) }
    }
}

@Composable
private fun DeviceLabel() {
    Text("device:${LocalSettings.current.syncSettings.deviceId}")
}

@Composable
private fun NavigationChrome(onComposed: () -> Unit) {
    SideEffect(onComposed)
    Text("navigation chrome")
}
