package app.amber.feature.ui.pages.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import app.amber.core.model.Conversation
import app.amber.core.settings.AgentOperationPreviewMode
import app.amber.feature.runtime.SandboxActivityUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class ChatSandboxProjection(
    val timeline: List<SandboxActivityUiState>,
    val scopedLiveActivity: SandboxActivityUiState?,
)

@Composable
internal fun rememberChatSandboxProjection(
    conversation: Conversation,
    conversationFlow: StateFlow<Conversation>,
    loading: Boolean,
    processingStatus: String?,
    previewMode: AgentOperationPreviewMode,
    liveSandboxActivity: SandboxActivityUiState?,
    resourceContext: Context,
    localeTag: String,
): ChatSandboxProjection {
    val conversationIdText = conversation.id.toString()
    val latestSandboxLoading by rememberUpdatedState(loading)
    val latestSandboxProcessingStatus by rememberUpdatedState(processingStatus)
    val latestSandboxLocaleTag by rememberUpdatedState(localeTag)
    val messageSandboxActivities by produceState(
        initialValue = emptyList<SandboxActivityUiState>(),
        key1 = conversation.id,
    ) {
        combine(
            conversationFlow.map { latestConversation ->
                SandboxToolsSnapshot(
                    conversationId = latestConversation.id.toString(),
                    tools = latestConversation.sandboxActivityTools(),
                )
            }.distinctUntilChanged { previous, current ->
                previous.conversationId == current.conversationId &&
                    previous.tools.size == current.tools.size &&
                    previous.tools.indices.all { previous.tools[it] === current.tools[it] }
            }.flowOn(Dispatchers.Default),
            snapshotFlow {
                Triple(
                    latestSandboxLoading,
                    latestSandboxProcessingStatus,
                    latestSandboxLocaleTag,
                )
            },
        ) { tools, status ->
            SandboxActivityInput(
                tools = tools,
                loading = status.first,
                processingStatus = status.second,
                localeTag = status.third,
            )
        }.collectLatest { input ->
            value = withContext(Dispatchers.Default) {
                input.tools.deriveSandboxActivities(
                    loading = input.loading,
                    processingStatus = input.processingStatus,
                    context = resourceContext,
                )
            }
        }
    }
    val scopedLiveSandboxActivity = liveSandboxActivity?.takeIf { live ->
        live.conversationId == conversationIdText ||
            (live.conversationId == null && messageSandboxActivities.any { it.toolCallId == live.toolCallId })
    }
    val rawSandboxTimeline = mergeSandboxTimeline(
        messageActivities = messageSandboxActivities,
        liveActivity = scopedLiveSandboxActivity,
    )
    val timeline = when {
        // The design's empty chat keeps the composer single-line and leaves the
        // hero unobstructed. The ALWAYS preview mode still shows real activity;
        // only the synthetic idle placeholder is suppressed until a task exists.
        conversation.messageNodes.isEmpty() && rawSandboxTimeline.isEmpty() -> emptyList()
        previewMode == AgentOperationPreviewMode.ALWAYS -> rawSandboxTimeline.ifEmpty {
            listOf(conversation.idleSandboxActivity(resourceContext))
        }
        previewMode == AgentOperationPreviewMode.AUTO -> rawSandboxTimeline.filter { it.isActiveOperation() }
        else -> emptyList()
    }
    return ChatSandboxProjection(timeline, scopedLiveSandboxActivity)
}
