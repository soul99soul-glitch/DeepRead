package app.amber.core.jev

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 截图回执的可诊断性：开了截图却一直走纯文字，必须在回执里看得出来，
 * 而不是只能靠猜。这里锁住四种结局的措辞。
 */
class ScreenshotAttemptTest {

    private fun asset() = JevImageAsset(
        reference = "<<SCREEN:1>>",
        format = "jpeg",
        data = byteArrayOf(9),
        widthPx = 800,
        heightPx = 1000,
    )

    @Test
    fun attachedIsReportedAsAttached() {
        assertEquals(
            "attached",
            describeImageAttempt(true, true, ScreenshotAttempt.Attached(asset())),
        )
    }

    @Test
    fun skippedCarriesTheReason() {
        assertEquals(
            "skipped:no_capture_session",
            describeImageAttempt(true, true, ScreenshotAttempt.Skipped("no_capture_session")),
        )
        assertEquals(
            "skipped:image_edge_out_of_range",
            describeImageAttempt(true, true, ScreenshotAttempt.Skipped("image_edge_out_of_range")),
        )
    }

    @Test
    fun scopeOffIsDistinctFromNotConfigured() {
        assertEquals("scope_off", describeImageAttempt(true, false, null))
        assertEquals("not_configured", describeImageAttempt(false, false, null))
    }

    @Test
    fun unansweredAttemptIsNotClaimedAsAttached() {
        assertEquals("skipped:unknown", describeImageAttempt(true, true, null))
    }
}
