package app.amber.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatErrorFilterTest {

    private fun chatError(conversationId: Uuid?, message: String) = ChatError(
        title = "t-$message",
        error = IllegalStateException(message),
        conversationId = conversationId,
    )

    @Test
    fun `conversation A generation error does not surface in conversation B`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val errors = listOf(
            chatError(a, "a-error"),
            chatError(b, "b-error"),
        )

        assertEquals(listOf("a-error"), errors.errorsForConversation(a).map { it.error.message })
        assertEquals(listOf("b-error"), errors.errorsForConversation(b).map { it.error.message })
    }

    @Test
    fun `A session generating while user switches to B does not leak A error into B`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val errors = listOf(
            chatError(a, "A generation failed"),
            chatError(a, "A second error"),
        )

        // B 会话看到的错误列表为空
        assertTrue(errors.errorsForConversation(b).isEmpty())
        assertEquals(2, errors.errorsForConversation(a).size)
    }

    @Test
    fun `global provider config error is separated from per-conversation errors`() {
        val a = Uuid.random()
        val errors = listOf(
            chatError(null, "provider config broken"),
            chatError(a, "generation failed"),
        )

        val global = errors.globalErrors()
        assertEquals(listOf("provider config broken"), global.map { it.error.message })
        assertTrue(global.all { it.conversationId == null })
        assertEquals(listOf("generation failed"), errors.errorsForConversation(a).map { it.error.message })
    }
}
