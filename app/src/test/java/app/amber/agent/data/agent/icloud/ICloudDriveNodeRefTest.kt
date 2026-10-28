package app.amber.feature.icloud

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICloudDriveNodeRefTest {
    @Test
    fun encodesAndDecodesOpaqueNodeRefs() {
        val node = ICloudDriveNode(
            name = "Daily.md",
            type = "file",
            drivewsid = "FILE::zone::daily",
            docwsid = "doc-1",
            zone = "com.apple.CloudDocs",
            etag = "etag-1",
            sizeBytes = 42L,
            raw = buildJsonObject {},
        )

        val token = ICloudDriveNodeRefs.encode("Notes/Daily.md", node)
        val decoded = ICloudDriveNodeRefs.decode(token)

        assertTrue(token.startsWith("icn_"))
        assertNotNull(decoded)
        assertEquals("Notes/Daily.md", decoded!!.path)
        assertEquals("FILE::zone::daily", decoded.drivewsId)
        assertEquals("doc-1", decoded.docwsId)
        assertEquals("etag-1", decoded.etag)
    }

    @Test
    fun rejectsInvalidNodeRefs() {
        assertNull(ICloudDriveNodeRefs.decode(null))
        assertNull(ICloudDriveNodeRefs.decode(""))
        assertNull(ICloudDriveNodeRefs.decode("not-a-node-ref"))
        assertNull(ICloudDriveNodeRefs.decode("icn_not-base64"))
    }
}
