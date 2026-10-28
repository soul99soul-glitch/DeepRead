package app.amber.feature.webmount

import app.amber.feature.webmount.primitives.WebMountBridgeBootstrap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebMountBridgeRefProtocolTest {
    @Test
    fun bridgeUsesStandardsCompliantCssRefs() {
        val source = WebMountBridgeBootstrap.load { assetPath ->
            File(locateBridge().parentFile, assetPath.removePrefix("webmount/")).readText()
        }

        assertTrue(source.contains(":nth-of-type("))
        assertFalse("bridge.js must not emit the old non-standard :nth(...) selector", source.contains(":nth("))
        assertTrue(source.contains("snapshot_id"))
        assertTrue(source.contains("fingerprint"))
        assertFalse("semantic_state must not call a missing cssPath helper", source.contains("cssPath("))
        assertTrue(source.contains("refs_shared: true"))
        assertTrue(source.contains("case 'restore_snapshot_refs'"))
        assertTrue(source.contains("case 'get'"))
        assertTrue(source.contains("allowFingerprintFallback: false"))
        assertTrue(source.contains("requireStableRect: true"))
        assertTrue(source.contains("sameFingerprint(fingerprintOf(entry.el), entry.fingerprint, requireStableRect)"))
        assertTrue(source.contains("sameFingerprint(fingerprintOf(el), entry.fingerprint, requireStableRect)"))
        assertTrue(source.contains("selector_fallback"))
        assertTrue(source.contains("case 'feishu_snapshot'"))
        assertTrue(source.contains("not_feishu_doc_page"))
        assertTrue(source.contains("current_webview_session"))
    }

    private fun locateBridge(): File {
        val candidates = listOf(
            File("src/main/assets/webmount/bridge.js"),
            File("app/src/main/assets/webmount/bridge.js"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate webmount bridge.js")
    }
}
