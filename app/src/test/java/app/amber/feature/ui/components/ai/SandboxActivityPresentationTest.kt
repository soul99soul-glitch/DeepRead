package app.amber.feature.ui.components.ai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.feature.webview.WebViewOperationState
import app.amber.feature.webview.WebViewOperationStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SandboxActivityPresentationTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun previewMatchesTheCurrentToolOrANormalizedNavigationUrl() {
        val state = WebViewOperationState(
            loadId = "load-2",
            toolCallId = "tool-2",
            requestedUrl = "https://example.com/start/",
            committedUrl = "https://example.com/result/#section",
        )

        assertTrue(state.matchesPreview("tool-2", "https://different.example"))
        assertTrue(state.matchesPreview("other-tool", "https://example.com/start"))
        assertTrue(state.matchesPreview("", " https://example.com/result/#other ".normalizedWebPreviewUrl()))
        assertFalse(state.matchesPreview("tool-1", "https://different.example"))
        assertFalse(state.copy(loadId = "").matchesPreview("tool-2", "https://example.com/start"))
    }

    @Test
    fun previewUsesTheCurrentThumbnailOnlyWhenItBelongsToTheDisplayedOperation() {
        val current = files.newFile("current.png").apply { writeText("current image") }
        val previous = files.newFile("previous.png").apply { writeText("previous image") }
        val state = WebViewOperationState(
            thumbnailPath = current.absolutePath,
            lastGoodThumbnailPath = previous.absolutePath,
        )

        assertEquals(current, state.bestThumbnailFile(isCurrentPreview = true))
        assertEquals(previous, state.bestThumbnailFile(isCurrentPreview = false))
        current.writeText("")
        assertEquals(previous, state.bestThumbnailFile(isCurrentPreview = true))
        assertNull(state.copy(lastGoodThumbnailPath = File(files.root, "missing.png").absolutePath).bestThumbnailFile(true))
    }

    @Test
    fun previousThumbnailRemainsAvailableWithoutClaimingItsUrlIsTheCurrentPreview() {
        val store = WebViewOperationStore()
        val previousUrl = "https://a.example/page"
        val previousLoadId = store.open(previousUrl, toolCallId = "previous-tool")
        val previousThumbnail = files.newFile("previous-preview.png").apply { writeText("previous capture") }
        store.updateThumbnail(previousLoadId, previousUrl, previousThumbnail.absolutePath)
        val currentUrl = "https://b.example/page"
        val currentLoadId = store.open(currentUrl, toolCallId = "current-tool")
        val current = store.state.value

        assertEquals(currentLoadId, current.loadId)
        assertEquals(currentUrl, current.requestedUrl)
        assertEquals(previousUrl, current.lastGoodPreviewUrl)
        assertEquals(previousThumbnail, current.bestThumbnailFile(isCurrentPreview = false))
        assertFalse(current.matchesPreview("previous-tool", "$previousUrl/#history".normalizedWebPreviewUrl()))
        assertTrue(current.matchesPreview("current-tool", currentUrl))
    }

    @Test
    fun webPreviewUsesDirectInputBeforeOutputAndEncodesSearchQueries() {
        val web = activity("search_web", input = "Read https://input.example/page", output = "https://output.example")

        assertEquals("https://input.example/page", web.operationPreviewUrl())
        assertEquals("https://output.example", web.copy(inputPreview = "no URL").operationPreviewUrl())
        assertEquals("https://www.google.com/search?q=two+words", web.copy(inputPreview = "two words", outputTail = "").operationPreviewUrl())
        assertNull(web.copy(toolName = "terminal_execute").operationPreviewUrl())
    }

    @Test
    fun transientAndTerminalPreviewsKeepTheirDistinctKindsAndTextLimits() {
        assertEquals("webview", activity("webview_read").operationPreviewKind())
        assertEquals("workspace", activity("file_read").operationPreviewKind())
        assertEquals("runtime", activity("terminal_execute").operationPreviewKind())
        assertEquals("hello world…", "  hello\n world and more  ".compactForSandbox(12))
        assertEquals("example.com", "https://www.example.com/page".webHostPreview())
    }

    @Test
    fun blankCaptureDetectionRejectsTransparentAndWhiteFramesButKeepsDarkContent() {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            assertTrue(bitmap.looksBlank())
            bitmap.eraseColor(Color.WHITE)
            assertTrue(bitmap.looksBlank())
            bitmap.eraseColor(Color.BLACK)
            assertFalse(bitmap.looksBlank())
        } finally {
            bitmap.recycle()
        }
    }

    private fun activity(tool: String, input: String = "", output: String = "") = SandboxActivityUiState(
        toolCallId = "tool-2",
        toolName = tool,
        title = "Tool operation",
        status = ToolActivityStatus.RUNNING,
        inputPreview = input,
        outputTail = output,
    )
}
