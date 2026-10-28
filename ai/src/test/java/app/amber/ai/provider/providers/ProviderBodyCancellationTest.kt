package app.amber.ai.provider.providers

import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.openai.ChatCompletionsAPI
import app.amber.ai.provider.providers.openai.ResponseAPI
import app.amber.ai.ui.UIMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProviderBodyCancellationTest {
    @Test fun claudeCompletionCancelsWhileReadingResponseBody() = runBlocking {
        assertBodyCancellation { client, baseUrl ->
            ClaudeProvider(client).complete(
                ProviderSetting.Claude(baseUrl = baseUrl, apiKey = "fixture-key"),
                listOf(UIMessage.user("hello")),
                TextGenerationParams(Model(modelId = "claude-fixture")),
            )
        }
    }

    @Test fun googleCompletionCancelsWhileReadingResponseBody() = runBlocking {
        assertBodyCancellation { client, baseUrl ->
            GoogleProvider(client).complete(
                ProviderSetting.Google(baseUrl = baseUrl, apiKey = "fixture-key"),
                listOf(UIMessage.user("hello")),
                TextGenerationParams(Model(modelId = "gemini-fixture")),
            )
        }
    }

    @Test fun chatCompletionCancelsWhileReadingResponseBody() = runBlocking {
        assertBodyCancellation { client, baseUrl ->
            ChatCompletionsAPI(client).generateText(
                ProviderSetting.OpenAI(baseUrl = baseUrl, apiKey = "fixture-key"),
                listOf(UIMessage.user("hello")),
                TextGenerationParams(Model(modelId = "chat-fixture")),
            )
        }
    }

    @Test fun responsesCompletionCancelsWhileReadingResponseBody() = runBlocking {
        assertBodyCancellation { client, baseUrl ->
            ResponseAPI(client).generateText(
                ProviderSetting.OpenAI(baseUrl = baseUrl, apiKey = "fixture-key"),
                listOf(UIMessage.user("hello")),
                TextGenerationParams(Model(modelId = "responses-fixture")),
            )
        }
    }

    @Test fun openAiImageGenerationCancelsWhileReadingResponseBody() = runBlocking {
        assertBodyCancellation { client, baseUrl ->
            OpenAIProvider(client).generateImage(
                ProviderSetting.OpenAI(baseUrl = baseUrl, apiKey = "fixture-key"),
                ImageGenerationParams(Model(modelId = "image-fixture"), prompt = "a circle"),
            )
        }
    }

    @Test fun googleImageGenerationCancelsWhileReadingResponseBody() = runBlocking {
        assertBodyCancellation { client, baseUrl ->
            GoogleProvider(client).generateImage(
                ProviderSetting.Google(baseUrl = baseUrl, apiKey = "fixture-key"),
                ImageGenerationParams(Model(modelId = "image-fixture"), prompt = "a circle"),
            )
        }
    }

    private suspend fun assertBodyCancellation(request: suspend (OkHttpClient, String) -> Unit) {
        val server = ServerSocket(0)
        val bodyStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val serverThread = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\nx".toByteArray())
                    flush()
                }
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { activeCall.set(call) }
            override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
        }).build()
        try {
            kotlinx.coroutines.supervisorScope {
                val reading = async(Dispatchers.IO) {
                    request(client, "http://127.0.0.1:${server.localPort}/v1")
                }
                try {
                    assertTrue("Provider must reach the body read", bodyStarted.await(2, TimeUnit.SECONDS))
                    reading.cancel()
                    assertTrue("Cancellation must reach the active HTTP Call after headers", activeCall.get().isCanceled())
                    withTimeout(1_000) { reading.cancelAndJoin() }
                } finally {
                    releaseServer.countDown()
                    reading.cancel()
                }
            }
        } finally {
            releaseServer.countDown()
            server.close()
            serverThread.join(1_000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
