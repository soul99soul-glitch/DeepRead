package app.amber.ai.provider.providers

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.openai.ChatCompletionsAPI
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderContentReplayTest {
    @Test fun mistralCompletionRetainsAllThinkingAndAnswerChunksInOrder() = runBlocking {
        val content = """[{"type":"thinking","thinking":[{"type":"text","text":"check "},{"type":"text","text":"twice"}]},{"type":"text","text":"42"},{"type":"text","text":" is the answer"}]"""
        val body = """{"id":"mistral-1","choices":[{"message":{"role":"assistant","content":$content},"finish_reason":"stop"}]}"""
        val message = ChatCompletionsAPI(client(body, "application/json")).generateText(
            ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1", apiKey = "fixture-key"),
            listOf(UIMessage.user("calculate")), params(),
        ).choices.single().message!!

        assertEquals("check twice", message.parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("") { it.reasoning })
        assertEquals("42 is the answer", message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
    }

    @Test fun mistralStreamRetainsTextArrayDeltas() = runBlocking {
        val arrays = chatMessages(listOf(
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"check"}]}]}}]}""",
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"ed"}]},{"type":"text","text":"4"}]}}]}""",
            """{"choices":[{"delta":{"content":"2"},"finish_reason":"stop"}]}""",
        ))
        val primitives = chatMessages(listOf(
            """{"choices":[{"delta":{"reasoning_content":"check"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"ed","content":"4"}}]}""",
            """{"choices":[{"delta":{"content":"2"},"finish_reason":"stop"}]}""",
        ))
        for ((message, primitive) in arrays.zip(primitives)) {
            assertEquals("42", message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
            assertEquals("checked", message.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
            // toText separates every message part, including an empty rendering of Reasoning.
            assertEquals("\n42", primitive.toText())
            assertEquals(primitive.toText(), message.toText())
        }
    }

    @Test fun geminiCompletionReplaysSignedTextAndImageTogether() = runBlocking {
        val payload = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Here is the image","thoughtSignature":"text-signature"},{"inlineData":{"mimeType":"image/png","data":"YQ=="},"thoughtSignature":"image-signature"}]},"finishReason":"STOP"}]}"""
        val message = GoogleProvider(client(payload, "application/json")).complete(
            ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "fixture-key"),
            listOf(UIMessage.user("draw")), params(),
        ).choices.single().message!!
        val parts = replay(message)
        assertEquals(listOf("text-signature", "image-signature"), parts.map { it.jsonObject["thoughtSignature"]?.jsonPrimitive?.content })
        assertEquals("Here is the image", parts.first().jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test fun geminiStreamKeepsEmptySignedTextAfterVisibleAnswer() = runBlocking {
        val chunks = googleChunks(listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"answer"}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"","thoughtSignature":"tail-signature"}]},"finishReason":"STOP"}]}""",
        ))
        for (message in accumulateBoth(chunks)) {
            val parts = replay(message)
            assertEquals("answer", parts.map { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.joinToString(""))
            assertEquals("tail-signature", parts.last().jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
        }
    }

    @Test fun geminiStreamDoesNotMergeDistinctSignedTextParts() = runBlocking {
        val chunks = googleChunks(listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"first","thoughtSignature":"sig-1"},{"text":"second","thoughtSignature":"sig-2"}]},"finishReason":"STOP"}]}""",
        ))
        for (message in accumulateBoth(chunks)) {
            val parts = replay(message)
            assertEquals(listOf("first", "second"), parts.map { it.jsonObject["text"]?.jsonPrimitive?.content })
            assertEquals(listOf("sig-1", "sig-2"), parts.map { it.jsonObject["thoughtSignature"]?.jsonPrimitive?.content })
        }
    }

    private suspend fun googleChunks(events: List<String>): List<MessageChunk> =
        GoogleProvider(client(events.joinToString("") { "data: $it\n\n" }, "text/event-stream")).stream(
            ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "fixture-key"),
            listOf(UIMessage.user("hello")), params(),
        ).toList()

    private suspend fun chatMessages(events: List<String>): List<UIMessage> {
        val sse = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        val chunks = ChatCompletionsAPI(client(sse, "text/event-stream")).streamText(
            ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1", apiKey = "fixture-key"),
            listOf(UIMessage.user("calculate")), params(),
        ).toList()
        return accumulateBoth(chunks)
    }

    private fun accumulateBoth(chunks: List<MessageChunk>): List<UIMessage> {
        val initial = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val accumulator = MessageStreamAccumulator(listOf(initial))
        chunks.forEach(accumulator::append)
        return listOf(accumulator.snapshot().single(), chunks.fold(initial) { message, chunk -> message + chunk })
    }

    private fun replay(message: UIMessage): JsonArray {
        val restored = Json.decodeFromString(
            UIMessage.serializer(), Json.encodeToString(UIMessage.serializer(), message),
        )
        return GeminiGenerateContentAdapter().encodeContents(listOf(restored))
            .single().jsonObject.getValue("parts").jsonArray
    }

    private fun params() = TextGenerationParams(Model(modelId = "protocol-fixture"))

    private fun client(body: String, contentType: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody(contentType.toMediaType())).build()
        }.build()
}
