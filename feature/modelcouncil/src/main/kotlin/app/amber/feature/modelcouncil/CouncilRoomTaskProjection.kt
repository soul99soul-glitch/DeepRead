package app.amber.feature.modelcouncil

import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskQueueState

/** Task-panel projection only; registration, callbacks and updates remain owned by the manager. */
internal fun CouncilRoom.toTaskSnapshot(): AgentTaskSnapshot = AgentTaskSnapshot(
    taskId = "council_room:${conversationId}",
    type = "council_room",
    title = "${mode.name.lowercase()} · ${objective.take(48)}",
    status = status.toTaskStatus(),
    queueState = status.toTaskStatus().toQueueState(),
    sourceToolName = "council_room_open",
    sourceConversationId = conversationId.toString(),
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    cancelCapability = status.running,
    summary = objective.take(1_000),
)

private fun CouncilRoomStatus.toTaskStatus(): AgentTaskStatus = when (this) {
    CouncilRoomStatus.IDLE -> AgentTaskStatus.QUEUED
    CouncilRoomStatus.EXPLORING,
    CouncilRoomStatus.DEBATING,
    CouncilRoomStatus.FINALIZING -> AgentTaskStatus.RUNNING
    CouncilRoomStatus.FINALIZED -> AgentTaskStatus.COMPLETED
    CouncilRoomStatus.CANCELLED -> AgentTaskStatus.CANCELLED
    CouncilRoomStatus.FAILED -> AgentTaskStatus.FAILED
    CouncilRoomStatus.INTERRUPTED -> AgentTaskStatus.INTERRUPTED
}

private fun AgentTaskStatus.toQueueState(): AgentTaskQueueState = when (this) {
    AgentTaskStatus.QUEUED -> AgentTaskQueueState.QUEUED
    AgentTaskStatus.RUNNING -> AgentTaskQueueState.ACTIVE
    AgentTaskStatus.COMPLETED,
    AgentTaskStatus.FAILED,
    AgentTaskStatus.CANCELLED,
    AgentTaskStatus.TIMED_OUT,
    AgentTaskStatus.INTERRUPTED -> AgentTaskQueueState.TERMINAL
}
