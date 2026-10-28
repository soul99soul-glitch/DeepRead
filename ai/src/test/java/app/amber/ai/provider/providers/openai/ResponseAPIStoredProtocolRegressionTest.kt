package app.amber.ai.provider.providers.openai

import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.json
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Wire-contract regressions for the existing opt-in stored-response feature. */
class ResponseAPIStoredProtocolRegressionTest {
    private class MemoryStore : ResponseResumeStore {
        var cursor: ResponseCursor? = null

        override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) {
            cursor = ResponseCursor(responseId, sequence, providerId)
        }

        override suspend fun load(runId: String): ResponseCursor? = cursor

        override suspend fun clear(runId: String) {
            cursor = null
        }
    }

    private class Source(private val request: Request) : EventSource {
        var cancelled = false
        override fun request(): Request = request
        override fun cancel() { cancelled = true }
    }

    private data class Stream(val request: Request, val listener: EventSourceListener, val source: Source) {
        fun event(type: String, data: String) = listener.onEvent(source, null, type, data)
    }

    private class Transport {
        val streams = mutableListOf<Stream>()
        fun open(request: Request, listener: EventSourceListener): EventSource = Source(request).also {
            streams += Stream(request, listener, it)
        }
    }

    private fun setting(enabled: Boolean = true, baseUrl: String = "https://api.openai.com/v1") =
        ProviderSetting.OpenAI(
            name = "OpenAI",
            baseUrl = baseUrl,
            useResponseApi = true,
            enableResponsesResume = enabled,
        )

    private fun params(store: ResponseResumeStore, resumeFrom: ResponseCursor? = null) = TextGenerationParams(
        model = Model(modelId = "gpt-5.4", displayName = "gpt-5.4"),
        responsesResume = ResponsesResumeRequest("run_1", store, resumeFrom),
    )

    private suspend fun Transport.awaitStream(index: Int = 0): Stream = withTimeout(5_000) {
        while (streams.size <= index) yield()
        streams[index]
    }

    private fun created() =
        """{"type":"response.created","sequence_number":0,"response":{"id":"resp_1","status":"in_progress"}}"""

    private fun delta(sequence: Int, text: String) =
        """{"type":"response.output_text.delta","sequence_number":$sequence,"item_id":"msg_1","delta":"$text"}"""

    private fun completed(sequence: Int, text: String = "") =
        """{"type":"response.completed","sequence_number":$sequence,"response":{"id":"resp_1","status":"completed","output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"$text"}]}]}}"""

    private fun MessageChunk.text() = choices.firstOrNull()?.delta?.parts
        ?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text }.orEmpty()

    @Test
    fun onlyOptedInOfficialStreamingCreationUsesBackgroundMode() = runBlocking {
        for (provider in listOf(setting(), setting(enabled = false), setting(baseUrl = "https://custom.example/v1"))) {
            val transport = Transport()
            val store = MemoryStore()
            val api = ResponseAPI(OkHttpClient(), transport = transport::open)
            val job = launch {
                api.streamText(provider, listOf(UIMessage.user("hello")), params(store)).collect()
            }
            try {
                val stream = transport.awaitStream()
                val body = json.parseToJsonElement(
                    Buffer().also { stream.request.body!!.writeTo(it) }.readUtf8()
                ).jsonObject
                val eligible = provider.enableResponsesResume && provider.baseUrl == "https://api.openai.com/v1"
                assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
                assertEquals(eligible, body["store"]?.jsonPrimitive?.content?.toBoolean())
                assertEquals(eligible, body["background"]?.jsonPrimitive?.content?.toBoolean() ?: false)
                stream.event("response.completed", completed(1))
                job.join()
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun nonStreamingEncoderDoesNotReturnAnImmediatelyQueuedBackgroundResponse() {
        val store = MemoryStore()
        val requestParams = params(store)
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(
            setting(), listOf(UIMessage.user("hello")), requestParams, stream = false,
            resume = requestParams.responsesResume,
        )
        assertFalse(body["background"]?.jsonPrimitive?.content?.toBoolean() ?: false)
    }

    @Test
    fun fullRebuildRequestsSseWithoutSkippingTheDurablyMissingPrefix() = runBlocking {
        val transport = Transport()
        val store = MemoryStore()
        val provider = setting()
        val cursor = ResponseCursor("resp_1", 8, provider.id.toString())
        store.cursor = cursor
        val api = ResponseAPI(OkHttpClient(), transport = transport::open)
        val job = launch {
            api.streamText(provider, listOf(UIMessage.assistant("partial")), params(store, cursor)).collect()
        }
        try {
            val stream = transport.awaitStream()
            assertEquals("GET", stream.request.method)
            assertEquals("true", stream.request.url.queryParameter("stream"))
            assertNull(stream.request.url.queryParameter("starting_after"))
            stream.event("response.created", created())
            stream.event("response.completed", completed(9, "complete"))
            job.join()
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun suffixReplayUsesServerCursorAndCheckpointsWithoutACreatedEvent() = runBlocking {
        val transport = Transport()
        val store = MemoryStore()
        val provider = setting()
        val cursor = ResponseCursor("resp_1", 3, provider.id.toString())
        store.cursor = cursor
        val api = ResponseAPI(OkHttpClient(), transport = transport::open)
        val chunks = mutableListOf<MessageChunk>()
        val accumulator = MessageStreamAccumulator(listOf(UIMessage.assistant("ha")))
        val job = launch {
            api.streamStored(provider, cursor.responseId, cursor, store, "run_1").collect {
                chunks += it
                accumulator.append(it)
            }
        }
        try {
            val stream = transport.awaitStream()
            assertEquals("true", stream.request.url.queryParameter("stream"))
            assertEquals("3", stream.request.url.queryParameter("starting_after"))
            stream.event("response.output_text.delta", delta(4, "ha"))
            stream.event("response.output_text.done",
                """{"type":"response.output_text.done","sequence_number":5,"item_id":"msg_1","text":"haha"}""")
            yield()
            assertEquals("Item snapshot must not invent a suffix before terminal delivery", "haha",
                accumulator.snapshot().single().parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
            stream.event("response.completed", completed(6, "haha"))
            job.join()
            assertEquals(ResponseCursor("resp_1", 6, provider.id.toString()), store.cursor)
            assertEquals("haha", accumulator.snapshot().single().parts
                .filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun createdIdentityIsDurableBeforeAnyVisibleOutput() = runBlocking {
        val transport = Transport()
        val store = MemoryStore()
        val provider = setting()
        val api = ResponseAPI(OkHttpClient(), transport = transport::open)
        val chunks = mutableListOf<MessageChunk>()
        val job = launch { api.streamText(provider, emptyList(), params(store)).toList(chunks) }
        try {
            val stream = transport.awaitStream()
            stream.event("response.created", created())
            yield()
            assertEquals(ResponseCursor("resp_1", 0, provider.id.toString()), store.cursor)
            assertTrue("Identity checkpoint must not create an empty assistant message", chunks.isEmpty())
            stream.event("response.completed", completed(1))
            job.join()
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun disconnectAfterCreatedBeforeTextCanReconnectAndKeepResponseIdentity() = runBlocking {
        val transport = Transport()
        val store = MemoryStore()
        val provider = setting()
        val api = ResponseAPI(OkHttpClient(), transport = transport::open)
        var failure: Throwable? = null
        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            try {
                api.streamText(provider, emptyList(), params(store)).toList(chunks)
            } catch (error: Throwable) {
                failure = error
            }
        }
        try {
            val initial = transport.awaitStream()
            initial.event("response.created", created())
            initial.listener.onFailure(initial.source, IOException("disconnected before first token"), null)
            yield()
            assertEquals("Created response must be resolvable for the existing single reconnect", 2, transport.streams.size)
            val replay = transport.streams[1]
            assertEquals("true", replay.request.url.queryParameter("stream"))
            assertEquals("0", replay.request.url.queryParameter("starting_after"))
            replay.event("response.output_text.delta", delta(1, "answer"))
            replay.event("response.completed", completed(2, "answer"))
            job.join()
            assertNull(failure)
            assertEquals("answer", chunks.joinToString("") { it.text() })
            assertEquals(ResponseCursor("resp_1", 2, provider.id.toString()), store.cursor)
        } finally {
            job.cancelAndJoin()
        }
    }
}
