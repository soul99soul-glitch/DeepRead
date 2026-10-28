package app.amber.feature.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test

class StreamingRenderPerformanceTest {
    private val style = MarkdownSingleTextStyle(16.sp, List(6) { 12.sp }, Color.Blue,
        Color.Blue, Color.Gray, Color.Gray, null, 12.sp, Color.Gray, Color.Gray)

    @Test fun mergedAppendAndRevealPreserveStyledUnicodeAndCopyText() {
        val prefix = buildAnnotatedString {
            append("已完成 👩‍💻 é ")
            addStyle(SpanStyle(color = Color.Blue), 0, 3)
            addStringAnnotation("url", "https://example.com", 0, 3)
        }
        val suffix = "新字 🇨🇳 **tail**"
        val former = applyStreamingWindowReveal(buildAnnotatedString {
            append(prefix); append(suffix)
        }, 12, Color.Black)
        assertEquals(former, buildStreamingDisplayAnnotated(prefix, suffix, 12, Color.Black))
        assertEquals(prefix.text + suffix, former.text)
        assertEquals(prefix.getStringAnnotations(0, prefix.length), former.getStringAnnotations(0, prefix.length))
    }

    @Test fun revealCodepointsRemainWholeAcrossEveryPrefixSuffixBoundary() {
        val text = "中文 👩‍💻 é 🇨🇳 end"
        for (split in 0..text.length) {
            for (window in listOf(0, 1, 2, 4, 48)) {
                val expected = applyStreamingWindowReveal(AnnotatedString(text), window, Color.Black)
                val actual = buildStreamingDisplayAnnotated(AnnotatedString(text.take(split)),
                    text.drop(split), window, Color.Black)
                assertEquals("split=$split window=$window", expected, actual)
            }
        }
    }

    @Test fun pacingScanBoundKeepsExactSpeedDecisionForLargeUnicodeBacklogs() {
        val input = "中文 👩‍💻 é mixed-word ".repeat(3000)
        val exact = countStreamingReleaseUnits(input, 0, input.length)
        val bounded = countStreamingReleaseUnits(input, 0, input.length, STREAMING_PACE_SATURATED_UNITS)
        assertEquals(STREAMING_PACE_SATURATED_UNITS, bounded)
        for (draining in listOf(false, true)) {
            assertEquals(streamingTargetUnitsPerSecond(exact, draining),
                streamingTargetUnitsPerSecond(bounded, draining))
        }
    }

    @Test fun laterReferenceDefinitionConservativelyInvalidatesEarlierBlocks() {
        val cache = StreamingTextBlockCache()
        val first = parseMarkdownUncached("See [late][ref]\n\nunchanged paragraph")
        mdNodeToAnnotatedString(first.preprocessed, first.tree, style, imageFallbackLabel = "Image", blockCache = cache)
        val mappedBefore = cache.mappedBlocks
        val final = parseMarkdownUncached(first.preprocessed + "\n\n[ref]: https://example.com/final")
        val actual = mdNodeToAnnotatedString(final.preprocessed, final.tree, style, imageFallbackLabel = "Image", blockCache = cache)
        val expected = mdNodeToAnnotatedString(final.preprocessed, final.tree, style, imageFallbackLabel = "Image")
        assertEquals(expected.text, actual.text)
        assertEquals(expected.spanStyles, actual.spanStyles)
        assertTrue("reference definitions disable reuse of earlier spans", cache.mappedBlocks > mappedBefore + 1)
    }

    @Test fun stableBlockMappingMatchesFreshMappingThroughStructuralChanges() {
        val source = "# 标题\n\n中文 **强调** 👩‍💻 é\n\n" +
            "```kotlin\nfun x() = 1\n```\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n结尾"
        val cache = StreamingTextBlockCache()
        for (length in 1..source.length) {
            val parsed = parseMarkdownUncached(source.take(length))
            val expected = mdNodeToAnnotatedString(parsed.preprocessed, parsed.tree, style, imageFallbackLabel = "Image")
            val actual = mdNodeToAnnotatedString(parsed.preprocessed, parsed.tree, style,
                imageFallbackLabel = "Image", blockCache = cache)
            assertEquals("prefix $length", expected, actual)
        }
        val before = cache.mappedBlocks
        val parsed = parseMarkdownUncached(source)
        mdNodeToAnnotatedString(parsed.preprocessed, parsed.tree, style, imageFallbackLabel = "Image", blockCache = cache)
        assertEquals("identical parse must reuse semantic block spans", before, cache.mappedBlocks)
        // A replacement, not an append, must never keep stale spans or text.
        val replacement = parseMarkdownUncached("[changed](https://example.com/new)\n\n全新答案")
        val fresh = mdNodeToAnnotatedString(replacement.preprocessed, replacement.tree, style, imageFallbackLabel = "Image")
        val cached = mdNodeToAnnotatedString(replacement.preprocessed, replacement.tree, style, imageFallbackLabel = "Image", blockCache = cache)
        assertEquals(fresh.text, cached.text)
        assertEquals(fresh.spanStyles, cached.spanStyles)
        fun links(text: AnnotatedString) = text.getLinkAnnotations(0, text.length).map {
            Triple(it.start, it.end, (it.item as LinkAnnotation.Clickable).tag)
        }
        assertEquals(links(fresh), links(cached))
    }
}
