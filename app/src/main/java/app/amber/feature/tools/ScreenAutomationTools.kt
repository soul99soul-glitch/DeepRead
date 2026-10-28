package app.amber.feature.tools

import android.content.Context
import app.amber.ai.core.Tool
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.settings.prefs.SettingsAggregator

class ScreenAutomationTools(
    context: Context,
    screenCaptureManager: ScreenCaptureManager,
    activityStore: AgentToolActivityStore,
    jevScreenGoalRunner: JevScreenGoalRunner? = null,
    settingsStore: SettingsAggregator? = null,
) {
    private val operations = ScreenOperationOwner(activityStore)
    private val scope = ScreenAppScopeTools(context, settingsStore, jevScreenGoalRunner, operations)
    private val primitives = ScreenPrimitiveTools(context, screenCaptureManager, operations)
    private val flow = ScreenFlowTools(settingsStore, operations)
    private val collection = ScreenListCollection(operations)

    fun getTools(runId: String? = null): List<Tool> = listOf(
        scope.screenStatusTool(runId),
        scope.screenAppsTool,
        scope.screenAddAppTool(runId),
        scope.screenRemoveAppTool,
        scope.screenStopTool(runId),
        scope.screenGoalTool(runId),
        primitives.screenClickTool,
        primitives.screenLongClickTool,
        primitives.screenSwipeTool,
        primitives.screenInputTextTool,
        primitives.screenBackTool,
        primitives.screenHomeTool,
        primitives.screenOpenAppTool,
        primitives.screenOpenUrlTool,
        primitives.screenReadUiTool,
        primitives.screenFindTextTool,
        primitives.screenTapTextTool,
        primitives.screenWaitForTextTool,
        primitives.screenScrollUntilTool,
        flow.screenRunFlowTool(runId),
        collection.screenCollectListTool,
        primitives.screenScreenshotTool,
        primitives.vlmTaskTool,
    )
}
