package app.amber.core.service

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * P8-07: 错误按会话过滤。带 conversationId 的错误只在对应会话展示；
 * conversationId 为 null 的是全局错误（如全局 Provider 配置错误），
 * 走全局 banner，不伪装成某个会话的消息错误。
 */
internal fun List<ChatError>.errorsForConversation(conversationId: Uuid): List<ChatError> =
    filter { it.conversationId == conversationId }

internal fun List<ChatError>.globalErrors(): List<ChatError> =
    filter { it.conversationId == null }

class ChatErrorStore {
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }
}
