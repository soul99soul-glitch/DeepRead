package app.amber.feature.live

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import app.amber.core.automation.LiveFillOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveDraftFillerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val setting = LiveModeSetting(sceneOverrides = mapOf("chat.target" to "chat", "second.target" to "chat"))
    private val state = LiveModeUiState(
        currentPackage = "chat.target", currentTitle = "Conversation",
        card = LiveModeCard(watching = "observation", suggestions = listOf("draft reply")),
    )

    @Test
    fun `existing text needs a fresh confirmation and the exact five second boundary writes once`() {
        var now = 10_000L
        val target = Target(existing = "unsent message")
        val filler = LiveDraftFiller(context, { target }, { now })
        assertEquals(LiveFillResult.NEEDS_CONFIRM, filler.fill(state, setting))
        assertTrue(target.writes.isEmpty())
        now += FILL_CONFIRM_WINDOW_MS
        assertEquals(LiveFillResult.FILLED, filler.fill(state, setting))
        assertEquals(listOf("chat.target" to "draft reply"), target.writes)
        assertEquals(listOf("capture", "read", "capture", "read", "verified-write"), target.calls)
        assertEquals(context.packageName, target.capturedOwnPackage)
        assertEquals(180, target.capturedMaxNodes)
    }

    @Test
    fun `expired confirmation and a changed draft or package never reuse overwrite consent`() {
        var now = 10_000L
        val target = Target(existing = "unsent message")
        val filler = LiveDraftFiller(context, { target }, { now })
        assertEquals(LiveFillResult.NEEDS_CONFIRM, filler.fill(state, setting))
        now += FILL_CONFIRM_WINDOW_MS + 1
        assertEquals(LiveFillResult.NEEDS_CONFIRM, filler.fill(state, setting))
        val changed = state.copy(card = LiveModeCard(suggestions = listOf("different reply")))
        assertEquals(LiveFillResult.NEEDS_CONFIRM, filler.fill(changed, setting))
        target.fresh = snapshot("second.target")
        assertEquals(LiveFillResult.NEEDS_CONFIRM, filler.fill(changed.copy(currentPackage = "second.target"), setting))
        assertTrue(target.writes.isEmpty())
    }

    @Test
    fun `fresh title mismatch copies without writing even while the tick still considers the card fresh`() {
        val target = Target().apply { fresh = snapshot().copy(title = "Another conversation") }
        val filler = LiveDraftFiller(context, { target })
        assertFalse(state.cardStale)
        assertEquals(LiveFillResult.COPIED, filler.fill(state, setting.copy(maxNodes = 999)))
        assertTrue(target.writes.isEmpty())
        assertEquals("draft reply", copiedText())
        assertEquals(260, target.capturedMaxNodes)
    }

    @Test
    fun `a readback mismatch copies and denies only that package for later attempts`() {
        val target = Target().apply { outcome = LiveFillOutcome.MISMATCH }
        val filler = LiveDraftFiller(context, { target })
        assertEquals(LiveFillResult.COPIED, filler.fill(state, setting))
        assertEquals("draft reply", copiedText())
        assertTrue(filler.isDenied("chat.target"))
        target.outcome = LiveFillOutcome.SUCCESS
        assertEquals(LiveFillResult.COPIED, filler.fill(state, setting))
        assertEquals(1, target.writes.size)
        target.fresh = snapshot("second.target")
        assertEquals(LiveFillResult.FILLED, filler.fill(state.copy(currentPackage = "second.target"), setting))
        assertEquals(2, target.writes.size)
        assertFalse(filler.isDenied("second.target"))
    }

    @Test
    fun `stale card copies with rejected status and never writes`() {
        val target = Target()
        val filler = LiveDraftFiller(context, { target })
        val stale = state.copy(cardSignature = "old-screen", lastSnapshotHash = "new-screen")
        assertEquals(LiveFillResult.REJECTED_STALE, filler.fill(stale, setting))
        assertTrue(target.writes.isEmpty())
        assertEquals("draft reply", copiedText())
    }

    @Test
    fun `missing service uses the same clipboard path and an empty card does no platform work`() {
        var lookups = 0
        val filler = LiveDraftFiller(context, { lookups++; null })
        assertEquals(LiveFillResult.NO_DRAFT, filler.fill(state.copy(card = LiveModeCard()), setting))
        assertEquals(0, lookups)
        assertEquals(LiveFillResult.COPIED, filler.fill(state.copy(card = LiveModeCard(watching = "fallback draft")), setting))
        assertEquals(1, lookups)
        assertEquals("fallback draft", copiedText())
    }

    @Test
    fun `missing target and unknown verified result copy without retries or a new deny entry`() {
        val target = Target(existing = null)
        val filler = LiveDraftFiller(context, { target })
        assertEquals(LiveFillResult.COPIED, filler.fill(state, setting))
        assertTrue(target.writes.isEmpty())
        target.existing = ""
        target.outcome = LiveFillOutcome.UNKNOWN
        assertEquals(LiveFillResult.COPIED, filler.fill(state, setting))
        assertEquals(1, target.writes.size)
        assertFalse(filler.isDenied("chat.target"))
        target.outcome = LiveFillOutcome.NOT_FOUND
        assertEquals(LiveFillResult.COPIED, filler.fill(state, setting))
        assertEquals(2, target.writes.size)
        assertFalse(filler.isDenied("chat.target"))
    }

    private fun copiedText(): String = context.getSystemService(ClipboardManager::class.java)
        .primaryClip!!.getItemAt(0).text.toString()

    private class Target(var existing: String? = "") : LiveFillTarget {
        var fresh: LiveScreenSnapshot? = snapshot()
        var outcome = LiveFillOutcome.SUCCESS
        val calls = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        var capturedOwnPackage = ""
        var capturedMaxNodes = 0
        override fun capture(ownPackageName: String, maxNodes: Int): LiveScreenSnapshot? {
            calls += "capture"
            capturedOwnPackage = ownPackageName
            capturedMaxNodes = maxNodes
            return fresh
        }
        override fun readText(packageName: String): String? {
            calls += "read"
            return existing
        }
        override fun setTextVerified(packageName: String, text: String): LiveFillOutcome {
            calls += "verified-write"
            writes += packageName to text
            return outcome
        }
    }

    private companion object {
        fun snapshot(packageName: String = "chat.target") = LiveScreenSnapshot(
            packageName = packageName, appLabel = "Chat", title = "Conversation",
            uiTree = "screen tree", visibleText = "visible conversation", nodeCount = 2,
        )
    }
}
