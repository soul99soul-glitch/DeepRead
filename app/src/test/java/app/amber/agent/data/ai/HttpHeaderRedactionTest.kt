package app.amber.core.ai

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HttpHeaderRedactionTest {
    @Test
    fun applicationLogsMaskBraveAndOtherCredentialsWithoutHidingOrdinaryHeaders() {
        val headers = Headers.Builder()
            .add("X-Subscription-Token", "brave-fixture-secret")
            .add("AUTHORIZATION", "Bearer fixture-secret")
            .add("Content-Type", "application/json")
            .build()

        val redacted = headers.toRedactedMap()

        assertEquals("[redacted]", redacted["X-Subscription-Token"])
        assertEquals("[redacted]", redacted["AUTHORIZATION"])
        assertEquals("application/json", redacted["Content-Type"])
    }

    @Test
    fun debugHttpLoggerUsesTheSameCredentialRedactions() {
        val lines = mutableListOf<String>()
        val logger = HttpLoggingInterceptor { lines += it }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            sensitiveHttpHeaders.forEach(::redactHeader)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logger)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()
        try {
            client.newCall(Request.Builder().url("https://example.test/search")
                .header("X-Subscription-Token", "brave-fixture-secret")
                .build()).execute().close()

            assertFalse(lines.joinToString("\n").contains("brave-fixture-secret"))
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
