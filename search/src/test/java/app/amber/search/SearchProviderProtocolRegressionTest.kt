package app.amber.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SearchProviderProtocolRegressionTest {
    @Test
    fun freeSearchDedupePreservesCaseSensitivePathAndQuery() {
        assertNotEquals(
            FreeSearchAggregator.dedupeKey("https://en.wikipedia.org/wiki/Latex"),
            FreeSearchAggregator.dedupeKey("https://en.wikipedia.org/wiki/LaTeX"),
        )
        assertNotEquals(
            FreeSearchAggregator.dedupeKey("https://example.com/article?id=Foo"),
            FreeSearchAggregator.dedupeKey("https://example.com/article?id=foo"),
        )
        assertEquals(
            FreeSearchAggregator.dedupeKey("https://EXAMPLE.com/article"),
            FreeSearchAggregator.dedupeKey("https://example.com/article"),
        )
    }

    @Test
    fun currentSearXngResponseDoesNotRequireRemovedResultCount() {
        val response = SearchService.json.decodeFromString<SearXNGService.SearXNGResponse>(
            """{"query":"amber","results":[],"answers":[],"suggestions":[],"unresponsive_engines":[]}""",
        )

        assertEquals("amber", response.query)
        assertTrue(response.results.isEmpty())
    }

    @Test
    fun legacySearXngResponseWithCountStillDecodes() {
        val response = SearchService.json.decodeFromString<SearXNGService.SearXNGResponse>(
            """{"query":"amber","number_of_results":2,"results":[]}""",
        )

        assertEquals(2, response.numberOfResults)
    }

    @Test
    fun searXngDateReachesStandardResultWithoutInventingMissingDate() = runBlocking {
        withResponse(
            """{"query":"amber","results":[{"title":"Dated","url":"https://example.com/dated","content":"A fact","engine":"fixture","template":"default.html","publishedDate":"2026-10-25T08:00:00"},{"title":"Undated","url":"https://example.com/undated","content":"Another fact","engine":"fixture","template":"default.html"}]}""",
        ) {
            val result = SearXNGService.search(
                query(), SearchCommonOptions(), SearchServiceOptions.SearXNGOptions(url = "https://example.com"),
            ).getOrThrow()

            assertEquals(listOf("2026-10-25T08:00:00", null), result.items.map { it.publishedAt })
            assertEquals("A fact", result.items.first().text)
        }
    }

    @Test
    fun metasoDateReachesStandardResultAndMayBeAbsent() = runBlocking {
        withResponse(
            """{"credits":1,"searchParameters":{"q":"amber","scope":"webpage","size":2},"webpages":[{"title":"Dated","link":"https://example.com/dated","score":"0.9","snippet":"A fact","summary":null,"position":1,"date":"2026-10-25"},{"title":"Undated","link":"https://example.com/undated","score":"0.8","snippet":"Another fact","summary":null,"position":2}]}""",
        ) {
            val result = MetasoSearchService.search(
                query(), SearchCommonOptions(), SearchServiceOptions.MetasoOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertEquals(listOf("2026-10-25", null), result.items.map { it.publishedAt })
            assertEquals("A fact", result.items.first().text)
        }
    }

    @Test
    fun jinaPublishedTimeReachesStandardResultAndMayBeNull() {
        val result = JinaSearchService.mapJinaSearchResponse(
            """{"data":[{"title":"Dated","url":"https://example.com/dated","content":"A fact","publishedTime":"2026-10-25T08:00:00Z"},{"title":"Undated","url":"https://example.com/undated","content":"Another fact","publishedTime":null}]}""",
            resultSize = 2,
        )

        assertEquals(listOf("2026-10-25T08:00:00Z", null), result.items.map { it.publishedAt })
        assertEquals("A fact", result.items.first().text)
    }

    @Test
    fun jinaSearchDateSupportsDateOnlyResultsAndPreservesPublishedTimePriority() {
        val result = JinaSearchService.mapJinaSearchResponse(
            """{"data":[{"title":"Date only","url":"https://example.com/date-only","description":"A fact","date":"2026-10-24"},{"title":"Both dates","url":"https://example.com/both-dates","content":"Another fact","publishedTime":"2026-10-25T08:00:00Z","date":"2026-10-24"},{"title":"Null published time","url":"https://example.com/null-published-time","content":"A third fact","publishedTime":null,"date":"2026-10-23"}]}""",
            resultSize = 3,
        )

        assertEquals(
            listOf("2026-10-24", "2026-10-25T08:00:00Z", "2026-10-23"),
            result.items.map { it.publishedAt },
        )
        assertEquals("A fact", result.items.first().text)
    }

    @Test
    fun perplexityDateReachesStandardResultWithoutUsingLastUpdatedAsPublication() = runBlocking {
        withResponse(
            """{"results":[{"title":"Dated","url":"https://example.com/dated","snippet":"A fact","date":"2026-10-24","last_updated":"2026-10-25"},{"title":"Undated","url":"https://example.com/undated","snippet":"Another fact","last_updated":"2026-10-25"}]}""",
        ) {
            val result = PerplexitySearchService.search(
                query(), SearchCommonOptions(), SearchServiceOptions.PerplexityOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertEquals(listOf("2026-10-24", null), result.items.map { it.publishedAt })
            assertEquals("A fact", result.items.first().text)
        }
    }

    @Test
    fun bravePageAgeReachesStandardResultWithoutParsingRelativeAgeOrFetchDate() = runBlocking {
        withResponse(
            """{"web":{"results":[{"type":"search_result","title":"Dated","url":"https://example.com/dated","description":"A fact","page_age":"2026-10-24T08:00:00","age":"1 day ago"},{"type":"search_result","title":"Undated","url":"https://example.com/undated","description":"Another fact","age":"2 days ago","page_fetched":"2026-10-25T08:00:00Z"}]}}""",
        ) {
            val result = BraveSearchService.search(
                query(), SearchCommonOptions(), SearchServiceOptions.BraveOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertEquals(listOf("2026-10-24T08:00:00", null), result.items.map { it.publishedAt })
            assertEquals("A fact", result.items.first().text)
        }
    }

    @Test
    fun exaPublicationDateReachesStandardResult() = runBlocking {
        withResponse(
            """{"results":[{"id":"https://example.com/recent","title":"Recent","url":"https://example.com/recent","publishedDate":"2026-10-25","author":null,"text":"A fact"}]}""",
        ) {
            val result = ExaSearchService.search(
                query(), SearchCommonOptions(), SearchServiceOptions.ExaOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertEquals("2026-10-25", result.items.single().publishedAt)
            assertEquals("A fact", result.items.single().text)
        }
    }

    @Test
    fun zhipuPublicationDateReachesStandardResult() = runBlocking {
        withResponse(
            """{"search_result":[{"title":"Recent","link":"https://example.com/recent","content":"A fact","icon":null,"media":"Example","refer":"ref_1","publish_date":"2026-10-25"}]}""",
        ) {
            val result = ZhipuSearchService.search(
                query(), SearchCommonOptions(), SearchServiceOptions.ZhipuOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertEquals("2026-10-25", result.items.single().publishedAt)
            assertEquals("A fact", result.items.single().text)
        }
    }

    @Test
    fun zhipuRejectsOverlengthOriginalQueryBeforeMakingRequest() = runBlocking {
        var requested = false
        withResponse("""{"search_result":[]}""", onBody = { requested = true }) {
            val result = ZhipuSearchService.search(
                buildJsonObject { put("query", "a".repeat(71)) },
                SearchCommonOptions(),
                SearchServiceOptions.ZhipuOptions(apiKey = "fixture-key"),
            )

            assertTrue("Overlength original queries must fail explicitly", result.isFailure)
            assertFalse("Invalid input must not consume a provider request", requested)
        }
    }

    @Test
    fun tavilyNewsPublicationDateReachesStandardResult() {
        val result = TavilySearchService.mapTavilySearchResponse(
            """{"query":"amber","results":[{"title":"Recent","url":"https://example.com/recent","content":"A fact","score":0.9,"published_date":"2026-10-25"}]}""",
            resultSize = 1,
        )

        assertEquals("2026-10-25", result.items.single().publishedAt)
    }

    @Test
    fun tavilyForwardsNativeTimeRangeToHttpRequest() = runBlocking {
        assertTavilyRequestField("time_range", "day")
    }

    @Test
    fun tavilyForwardsExplicitRecencyDateBoundaryToHttpRequest() = runBlocking {
        assertTavilyRequestField("start_date", "2026-10-20")
    }

    @Test
    fun tavilyRecencyDaysOverridesExplicitDateAndRelativeTimeRange() = runBlocking {
        val beforeSearch = LocalDate.now().minusDays(3).toString()
        var requestBody: JsonObject? = null
        withResponse("""{"query":"amber","results":[]}""", onBody = { requestBody = it }) {
            TavilySearchService.search(
                buildJsonObject {
                    put("query", "amber")
                    put("topic", "news")
                    put("recency_days", 3)
                    put("start_date", "2000-01-01")
                    put("time_range", "day")
                },
                SearchCommonOptions(),
                SearchServiceOptions.TavilyOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            val afterSearch = LocalDate.now().minusDays(3).toString()
            assertTrue(requestBody?.get("start_date")?.jsonPrimitive?.content in setOf(beforeSearch, afterSearch))
            assertFalse("A relative range must not narrow the explicit day boundary", requestBody!!.containsKey("time_range"))
            assertFalse(requestBody!!.containsKey("recency_days"))
            assertFalse(requestBody!!.containsKey("days"))
        }
    }

    @Test
    fun tavilyExplicitDateOverridesTimeRangeAndAnyDoesNotReachNativeEnum() = runBlocking {
        var requestBody: JsonObject? = null
        withResponse("""{"query":"amber","results":[]}""", onBody = { requestBody = it }) {
            TavilySearchService.search(
                buildJsonObject {
                    put("query", "amber")
                    put("start_date", "2026-10-20")
                    put("time_range", "day")
                },
                SearchCommonOptions(),
                SearchServiceOptions.TavilyOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertEquals("2026-10-20", requestBody?.get("start_date")?.jsonPrimitive?.content)
            assertFalse(requestBody!!.containsKey("time_range"))

            TavilySearchService.search(
                buildJsonObject {
                    put("query", "amber")
                    put("time_range", "any")
                },
                SearchCommonOptions(),
                SearchServiceOptions.TavilyOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertFalse(requestBody!!.containsKey("time_range"))
            assertFalse(requestBody!!.containsKey("start_date"))
        }
    }

    @Test
    fun firecrawlNewsRequestDoesNotLoseNewsBehindFullWebArray() = runBlocking {
        withResponse(
            """{"success":true,"data":{"web":[{"title":"Web one","url":"https://example.com/w1"},{"title":"Web two","url":"https://example.com/w2"}],"news":[{"title":"News one","url":"https://example.com/n1","date":"2026-10-25"},{"title":"News two","url":"https://example.com/n2","date":"2026-10-24"}]}}""",
        ) {
            val result = FirecrawlSearchService.search(
                buildJsonObject {
                    put("query", "amber")
                    put("sources", buildJsonArray {
                        add(JsonPrimitive("news"))
                        add(JsonPrimitive("web"))
                    })
                },
                SearchCommonOptions(resultSize = 2),
                SearchServiceOptions.FirecrawlOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertEquals(2, result.items.size)
            assertEquals("Requested news source must retain priority", "News one", result.items.first().title)
            assertTrue("News rows must survive the combined result cap", result.items.any { it.publishedAt != null })
        }
    }

    @Test
    fun firecrawlKeepsRequestedSourceOrderAndCapsCombinedResults() {
        val body = """{"success":true,"data":{"web":[{"title":"Web one","url":"https://example.com/w1"},{"title":"Web two","url":"https://example.com/w2"}],"news":[{"title":"News one","url":"https://example.com/n1","date":"2026-10-25"}]}}"""

        val newsFirst = FirecrawlSearchService.mapFirecrawlSearchResponse(
            body, resultSize = 2, sourceOrder = listOf("news", "web"),
        )
        assertEquals(listOf("News one", "Web one"), newsFirst.items.map { it.title })

        val webFirst = FirecrawlSearchService.mapFirecrawlSearchResponse(
            body, resultSize = 2, sourceOrder = listOf("web", "news"),
        )
        assertEquals(listOf("Web one", "Web two"), webFirst.items.map { it.title })
    }

    private suspend fun assertTavilyRequestField(field: String, expected: String) {
        var requestBody: JsonObject? = null
        withResponse("""{"query":"amber","results":[]}""", onBody = { requestBody = it }) {
            TavilySearchService.search(
                buildJsonObject {
                    put("query", "amber")
                    put("topic", "news")
                    put(field, expected)
                },
                SearchCommonOptions(),
                SearchServiceOptions.TavilyOptions(apiKey = "fixture-key"),
            ).getOrThrow()

            assertEquals(expected, requestBody?.get(field)?.jsonPrimitive?.content)
        }
    }

    private fun query(): JsonObject = buildJsonObject { put("query", "amber") }

    private suspend fun withResponse(
        body: String,
        onBody: (JsonObject) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        val previous = SearchService.httpClient
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            if (buffer.size > 0) {
                onBody(SearchService.json.parseToJsonElement(buffer.readUtf8()).jsonObject)
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }.build()
        SearchService.httpClient = client
        try {
            block()
        } finally {
            SearchService.httpClient = previous
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
