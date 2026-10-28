package app.amber.feature.tools

import android.provider.Settings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.getActiveAccessibilityController
import kotlinx.coroutines.sync.Mutex

/** Owns one screen operation, run stop markers and the matching activity settlement. */
internal class ScreenOperationOwner(private val activityStore: AgentToolActivityStore) {
    private val screenOwner = Mutex()

    /**
     * 已由 screen_stop 主动结束的 run。按 run 键控：本工具是 Koin 单例，
     * 进程级布尔会把一次 stop 变成"App 级永久关闭屏幕自控"。
     */
    private val stoppedRuns: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun runKey(runId: String?): String? = runId?.takeIf { it.isNotBlank() }

    fun isStopped(runId: String?): Boolean = runKey(runId)?.let { it in stoppedRuns } ?: false

    val isActive: Boolean get() = screenOwner.isLocked

    fun stop(runId: String) { stoppedRuns += runId }

    suspend fun trackScreenTool(
        toolName: String,
        title: String,
        input: JsonElement,
        runtime: String = "Android Accessibility",
        runId: String? = null,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        // 只有能界定"本次 run"的入口才受 screen_stop 约束；低级手势由每次审批把关。
        check(!isStopped(runId)) {
            "Screen control was ended by screen_stop for this run; no further action is dispatched."
        }
        check(screenOwner.tryLock()) { "Another screen operation is active; wait for it to finish before observing again." }
        try {
            val toolCallId = activityStore.startTool(
                toolName = toolName,
                title = title,
                inputPreview = input.activityInputPreview(toolName),
                runtime = runtime,
            )
            return try {
                val result = block()
                activityStore.complete(toolCallId, result.previewText())
                result
            } catch (error: Throwable) {
                activityStore.fail(toolCallId, error)
                throw error
            }
        } finally {
            screenOwner.unlock()
        }
    }
    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)

    private fun JsonElement.activityInputPreview(toolName: String): String =
        when (toolName) {
            "screen_run_goal" -> buildJsonObject {
                put("package_name", string("package_name").orEmpty())
                put("goal_chars", string("goal")?.length ?: 0)
            }.toString()
            "screen_input_text" -> buildJsonObject {
                put("text_chars", string("text")?.length ?: 0)
            }.toString()
            else -> toString()
        }
}

internal fun requireScreenAccessibility(): AccessibilityController =
    getActiveAccessibilityController()
        ?: error("AmberAgent Accessibility is not enabled. Open ${Settings.ACTION_ACCESSIBILITY_SETTINGS} and enable AmberAgent Accessibility.")
