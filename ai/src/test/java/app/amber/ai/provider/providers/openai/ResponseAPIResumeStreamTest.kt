package app.amber.ai.provider.providers.openai

import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.providers.StreamProtocol
import app.amber.ai.provider.providers.StreamTerminationGuard
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/** In-memory ResponseResumeStore for transport-level tests. */
private class InMemoryResumeStore : ResponseResumeStore {
    private var row: Pair<String, ResponseCursor>? = null

    override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) {
        row = runId to ResponseCursor(responseId, sequence, providerId)
    }

    override suspend fun load(runId: String): ResponseCursor? = row?.takeIf { it.first == runId }?.second

    override suspend fun clear(runId: String) {
        if (row?.first == runId) row = null
    }
}

private class FakeEventSource(private val request: Request) : EventSource {
    var cancelled = false
    override fun request(): Request = request
    override fun cancel() {
        cancelled = true
    }
}

/** Scripted SSE transport: records every streamed request+listener for the test to drive. */
private class FakeSseTransport {
    val streams = mutableListOf<Pair<Request, EventSourceListener>>()
    val sources = mutableListOf<FakeEventSource>()

    operator fun invoke(request: Request, listener: EventSourceListener): EventSource {
        val source = FakeEventSource(request)
        streams += request to listener
        sources += source
        return source
    }
}

/**
 * P6-01 — reconnect + dedup + cursor semantics of the stored-response stream,
 * driven through a fake SSE transport (no network).
 */
class ResponseAPIResumeStreamTest {

    @Test
    fun explicitResumeUsesGetAndReplacesPartialForStreamingAndNonStreaming() = runBlocking {
        for (streaming in listOf(true, false)) {
            val transport = FakeSseTransport()
            val store = InMemoryResumeStore()
            val setting = providerSetting()
            val cursor = ResponseCursor("resp_old", 5, setting.id.toString())
            store.save("run_1", cursor.responseId, cursor.sequence, cursor.providerId)
            val partial = UIMessage.assistant("ab") // the cursor is ahead of the durable text
            val accumulator = MessageStreamAccumulator(listOf(partial))
            val api = ResponseAPI(client = okhttp3.OkHttpClient(), transport = transport::invoke)
            val requestParams = params(ResponsesResumeRequest("run_1", store, resumeFrom = cursor))
            val job = launch {
                withTimeout(10_000) {
                    if (streaming) {
                        api.streamText(setting, listOf(partial), requestParams).collect(accumulator::append)
                    } else {
                        accumulator.append(api.generateText(setting, listOf(partial), requestParams))
                    }
                }
            }
            withTimeout(10_000) { while (transport.streams.isEmpty()) yield() }
            val (request, listener) = transport.streams.single()
            assertEquals("GET", request.method)
            assertEquals("/v1/responses/resp_old", request.url.encodedPath)
            assertEquals("true", request.url.queryParameter("stream"))
            assertNull(request.url.queryParameter("starting_after"))
            listener.onEvent(transport.sources.single(), null, "response.created", createdEvent(0, "resp_old"))
            for ((index, char) in "abcdef".withIndex()) {
                listener.onEvent(transport.sources.single(), null, "response.output_text.delta",
                    deltaEvent(index + 1L, "resp_old", "msg_old", char.toString()))
            }
            listener.onEvent(transport.sources.single(), null, "response.completed", completedEvent(7, "resp_old",
                """[{"type":"message","id":"msg_old","role":"assistant","content":[{"type":"output_text","text":"abcdef"}]}]"""))
            job.join()

            val recovered = accumulator.snapshot().single()
            assertEquals(partial.id, recovered.id)
            assertEquals("abcdef", recovered.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
            assertEquals(ResponseCursor("resp_old", 7, setting.id.toString()), store.load("run_1"))
        }
    }

    @Test
    fun storedCursorWithoutExplicitContinuationStartsANewModelRound() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val setting = providerSetting()
        store.save("run_1", "resp_previous_round", 5, setting.id.toString())
        val api = ResponseAPI(client = okhttp3.OkHttpClient(), transport = transport::invoke)
        val job = launch {
            withTimeout(10_000) {
                api.streamText(setting, listOf(UIMessage.user("next round")),
                    params(ResponsesResumeRequest("run_1", store))).toList()
            }
        }
        withTimeout(10_000) { while (transport.streams.isEmpty()) yield() }
        val (request, listener) = transport.streams.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.openai.com/v1/responses", request.url.toString())
        listener.onEvent(transport.sources.single(), null, "response.created", createdEvent(0, "resp_new"))
        listener.onEvent(transport.sources.single(), null, "response.completed", completedEvent(1, "resp_new", "[]"))
        job.join()
        assertEquals("resp_new", store.load("run_1")?.responseId)
    }

    private fun providerSetting(
        baseUrl: String = "https://api.openai.com/v1",
        authMode: OpenAIAuthMode = OpenAIAuthMode.API_KEY,
        useResponseApi: Boolean = true,
        enableResponsesResume: Boolean = true,
    ) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        baseUrl = baseUrl,
        useResponseApi = useResponseApi,
        enableResponsesResume = enableResponsesResume,
        authMode = authMode,
        name = "OpenAI",
    )

    private fun params(resume: ResponsesResumeRequest?): TextGenerationParams = TextGenerationParams(
        model = Model(modelId = "gpt-5.4", displayName = "gpt-5.4"),
        responsesResume = resume,
    )

    private fun createdEvent(sequence: Long, responseId: String): String = buildJsonObject {
        put("type", "response.created")
        put("sequence_number", sequence)
        put("response", buildJsonObject {
            put("id", responseId)
            put("object", "response")
            put("status", "in_progress")
        })
    }.toString()

    private fun deltaEvent(sequence: Long, responseId: String, itemId: String, delta: String): String =
        buildJsonObject {
            put("type", "response.output_text.delta")
            put("sequence_number", sequence)
            put("item_id", itemId)
            put("delta", delta)
        }.toString()

    private fun completedEvent(sequence: Long, responseId: String, outputJson: String): String =
        buildJsonObject {
            put("type", "response.completed")
            put("sequence_number", sequence)
            put("response", buildJsonObject {
                put("id", responseId)
                put("status", "completed")
                put("output", json.parseToJsonElement(outputJson))
            })
        }.toString()

    private fun deltaText(chunk: MessageChunk): String =
        chunk.choices.firstOrNull()
            ?.delta?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text }
            ?: chunk.choices.firstOrNull()
                ?.message?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text }
            ?: ""

    @Test
    fun disconnectReconnectsToStoredResponseAndDedupsEvents() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        val setting = providerSetting()
        val resume = ResponsesResumeRequest(runId = "run_1", store = store)

        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            withTimeout(10_000) {
                api.streamText(setting, emptyList(), params(resume)).toList(chunks)
            }
        }
        // Wait for the POST stream to be registered.
        while (transport.streams.isEmpty()) yield()

        // First stream: created + deltas 1..5, then the connection drops.
        transport.streams[0].second.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        for (i in 1..5) {
            transport.streams[0].second.onEvent(
                transport.sources[0], null, "response.output_text.delta", deltaEvent(i.toLong(), "resp_1", "msg_1", "abcde"[i - 1].toString())
            )
        }
        yield()
        // write-ahead cursor at the last emitted event
        assertEquals(5L, store.load("run_1")!!.sequence)
        transport.streams[0].second.onFailure(transport.sources[0], IOException("connection reset"), null)

        // The drain asks the server for SSE events after the durable cursor.
        while (transport.streams.size < 2) yield()
        val (replayRequest, replayListener) = transport.streams[1]
        assertEquals("GET", replayRequest.method)
        assertEquals("/v1/responses/resp_1", replayRequest.url.encodedPath)
        assertEquals("true", replayRequest.url.queryParameter("stream"))
        assertEquals("5", replayRequest.url.queryParameter("starting_after"))

        // Duplicate history is still accepted, without contaminating final
        // reconciliation. The terminal output supplies the final-only suffix k.
        replayListener.onEvent(transport.sources[1], null, "response.created", createdEvent(0, "resp_1"))
        for (i in 1..10) {
            replayListener.onEvent(
                transport.sources[1], null, "response.output_text.delta", deltaEvent(i.toLong(), "resp_1", "msg_1", "abcdefghij"[i - 1].toString())
            )
        }
        replayListener.onEvent(
            transport.sources[1], null, "response.completed",
            buildJsonObject {
                put("type", "response.completed")
                put("sequence_number", 11)
                put("response", buildJsonObject {
                    put("id", "resp_1")
                    put("status", "completed")
                    put("output", json.parseToJsonElement(
                        """[{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[{"type":"output_text","text":"abcdefghijk"}]}]"""
                    ))
                })
            }.toString()
        )

        job.join()

        // 1..5 from the first stream + 6..10 from the replay (dedup skipped
        // the replayed 1..5) + the terminal chunk = 11 chunks, no duplicates.
        assertEquals(11, chunks.size)
        val deltaTexts = chunks.take(10).joinToString("") { deltaText(it) }
        assertEquals("abcdefghij", deltaTexts)
        assertEquals(10, deltaTexts.toSet().size)
        assertEquals("abcdefghijk", chunks.joinToString("") { deltaText(it) })
        // The chat owner clears the cursor after its durable final
        // conversation checkpoint; this transport-level test has no owner,
        // so the terminal cursor remains available for recovery.
        assertEquals(11L, store.load("run_1")?.sequence)
    }

    @Test
    fun secondDisconnectFailsWithoutAThirdRequestAndCancelsBothSources() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val setting = providerSetting()
        val api = ResponseAPI(okhttp3.OkHttpClient(), transport = transport::invoke)
        val chunks = mutableListOf<MessageChunk>()
        val outcome = CompletableDeferred<Result<Unit>>()
        val job = launch {
            outcome.complete(runCatching {
                withTimeout(10_000) {
                    api.streamText(setting, emptyList(), params(ResponsesResumeRequest("run_1", store)))
                        .collect { chunks += it }
                }
            })
        }
        withTimeout(10_000) { while (transport.streams.isEmpty()) yield() }
        val initial = transport.streams[0].second
        initial.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        initial.onEvent(transport.sources[0], null, "response.output_text.delta", deltaEvent(1, "resp_1", "msg_1", "a"))
        withTimeout(10_000) { while (store.load("run_1")?.sequence != 1L) yield() }
        initial.onFailure(transport.sources[0], IOException("first disconnect"), null)
        withTimeout(10_000) { while (transport.streams.size < 2) yield() }
        assertEquals("GET", transport.streams[1].first.method)
        val replay = transport.streams[1].second
        replay.onEvent(transport.sources[1], null, "response.created", createdEvent(0, "resp_1"))
        replay.onEvent(transport.sources[1], null, "response.output_text.delta", deltaEvent(2, "resp_1", "msg_1", "b"))
        withTimeout(10_000) { while (store.load("run_1")?.sequence != 2L) yield() }
        val secondFailure = IOException("second disconnect must escape")
        replay.onFailure(transport.sources[1], secondFailure, null)
        val result = withTimeout(10_000) { outcome.await() }
        job.join()

        val failure = requireNotNull(result.exceptionOrNull())
        assertTrue(failure is IOException)
        assertEquals(secondFailure.message, failure.message)
        assertTrue("Coroutine stacktrace recovery must retain the original second failure",
            generateSequence(failure) { it.cause }.any { it === secondFailure })
        assertEquals(2, transport.streams.size)
        assertTrue("Both real transport handles must be cancelled", transport.sources.all { it.cancelled })
        assertEquals("ab", chunks.joinToString("") { deltaText(it) })
        assertEquals(ResponseCursor("resp_1", 2, setting.id.toString()), store.load("run_1"))
    }

    @Test
    fun collectingCancellationCancelsTheActiveEventSourceWithoutReconnectOrCursorDeletion() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val setting = providerSetting()
        val api = ResponseAPI(okhttp3.OkHttpClient(), transport = transport::invoke)
        val chunks = mutableListOf<MessageChunk>()
        val received = CompletableDeferred<Unit>()
        val job = launch {
            api.streamText(setting, emptyList(), params(ResponsesResumeRequest("run_1", store))).collect {
                chunks += it
                received.complete(Unit)
            }
        }
        withTimeout(10_000) { while (transport.streams.isEmpty()) yield() }
        val listener = transport.streams.single().second
        val source = transport.sources.single()
        listener.onEvent(source, null, "response.created", createdEvent(0, "resp_1"))
        listener.onEvent(source, null, "response.output_text.delta", deltaEvent(1, "resp_1", "msg_1", "a"))
        withTimeout(10_000) { received.await(); job.cancelAndJoin() }

        assertTrue(job.isCancelled)
        assertTrue("Cancellation must reach the actual EventSource handle", source.cancelled)
        assertEquals(1, transport.streams.size)
        assertEquals(ResponseCursor("resp_1", 1, setting.id.toString()), store.load("run_1"))
        listener.onEvent(source, null, "response.completed", completedEvent(2, "resp_1", "[]"))
        listener.onFailure(source, IOException("late callback after cancellation"), null)
        yield()
        assertEquals("Late provider callbacks cannot reach the cancelled consumer", "a", chunks.joinToString("") { deltaText(it) })
        assertEquals(1, transport.streams.size)
        assertEquals(1L, store.load("run_1")?.sequence)
    }

    @Test
    fun cursorSaveCompletesBeforeAnyDownstreamEventIsVisible() = runBlocking {
        val transport = FakeSseTransport()
        val durable = InMemoryResumeStore()
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val store = object : ResponseResumeStore by durable {
            override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) {
                if (sequence == 1L) {
                    saveEntered.complete(Unit)
                    releaseSave.await()
                }
                durable.save(runId, responseId, sequence, providerId)
            }
        }
        val setting = providerSetting()
        val api = ResponseAPI(okhttp3.OkHttpClient(), transport = transport::invoke)
        val chunks = mutableListOf<MessageChunk>()
        val cursorAtEmission = mutableListOf<Long>()
        val job = launch {
            withTimeout(10_000) {
                api.streamText(setting, emptyList(), params(ResponsesResumeRequest("run_1", store))).collect {
                    cursorAtEmission += requireNotNull(store.load("run_1")).sequence
                    chunks += it
                }
            }
        }
        withTimeout(10_000) { while (transport.streams.isEmpty()) yield() }
        val listener = transport.streams.single().second
        val source = transport.sources.single()
        listener.onEvent(source, null, "response.created", createdEvent(0, "resp_1"))
        listener.onEvent(source, null, "response.output_text.delta", deltaEvent(1, "resp_1", "msg_1", "a"))
        listener.onEvent(source, null, "response.completed", completedEvent(2, "resp_1",
            """[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"a"}]}]"""))
        withTimeout(10_000) { saveEntered.await() }
        assertTrue("Queued events must not escape a suspended cursor save", chunks.isEmpty())
        assertEquals(ResponseCursor("resp_1", 0, setting.id.toString()), store.load("run_1"))
        releaseSave.complete(Unit)
        job.join()

        assertEquals(listOf(1L, 2L), cursorAtEmission)
        assertEquals("a", chunks.joinToString("") { deltaText(it) })
        assertEquals(2, chunks.size)
        assertEquals(ResponseCursor("resp_1", 2, setting.id.toString()), store.load("run_1"))
        assertTrue(source.cancelled)
    }

    @Test
    fun resumeDisabledKeepsTodayBehaviorNoCursorAndStoreFalse() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        val setting = providerSetting(enableResponsesResume = true)

        // store=false unless a resume request is carried (plan: store=false
        // keeps the exact pre-P6-01 behavior).
        val bodyWithoutResume = api.buildRequestBody(
            providerSetting = setting,
            messages = emptyList(),
            params = params(resume = null),
            stream = true,
        )
        assertEquals(false, bodyWithoutResume["store"]?.jsonPrimitive?.contentOrNull?.toBoolean())

        val bodyWithResume = api.buildRequestBody(
            providerSetting = setting,
            messages = emptyList(),
            params = params(resume = ResponsesResumeRequest(runId = "run_1", store = store)),
            stream = true,
            resume = ResponsesResumeRequest(runId = "run_1", store = store),
        )
        assertEquals(true, bodyWithResume["store"]?.jsonPrimitive?.contentOrNull?.toBoolean())

        // A stream without a resume request never touches the cursor store.
        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            withTimeout(10_000) {
                api.streamText(setting, emptyList(), params(resume = null)).toList(chunks)
            }
        }
        while (transport.streams.isEmpty()) yield()
        transport.streams[0].second.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        transport.streams[0].second.onEvent(
            transport.sources[0], null, "response.output_text.delta", deltaEvent(1, "resp_1", "msg_1", "x")
        )
        transport.streams[0].second.onEvent(
            transport.sources[0], null, "response.completed",
            buildJsonObject {
                put("type", "response.completed")
                put("sequence_number", 2)
                put("response", buildJsonObject {
                    put("id", "resp_1")
                    put("status", "completed")
                    put("output", json.parseToJsonElement("""[]"""))
                })
            }.toString()
        )
        job.join()
        assertNull(store.load("run_1"))
        // delta + terminal chunk.
        assertEquals(2, chunks.size)
        // A network failure without resume fails the flow (no reconnect).
        assertEquals(1, transport.streams.size)
    }

    @Test
    fun userToggleOffNeverSendsStoreTrueEvenWithResumeRequest() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        // Capability side fully on (strict official-endpoint match) but the
        // user switch is off: the provider must ignore the resume request —
        // store=false, no cursor writes, no reconnect (pre-P6-01 behavior).
        val setting = providerSetting(enableResponsesResume = false)
        val resumeRequest = ResponsesResumeRequest(runId = "run_1", store = store)

        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            runCatching {
                withTimeout(10_000) {
                    api.streamText(setting, emptyList(), params(resume = resumeRequest)).toList(chunks)
                }
            }
        }
        while (transport.streams.isEmpty()) yield()
        // The wire body must carry store=false — the resume request was dropped.
        val wireBody = json.parseToJsonElement(
            Buffer().also { transport.streams[0].first.body!!.writeTo(it) }.readUtf8()
        ).jsonObject
        assertEquals(false, wireBody["store"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        transport.streams[0].second.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        transport.streams[0].second.onEvent(
            transport.sources[0], null, "response.output_text.delta", deltaEvent(1, "resp_1", "msg_1", "x")
        )
        yield()
        // The cursor store is never touched.
        assertNull(store.load("run_1"))
        // A mid-stream failure does not reconnect: resume is not honored.
        transport.streams[0].second.onFailure(transport.sources[0], IOException("connection reset"), null)
        job.join()
        assertEquals(1, transport.streams.size)
        // The delta was still delivered; the failure ended the stream.
        assertEquals("x", deltaText(chunks.single()))
    }

    @Test
    fun replayAfterCursorSkipsAlreadyDeliveredEvents() = runBlocking {
        // Directly exercise streamStored with a persisted cursor: only events
        // above the cursor are delivered (server replays from sequence 0).
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        val setting = providerSetting()

        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            withTimeout(10_000) {
                api.streamStored(
                    providerSetting = setting,
                    responseId = "resp_1",
                    cursor = ResponseCursor("resp_1", sequence = 3, providerId = setting.id.toString()),
                    store = store,
                    runId = "run_1",
                ).toList(chunks)
            }
        }
        while (transport.streams.isEmpty()) yield()
        val listener = transport.streams[0].second
        listener.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        for (i in 0..5) {
            listener.onEvent(
                transport.sources[0], null, "response.output_text.delta", deltaEvent(i.toLong(), "resp_1", "msg_1", "abcdef"[i].toString())
            )
        }
        listener.onEvent(
            transport.sources[0], null, "response.completed",
            buildJsonObject {
                put("type", "response.completed")
                put("sequence_number", 6)
                put("response", buildJsonObject {
                    put("id", "resp_1")
                    put("status", "completed")
                    put("output", json.parseToJsonElement("""[]"""))
                })
            }.toString()
        )
        job.join()

        // seq 0..3 skipped (already delivered), 4..5 emitted + terminal.
        assertEquals(3, chunks.size)
        assertEquals("ef", chunks.take(2).joinToString("") { deltaText(it) })
    }
}
