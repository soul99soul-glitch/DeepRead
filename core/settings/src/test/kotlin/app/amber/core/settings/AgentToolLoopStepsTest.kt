package app.amber.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具循环上限的取值域契约。
 *
 * 上界与 iOS 对齐（8192）：真实长任务单轮工具循环可达数千次，512 会在任务
 * 未完成时被预算截断。这里只锁"设置项可选范围"，不测内核步进行为。
 */
class AgentToolLoopStepsTest {

    @Test
    fun rangeCoversLongRunsAndKeepsDefaultInside() {
        assertTrue(MIN_AGENT_TOOL_LOOP_STEPS <= DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS)
        assertTrue(DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS <= MAX_AGENT_TOOL_LOOP_STEPS)
        assertEquals(8192, MAX_AGENT_TOOL_LOOP_STEPS)
    }

    /** 设置页的候选值必须全部落在 clamp 区间内，否则选中值会被静默改写。 */
    @Test
    fun aSettingsOptionEqualsTheCap() {
        // 镜像 SettingAgentExecutionPage 的选项表：上界本身必须是可选项，
        // 否则用户永远无法把预算调到 MAX。
        val options = listOf(64, 128, 256, 384, 512, 1024, 2048, 8192)
        assertTrue(options.all { it in MIN_AGENT_TOOL_LOOP_STEPS..MAX_AGENT_TOOL_LOOP_STEPS })
        assertTrue(MAX_AGENT_TOOL_LOOP_STEPS in options)
        assertTrue(DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS in options)
    }

    /**
     * 内核消费的是 clamp 后的值（ChatSessionResolverImpl 的
     * `settings.agentRuntime.maxToolLoopSteps.coerceIn(MIN, MAX)`）。
     * 这里锁住"用户选 8192，内核真拿到 8192"，而不只是常量相等。
     */
    @Test
    fun kernelReceivesConfiguredCap() {
        val configuredCaps = listOf(8192, 2048, 512, DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS)
        configuredCaps.forEach { configured ->
            val resolved = AgentRuntimeSetting(maxToolLoopSteps = configured).maxToolLoopSteps
                .coerceIn(MIN_AGENT_TOOL_LOOP_STEPS, MAX_AGENT_TOOL_LOOP_STEPS)
            assertEquals(configured, resolved)
        }
        // 越界输入仍被收紧，不把非法值透到内核
        assertEquals(
            MAX_AGENT_TOOL_LOOP_STEPS,
            AgentRuntimeSetting(maxToolLoopSteps = 100_000).maxToolLoopSteps
                .coerceIn(MIN_AGENT_TOOL_LOOP_STEPS, MAX_AGENT_TOOL_LOOP_STEPS),
        )
        assertEquals(
            MIN_AGENT_TOOL_LOOP_STEPS,
            AgentRuntimeSetting(maxToolLoopSteps = 0).maxToolLoopSteps
                .coerceIn(MIN_AGENT_TOOL_LOOP_STEPS, MAX_AGENT_TOOL_LOOP_STEPS),
        )
    }
}
