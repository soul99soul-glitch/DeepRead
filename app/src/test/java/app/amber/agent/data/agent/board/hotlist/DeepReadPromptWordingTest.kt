package app.amber.feature.board.hotlist

import app.amber.ai.core.Tool
import app.amber.feature.tools.DeepReadToolDescriptionContext
import app.amber.feature.tools.withDeepReadDescriptionContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadPromptWordingTest {
    @Test
    fun deepReadToolDescriptionsCarryStageBudgetWithoutChangingGlobalSearchText() {
        val context = DeepReadToolDescriptionContext(
            stageLabel = "深度分析",
            writerToolName = "deep_read_write_analysis",
            stageTimeoutSeconds = 150,
        )
        val writer = testTool("deep_read_write_analysis")
            .withDeepReadDescriptionContext(context)
        val search = testTool("search_web")
            .withDeepReadDescriptionContext(context)
        val scrape = testTool("scrape_web")
            .withDeepReadDescriptionContext(context)

        assertTrue(writer.description.contains("本段（深度分析）预算约 150 秒"))
        assertTrue(writer.description.contains("优先调用 deep_read_write_analysis"))
        assertTrue(search.description.contains("本段预算约 150 秒"))
        assertTrue(search.description.contains("只为关键事实缺口"))
        assertTrue(scrape.description.contains("deep_read_write_analysis"))
        assertFalse(writer.description.contains("首个 writer tool 目标"))
        assertFalse(search.description.contains("首个 writer tool 目标"))
    }

    @Test
    fun deepReadToolDescriptionsDoNotMentionVerificationTool() {
        val context = DeepReadToolDescriptionContext(
            stageTimeoutSeconds = 60,
        )
        val search = testTool("search_web")
            .withDeepReadDescriptionContext(context)
        val writer = testTool("deep_read_write_overview")
            .withDeepReadDescriptionContext(context)

        assertFalse(search.description.contains("验真"))
        assertFalse(search.description.contains("deep_read_verify_claims"))
        assertFalse(writer.description.contains("deep_read_verify_claims"))
    }

    private fun testTool(name: String) = Tool(
        name = name,
        description = "base description",
        execute = { emptyList() },
    )
}
