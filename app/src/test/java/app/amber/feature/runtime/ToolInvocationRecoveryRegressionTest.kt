package app.amber.feature.runtime

import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.ToolEffectClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInvocationRecoveryRegressionTest : DurableRuntimeTestBase() {
    @Test
    fun interruptedAppendHttpMutationAndMcpCallsRequireAnOutcomeDecision() = runBlocking {
        val dispatcher = AgentToolDispatcher(Json, PermissionDecisionResolver())
        val calls = listOf(
            "file_write" to """{"path":"notes/log.txt","content":"next","append":true}""",
            "http_request" to """{"url":"https://example.com/actions","method":"POST","body":"action"}""",
            "mcp__bank__transfer" to """{"amount":10}""",
        )
        calls.forEachIndexed { index, (name, input) ->
            val call = UIMessagePart.Tool(
                toolCallId = "interrupted_$index",
                toolName = name,
                input = input,
                approvalState = ToolApprovalState.Approved,
            )
            val result = runCatching {
                dispatcher.execute(
                    tool = call,
                    toolDef = Tool(name, name, execute = { throw CancellationException("Interrupted after side effect") }),
                    ledgerContext = ToolLedgerContext("run_invocation", 0, ledger),
                )
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
        }

        val recovery = RunRecoveryService(ledger, runTerminalStore, conversationRepository(), Json)
        assertTrue(recovery.reconcileStartedEffects("run_invocation"))
        calls.forEachIndexed { index, (name, _) ->
            val effect = ledger.getByToolCallId("interrupted_$index")!!
            assertEquals(name, ToolEffectClass.NON_IDEMPOTENT_WRITE, effect.effectClass)
            assertEquals(name, ToolEffectStatus.OUTCOME_UNKNOWN, effect.status)
        }
    }
}
