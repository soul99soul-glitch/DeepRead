package app.amber.feature.ui.pages.setting

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.agent.R
import app.amber.core.model.AssistantMemory
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryEditorDialogLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun failedSaveKeepsDraftAndOffersRetryWithoutDismissing() {
        val draft = AssistantMemory(id = 0, content = "draft kept after a write failure")
        val failure = MemoryMutationState.Failed(MemoryMutationOperation.CREATE, draft, "disk unavailable")
        var saveCalls = 0
        var dismissCalls = 0
        // Render a deterministic dialog frame, including the in-flight indicator.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MemoryDialogTheme {
                MemoryEditorDialog(
                    memory = draft,
                    mutation = failure,
                    onChange = {},
                    onSave = { saveCalls++ },
                    onDismiss = { dismissCalls++ },
                )
            }
        }

        compose.mainClock.advanceTimeBy(32)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        capture("07-memory-failed-save")
        memoryField(draft.content).assertExists()
        compose.onNodeWithText(context.getString(R.string.setting_agent_memory_write_error, failure.message))
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.common_save)).assertIsEnabled().performClick()
        memoryField(draft.content).assertExists()
        assertEquals(1, saveCalls)
        assertEquals(0, dismissCalls)
    }

    @Test
    fun savingDisablesEditingSaveAndCancel() {
        val draft = AssistantMemory(id = 0, content = "in-flight draft")
        // Render a deterministic dialog frame, including the in-flight indicator.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MemoryDialogTheme {
                MemoryEditorDialog(
                    memory = draft,
                    mutation = MemoryMutationState.Saving(draft, MemoryMutationOperation.CREATE),
                    onChange = {},
                    onSave = {},
                    onDismiss = {},
                )
            }
        }

        compose.mainClock.advanceTimeBy(32)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        capture("08-memory-saving-disabled")
        memoryField(draft.content).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.common_save)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cancel)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.setting_agent_memory_saving)).assertExists()
    }

    private fun memoryField(content: String) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(content)),
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Dialog window entrance uses the Android frame clock, outside Compose's test clock.
        SystemClock.sleep(300)
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("modularization-phase-1"))
        check(directory.exists() || directory.mkdirs())
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Composable
    private fun MemoryDialogTheme(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A)),
        ) {
            MaterialTheme(content = content)
        }
    }
}
