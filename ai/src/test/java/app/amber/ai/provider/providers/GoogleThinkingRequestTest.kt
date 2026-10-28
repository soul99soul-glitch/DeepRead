package app.amber.ai.provider.providers

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class GoogleThinkingRequestTest {
    @Test fun completeMapsProOffToItsLowestSupportedLevel() = runBlocking {
        assertAcceptedRequest("gemini-3.1-pro-preview", ReasoningLevel.OFF, false, expectedLevel = "low")
    }

    @Test fun streamMapsProOffToItsLowestSupportedLevel() = runBlocking {
        assertAcceptedRequest("gemini-3.1-pro-preview-customtools", ReasoningLevel.OFF, true, expectedLevel = "low")
    }

    @Test fun completeMapsOriginalProMediumToSupportedLevel() = runBlocking {
        assertAcceptedRequest("gemini-3-pro-preview", ReasoningLevel.MEDIUM, false, expectedLevel = "high")
    }

    @Test fun streamMapsOriginalProMediumToSupportedLevel() = runBlocking {
        assertAcceptedRequest("gemini-3-pro-preview", ReasoningLevel.MEDIUM, true, expectedLevel = "high")
    }

    @Test fun completeCapsFlashBudgetAtItsPublishedMaximum() = runBlocking {
        assertAcceptedRequest("gemini-2.5-flash", ReasoningLevel.MAX, false, expectedBudget = 24_576)
    }

    @Test fun streamCapsFlashBudgetAtItsPublishedMaximum() = runBlocking {
        assertAcceptedRequest("gemini-2.5-flash", ReasoningLevel.MAX, true, expectedBudget = 24_576)
    }

    @Test fun legalNeighboringModelOptionsKeepTheirValues() = runBlocking {
        assertAcceptedRequest("gemini-3.1-pro-preview", ReasoningLevel.MEDIUM, false, expectedLevel = "medium")
        assertAcceptedRequest("gemini-3-flash-preview", ReasoningLevel.OFF, true, expectedLevel = "minimal")
        assertAcceptedRequest("gemini-2.5-pro", ReasoningLevel.MAX, false, expectedBudget = 32_000)
        assertAcceptedRequest("gemini-2.5-flash", ReasoningLevel.XHIGH, true, expectedBudget = 16_000)
    }

    @Test fun automaticAndCodeAssistOffKeepDynamicThinkingConfiguration() {
        val adapter = GeminiGenerateContentAdapter()
        for ((level, codeAssist) in listOf(ReasoningLevel.AUTO to false, ReasoningLevel.OFF to true)) {
            val body = adapter.encodeRequest(
                listOf(UIMessage.user("hello")),
                params("gemini-3.1-pro-preview", level),
                codeAssistTransport = codeAssist,
            )
            val thinking = body.getValue("generationConfig").jsonObject.getValue("thinkingConfig").jsonObject
            assertFalse(thinking.containsKey("thinkingLevel"))
            assertFalse(thinking.containsKey("thinkingBudget"))
        }
    }

    private suspend fun assertAcceptedRequest(
        modelId: String,
        level: ReasoningLevel,
        streaming: Boolean,
        expectedLevel: String? = null,
        expectedBudget: Int? = null,
    ) {
        val uploaded = AtomicReference<JsonObject>()
        val completion = """{"candidates":[{"content":{"role":"model","parts":[{"text":"accepted"}]},"finishReason":"STOP"}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            uploaded.set(body)
            val thinking = body.getValue("generationConfig").jsonObject.getValue("thinkingConfig").jsonObject
            val accepted = acceptsPublishedThinkingConfig(modelId, thinking)
            val responseBody = when {
                !accepted -> """{"error":{"message":"Unsupported thinking configuration for $modelId"}}"""
                streaming -> "data: $completion\n\n"
                else -> completion
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (accepted) 200 else 400).message(if (accepted) "OK" else "Bad Request")
                .body(responseBody.toResponseBody(
                    (if (accepted && streaming) "text/event-stream" else "application/json").toMediaType(),
                )).build()
        }.build()
        val provider = GoogleProvider(client)
        val setting = ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "test")
        val messages = listOf(UIMessage.user("hello"))
        if (streaming) {
            assertTrue(provider.stream(setting, messages, params(modelId, level)).toList().isNotEmpty())
        } else {
            assertEquals("accepted", provider.complete(setting, messages, params(modelId, level))
                .choices.single().message!!.toText())
        }
        val thinking = uploaded.get().getValue("generationConfig").jsonObject.getValue("thinkingConfig").jsonObject
        assertEquals(expectedLevel, thinking["thinkingLevel"]?.jsonPrimitive?.content)
        assertEquals(expectedBudget, thinking["thinkingBudget"]?.jsonPrimitive?.int)
    }

    // Google Thinking docs: original 3 Pro supports low/high; 3.1 Pro adds
    // medium; 2.5 Flash accepts budgets up to 24576, while 2.5 Pro allows 32768.
    private fun acceptsPublishedThinkingConfig(modelId: String, thinking: JsonObject): Boolean {
        val level = thinking["thinkingLevel"]?.jsonPrimitive?.content
        val budget = thinking["thinkingBudget"]?.jsonPrimitive?.int
        return when {
            modelId.startsWith("gemini-3.1-pro-") -> level in setOf("low", "medium", "high")
            modelId == "gemini-3-pro-preview" -> level in setOf("low", "high")
            modelId == "gemini-3-flash-preview" -> level in setOf("minimal", "low", "medium", "high")
            modelId == "gemini-2.5-flash" -> budget != null && budget in 0..24_576
            modelId == "gemini-2.5-pro" -> budget != null && budget in 128..32_768
            else -> error("No contract fixture for $modelId")
        }
    }

    private fun params(modelId: String, level: ReasoningLevel) = TextGenerationParams(
        model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
        reasoningLevel = level,
    )
}
