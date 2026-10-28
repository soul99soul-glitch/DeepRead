package app.amber.feature.ui.components.message

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WidgetDeckArchiveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: ContextWrapper

    @Before
    fun setUp() {
        val cache = temporaryFolder.newFolder("cache")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getCacheDir(): File = cache
        }
    }

    @Test
    fun `slides archive preserves spec theme standalone runtime and explicit font fallback`() {
        val spec = """{"style":"swiss","accent":"#123456","fontPack":"source-han-sans-sc-regular","slides":[{"title":"A","content":["B"]}]}"""
        val archive = createSlidesDeckArchive(context, "A / Deck", spec, COVER)

        ZipFile(archive).use { zip ->
            assertEquals(
                setOf("manifest.json", "slides.json", "theme.json", "index.html", "README.md", "cover.svg"),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            val manifest = JSONObject(zip.text("manifest.json"))
            assertEquals("amberagent-slides", manifest.getString("type"))
            assertEquals("A / Deck", manifest.getString("title"))
            assertEquals(1, manifest.getInt("slide_count"))
            assertTrue(manifest.getLong("created_at_ms") > 0)
            val slides = JSONObject(zip.text("slides.json"))
            assertEquals("A", slides.getJSONArray("slides").getJSONObject(0).getString("title"))
            val theme = JSONObject(zip.text("theme.json"))
            assertEquals("swiss", theme.getString("style"))
            assertEquals("#123456", theme.getString("accent"))
            assertEquals("source-han-sans-sc-regular", theme.getString("fontPack"))
            assertEquals("not_included_large_cjk_fonts_use_system_fallback", theme.getString("fontEmbedding"))
            val html = zip.text("index.html")
            assertTrue(html.contains("<title>A / Deck</title>"))
            assertTrue(html.contains("window.__renderSlides && window.__renderSlides("))
            assertFalse(html.contains("{{AMBER_"))
            assertEquals(COVER, zip.text("cover.svg"))
            assertTrue(archive.name.startsWith("AmberAgent_A___Deck_"))
        }
    }

    @Test
    fun `full html archive rewrites only runtime resources and packages actual asset bytes`() {
        val html = """<html><body><div id="deck"><section class="slide"><h1>Deck</h1></section></div><script src="${GuizangHtmlDeckValidator.LOCAL_MOTION_URL}"></script><script src="${GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL}"></script></body></html>"""
        val spec = JSONObject().put("html", html).put("source", "fixture")
            .put("allowRemoteImages", false).put("allowRemoteFonts", false).toString()
        val archive = createGuizangDeckArchive(context, "Full deck", spec, COVER)

        ZipFile(archive).use { zip ->
            assertEquals(
                setOf("manifest.json", "index.html", "assets/motion.min.js", "assets/lucide.min.js", "README.md", "cover.svg"),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            val manifest = JSONObject(zip.text("manifest.json"))
            assertEquals("amberagent-full-html", manifest.getString("type"))
            assertEquals("fixture", manifest.getString("source"))
            assertFalse(manifest.getBoolean("allow_remote_images"))
            assertFalse(manifest.getBoolean("allow_remote_fonts"))
            val exported = zip.text("index.html")
            assertTrue(exported.contains("assets/motion.min.js"))
            assertTrue(exported.contains("assets/lucide.min.js"))
            assertFalse(exported.contains("https://amberagent.local"))
            assertArrayEquals(context.assets.open(GuizangHtmlDeckValidator.MOTION_ASSET_PATH).use { it.readBytes() }, zip.bytes("assets/motion.min.js"))
            assertArrayEquals(context.assets.open(GuizangHtmlDeckValidator.LUCIDE_ASSET_PATH).use { it.readBytes() }, zip.bytes("assets/lucide.min.js"))
            assertEquals(COVER, zip.text("cover.svg"))
        }
    }

    @Test
    fun `three asset is included only when referenced and a non svg cover is omitted`() {
        val html = """<html><body><div id="deck"><section class="slide">Three</section></div><script src="${GuizangHtmlDeckValidator.LOCAL_THREE_URL}"></script></body></html>"""
        val archive = createGuizangDeckArchive(context, "Three", JSONObject().put("html", html).toString(), "<div>not a cover</div>")

        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("assets/three.min.js" in entries)
            assertFalse("cover.svg" in entries)
            assertArrayEquals(context.assets.open(GuizangHtmlDeckValidator.THREE_ASSET_PATH).use { it.readBytes() }, zip.bytes("assets/three.min.js"))
            assertTrue(zip.text("index.html").contains("assets/three.min.js"))
        }
    }

    private fun ZipFile.bytes(name: String): ByteArray = getInputStream(getEntry(name)).use { it.readBytes() }
    private fun ZipFile.text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    private companion object {
        const val COVER = "<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>"
    }
}
