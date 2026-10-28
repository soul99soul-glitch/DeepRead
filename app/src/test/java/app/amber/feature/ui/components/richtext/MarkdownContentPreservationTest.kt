package app.amber.feature.ui.components.richtext

import android.app.Application
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarkdownContentPreservationTest {
    private lateinit var previousConfig: MarkdownNativeSwitch.Config

    @Before
    fun useJvmPreprocessing() {
        previousConfig = MarkdownNativeSwitch.config
        MarkdownNativeSwitch.config = MarkdownNativeSwitch.DisabledConfig
    }

    @After
    fun restoreNativeConfig() {
        MarkdownNativeSwitch.config = previousConfig
    }

    @Test
    fun inlineFormulaBeforeCodeDoesNotRewriteTheCodeFormula() {
        assertEquals(
            "\$a\$ `\\[x\\]` then \$\$z\$\$",
            preProcessForTest("\\(a\\) `\\[x\\]` then \\[z\\]"),
        )
    }

    @Test
    fun htmlRendererAlsoPreservesCodeAfterAnInlineFormula() {
        val html = generateMarkdownHtmlForTest("<div>hello</div>\n\n\\(a\\) `\\[x\\]`")

        assertEquals("\\[x\\]", Jsoup.parse(html).selectFirst("code")?.wholeText())
    }

    @Test
    fun tildeFencePreservesDomainsAndBothFormulaDelimiters() {
        val code = "~~~text\nexample.com \\(a\\) \\[x\\]\n~~~"
        assertEquals(
            "[example.com](https://example.com) \$a\$\n\n$code\n\n[example.com](https://example.com) \$\$x\$\$",
            preProcessForTest("example.com \\(a\\)\n\n$code\n\nexample.com \\[x\\]"),
        )
    }

    @Test
    fun htmlRendererPreservesLatexInTildeCodeFence() {
        val code = "example.com \\(a\\) \\[x\\]"
        val html = generateMarkdownHtmlForTest("<div>hello</div>\n\n~~~text\n$code\n~~~")

        assertEquals("$code\n", Jsoup.parse(html).selectFirst("code")?.wholeText())
    }

    @Test
    fun longerTldsDoNotLinkToTheirRecognizedPrefix() {
        val input = "visit example.company and example.infox today"
        assertEquals(input, preProcessForTest(input))
    }

    @Test
    fun recognizedTldsAndPathsKeepPunctuationOutsideTheirLinks() {
        assertEquals(
            "visit [example.com](https://example.com), [example.info/path](https://example.info/path).",
            preProcessForTest("visit example.com, example.info/path."),
        )
    }
}
