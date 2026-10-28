package app.amber.feature.subagent

import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.infra.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Runner/payload resource lifecycle for one turn; thread settlement stays with SubAgentManager. */
internal class SubAgentTurnLauncher(
    private val agentRunner: AgentRunner,
    private val turnPayloads: SubAgentTurnPayloads,
    private val appScope: AppScope,
) {
    fun launch(
        requestedRunId: AgentRunId,
        input: SubAgentTurnInput,
        payload: SubAgentTurnPayloads.Payload,
    ): AgentRunId? {
        turnPayloads.register(requestedRunId.value, payload)
        val handle = agentRunner.launch(
            SubAgentTurnDescriptor.ID,
            input,
            requestedRunId = requestedRunId,
        ).getOrElse {
            turnPayloads.remove(requestedRunId.value)
            return null
        }
        return handle.runId
    }

    /** Called after Manager publishes the run id, so timeout/cancel sees the active turn. */
    fun observe(
        turnRunId: AgentRunId,
        timeoutMs: Long,
        onTimeout: suspend (TimeoutCancellationException) -> Unit,
        onSnapshot: suspend (AgentRunSnapshot) -> Unit,
    ): Job = appScope.launch(Dispatchers.IO) {
        try {
            val snapshot = try {
                withTimeout(timeoutMs) {
                    agentRunner.observe(turnRunId).first { it.status.isTerminal || it.status.isPause }
                }
            } catch (timeout: TimeoutCancellationException) {
                onTimeout(timeout)
                return@launch
            }
            onSnapshot(snapshot)
        } finally {
            turnPayloads.remove(turnRunId.value)
        }
    }
}
