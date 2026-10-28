package app.amber.feature.miniapp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MiniAppHttpClientTest {
    @Test
    fun temporaryAndPermanentRedirectsPreservePostBody() = runBlocking {
        for (status in listOf(307, 308)) {
            val requests = mutableListOf<Request>()
            val http = redirectingClient(status, requests)

            val result = http.fetch(postParams()).jsonObject

            assertEquals(true, result["ok"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(listOf("POST", "POST"), requests.map { it.method })
            assertEquals("/destination", requests.last().url.encodedPath)
            assertEquals("{\"query\":\"amber\"}", requests.last().bodyText())
            assertEquals("application", requests.last().body?.contentType()?.type)
            assertEquals("json", requests.last().body?.contentType()?.subtype)
        }
    }

    @Test
    fun seeOtherRedirectChangesPostIntoGet() = runBlocking {
        val requests = mutableListOf<Request>()

        redirectingClient(303, requests).fetch(postParams())

        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        assertEquals(null, requests.last().body)
    }

    @Test
    fun redirectedPostStillRejectsPrivateDestinationBeforeDispatch() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = redirectingClient(307, requests, "https://internal.example/destination")

        val failure = runCatching { http.fetch(postParams()) }.exceptionOrNull()

        assertTrue(failure is MiniAppValidationException)
        assertEquals(1, requests.size)
    }

    private fun redirectingClient(
        status: Int,
        requests: MutableList<Request>,
        location: String = "https://public.example/destination",
    ): MiniAppHttpClient {
        val guard = MiniAppUrlGuard { host ->
            listOf(InetAddress.getByName(if (host == "internal.example") "127.0.0.1" else "93.184.216.34"))
        }
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (requests.size == 1) status else 200)
                    .message("fixture")
                    .apply { if (requests.size == 1) header("Location", location) }
                    .body("ok".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        return MiniAppHttpClient(urlGuard = guard, client = client)
    }

    private fun postParams() = buildJsonObject {
        put("url", "https://public.example/original")
        put("method", "POST")
        put("body", "{\"query\":\"amber\"}")
        put("contentType", "application/json")
    }

    private fun Request.bodyText(): String? = body?.let { body ->
        Buffer().also(body::writeTo).readUtf8()
    }
}
