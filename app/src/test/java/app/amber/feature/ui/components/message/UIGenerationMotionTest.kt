package app.amber.feature.ui.components.message

import app.amber.core.files.SkillFrontmatterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class UIGenerationMotionTest {
    @Before
    fun setUp() {
        ChatLiveEntranceRegistry.clearForTesting()
    }

    @Test
    fun `historical completed card never triggers generation reveal`() {
        val state = UIGenerationRevealState(key = "widget:history-1")
        assertFalse(state.update(isStreaming = false, complete = true))
        assertFalse(state.sawStreaming)
        assertFalse(state.consumed)
        assertFalse(ChatLiveEntranceRegistry.hasRevealed("widget:history-1"))
    }

    @Test
    fun `streamed card triggers reveal exactly once upon completion and blocks remount replay`() {
        val state = UIGenerationRevealState(key = "widget:live-1")
        assertFalse(state.update(isStreaming = true, complete = false))
        assertTrue(state.sawStreaming)
        assertFalse(state.consumed)

        assertTrue(state.update(isStreaming = false, complete = true))
        assertTrue(state.consumed)
        assertTrue(ChatLiveEntranceRegistry.hasRevealed("widget:live-1"))

        // Subsequent updates on same state or a remounted state with same key must not replay.
        assertFalse(state.update(isStreaming = false, complete = true))
        val remounted = UIGenerationRevealState(key = "widget:live-1")
        assertFalse(remounted.update(isStreaming = true, complete = false))
        assertFalse(remounted.update(isStreaming = false, complete = true))
    }

    @Test
    fun `bundled UI generation assets and amber-ui-design skill are present and valid`() {
        val assetsRoot = File("src/main/assets")
        val css = File(assetsRoot, "generative-libs/ui-generation.css")
        val runtime = File(assetsRoot, "generative-libs/ui-generation.js")
        val host = File(assetsRoot, "generative-libs/amber-ui-generation.js")
        val license = File(assetsRoot, "generative-libs/transitions-ui-generation-LICENSE.txt")
        val skill = File(assetsRoot, "builtin-skills/amber-ui-design/SKILL.md")

        assertTrue(css.isFile)
        assertTrue(runtime.isFile)
        assertTrue(host.isFile)
        assertTrue(license.isFile)
        assertTrue(skill.isFile)

        val script = UIGenerationMotion.buildInstallScript(
            css = css.readText(),
            runtimeJs = runtime.readText(),
            hostJs = host.readText(),
        )
        assertFalse(script.contains("export function "))
        assertTrue(script.contains("window.__amberGenerationPlay"))
        assertTrue(script.contains("window.__amberGenerationCancel"))
        assertTrue(script.contains(".t-gen { overflow: clip; contain: layout paint; }"))

        val skillText = skill.readText()
        val frontmatter = SkillFrontmatterParser.parse(skillText)
        assertEquals("amber-ui-design", frontmatter["name"])
        assertFalse(SkillFrontmatterParser.isPlaceholderDescription(frontmatter["description"]))
        assertTrue(skillText.contains("data-amber-reveal"))
    }
}
