package app.amber.feature.ui.components.message

import app.amber.agent.data.model.nativebridge.RegexNativeSwitch
import app.amber.core.model.AssistantAffectScope.ASSISTANT
import app.amber.core.model.AssistantRegex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class MessageRenderRegexCacheTest {
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
    fun editingSameHashVisualPatternUpdatesAlreadyRenderedText() {
        val original = rule("Aa", "X")
        assertEquals("X BB", MessageRenderCache.visualRegexText("Aa BB", listOf(original), ASSISTANT))

        assertEquals(
            "Aa X",
            MessageRenderCache.visualRegexText("Aa BB", listOf(original.copy(findRegex = "BB")), ASSISTANT),
        )
    }

    @Test
    fun editingSameHashVisualReplacementUpdatesAlreadyRenderedText() {
        val original = rule("hello", "Aa")
        assertEquals("Aa", MessageRenderCache.visualRegexText("hello", listOf(original), ASSISTANT))

        assertEquals(
            "BB",
            MessageRenderCache.visualRegexText("hello", listOf(original.copy(replaceString = "BB")), ASSISTANT),
        )
    }

    private fun rule(find: String, replacement: String) = AssistantRegex(
        id = Uuid.random(),
        findRegex = find,
        replaceString = replacement,
        affectingScope = setOf(ASSISTANT),
        visualOnly = true,
    )
}
