package app.amber.feature.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨对话活动提示的三条规则：跳过当前对话、同一对话只留一条、标题拿不到时回落。
 *
 * 只投影"运行中"：失败/完成的终态需要持久化水位，不在本类职责内。
 */
class ConversationActivityCenterTest {

    private fun registryWith(vararg runs: Triple<String, String, Job>): RunOwnershipRegistry {
        val registry = RunOwnershipRegistry()
        runs.forEach { (assistantId, conversationId, job) ->
            registry.register(assistantId, conversationId, "run-$conversationId", job)
        }
        return registry
    }

    @Test
    fun registryProjectionTracksRegisterAndUnregister() = runTest {
        val registry = RunOwnershipRegistry()
        val jobA = Job()
        assertTrue(registry.activeRuns.value.isEmpty())
        registry.register("a", "conv-1", "run-1", jobA)
        assertEquals(listOf("conv-1"), registry.activeRuns.value.map { it.conversationId })
        registry.unregister("run-1")
        assertTrue(registry.activeRuns.value.isEmpty())
    }

    @Test
    fun refusedRegistrationDoesNotPublishAnotherOwner() = runTest {
        val registry = RunOwnershipRegistry()
        registry.register("a", "conv-1", "run-1", Job())
        // 同一 runId 不能被另一个对话接管
        registry.register("a", "conv-2", "run-1", Job())
        assertEquals(listOf("conv-1"), registry.activeRuns.value.map { it.conversationId })
    }

    @Test
    fun mismatchedCancelLeavesTheProjectionUntouched() = runTest {
        val registry = registryWith(Triple("a", "conv-1", Job()))
        assertEquals(false, registry.cancel("run-conv-1", "conv-other"))
        assertEquals(listOf("conv-1"), registry.activeRuns.value.map { it.conversationId })
    }

    @Test
    fun noticesSkipTheCurrentConversation() = runTest {
        val registry = registryWith(
            Triple("a", "conv-current", Job()),
            Triple("a", "conv-other", Job()),
        )
        val center = ConversationActivityCenter(registry) { id -> "标题-$id" }
        val notices = center.notices(excluding = "conv-current").first()
        assertEquals(listOf("conv-other"), notices.map { it.conversationId })
        assertEquals("标题-conv-other", notices.single().title)
        assertEquals(ConversationActivityKind.RUNNING, notices.single().kind)
    }

    @Test
    fun oneNoticePerConversationKeepsTheEarliestRun() = runTest {
        val registry = RunOwnershipRegistry()
        registry.register("a", "conv-1", "run-old", Job())
        // 两次注册必须跨毫秒，否则"最早 vs 最晚"在毫秒精度下无从区分，断言会退化成恒真。
        Thread.sleep(5)
        registry.register("a", "conv-1", "run-retry", Job())
        val center = ConversationActivityCenter(registry) { null }
        val notices = center.notices(excluding = null).first()
        assertEquals(1, notices.size)
        // 同一对话只留最早注册的一条，避免重复条目
        assertEquals(
            registry.activeRuns.value.minOf { it.registeredAtMs },
            notices.single().sinceMs,
        )
    }

    @Test
    fun missingOrBlankTitleFallsBackToIdPrefix() = runTest {
        val registry = registryWith(Triple("a", "0123456789abcdef", Job()))
        val nullTitle = ConversationActivityCenter(registry) { null }.notices(excluding = null).first()
        assertEquals("01234567", nullTitle.single().title)

        val blankTitle = ConversationActivityCenter(registry) { "  " }.notices(excluding = null).first()
        assertEquals("01234567", blankTitle.single().title)
    }

    @Test
    fun throwingTitleProviderDoesNotBreakTheNotice() = runTest {
        val registry = registryWith(Triple("a", "conv-1", Job()))
        val center = ConversationActivityCenter(registry) { error("db gone") }
        val notices = center.notices(excluding = null).first()
        assertEquals("conv-1", notices.single().title)
    }
}
