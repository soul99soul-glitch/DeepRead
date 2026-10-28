package app.amber.feature.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class StreamingTextChunksTest {
    @Test fun chunksReconstructEveryUnicodeCharacterAndExplicitBreak() {
        val source = (0 until 100).joinToString("\n") { "$it 中文 👩‍💻 é 🇨🇳 " + "body ".repeat(8) }
        val text = buildAnnotatedString {
            append(source)
            addStyle(SpanStyle(color = Color.Blue), 50, 3300)
            addStringAnnotation("link", "https://example.com", 80, 3400)
        }
        val chunks = splitStreamingTextChunks(text)
        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString("") { it.text.text + it.lineBreak.text })
        for (chunk in chunks) {
            assertEquals(text.subSequence(chunk.start, chunk.start + chunk.text.length), chunk.text)
            if (chunk.lineBreak.isNotEmpty()) {
                assertEquals("\n", source.substring(chunk.start + chunk.text.length, chunk.start + chunk.text.length + 1))
            }
        }
    }
    @Test fun activeFadeWindowDoesNotDirtyCompletedChunks() {
        val prefix = AnnotatedString((0 until 100).joinToString("\n") { "paragraph-$it " + "字".repeat(35) })
        val first = splitStreamingTextChunks(prefix)
        val second = splitStreamingTextChunks(AnnotatedString(prefix.text + "追加文字 👩‍💻"))
        first.dropLast(1).forEachIndexed { index, chunk -> assertEquals(chunk, second[index]) }
        assertTrue(first.last().text.length >= 96)
    }
    @Test fun longSingleParagraphAndCrLfKeepTheOriginalSingleText() {
        val noBreak = AnnotatedString("字".repeat(5000))
        assertEquals(1, splitStreamingTextChunks(noBreak).size)
        val crlf = AnnotatedString("paragraph\r\n".repeat(500))
        assertEquals(listOf(StreamingTextChunk(0, crlf)), splitStreamingTextChunks(crlf))
    }

    @Test fun incrementalPlannerMatchesReferenceSplitWhileTailGrows() {
        val source = (0 until 90).joinToString("\n") {
            "## 小节 $it\n\n中文 **重点** 👩‍💻 é and `code`."
        }
        val base = buildAnnotatedString {
            append(source)
            addStyle(SpanStyle(color = Color.Blue), 0, source.length)
        }
        val planner = StreamingChunkPlanner()
        val suffix = StringBuilder()
        repeat(48) { frame ->
            suffix.append(if (frame % 5 == 0) "\n\n新段落 $frame 中文。" else "追加 $frame 文字。")
            val full = source + suffix
            val expected = splitStreamingTextChunks(AnnotatedString(full))
            val actual = planner.chunks(base, suffix.toString(), 0, Color.Unspecified)
            assertEquals("frame $frame", expected.map { it.start }, actual.map { it.start })
            assertEquals("frame $frame", full, actual.joinToString("") { it.text.text + it.lineBreak.text })
            assertEquals(
                "frame $frame",
                expected.dropLast(1).map { it.text.text },
                actual.dropLast(1).map { it.text.text },
            )
        }
    }

    @Test fun committedChunksAreReusedAcrossTailFrames() {
        val source = (0 until 90).joinToString("\n") { "## $it\n\n" + "稳定正文 paragraph $it。".repeat(3) }
        val base = AnnotatedString(source)
        val planner = StreamingChunkPlanner()
        val first = planner.chunks(base, "a", 0, Color.Unspecified)
        val second = planner.chunks(base, "ab 👩‍💻", 0, Color.Unspecified)
        assertTrue(first.size > 1)
        assertEquals(first.size, second.size)
        first.dropLast(1).indices.forEach { index ->
            assertSame("chunk $index must be reused", first[index].text, second[index].text)
        }
        assertNotSame(first.last().text, second.last().text)
    }

    @Test fun revealWindowOnlyDirtyTheFinalChunk() {
        val source = (0 until 90).joinToString("\n") { "## $it\n\n" + "可读正文 $it。".repeat(3) }
        val base = AnnotatedString(source)
        val planner = StreamingChunkPlanner()
        val plain = planner.chunks(base, "", 0, Color.Red)
        val revealed = planner.chunks(base, "", 40, Color.Red)
        assertEquals(plain.dropLast(1).map { it.text }, revealed.dropLast(1).map { it.text })
        assertEquals(0, plain.last().text.spanStyles.size)
        assertTrue(revealed.last().text.spanStyles.isNotEmpty())
    }

    @Test fun newParseSnapshotRebuildsBoundariesAndStyles() {
        val source = (0 until 90).joinToString("\n") { "## $it\n\n" + "正文 $it。".repeat(4) }
        val planner = StreamingChunkPlanner()
        val first = planner.chunks(AnnotatedString(source), "", 0, Color.Unspecified)
        val restyled = buildAnnotatedString {
            append(source)
            addStyle(SpanStyle(color = Color.Red), 0, source.length)
        }
        val second = planner.chunks(restyled, "", 0, Color.Unspecified)
        assertEquals(splitStreamingTextChunks(restyled).map { it.text }, second.map { it.text })
        assertNotEquals(first.first().text, second.first().text)
        assertEquals(source, second.joinToString("") { it.text.text + it.lineBreak.text })
    }

    @Test fun workerPreparedChunksPreserveIdentityAndHandleNewTailBoundaries() {
        val source = (0 until 100).joinToString("\n") { "$it **中文** 👩‍💻 é " + "body ".repeat(8) }
        val base = AnnotatedString(source)
        val prepared = splitStreamingTextChunks(base)
        val reused = reuseStreamingTextChunks(prepared, splitStreamingTextChunks(AnnotatedString(source)))
        prepared.indices.forEach { assertSame(prepared[it], reused[it]) }
        val planner = StreamingChunkPlanner()
        val suffix = "\nnew tail 中文\n".repeat(130)
        val actual = planner.chunks(base, suffix, 0, Color.Red, reused)
        val expected = splitStreamingTextChunks(AnnotatedString(source + suffix))
        assertEquals(expected, actual)
        prepared.dropLast(1).indices.forEach { assertSame(prepared[it], actual[it]) }
        val changed = buildAnnotatedString { append(source); addStyle(SpanStyle(color = Color.Blue), 0, 100) }
        val restyled = reuseStreamingTextChunks(prepared, splitStreamingTextChunks(changed))
        assertNotSame(prepared.first(), restyled.first())
        assertEquals(splitStreamingTextChunks(changed), restyled)
    }

    @Test fun nonPrefixTailResetsInsteadOfReusingStaleChunks() {
        val source = (0 until 90).joinToString("\n") { "## $it\n\n" + "正文 $it。".repeat(3) }
        val base = AnnotatedString(source)
        val planner = StreamingChunkPlanner()
        val first = planner.chunks(base, "old tail", 0, Color.Unspecified)
        val rewritten = planner.chunks(base, "rewritten tail 中文", 0, Color.Unspecified)
        assertEquals(source + "rewritten tail 中文", rewritten.joinToString("") { it.text.text + it.lineBreak.text })
        assertNotEquals(first.size to first.last().text.text, rewritten.size to rewritten.last().text.text)
    }
}
