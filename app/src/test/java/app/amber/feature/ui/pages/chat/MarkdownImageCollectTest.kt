package app.amber.feature.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 产物架从正文里抽图片的规则。
 *
 * 用户实际报的问题是"产物架里没有这几张图"：模型把图片写成 `![说明](url)`，
 * 而不是 Image part，所以采集必须看得懂这种写法；同时不能把代码块里的
 * 示例语法当成真图片。
 */
class MarkdownImageCollectTest {

    @Test
    fun collectsInlineMarkdownImages() {
        val images = collectMarkdownImages(
            "这是宋太祖：\n![赵匡胤画像](https://example.com/a.jpg)\n还有一张 ![b](https://example.com/b.png)",
        )
        assertEquals(
            listOf("https://example.com/a.jpg", "https://example.com/b.png"),
            images.map { it.url },
        )
        assertEquals("赵匡胤画像", images.first().alt)
    }

    @Test
    fun ignoresFencedCodeBlocks() {
        val text = """
            看下面这段示例（不是真图）：
            ```markdown
            ![示例](https://example.com/placeholder.png)
            ```
            真图在最后 ![真图](https://example.com/real.png)
        """.trimIndent()
        assertEquals(listOf("https://example.com/real.png"), collectMarkdownImages(text).map { it.url })
    }

    @Test
    fun keepsImagesOutsideCodeAfterFenceCloses() {
        val text = "```\n![a](https://x/1.png)\n```\n![b](https://x/2.png)\n~~~\n![c](https://x/3.png)\n~~~"
        assertEquals(listOf("https://x/2.png"), collectMarkdownImages(text).map { it.url })
    }

    @Test
    fun dropsEmptyUrlsAndIncompleteSyntax() {
        assertEquals(emptyList<MarkdownImage>(), collectMarkdownImages("![](  )"))
        assertEquals(emptyList<MarkdownImage>(), collectMarkdownImages("![alt](https://x/a.png"))
        assertEquals(emptyList<MarkdownImage>(), collectMarkdownImages("没有图片的一段话"))
    }

    @Test
    fun stripsOptionalTitleFromUrl() {
        val images = collectMarkdownImages("""![](https://example.com/a.png "标题")""")
        assertEquals(listOf("https://example.com/a.png"), images.map { it.url })
    }

    @Test
    fun keepsReadingOrderAcrossOneLine() {
        val images = collectMarkdownImages("![1](https://x/1.png) ![2](https://x/2.png) ![3](https://x/3.png)")
        assertEquals(listOf("https://x/1.png", "https://x/2.png", "https://x/3.png"), images.map { it.url })
        assertTrue(collectMarkdownImages("").isEmpty())
    }
}
