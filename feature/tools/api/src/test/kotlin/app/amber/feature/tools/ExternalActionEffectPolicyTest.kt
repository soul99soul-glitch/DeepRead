package app.amber.feature.tools

import app.amber.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalActionEffectPolicyTest {
    private fun tool(name: String) = Tool(
        name = name,
        description = name,
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { emptyList() },
    )

    @Test
    fun screenActionsCannotBeRecoveredAsReadOnlyObservations() {
        val names = listOf(
            "screen_click", "screen_long_click", "screen_swipe", "screen_input_text",
            "screen_tap_text", "screen_back", "screen_home", "screen_open_app",
            "screen_open_url", "screen_scroll_until", "screen_collect_list",
        )
        for (name in names) {
            val action = tool(name)
            val metadata = ToolRegistry.from(listOf(action)).metadataFor(name)!!
            assertTrue("$name changes external screen state", metadata.mutates)
            assertEquals(name, ToolEffectClass.NON_IDEMPOTENT_WRITE, action.effectClass("{}"))
            assertEquals(name, ToolEffectClass.NON_IDEMPOTENT_WRITE, metadata.effectClass)
        }
    }

    @Test
    fun commandExecutionAndWorkspaceFlushCannotBeSilentlyRetried() {
        for (name in listOf(
            "terminal_execute", "terminal_job_start", "terminal_session_exec", "terminal_workspace_flush",
            "call_phone", "intent_open",
        )) {
            assertEquals(name, ToolEffectClass.NON_IDEMPOTENT_WRITE, tool(name).effectClass("{}"))
        }
    }

    @Test
    fun screenAndTerminalObservationsKeepTheirReadOnlyRecovery() {
        for (name in listOf(
            "screen_read_ui", "screen_find_text", "screen_wait_for_text", "screen_status",
            "screen_apps", "screen_screenshot", "terminal_job_read", "terminal_job_wait",
            "terminal_session_read", "terminal_ssh_profiles",
        )) {
            val observation = tool(name)
            val metadata = ToolRegistry.from(listOf(observation)).metadataFor(name)!!
            assertFalse(name, metadata.mutates)
            assertEquals(name, ToolEffectClass.READ_ONLY, observation.effectClass("{}"))
        }
    }

    @Test
    fun fileWriteRecoveryDistinguishesOverwriteFromAppend() {
        val write = tool("file_write")
        assertEquals(ToolEffectClass.IDEMPOTENT_WRITE, write.effectClass("""{"path":"notes/log.txt","content":"next"}"""))
        assertEquals(ToolEffectClass.IDEMPOTENT_WRITE, write.effectClass("""{"append":false}"""))
        assertEquals(ToolEffectClass.NON_IDEMPOTENT_WRITE, write.effectClass("""{"append":true}"""))
        assertEquals("Static metadata cannot promise append-safe replay", ToolEffectClass.NON_IDEMPOTENT_WRITE, write.effectClass())
    }

    @Test
    fun httpRecoveryUsesTheActualRequestMethod() {
        val http = tool("http_request")
        for (method in listOf("GET", "HEAD", "get")) {
            assertEquals(method, ToolEffectClass.READ_ONLY, http.effectClass("""{"method":"$method"}"""))
        }
        assertEquals(ToolEffectClass.READ_ONLY, http.effectClass("{}"))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            assertEquals(method, ToolEffectClass.NON_IDEMPOTENT_WRITE, http.effectClass("""{"method":"$method"}"""))
        }
        assertEquals(ToolEffectClass.NON_IDEMPOTENT_WRITE, http.effectClass())
    }

    @Test
    fun mcpExternalCallsDoNotDeriveReplaySafetyFromTheirNames() {
        for (name in listOf("mcp__bank__transfer", "mcp__service__execute", "mcp__transfer")) {
            val external = tool(name).copy(needsApproval = false, allowsAutoApproval = true)
            val metadata = ToolRegistry.from(listOf(external)).metadataFor(name)!!
            val policy = external.invocationPolicy("{}")
            assertEquals(name, ToolEffectClass.NON_IDEMPOTENT_WRITE, external.effectClass("{}"))
            assertTrue(name, metadata.mutates)
            assertTrue(name, policy.needsApproval)
            assertFalse(name, policy.concurrencySafe)
            assertFalse(name, policy.speculativeEligible)
        }
    }
}
