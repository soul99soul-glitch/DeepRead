package app.amber.core.service

import android.content.Context
import androidx.core.net.toUri
import app.amber.agent.R
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.core.context.ConversationContextRepository
import app.amber.core.files.FilesManager
import app.amber.core.model.Conversation
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** User edits and forks, with conversation persistence delegated to its single owner. */
class ConversationEditor(
    private val context: Context,
    private val conversationStore: ConversationStore,
    private val contextRepository: ConversationContextRepository,
    private val userInputPreprocessor: UserInputPreprocessor,
    private val filesManager: FilesManager,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
    ): Boolean = withCapturedRestoreWriteContext restore@{
        if (parts.isEmptyInputMessage()) return@restore false
        val processedParts = userInputPreprocessor.process(parts)
        val conflictReason = blockedReason(
            conversationStore.session(conversationId).isGenerating,
            context.getString(R.string.chat_page_edit_generating),
        )
        if (conflictReason != null) {
            conversationStore.addError(
                IllegalStateException(conflictReason),
                conversationId = conversationId,
                title = context.getString(R.string.error_title_operation),
            )
            return@restore false
        }
        val currentConversation = conversationStore.ensureFullConversationLoaded(conversationId)
        val updatedConversation = currentConversation.withEditedUserVariant(messageId, processedParts)
        if (updatedConversation === currentConversation) return@restore false
        contextRepository.invalidateCompacts(conversationId, "message_edited")
        conversationStore.saveConversation(conversationId, updatedConversation)
        true
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation = withCapturedRestoreWriteContext {
        val expectedRestoreEpoch = coroutineContext[SyncRestoreWriteEpoch]?.value
        val currentConversation = conversationStore.ensureFullConversationLoaded(conversationId)
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }.takeIf { it >= 0 } ?: throw NoSuchElementException("Message not found")

        val copiedFiles = mutableListOf<android.net.Uri>()
        val forkedId = Uuid.random()
        val forked = try {
            val sourceNodes = currentConversation.messageNodes.take(targetNodeIndex + 1)
            val copiedNodes = sourceNodes.map { sourceNode ->
                sourceNode.copy(
                    id = Uuid.random(),
                    messages = sourceNode.messages.map { sourceMessage ->
                        sourceMessage.copy(
                            parts = sourceMessage.parts.map { part -> part.copyWithForkedFileUrl(copiedFiles) },
                        )
                    },
                )
            }
            Conversation(
                id = forkedId,
                assistantId = currentConversation.assistantId,
                messageNodes = copiedNodes,
            ).also { conversationStore.saveConversation(it.id, it) }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                // A cancelled save may already have committed. Only reclaim an
                // aborted fork whose files have no persisted conversation owner.
                if (!conversationStore.existsConversation(forkedId)) {
                    // appScope cleanup does not inherit the fork epoch. A restore may
                    // already have adopted this unfinished fork's copied files.
                    filesManager.deleteChatFiles(copiedFiles, expectedRestoreEpoch).join()
                }
            }
            throw error
        }
        contextRepository.copyValidCompactsToConversation(
            sourceConversationId = conversationId,
            targetConversation = forked,
            reason = "conversation_forked_compacts_copied",
        )
        forked
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        withCapturedRestoreWriteContext restore@{
            val session = conversationStore.session(conversationId)
            // Minor-1: 生成中切换 user variant 与生成写竞争（saveConversation 可能
            // 覆盖流式写入的下游分支）。与 editMessage 的权威守卫一致：明确提示并
            // 拒绝，不打断当前生成，也不做任何写操作。
            val conflictReason = blockedReason(session.isGenerating, context.getString(R.string.chat_page_edit_generating))
            if (conflictReason != null) {
                conversationStore.addError(
                    IllegalStateException(conflictReason),
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_operation),
                )
                return@restore
            }

            val currentConversation = conversationStore.ensureFullConversationLoaded(conversationId)
            // P8-02: 切换 variant 后，下游可见分支同步切换（截断到被切换节点，
            // 与新 variant 上下文保持一致）。
            val updatedConversation = currentConversation.withSelectedVariant(nodeId, selectIndex)
            if (updatedConversation === currentConversation) return@restore

            contextRepository.invalidateCompacts(conversationId, "message_branch_changed")
            conversationStore.saveConversation(conversationId, updatedConversation)
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        withCapturedRestoreWriteContext restore@{
            val currentConversation = conversationStore.ensureFullConversationLoaded(conversationId)
            val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

            if (updatedConversation == null) {
                if (failIfMissing) {
                    throw NoSuchElementException("Message not found")
                }
                return@restore
            }

            contextRepository.invalidateCompacts(conversationId, "message_deleted")
            conversationStore.saveConversation(conversationId, updatedConversation)
            val retainedFiles = updatedConversation.files.toHashSet()
            withEditorWrite {
                filesManager.deleteChatImageFiles(
                    conversationId,
                    currentConversation.files.filterNot(retainedFiles::contains),
                )
            }
        }
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { message -> message.id == messageId }
        }.takeIf { it >= 0 } ?: return null
        val targetNode = conversation.messageNodes[nodeIndex]
        val remainingMessages = targetNode.messages.filterNot { message -> message.id == messageId }
        val nextNodes = conversation.messageNodes.toMutableList()

        if (remainingMessages.isEmpty()) {
            nextNodes.removeAt(nodeIndex)
        } else {
            nextNodes[nodeIndex] = targetNode.copy(
                messages = remainingMessages,
                selectIndex = targetNode.selectIndex.coerceAtMost(remainingMessages.lastIndex),
            )
        }
        return conversation.copy(messageNodes = nextNodes)
    }

    private suspend fun UIMessagePart.copyWithForkedFileUrl(copiedFiles: MutableList<android.net.Uri>): UIMessagePart {
        if (this is UIMessagePart.Tool) {
            return copy(output = output.map { it.copyWithForkedFileUrl(copiedFiles) })
        }
        val sourceUrl = when (this) {
            is UIMessagePart.Image -> url
            is UIMessagePart.Document -> url
            is UIMessagePart.Video -> url
            is UIMessagePart.Audio -> url
            else -> return this
        }
        if (!sourceUrl.startsWith("file:")) return this
        val copiedUrl = filesManager.createChatFilesByContents(listOf(sourceUrl.toUri()))
            .firstOrNull()
            ?.toString()
            ?: error("Failed to copy attachment for fork: $sourceUrl")
        copiedFiles.add(copiedUrl.toUri())

        return when (this) {
            is UIMessagePart.Image -> copy(url = copiedUrl)
            is UIMessagePart.Document -> copy(url = copiedUrl)
            is UIMessagePart.Video -> copy(url = copiedUrl)
            is UIMessagePart.Audio -> copy(url = copiedUrl)
        }
    }

    private suspend fun <T> withCapturedRestoreWriteContext(block: suspend () -> T): T {
        val gate = restoreWriteGate ?: return block()
        val epoch = coroutineContext[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
        return withContext(SyncRestoreWriteEpoch(epoch)) { block() }
    }

    private suspend fun <T> withEditorWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) block() else gate.withCurrentWriterOrCancel(block)
    }
}

/**
 * 生成中的冲突策略 —— 编辑消息（P8-01）与切换 user variant（Minor-1）共用：
 * 生成中拒绝（明确提示，不打断当前生成，不做任何写操作）。返回 null 表示允许，
 * 否则返回给用户的提示文案。ConversationEditor 的命令与 ChatVM 预检共用。
 */
internal fun blockedReason(isGenerating: Boolean, message: String): String? =
    if (isGenerating) message else null
