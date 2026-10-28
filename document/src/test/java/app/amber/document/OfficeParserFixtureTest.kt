package app.amber.document

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfficeParserFixtureTest {
    @Test
    fun docxReadsParagraphListAndTable() {
        val file = zipFixture(
            "word/numbering.xml" to """
                <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum>
                  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
                </w:numbering>
            """.trimIndent(),
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                  <w:p><w:r><w:t>Paragraph</w:t></w:r></w:p>
                  <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Item</w:t></w:r></w:p>
                  <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Head</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                </w:body></w:document>
            """.trimIndent(),
        )

        val text = DocxParser.parse(file)

        assertTrue(text.contains("Paragraph"))
        assertTrue(text.contains("- Item"))
        assertTrue(text.contains("| Head | Value |"))
        assertEquals(text, DocxParser.extractText(file))
    }

    @Test
    fun docxReadsContentControlParagraphsAndTablesInOrderWithoutDuplicates() {
        val file = zipFixture(
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                  <w:p><w:r><w:t>Before</w:t></w:r></w:p>
                  <w:sdt><w:sdtPr><w:alias w:val="Template content"/></w:sdtPr><w:sdtContent>
                    <w:p><w:r><w:t>Controlled</w:t></w:r></w:p>
                    <w:sdt><w:sdtPr/><w:sdtContent>
                      <w:p><w:r><w:t>Nested</w:t></w:r></w:p>
                    </w:sdtContent></w:sdt>
                    <w:tbl><w:tr>
                      <w:tc><w:p><w:r><w:t>Header</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc>
                    </w:tr></w:tbl>
                  </w:sdtContent></w:sdt>
                  <w:p><w:r><w:t>After</w:t></w:r></w:p>
                </w:body></w:document>
            """.trimIndent(),
        )

        val text = DocxParser.parse(file)

        assertEquals(
            listOf("Before", "Controlled", "Nested", "Header", "Value", "After"),
            Regex("Before|Controlled|Nested|Header|Value|After").findAll(text).map { it.value }.toList(),
        )
        assertTrue(text, text.contains("| Header | Value |"))
    }

    @Test
    fun pptxUsesPresentationSlideOrderAndReadsTable() {
        val file = zipFixture(
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:sldIdLst><p:sldId id="256" r:id="rId1"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst>
                </p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="slides/slide2.xml" Type="slide"/>
                  <Relationship Id="rId2" Target="slides/slide1.xml" Type="slide"/>
                </Relationships>
            """.trimIndent(),
            "ppt/slides/slide1.xml" to """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>First</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>
            """.trimIndent(),
            "ppt/slides/slide2.xml" to """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Second</a:t></a:r><a:br/><a:r><a:t>Line</a:t></a:r></a:p></p:txBody></p:sp><p:graphicFrame><a:graphic><a:graphicData><a:tbl><a:tr><a:tc><a:txBody><a:p><a:r><a:t>Cell</a:t></a:r><a:br/><a:r><a:t>After</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame></p:spTree></p:cSld></p:sld>
            """.trimIndent(),
            "ppt/slides/_rels/slide2.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdNotes" Target="../notesSlides/notesSlide2.xml" Type="notesSlide"/></Relationships>
            """.trimIndent(),
            "ppt/notesSlides/notesSlide2.xml" to """
                <p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sp>
            """.trimIndent(),
        )

        val text = PptxParser.parse(file)

        assertTrue(text.indexOf("Second") < text.indexOf("First"))
        assertTrue(text, text.contains("| Cell\nAfter |"))
        assertTrue(text.contains("Second\nLine"))
        assertTrue(text.contains("Cell\nAfter"))
        assertTrue(text.contains("Second"))
        assertTrue(!text.contains("Speaker Notes"))
        assertEquals("Optional corrupt notes must not discard readable slides", text, PptxParser.extractText(file))
        assertTrue(PptxParser.parse(file, maxChars = 30).contains("[TRUNCATED: PPTX text exceeds 30 characters]"))
    }

    @Test
    fun pptxReportsWhenDeclaredSlidesAreMissing() {
        val file = zipFixture(
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId r:id="rId1"/></p:sldIdLst></p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="slides/missing.xml" Type="slide"/></Relationships>
            """.trimIndent(),
        )

        assertEquals("No readable slides in PPTX file", PptxParser.parse(file))
        assertEquals("", PptxParser.extractText(file))
    }

    @Test
    fun pptxUsesRelationshipNamespaceRegardlessOfAttributeOrder() {
        for (attributes in listOf("id=\"256\" r:id=\"rId1\"", "r:id=\"rId1\" id=\"256\"")) {
            val file = zipFixture(
                "ppt/presentation.xml" to """
                    <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId $attributes/></p:sldIdLst></p:presentation>
                """.trimIndent(),
                "ppt/_rels/presentation.xml.rels" to """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="slides/slide2.xml" Type="slide"/></Relationships>
                """.trimIndent(),
                "ppt/slides/slide1.xml" to slideXml("Unreferenced"),
                "ppt/slides/slide2.xml" to slideXml("Referenced"),
            )
            val text = PptxParser.parse(file)
            assertTrue(text, text.contains("Referenced"))
            assertTrue("Unreferenced slides must not be imported: $text", !text.contains("Unreferenced"))
        }
    }

    @Test
    fun pptxFallbackSortsSlideBasenamesNumerically() {
        val file = zipFixture(
            "ppt/slides/slide10.xml" to slideXml("Ten"),
            "ppt/slides/slide2.xml" to slideXml("Two"),
            "ppt/slides/slide1.xml" to slideXml("One"),
        )

        val text = PptxParser.parse(file)

        assertTrue(text, text.indexOf("One") < text.indexOf("Two"))
        assertTrue(text, text.indexOf("Two") < text.indexOf("Ten"))
    }

    @Test
    fun epubUsesSpineOrder() {
        val file = zipFixture(
            "META-INF/container.xml" to """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>
            """.trimIndent(),
            "OPS/package.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf"><manifest>
                  <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>
            """.trimIndent(),
            "OPS/a.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Alpha</p></body></html>",
            "OPS/b.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Beta</p></body></html>",
        )

        val text = EpubParser.parse(file)

        assertTrue(text.indexOf("Beta") < text.indexOf("Alpha"))
        assertEquals(text, EpubParser.extractText(file))
        val truncated = EpubParser.parse(file, maxChars = 5)
        assertTrue(truncated, truncated.contains("[TRUNCATED: EPUB text exceeds 5 characters]"))
    }

    @Test
    fun epubPreservesWhitespaceBetweenInlineElements() {
        val file = epubFixture("<p>Hello<span> </span>world <em>with</em> <strong>space</strong>.</p>")

        assertEquals("Hello world *with* **space**.", EpubParser.parse(file))
    }

    @Test
    fun epubPreservesXhtmlNamedAndXmlEntities() {
        val file = epubFixture(
            "<p>caf&eacute; &amp; &lt;tea&gt; &quot;quoted&quot; &apos;apostrophe&apos; tea&nbsp;time&hellip; &#20013;&#x6587;</p>",
        )

        assertEquals("café & <tea> \"quoted\" 'apostrophe' tea\u00a0time… 中文", EpubParser.parse(file))
        assertEquals(EpubParser.parse(file), EpubParser.extractText(file))
    }

    @Test
    fun epubIgnoresSourceIndentationBetweenBlocks() {
        val file = epubFixture(
            """

                  <h2>Heading</h2>
                  <p>First <em>line</em>.</p>
                  <p>Second.</p>

            """.trimIndent(),
        )

        assertEquals("## Heading\n\nFirst *line*.\n\nSecond.", EpubParser.parse(file))
        assertEquals(EpubParser.parse(file), EpubParser.extractText(file))
    }

    @Test
    fun strictReadersThrowForDamagedArchivesWhileLegacyCallsKeepTheirDiagnostics() {
        val file = File.createTempFile("damaged-office-", ".zip").apply {
            writeText("not an archive")
            deleteOnExit()
        }
        assertTrue(DocxParser.parse(file).startsWith("Error parsing DOCX file:"))
        assertTrue(PptxParser.parse(file).startsWith("Error parsing PPTX file:"))
        assertTrue(EpubParser.parse(file).startsWith("Error parsing EPUB file:"))
        assertThrows(Exception::class.java) { DocxParser.extractText(file) }
        assertThrows(Exception::class.java) { PptxParser.extractText(file) }
        assertThrows(Exception::class.java) { EpubParser.extractText(file) }
    }

    @Test
    fun strictDocxThrowsForBrokenBodyXmlAndLeavesLegacyDiagnosticUnchanged() {
        val file = zipFixture("word/document.xml" to "<document><body><p>")
        assertTrue(DocxParser.parse(file).startsWith("Error parsing document XML:"))
        assertThrows(Exception::class.java) { DocxParser.extractText(file) }
    }

    @Test
    fun strictPptxDoesNotTreatAnEmptySlideLabelAsDocumentText() {
        val file = zipFixture("ppt/slides/slide1.xml" to slideXml(""))
        assertEquals("## Slide 1", PptxParser.parse(file))
        assertEquals("", PptxParser.extractText(file))
    }

    @Test
    fun strictEpubEmptySpineHasNoBodyAndKeepsLegacyNoContentMessage() {
        val file = zipFixture(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>",
            "content.opf" to "<package><manifest/><spine/></package>",
        )
        assertEquals("No readable content found in EPUB file", EpubParser.parse(file))
        assertEquals("", EpubParser.extractText(file))
    }

    @Test
    fun strictDocxDoesNotTreatEmptyTableMarkupAsBody() {
        val file = zipFixture("word/document.xml" to """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:tbl><w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>
            </w:body></w:document>
        """.trimIndent())
        assertEquals("|  |  | \n| --- | --- |", DocxParser.parse(file))
        assertEquals("", DocxParser.extractText(file))
    }

    @Test
    fun strictPptxDoesNotTreatEmptyTableMarkupAsBody() {
        val file = zipFixture("ppt/slides/slide1.xml" to """
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree>
              <p:graphicFrame><a:graphic><a:graphicData><a:tbl><a:tr>
                <a:tc><a:txBody><a:p/></a:txBody></a:tc><a:tc><a:txBody><a:p/></a:txBody></a:tc>
              </a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>
            </p:spTree></p:cSld></p:sld>
        """.trimIndent())
        assertEquals("## Slide 1\n\n|  |  | \n| --- | --- |", PptxParser.parse(file))
        assertEquals("", PptxParser.extractText(file))
    }

    @Test
    fun strictEpubDoesNotTreatEmptyHeadingsOrListItemsAsBody() {
        for ((body, legacy) in listOf("<h1/>" to "#", "<ul><li/></ul>" to "-")) {
            val file = epubFixture(body)
            assertEquals(legacy, EpubParser.parse(file))
            assertEquals("", EpubParser.extractText(file))
        }
        for ((body, text) in listOf(
            "<p>#</p>" to "#",
            "<p>-</p>" to "-",
            "<img alt=\"A diagram\"/>" to "[image: A diagram]",
        )) {
            assertEquals(text, EpubParser.extractText(epubFixture(body)))
        }
    }

    private fun epubFixture(body: String): File = zipFixture(
        "META-INF/container.xml" to """
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
        """.trimIndent(),
        "OPS/package.opf" to """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="book-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Fixture</dc:title><dc:language>en</dc:language><dc:identifier id="book-id">fixture</dc:identifier></metadata>
              <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="chapter"/></spine>
            </package>
        """.trimIndent(),
        "OPS/chapter.xhtml" to (
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            """.trimIndent() + "\n" +
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixture</title></head><body>$body</body></html>"""
        ),
    )

    private fun slideXml(text: String): String = """
        <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>
    """.trimIndent()

    private fun zipFixture(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("office-parser-fixture-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }
}
