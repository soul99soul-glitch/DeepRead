package app.amber.feature.ui.pages.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCompanionDeepLinkTest {
    @Test
    fun `a claimed request is not replayed when the page is recreated`() {
        LiveCompanionDeepLink.request()
        val first = LiveCompanionDeepLink.requests.value
        assertTrue(LiveCompanionDeepLink.claim(first))
        assertFalse(LiveCompanionDeepLink.claim(LiveCompanionDeepLink.requests.value))
        LiveCompanionDeepLink.request()
        assertTrue(LiveCompanionDeepLink.claim(LiveCompanionDeepLink.requests.value))
        assertFalse(LiveCompanionDeepLink.claim(first))
    }
}
