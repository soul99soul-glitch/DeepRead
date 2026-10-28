package app.amber.feature.subagent

import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskOutputRef
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.running
import app.amber.feature.task.toQueueState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

internal fun SubAgentRun.toAgentTaskSnapshot() = AgentTaskSnapshot(
    taskId = runId,
    type = "subagent",
    title = definition.name,
    sourceConversationId = parentConversationId.toString(),
    status = status.toAgentTaskStatus(),
    queueState = status.toAgentTaskStatus().toQueueState("subagent"),
    outputPath = transcriptPath,
    outputRef = AgentTaskOutputRef(
        type = "transcript",
        path = transcriptPath,
        exists = File(transcriptPath).exists(),
    ),
    retryPolicy = AgentTaskRetryPolicy(
        retryable = status == SubAgentRunStatus.FAILED,
        requiresApproval = false,
        maxRetries = 1,
        reason = "Sub Agent retry starts a new isolated run from the original task spec.",
    ),
    sourceToolName = "subagent_start",
    // A thread followup deliberately retains its original startedAtMs, but the task-store
    // row represents this generation's activation so UI observers can distinguish it.
    createdAtMs = updatedAtMs,
    updatedAtMs = updatedAtMs,
    cancelCapability = status.running,
    summary = task.objective.take(1_000),
)

internal fun SubAgentRunStatus.toAgentTaskStatus(): AgentTaskStatus = when (this) {
    SubAgentRunStatus.RUNNING,
    SubAgentRunStatus.APPROVAL_REQUIRED -> AgentTaskStatus.RUNNING
    SubAgentRunStatus.COMPLETED -> AgentTaskStatus.COMPLETED
    SubAgentRunStatus.FAILED -> AgentTaskStatus.FAILED
    SubAgentRunStatus.CANCELLED -> AgentTaskStatus.CANCELLED
    SubAgentRunStatus.TIMED_OUT -> AgentTaskStatus.TIMED_OUT
    SubAgentRunStatus.INTERRUPTED -> AgentTaskStatus.INTERRUPTED
}

fun subAgentRunToPayload(
    run: SubAgentRun,
    json: Json,
    includeDisplayText: Boolean = false,
): JsonObject = buildJsonObject {
    put("status", run.status.name.lowercase())
    put("run_id", run.runId)
    put("subagent_id", run.definition.id)
    put("subagent_name", run.definition.name)
    put("dynamic", run.definition.dynamic)
    put("task_objective", run.task.objective.take(1_000))
    put("started_at_ms", run.startedAtMs)
    put("updated_at_ms", run.updatedAtMs)
    run.task.sessionGrantId.takeIf { it.isNotBlank() }?.let { put("session_grant_id", it) }
    run.result?.let { put("result", json.encodeToString(it)) }
    if (run.displayText.isNotBlank()) {
        put("display_text_chars", run.displayText.length)
        if (includeDisplayText) put("display_text", run.displayText)
    }
}
