package app.amber.core.ai.tools

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.ImageGenerationRepository
import app.amber.core.settings.getCurrentImageGenerationModel
import app.amber.core.settings.prefs.SettingsAggregator
import kotlin.uuid.Uuid

/** Resolves image-tool availability and attachments for the owning conversation. */
class ImageGenerationLocalTools(
    private val settingsStore: SettingsAggregator,
    private val imageGenerationRepository: ImageGenerationRepository,
    private val conversationRepository: ConversationRepository,
) {
    fun getTool(conversationId: Uuid?): Tool? {
        // Output files require a real conversation owner; debug catalogs have none.
        if (conversationId == null || settingsStore.settingsFlow.value.getCurrentImageGenerationModel() == null) {
            return null
        }
        return createImageGenTool(
            conversationId = conversationId,
            settingsStore = settingsStore,
            imageGenerationRepository = imageGenerationRepository,
            sourceImageResolver = { resolveLatestUserMessageImage(conversationId) },
        )
    }

    // The model cannot supply a trusted local URL. Resolve the last local image
    // from selected user messages in this conversation, as attached by the UI.
    private suspend fun resolveLatestUserMessageImage(conversationId: Uuid): String? {
        val conversation = conversationRepository.getConversationById(conversationId) ?: return null
        return conversation.messageNodes.asReversed().firstNotNullOfOrNull { node ->
            val message = node.messages.getOrNull(node.selectIndex) ?: return@firstNotNullOfOrNull null
            if (message.role != MessageRole.USER) return@firstNotNullOfOrNull null
            message.parts.filterIsInstance<UIMessagePart.Image>()
                .lastOrNull { it.url.startsWith("file://") && it.url.isNotBlank() }
                ?.url
        }
    }
}
