package app.amber.ai.provider.providers.openai

import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual ResponseAPI Codex HTTP/SSE path; only provider I/O is scripted. */
class ResponseAPICodexTransportTest {
    @Test
    fun unauthorizedResponseClosesBeforeOneRefreshAndCompletedSseIsDecoded() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val refreshes = CopyOnWriteArrayList<Boolean>()
        val unauthorized = TrackedBody("unauthorized")
        val completed = TrackedBody(
            "event: response.completed\ndata: " +
                """{"type":"response.completed","response":{"id":"resp_codex","status":"completed","output":[{"type":"message","id":"msg_codex","role":"assistant","content":[{"type":"output_text","text":"Codex complete output"}]}]}}""" +
                "\n\n",
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            if (requests.size == 2) assertTrue("The old unauthorized response must close before retry", unauthorized.closed)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (requests.size == 1) 401 else 200)
                .message(if (requests.size == 1) "Unauthorized" else "OK")
                .body(if (requests.size == 1) unauthorized.body else completed.body)
                .build()
        }.build()
        val api = ResponseAPI(client, bearerResolver = { _, refresh ->
            refreshes += refresh
            if (refresh) "refreshed-token" else "original-token"
        })

        val chunks = withTimeout(10_000) { api.streamText(setting(), emptyList(), params()).toList() }

        assertEquals(listOf(false, true), refreshes.toList())
        assertEquals(listOf("Bearer original-token", "Bearer refreshed-token"), requests.map { it.header("Authorization") })
        assertEquals(2, requests.size)
        assertTrue(unauthorized.closed)
        assertTrue("The completed SSE body must close", completed.closed)
        val accumulator = MessageStreamAccumulator(listOf(UIMessage.assistant("")))
        chunks.forEach(accumulator::append)
        assertEquals("Codex complete output", accumulator.snapshot().single().parts
            .filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
    }

    @Test
    fun secondUnauthorizedResponseFailsWithoutAnotherRefreshOrThirdRequest() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val bodies = CopyOnWriteArrayList<TrackedBody>()
        val refreshes = CopyOnWriteArrayList<Boolean>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = TrackedBody("provider rejected credentials").also { bodies += it }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("Unauthorized").body(body.body).build()
        }.build()
        val api = ResponseAPI(client, bearerResolver = { _, refresh ->
            refreshes += refresh
            if (refresh) "refreshed-token" else "original-token"
        })

        val failure = runCatching {
            withTimeout(10_000) { api.streamText(setting(), emptyList(), params()).toList() }
        }.exceptionOrNull()

        assertTrue("The second HTTP 401 must remain a stream failure", failure?.message?.contains("401") == true)
        assertEquals(listOf(false, true), refreshes.toList())
        assertEquals(2, requests.size)
        assertEquals(2, bodies.size)
        assertTrue("Both failed HTTP bodies must close", bodies.all { it.closed })
    }

    private fun setting() = ProviderSetting.OpenAI(
        baseUrl = "https://chatgpt.com/backend-api/codex",
        authMode = OpenAIAuthMode.CODEX_OAUTH,
        useResponseApi = true,
    )

    private fun params() = TextGenerationParams(model = Model(modelId = "gpt-5.4"))

    private class TrackedBody(content: String) {
        @Volatile var closed = false
            private set
        private val bytes = content.toByteArray()
        private val stream = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        val body = RealResponseBody("text/event-stream", bytes.size.toLong(), stream)
    }
}
