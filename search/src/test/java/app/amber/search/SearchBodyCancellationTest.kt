package app.amber.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real socket reads remain pending after headers; cancellation must close the underlying call. */
class SearchBodyCancellationTest {
    @Test
    fun tavilyCancellationReachesCallDuringResponseBodyRead() = runBlocking {
        assertBodyCancellation(SearchServiceOptions.TavilyOptions(apiKey = "fixture-key"))
    }

    @Test
    fun exaCancellationReachesCallDuringResponseBodyRead() = runBlocking {
        assertBodyCancellation(SearchServiceOptions.ExaOptions(apiKey = "fixture-key"))
    }

    @Test
    fun zhipuCancellationReachesCallDuringResponseBodyRead() = runBlocking {
        assertBodyCancellation(SearchServiceOptions.ZhipuOptions(apiKey = "fixture-key"))
    }

    private suspend fun assertBodyCancellation(options: SearchServiceOptions) {
        val previous = SearchService.httpClient
        val server = ServerSocket(0).apply { soTimeout = 3_000 }
        val bodyStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val serverThread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\nx".toByteArray())
                        flush()
                    }
                    releaseServer.await(5, TimeUnit.SECONDS)
                }
            }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .url("http://127.0.0.1:${server.localPort}/search")
                    .build())
            }
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { activeCall.set(call) }
                override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
            })
            .build()
        SearchService.httpClient = client
        try {
            supervisorScope {
                val reading = async(Dispatchers.IO) {
                    SearchService.getService(options).search(
                        buildJsonObject { put("query", "amber") }, SearchCommonOptions(), options,
                    )
                }
                try {
                    assertTrue("${options::class.simpleName} must reach the response body", bodyStarted.await(3, TimeUnit.SECONDS))
                    val settled = withTimeoutOrNull(1_000) {
                        reading.cancelAndJoin()
                        true
                    } == true
                    assertTrue("${options::class.simpleName} cancellation must finish without waiting for body delivery", settled)
                    assertTrue("${options::class.simpleName} cancellation must reach the active HTTP Call after headers", activeCall.get().isCanceled())
                } finally {
                    releaseServer.countDown()
                    reading.cancel()
                }
            }
        } finally {
            SearchService.httpClient = previous
            releaseServer.countDown()
            server.close()
            serverThread.join(1_000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
