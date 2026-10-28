package app.amber.feature.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAppStreamingCodePreviewTest {
    @Test
    fun `incrementally decodes html field across arbitrary chunk boundaries`() {
        val preview = MiniAppStreamingCodePreview()
        val full = """{"title":"时钟","description":"演示","html":"<!DOCTYPE html>\n<html>\n<body>\u00b7 Hello \"Amber\"</body>\n</html>"}"""
        val stepSizes = listOf(3, 5, 2, 7, 1, 4, 11)
        var cursor = 0
        var stepIdx = 0
        while (cursor < full.length) {
            cursor = (cursor + stepSizes[stepIdx % stepSizes.size]).coerceAtMost(full.length)
            preview.update(full.substring(0, cursor))
            stepIdx++
        }
        assertEquals(
            "<!DOCTYPE html>\n<html>\n<body>· Hello \"Amber\"</body>\n</html>",
            preview.displayText(),
        )
    }

    @Test
    fun `resets cleanly when stream snapshot is replaced rather than appended`() {
        val preview = MiniAppStreamingCodePreview()
        preview.update("""{"title":"旧版","html":"<div>Old</div>"}""")
        assertEquals("<div>Old</div>", preview.displayText())

        preview.update("""{"title":"新版","html":"<main>New</main>"}""")
        assertEquals("<main>New</main>", preview.displayText())
    }

    @Test
    fun `caps displayed tail window at limit and prepends omitted character count`() {
        val preview = MiniAppStreamingCodePreview()
        val longBody = "A".repeat(2500)
        preview.update("""{"title":"长页面","html":"$longBody"}""")
        val text = preview.displayText(limit = 2000)
        assertTrue(text.startsWith("已省略 500 字\n"))
        assertEquals("已省略 500 字\n".length + 2000, text.length)
    }
}
