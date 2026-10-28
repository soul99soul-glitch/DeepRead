package app.amber.feature.ui.components.message

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MessageNode
import app.amber.feature.ui.theme.ThemePackageTryOn
import app.amber.feature.ui.theme.ThemeTryOnHost

data class ThemeTryOnCardTarget(
    val toolCallId: String,
    val packageId: String,
    val candidateDigest: String,
    val isApplying: Boolean = false,
)

private data class ThemeTryOnToolBinding(
    val packageId: String,
    val candidateDigest: String,
    val pendingApply: Boolean,
    val applying: Boolean,
    val preparedReceipt: Boolean,
)

private fun themeTryOnToolBinding(tool: UIMessagePart.Tool): ThemeTryOnToolBinding? {
    if (tool.toolName != "theme_pack_import") return null
    val arguments = MessageRenderCache.toolInputJson(tool.input)
    val action = arguments.getStringContent("action")?.lowercase() ?: "prepare"
    if (action == "apply") {
        val packageId = arguments.getStringContent("id") ?: return null
        val candidateDigest = arguments.getStringContent("candidate_digest") ?: return null
        return when {
            tool.approvalState is ToolApprovalState.Pending -> ThemeTryOnToolBinding(
                packageId,
                candidateDigest,
                pendingApply = true,
                applying = false,
                preparedReceipt = false,
            )
            tool.approvalState is ToolApprovalState.Approved && !tool.isExecuted -> ThemeTryOnToolBinding(
                packageId,
                candidateDigest,
                pendingApply = false,
                applying = true,
                preparedReceipt = false,
            )
            else -> null
        }
    }
    if (!tool.isExecuted || action != "prepare") return null
    val output = MessageRenderCache.toolOutputJson(tool.output)
    if (output.getStringContent("status") != "prepared") return null
    val packageId = output.getStringContent("package_id") ?: return null
    val candidateDigest = output.getStringContent("candidate_digest") ?: return null
    return ThemeTryOnToolBinding(
        packageId,
        candidateDigest,
        pendingApply = false,
        applying = false,
        preparedReceipt = true,
    )
}

internal fun resolveThemeTryOnCardTarget(
    messageNodes: List<MessageNode>,
    tryOn: ThemePackageTryOn?,
): ThemeTryOnCardTarget? {
    tryOn ?: return null
    val tools = messageNodes.asReversed().asSequence()
        .flatMap { node -> node.currentMessage.parts.asReversed().asSequence() }
        .filterIsInstance<UIMessagePart.Tool>()
        .toList()
    val matching = tools.mapNotNull { tool ->
        themeTryOnToolBinding(tool)?.takeIf {
            it.packageId == tryOn.pkg.id && it.candidateDigest == tryOn.candidateDigest
        }?.let { tool to it }
    }
    matching.firstOrNull { it.second.pendingApply }?.let { (tool, binding) ->
        return ThemeTryOnCardTarget(tool.toolCallId, binding.packageId, binding.candidateDigest)
    }
    matching.firstOrNull { it.second.applying }?.let { (tool, binding) ->
        return ThemeTryOnCardTarget(tool.toolCallId, binding.packageId, binding.candidateDigest, isApplying = true)
    }
    val applyExecutionInFlight = tools.any { tool ->
        if (tool.toolName != "theme_pack_import" || tool.isExecuted ||
            tool.approvalState is ToolApprovalState.Pending ||
            tool.approvalState is ToolApprovalState.Denied
        ) return@any false
        val arguments = MessageRenderCache.toolInputJson(tool.input)
        arguments.getStringContent("action")?.lowercase() == "apply" &&
            arguments.getStringContent("id") == tryOn.pkg.id &&
            arguments.getStringContent("candidate_digest") == tryOn.candidateDigest
    }
    if (applyExecutionInFlight) return null
    val (tool, binding) = matching.firstOrNull { it.second.preparedReceipt } ?: return null
    return ThemeTryOnCardTarget(tool.toolCallId, binding.packageId, binding.candidateDigest)
}

internal fun UIMessagePart.Tool.matchesThemeTryOnCardTarget(target: ThemeTryOnCardTarget?): Boolean {
    if (target == null || toolCallId != target.toolCallId) return false
    val binding = themeTryOnToolBinding(this) ?: return false
    return binding.packageId == target.packageId && binding.candidateDigest == target.candidateDigest
}

@Composable
internal fun ThemeTryOnToolCard(
    tool: UIMessagePart.Tool,
    target: ThemeTryOnCardTarget,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)?,
) {
    val binding = themeTryOnToolBinding(tool) ?: return
    if (!tool.matchesThemeTryOnCardTarget(target)) return
    if (binding.pendingApply && onToolApproval == null) return
    ThemeTryOnHost(
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
        packageId = binding.packageId,
        candidateDigest = binding.candidateDigest,
        isApplying = binding.applying,
        onApplyRequest = if (binding.pendingApply) {
            { onToolApproval?.invoke(tool.toolCallId, true, "") }
        } else null,
        onRestoreRequest = if (binding.pendingApply) {
            { onToolApproval?.invoke(tool.toolCallId, false, "") }
        } else null,
    )
}
