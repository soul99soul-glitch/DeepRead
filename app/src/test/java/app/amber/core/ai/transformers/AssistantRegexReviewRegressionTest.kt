package app.amber.core.ai.transformers

import app.amber.agent.data.model.nativebridge.RegexNativeSwitch
import app.amber.core.model.AssistantAffectScope.USER
import app.amber.core.model.AssistantRegex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantRegexReviewRegressionTest {
    private lateinit var previousConfig: RegexNativeSwitch.Config

    @Before
    fun disableNativeLibraryForHostTests() {
        previousConfig = RegexNativeSwitch.config
        RegexNativeSwitch.config = RegexNativeSwitch.DisabledConfig
    }

    @After
    fun restoreNativeConfig() {
        RegexNativeSwitch.config = previousConfig
    }

    @Test
    fun editedPatternWithSameHashImmediatelyReplacesTheNewText() {
        val original = rule("Aa", "X")
        assertEquals("X BB", "Aa BB".replaceRegexes(listOf(original), USER))

        assertEquals(
            "Aa X",
            "Aa BB".replaceRegexes(listOf(original.copy(findRegex = "BB")), USER),
        )
    }

    @Test
    fun editedReplacementWithSameHashImmediatelyUsesTheNewValue() {
        val original = rule("hello", "Aa")
        assertEquals("Aa", "hello".replaceRegexes(listOf(original), USER))

        assertEquals(
            "BB",
            "hello".replaceRegexes(listOf(original.copy(replaceString = "BB")), USER),
        )
    }

    @Test
    fun numericCaptureFollowedByTextKeepsJavaReplacementMeaning() {
        assertEquals(
            "foobar",
            "foo".replaceRegexes(listOf(rule("(foo)", "${'$'}1bar")), USER, false) { _, _, _, _ ->
                // Rust regex expands the longest group name, `1bar`, to empty.
                ""
            },
        )
    }

    @Test
    fun backslashReplacementKeepsJavaEscaping() {
        assertEquals(
            "x",
            "foo".replaceRegexes(listOf(rule("foo", "\\x")), USER, false) { _, _, _, _ -> "\\x" },
        )
    }

    @Test
    fun invalidCaptureReferenceSkipsOnlyItsRule() {
        val rules = listOf(rule("foo", "${'$'}99"), rule("bar", "better"))
        assertEquals(
            "foo better",
            "foo bar".replaceRegexes(rules, USER, false) { _, _, _, _ -> " better" },
        )
    }

    @Test
    fun plainReplacementStillUsesAvailableNativePipeline() {
        var nativeApplied = false
        val result = "hello".replaceRegexes(listOf(rule("hello", "hi")), USER, false) {
                _, _, _, _ ->
            nativeApplied = true
            "hi"
        }

        assertEquals("hi", result)
        assertTrue("plain native replacements remain enabled", nativeApplied)
    }

    @Test
    fun lookbehindStillUsesJavaAndRetainsTheRestOfTheBatch() {
        val result = "at now and later".replaceRegexes(
            listOf(rule("(?<=at )now", "once"), rule("later", "after")),
            USER,
            false,
        ) { _, _, _, _ -> error("Java-only lookbehind must stay on the JVM") }

        assertEquals("at once and after", result)
    }

    private fun rule(find: String, replacement: String) = AssistantRegex(
        id = Uuid.random(),
        findRegex = find,
        replaceString = replacement,
        affectingScope = setOf(USER),
    )
}
