package app.amber.ai.provider.providers

import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.openai.ChatCompletionsAPI
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire events must survive the same accumulator used by chat and remain replayable. */
class ProviderStreamRoundTripRegressionTest {
    @Test
    fun `gemini independent tool calls in different events keep their ids and arguments`() = runBlocking {
        assertIndependentGeminiTools(includeProviderIds = true)
    }

    @Test
    fun `gemini independent calls without provider ids stay separate across events`() = runBlocking {
        assertIndependentGeminiTools(includeProviderIds = false)
    }

    private suspend fun assertIndependentGeminiTools(includeProviderIds: Boolean) {
        fun call(id: String, name: String, query: String): String {
            val idField = if (includeProviderIds) "\"id\":\"$id\"," else ""
            return """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{$idField"name":"$name","args":{"q":"$query"}}}]}}]}"""
        }
        val chunks = googleStream(
            listOf(
                call("call-a", "search", "a"),
                call("call-b", "lookup", "b"),
                """{"candidates":[{"finishReason":"STOP"}]}""",
            ),
        )
        val message = accumulate(chunks)
        val tools = message.getTools()
        assertEquals(2, tools.size)
        assertEquals(listOf("search", "lookup"), tools.map { it.toolName })
        assertEquals(listOf("a", "b"), tools.map {
            it.inputAsJson().jsonObject.getValue("q").jsonPrimitive.content
        })
        assertEquals(2, tools.map { it.toolCallId }.distinct().size)
        if (includeProviderIds) assertEquals(listOf("call-a", "call-b"), tools.map { it.toolCallId })

        val executed = message.copy(parts = tools.map {
            it.copy(output = listOf(UIMessagePart.Text("result-${it.toolName}")))
        })
        val turns = GeminiGenerateContentAdapter().encodeContents(listOf(executed))
        val calls = turns[0].jsonObject.getValue("parts").jsonArray.map {
            it.jsonObject.getValue("functionCall").jsonObject
        }
        val results = turns[1].jsonObject.getValue("parts").jsonArray.map {
            it.jsonObject.getValue("functionResponse").jsonObject
        }
        assertEquals(listOf("search", "lookup"), calls.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("search", "lookup"), results.map { it.getValue("name").jsonPrimitive.content })
        if (includeProviderIds) {
            assertEquals(listOf("call-a", "call-b"), results.map { it.getValue("id").jsonPrimitive.content })
        }
    }

    @Test
    fun `gemini complete inline images in separate events remain separate images`() = runBlocking {
        val message = accumulate(googleStream(listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"inlineData":{"mimeType":"image/png","data":"YQ=="}}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"inlineData":{"mimeType":"image/jpeg","data":"Yg=="}}]},"finishReason":"STOP"}]}""",
        )))

        assertEquals(
            listOf("data:image/png;base64,YQ==", "data:image/jpeg;base64,Yg=="),
            message.parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
        )
        val encoded = GeminiGenerateContentAdapter().encodeContents(listOf(message))
            .single().jsonObject.getValue("parts").jsonArray
        assertEquals(listOf("YQ==", "Yg=="), encoded.map {
            it.jsonObject.getValue("inlineData").jsonObject.getValue("data").jsonPrimitive.content
        })
    }

    @Test
    fun `hosted image urls survive chat completion stream without data uri wrapping`() = runBlocking {
        val sse = listOf("https://images.test/a.png", "https://images.test/b.png").joinToString("") { url ->
            "data: {\"choices\":[{\"delta\":{\"images\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"$url\"}}]}}]}\n\n"
        } + "data: [DONE]\n\n"
        val chunks = ChatCompletionsAPI(sseClient(sse)).streamText(
            ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1", apiKey = "test"),
            listOf(UIMessage.user("draw two")), TextGenerationParams(Model(modelId = "image-test")),
        ).toList()
        val expected = listOf("https://images.test/a.png", "https://images.test/b.png")
        assertEquals(expected, accumulate(chunks).parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
        val legacy = chunks.fold(UIMessage.assistant("")) { message, chunk -> message + chunk }
        assertEquals(expected, legacy.parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
    }

    @Test
    fun `gemini empty signed thought survives snapshot and replay`() = runBlocking {
        val message = accumulate(googleStream(listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"","thought":true,"thoughtSignature":"opaque-signature"}]},"finishReason":"STOP"}]}""",
        )))
        val block = GeminiGenerateContentAdapter().encodeContents(listOf(message)).single().jsonObject
            .getValue("parts").jsonArray.single().jsonObject
        assertEquals("", block.getValue("text").jsonPrimitive.content)
        assertEquals("opaque-signature", block.getValue("thoughtSignature").jsonPrimitive.content)
    }

    @Test
    fun `anthropic redacted block survives stream snapshot and tool replay`() = runBlocking {
        val message = anthropicMessage(listOf(
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"opaque-a"}}""",
        ))
        val blocks = replayAnthropic(message)
        assertEquals(listOf("redacted_thinking", "tool_use"), blocks.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("opaque-a", blocks[0].getValue("data").jsonPrimitive.content)
    }

    @Test
    fun `anthropic adjacent signed and redacted blocks retain their boundaries`() = runBlocking {
        val message = anthropicMessage(listOf(
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"first"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":" thought"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"a"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque-b"}}""",
        ))
        val blocks = replayAnthropic(message)
        assertEquals(listOf("thinking", "redacted_thinking", "tool_use"), blocks.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("first thought", blocks[0].getValue("thinking").jsonPrimitive.content)
        assertEquals("sig-a", blocks[0].getValue("signature").jsonPrimitive.content)
        assertEquals("opaque-b", blocks[1].getValue("data").jsonPrimitive.content)
        assertEquals(setOf("type", "thinking", "signature"), blocks[0].keys)
    }

    @Test
    fun `anthropic consecutive redacted blocks both survive replay`() = runBlocking {
        val message = anthropicMessage(listOf(
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"opaque-a"}}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque-b"}}""",
        ))
        val blocks = replayAnthropic(message).filter { it.getValue("type").jsonPrimitive.content == "redacted_thinking" }
        assertEquals(listOf("opaque-a", "opaque-b"), blocks.map { it.getValue("data").jsonPrimitive.content })
    }

    @Test
    fun `anthropic repeated block index belongs to different streams`() = runBlocking {
        val sse = "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        val provider = ClaudeProvider(sseClient(sse))
        val chunks = buildList {
            repeat(2) {
                addAll(provider.stream(
                    ProviderSetting.Claude(baseUrl = "https://provider.test/v1", apiKey = "test"),
                    listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "claude-test")),
                ).toList())
            }
        }
        val message = accumulate(chunks)
        val blocks = AnthropicMessagesAdapter().encodeConversation(listOf(message), promptCaching = false)
            .single().jsonObject.getValue("content").jsonArray
        assertEquals(2, blocks.size)
        blocks.forEach { block ->
            assertEquals(setOf("type", "thinking", "signature"), block.jsonObject.keys)
            assertEquals("sig", block.jsonObject.getValue("signature").jsonPrimitive.content)
        }
    }

    @Test
    fun `claude 4_5 uses manual thinking while adaptive models keep adaptive thinking`() {
        for (modelId in listOf("claude-sonnet-4-5", "claude-haiku-4-5", "claude-sonnet-4-20250514", "claude-3-7-sonnet-20250219")) {
            val body = anthropicRequest(modelId, ReasoningLevel.MEDIUM)
            val thinking = body.getValue("thinking").jsonObject
            assertEquals("enabled", thinking.getValue("type").jsonPrimitive.content)
            assertEquals(2_000, thinking.getValue("budget_tokens").jsonPrimitive.int)
            assertTrue("Unsupported effort must not be sent to $modelId", "output_config" !in body)
        }
        for (modelId in listOf("claude-sonnet-4-6", "claude-opus-4-7")) {
            val body = anthropicRequest(modelId, ReasoningLevel.HIGH)
            assertEquals("adaptive", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("high", body.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        }
    }

    @Test
    fun `claude manual thinking budget stays valid for low level auto and short output limit`() {
        for (level in listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MAX)) {
            val body = anthropicRequest("claude-sonnet-4-5", level, maxTokens = 1_500)
            val thinking = body.getValue("thinking").jsonObject
            assertEquals("enabled", thinking.getValue("type").jsonPrimitive.content)
            val budget = thinking.getValue("budget_tokens").jsonPrimitive.int
            assertTrue("Anthropic manual thinking requires at least 1024 tokens", budget >= 1_024)
            assertTrue("Thinking budget must leave output room", budget < 1_500)
        }
        val disabled = anthropicRequest("claude-sonnet-4-5", ReasoningLevel.OFF)
        assertEquals("disabled", disabled.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        val opus45 = anthropicRequest("claude-opus-4-5", ReasoningLevel.MAX)
        assertEquals("high", opus45.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        val opus46 = anthropicRequest("claude-opus-4-6", ReasoningLevel.XHIGH)
        assertEquals("high", opus46.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    private fun anthropicRequest(modelId: String, level: ReasoningLevel, maxTokens: Int? = null): JsonObject =
        AnthropicMessagesAdapter().encodeRequest(
            setting = ProviderSetting.Claude(apiKey = "test"),
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
                reasoningLevel = level,
                maxTokens = maxTokens,
            ),
            streaming = true,
        )

    private suspend fun anthropicMessage(events: List<Pair<String, String>>): UIMessage {
        val fixture = events + listOf(
            "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"call-1","name":"lookup","input":{"q":"a"}}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        val sse = fixture.joinToString("") { (type, data) -> "event: $type\ndata: $data\n\n" }
        val chunks = ClaudeProvider(sseClient(sse)).stream(
            ProviderSetting.Claude(baseUrl = "https://provider.test/v1", apiKey = "test"),
            listOf(UIMessage.user("use a tool")),
            TextGenerationParams(Model(modelId = "claude-test")),
        ).toList()
        return accumulate(chunks)
    }

    private fun replayAnthropic(message: UIMessage): List<JsonObject> {
        val executed = message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(output = listOf(UIMessagePart.Text("result"))) else part
        })
        return AnthropicMessagesAdapter().encodeConversation(listOf(executed), promptCaching = false)
            .first().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
    }

    private suspend fun googleStream(events: List<String>): List<MessageChunk> =
        GoogleProvider(sseClient(events.joinToString("") { "data: $it\n\n" })).stream(
            ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "test"),
            listOf(UIMessage.user("hello")),
            TextGenerationParams(Model(modelId = "gemini-test")),
        ).toList()

    private fun accumulate(chunks: List<MessageChunk>): UIMessage {
        val accumulator = MessageStreamAccumulator(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())))
        chunks.forEach(accumulator::append)
        return accumulator.snapshot().single()
    }

    private fun sseClient(sse: String): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(sse.toResponseBody("text/event-stream".toMediaType())).build()
    }.build()
}
