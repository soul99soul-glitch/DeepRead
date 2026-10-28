package app.amber.feature.miniapp

import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.review.ReflectionFixture
import app.amber.search.SearchService
import app.amber.search.SearchServiceOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAppSearchBridgeContractTest {
    @Test
    fun answerOnlyProviderSuccessRemainsEmptyInThePageListContract() = runBlocking {
        val output = searchWithGrok(emptyList())

        assertEquals("empty", output["status"]!!.jsonPrimitive.content)
        assertTrue(output["items"]!!.jsonArray.isEmpty())
        assertFalse(output.containsKey("answers"))
    }

    @Test
    fun providerPageResultsKeepTheirSuccessfulStatusAndUrl() = runBlocking {
        val output = searchWithGrok(listOf("https://example.test/source"))

        assertEquals("ok", output["status"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/source", output["items"]!!.jsonArray.single().jsonObject["url"]!!.jsonPrimitive.content)
    }

    private suspend fun searchWithGrok(citations: List<String>): JsonObject {
        val grok = SearchServiceOptions.GrokOptions(apiKey = "fixture-key")
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(Settings(
            searchServices = listOf(grok),
            searchEnabledServiceIds = listOf(grok.id),
        )))
        val urls = JsonArray(citations.map(::JsonPrimitive))
        val response = """{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Grounded provider answer","annotations":[]}]}],"citations":$urls}"""
        val clientField = SearchService.Companion::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        val previous = clientField.get(null)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(response.toResponseBody()).build()
        }.build()
        try {
            clientField.set(null, client)
            return MiniAppSearchBridge(settings).search(buildJsonObject { put("query", "amber") })
        } finally {
            clientField.set(null, previous)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
