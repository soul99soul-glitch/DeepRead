package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h720dp-xxhdpi")
class SettingSegmentedChoiceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectingAnOptionMovesSelectionWithoutRepeatingTheCurrentChoice() {
        val options = listOf("Always", "Auto", "Off")
        val selected = mutableStateOf("Always")
        val choices = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SettingSegmentedChoice(
                    options = options,
                    selected = selected.value,
                    onSelected = { choices.add(it); selected.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(it) },
                )
            }
        }

        compose.onNodeWithText("Always").assertIsSelected()
        compose.onNodeWithText("Off").assertIsNotSelected()
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithText("Off").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Off"), choices)
            assertEquals("Off", selected.value)
        }
        compose.onNodeWithText("Off").assertIsSelected()
        compose.onNodeWithText("Always").assertIsNotSelected()
        compose.onNodeWithText("Auto").assertIsNotSelected()
    }

    @Test
    fun rapidOptionChangesKeepLatestSelection() {
        val speeds = listOf("0.5×", "1×", "1.5×", "2×")
        val selected = mutableStateOf(1)
        val choices = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                SettingSegmentedChoice(
                    options = speeds,
                    selected = speeds[selected.value],
                    onSelected = { label ->
                        val index = speeds.indexOf(label)
                        choices.add(index)
                        selected.value = index
                    },
                    equalWeight = false,
                    label = { Text(it) },
                )
            }
        }

        compose.onNodeWithText("1×").assertIsSelected()
        compose.onNodeWithText("1.5×").performClick()
        compose.onNodeWithText("2×").performClick()
        compose.onNodeWithText("0.5×").performClick()
        compose.runOnIdle {
            assertEquals(listOf(2, 3, 0), choices)
            assertEquals(0, selected.value)
        }
        compose.onNodeWithText("0.5×").assertIsSelected()
        compose.onNodeWithText("1×").assertIsNotSelected()
        compose.onNodeWithText("2×").assertIsNotSelected()
    }
}
