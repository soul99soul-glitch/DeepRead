package app.amber.core.ai.transformers

import android.content.ContextWrapper
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.Modality
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.MiniAppSetting
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TransformerSettingsSnapshotTest {
    @Test
    fun placeholderReadsTheTransformerSettingsSnapshot() {
        val value = DefaultPlaceholderProvider.placeholders
            .getValue("nickname")
            .resolver(
                PlaceholderCtx(
                    context = ContextWrapper(null),
                    userNickname = Settings(displaySetting = DisplaySetting(userNickname = "snapshot-user")).toTransformerSettings().userNickname,
                    model = Model(modelId = "test"),
                ),
            )

        assertEquals("snapshot-user", value)
    }

    @Test
    fun templateReadsTheTransformerSettingsSnapshot() = runBlocking {
        val output = TemplateTransformer().transform(
            ctx = TransformerContext(
                context = RuntimeEnvironment.getApplication(),
                model = Model(modelId = "test"),
                settings = Settings(messageTemplate = "[{{message}}]").toTransformerSettings(),
            ),
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("hello")),
                ),
            ),
        )

        assertEquals("[hello]", (output.single().parts.single() as UIMessagePart.Text).text)
    }
    @Test
    fun runSnapshotControlsTimeAndMiniAppGates() = runBlocking {
        val settings = Settings(agentRuntime = AgentRuntimeSetting(
            enableTimeReminder = true,
            miniApp = MiniAppSetting(enabled = false),
        ))
        val ctx = TransformerContext(
            context = RuntimeEnvironment.getApplication(),
            model = Model(modelId = "test"),
            settings = settings.toTransformerSettings(),
        )
        val messages = listOf(
            UIMessage.user("Earlier").copy(createdAt = LocalDateTime(2026, 10, 18, 8, 0)),
            UIMessage.user("Create a mini app").copy(createdAt = LocalDateTime(2026, 10, 18, 12, 0)),
        )
        val timed = TimeReminderTransformer.transform(ctx, messages)
        assertEquals(3, timed.size)
        assertTrue(timed[1].toText().contains("<time_reminder>"))
        // A disabled run snapshot must not resolve the MiniApp repository or inject instructions.
        assertEquals(messages, MiniAppPromptTransformer.transform(ctx, messages))
        val disabled = ctx.settings.copy(timeReminderEnabled = false)
        assertEquals(messages, TimeReminderTransformer.transform(
            TransformerContext(ctx.context, ctx.model, disabled), messages,
        ))
    }

    @Test
    fun missingOcrConfigurationIsCheckedOnlyWhenRecognitionIsNeeded() = runBlocking {
        val ctx = TransformerContext(
            context = RuntimeEnvironment.getApplication(),
            model = Model(modelId = "text-only", inputModalities = listOf(Modality.TEXT)),
            settings = Settings(providers = emptyList()).toTransformerSettings(),
        )
        val text = listOf(UIMessage.user("No image needs recognition"))
        assertEquals(text, OcrTransformer.transform(ctx, text))
        val image = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///image.png"))))
        val visionCtx = TransformerContext(ctx.context, ctx.model.copy(inputModalities = listOf(Modality.TEXT, Modality.IMAGE)), ctx.settings)
        assertEquals(image, OcrTransformer.transform(visionCtx, image))
        try {
            OcrTransformer.transform(ctx, image)
            fail("Text-only model must report missing OCR configuration when an image needs recognition")
        } catch (error: VisualRecognitionException) {
            assertEquals(OcrStrings.from(ctx.context).modelMissing, error.message)
            assertEquals(null, ctx.processingStatus.value)
        }
    }

}
