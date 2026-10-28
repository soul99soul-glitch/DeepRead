package app.amber.core.settings

import app.amber.core.agent.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机自控的应用白名单语义。
 *
 * 白名单是 screen_run_goal 唯一可以进入的应用集合，必须跨任务持久；空集合
 * 表示 no_scope（未授权任何应用），绝不是"全部允许"。这里锁住默认值、
 * 命中判断和旧设置的兼容，不测工具执行本身。
 */
class ScreenAutomationAllowListTest {

    @Test
    fun defaultIsNoScopeNotAllowAll() {
        val setting = ScreenAutomationSetting()
        assertEquals(emptyList<String>(), setting.allowedPackages)
        assertFalse(setting.allows("com.example.anything"))
    }

    @Test
    fun allowsExactPackageOnly() {
        val setting = ScreenAutomationSetting(allowedPackages = listOf("tv.danmaku.bili"))
        assertTrue(setting.allows("tv.danmaku.bili"))
        assertFalse("prefix match would widen the scope", setting.allows("tv.danmaku.bili.evil"))
        assertFalse(setting.allows("TV.DANMAKU.BILI"))
    }

    /** 旧版本持久化的 Settings 没有 screenAutomation 字段，解码后必须是 no_scope。 */
    @Test
    fun legacySettingsWithoutFieldDecodeToNoScope() {
        val legacy = """{"agentRuntime":{"maxToolLoopSteps":256}}"""
        val runtime = JsonInstant.decodeFromString(
            AgentRuntimeSetting.serializer(),
            legacy,
        )
        assertEquals(emptyList<String>(), runtime.screenAutomation.allowedPackages)
        assertFalse(runtime.screenAutomation.allows("com.example.app"))
    }

    @Test
    fun allowListSurvivesRoundTrip() {
        val original = AgentRuntimeSetting(
            screenAutomation = ScreenAutomationSetting(
                allowedPackages = listOf("tv.danmaku.bili", "com.tencent.mm"),
            ),
        )
        val decoded = JsonInstant.decodeFromString(
            AgentRuntimeSetting.serializer(),
            JsonInstant.encodeToString(AgentRuntimeSetting.serializer(), original),
        )
        assertEquals(original.screenAutomation.allowedPackages, decoded.screenAutomation.allowedPackages)
        assertTrue(decoded.screenAutomation.allows("com.tencent.mm"))
    }
}
