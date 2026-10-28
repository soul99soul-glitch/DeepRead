package app.amber.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File
import java.util.concurrent.CancellationException

object PdfParser {
    fun parserPdf(file: File, maxChars: Int = Int.MAX_VALUE): String = read(file, maxChars, strict = false)

    /** Returns page text without treating empty-page labels or open errors as document content. */
    fun extractText(file: File, maxChars: Int = Int.MAX_VALUE): String = read(file, maxChars, strict = true)

    private fun read(file: File, maxChars: Int, strict: Boolean): String {
        val document = try {
            PDFDocument.openDocument(file.absolutePath).asPDF()
        } catch (e: Exception) {
            if (strict || e is CancellationException) throw e
            return "Error parsing PDF file: ${e.message}"
        }
        try {
            val pages = document.countPages()
            val result = StringBuilder()
            for (i in 0 until pages) {
                val page = document.loadPage(i)
                try {
                    val text = page.toStructuredText()
                    try {
                        val pageText = text.asText()
                        if (strict && pageText.isBlank()) continue
                        result.append("---")
                        result.append("Page ${i + 1}:\n")
                        result.append(pageText)
                        result.appendLine()
                        if (result.length > maxChars) {
                            result.setLength(maxChars)
                            result.append("\n[TRUNCATED: PDF text exceeds $maxChars characters]")
                            break
                        }
                    } finally {
                        text.destroy()
                    }
                } finally {
                    page.destroy()
                }
            }
            return result.toString()
        } finally {
            document.destroy()
        }
    }
}
