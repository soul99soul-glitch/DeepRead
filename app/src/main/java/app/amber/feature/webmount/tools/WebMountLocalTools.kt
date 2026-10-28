package app.amber.feature.webmount.tools

import app.amber.ai.core.Tool
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.usersites.UserSiteRegistry

/** WebMount's catalog gates, adapter selection and host-owned run scope. */
class WebMountLocalTools(
    private val primitiveTools: WebMountPrimitiveTools,
    private val manager: WebMountManager,
    private val userSiteRegistry: UserSiteRegistry,
) {
    fun getTools(conversationId: String?, runId: String?): List<Tool> {
        // The global settings, rather than the removed assistant options,
        // govern both native adapters and the primitive catalog.
        if (!manager.globalEnabled) return emptyList()

        val tools = primitiveTools.getTools(
            includeEval = manager.evalEnabled,
            conversationId = conversationId,
            runId = runId,
        )
        val activeAdapterIds = userSiteRegistry.activeNativeAdapterIds()
        val adapterTools = manager.allToolsByAdapter().asSequence()
            .filter { (adapterId, _) -> adapterId in activeAdapterIds }
            .flatMap { it.value.asSequence() }
            // Only these Feishu tools use the pooled WebView; OpenAPI tools
            // retain their original execution and parameter contract.
            .map { tool ->
                if (tool.name !in FEISHU_WEBMOUNT_SCOPED_TOOLS) {
                    tool
                } else {
                    tool.copy(
                        execute = { input ->
                            tool.execute(input.withWebMountScope(conversationId, runId))
                        },
                    )
                }
            }
            .toList()
        return tools + adapterTools
    }

    /** Release leases even if WebMount was disabled after this run started. */
    fun endRun(
        runId: String,
        conversationId: String?,
        reason: String = "run ended",
        preservePendingHandoff: Boolean = false,
    ) {
        primitiveTools.endRun(
            runId = runId,
            conversationId = conversationId,
            reason = reason,
            preservePendingHandoff = preservePendingHandoff,
        )
    }
}

private val FEISHU_WEBMOUNT_SCOPED_TOOLS = setOf(
    "feishu_docs_snapshot",
    "feishu_docs_network_summary",
)
