package app.amber.feature.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebViewOperationStoreTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun staleCallbacksFromOldLoadCannotOverwriteCurrentPage() {
        val store = WebViewOperationStore()
        val firstLoadId = store.open("https://a.example")
        val secondLoadId = store.open("https://b.example")

        store.updateLoading(firstLoadId, "https://a.example", 100)
        store.updateReadablePage(
            loadId = firstLoadId,
            url = "https://a.example",
            title = "A",
            readableText = "old page",
            links = listOf(WebViewLink("old", "https://a.example/old")),
        )

        val afterStaleCallback = store.state.value
        assertEquals(secondLoadId, afterStaleCallback.loadId)
        assertEquals("https://b.example", afterStaleCallback.requestedUrl)
        assertEquals("", afterStaleCallback.title)
        assertEquals("", afterStaleCallback.readableText)

        store.updateReadablePage(
            loadId = secondLoadId,
            url = "https://b.example",
            title = "B",
            readableText = "new page",
            links = listOf(WebViewLink("new", "https://b.example/new")),
        )

        val current = store.state.value
        assertEquals("B", current.title)
        assertEquals("new page", current.readableText)
        assertEquals(WebViewLoadStatus.INTERACTIVE, current.status)
    }

    @Test
    fun readablePageBecomesInteractiveBeforeFinished() {
        val store = WebViewOperationStore()
        val loadId = store.open("https://example.com")

        store.updateLoading(loadId, "https://example.com", 45)
        store.updateReadablePage(
            loadId = loadId,
            url = "https://example.com",
            title = "Example",
            readableText = "hello webview",
            links = emptyList(),
        )

        val state = store.state.value
        assertEquals(WebViewLoadStatus.INTERACTIVE, state.status)
        assertEquals("Example", state.title)
        assertEquals("hello webview", state.readableText)
    }

    @Test
    fun finishedReadablePageBecomesReady() {
        val store = WebViewOperationStore()
        val loadId = store.open("https://example.com")

        store.updateReadablePage(
            loadId = loadId,
            url = "https://example.com",
            title = "Example",
            readableText = "hello webview",
            links = emptyList(),
        )
        store.markPageFinished(loadId, "https://example.com")

        val state = store.state.value
        assertEquals(100, state.loadingProgress)
        assertEquals(WebViewLoadStatus.READY, state.status)
        assertTrue(state.hasReadableContent)
    }

    @Test
    fun loadingWithoutProgressBecomesStalled() {
        val store = WebViewOperationStore()
        val loadId = store.open("https://slow.example")
        val openedAt = store.state.value.lastProgressAtEpochMillis

        store.updateLoading(loadId, "https://slow.example", 10)
        val progressAt = store.state.value.lastProgressAtEpochMillis
        val state = store.refreshStalled(progressAt + 12_001L)

        assertTrue(progressAt >= openedAt)
        assertEquals(WebViewLoadStatus.STALLED, state.status)
        assertEquals("https://slow.example", state.displayUrl)
    }

    @Test
    fun disposingAnOldRendererCannotDeactivateTheNewLoad() {
        val store = WebViewOperationStore()
        val oldLoadId = store.open("https://old.example", toolCallId = "old-tool")
        store.markRendererActive(oldLoadId, true)
        val newLoadId = store.open("https://new.example", toolCallId = "new-tool")
        store.markRendererActive(newLoadId, true)

        store.markRendererActive(oldLoadId, false)
        store.markPageFinished(oldLoadId, "https://old.example/late")
        store.markFailed(oldLoadId, "https://old.example/late", "late failure")

        assertEquals(newLoadId, store.state.value.loadId)
        assertTrue(store.state.value.rendererActive)
        assertEquals("https://new.example", store.state.value.displayUrl)
        assertEquals(WebViewLoadStatus.REQUESTED, store.state.value.status)
        assertEquals("", store.state.value.lastError)
    }

    @Test
    fun completedThumbnailFromAnOldLoadCannotReplaceTheCurrentCapture() {
        val store = WebViewOperationStore()
        val oldLoadId = store.open("https://old.example")
        val oldThumbnail = files.newFile("old.png").apply { writeText("old capture") }
        val newLoadId = store.open("https://new.example")
        val newThumbnail = files.newFile("new.png").apply { writeText("current capture") }
        store.updateThumbnail(newLoadId, "https://new.example", newThumbnail.absolutePath)

        store.updateThumbnail(oldLoadId, "https://old.example", oldThumbnail.absolutePath)

        assertEquals(newLoadId, store.state.value.loadId)
        assertEquals("https://new.example", store.state.value.displayUrl)
        assertEquals(newThumbnail.absolutePath, store.state.value.thumbnailPath)
        assertEquals(newThumbnail.absolutePath, store.state.value.lastGoodThumbnailPath)
    }

    @Test
    fun reopeningThePreviousPreviewCreatesANewLoadInsteadOfReusingItsThumbnailSuccessor() {
        val store = WebViewOperationStore()
        val firstUrl = "https://a.example/page"
        val secondUrl = "https://b.example/page"
        val firstLoadId = store.open(firstUrl, toolCallId = "first-tool")
        val firstThumbnail = files.newFile("first-preview.png").apply { writeText("first capture") }
        store.updateThumbnail(firstLoadId, firstUrl, firstThumbnail.absolutePath)
        val secondLoadId = store.open(secondUrl, toolCallId = "second-tool")
        assertEquals(firstUrl, store.state.value.lastGoodPreviewUrl)
        assertEquals(firstThumbnail.absolutePath, store.state.value.lastGoodThumbnailPath)

        val reopenedLoadId = store.open(firstUrl, toolCallId = "reopened-first-tool")

        assertNotEquals("A previous thumbnail does not identify the active load", secondLoadId, reopenedLoadId)
        assertNotEquals(firstLoadId, reopenedLoadId)
        assertEquals(firstUrl, store.state.value.requestedUrl)
        assertEquals(firstUrl, store.state.value.url)
        assertEquals("reopened-first-tool", store.state.value.toolCallId)
    }
}
