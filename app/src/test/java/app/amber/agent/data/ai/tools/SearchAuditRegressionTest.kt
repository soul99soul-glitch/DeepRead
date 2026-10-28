package app.amber.core.ai.tools

import app.amber.core.settings.Settings
import app.amber.core.utils.toLocalString
import app.amber.search.SearchCommonOptions
import app.amber.search.SearchResult
import app.amber.search.SearchServiceOptions
import app.amber.search.ZhipuSearchService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class SearchAuditRegressionTest {
    @Test
    fun newsReachesNativeSerperAndSerpApiRoutingWithoutDroppingTheTimeHint() {
        listOf(
            SearchServiceOptions.SerperOptions(apiKey = "key"),
            SearchServiceOptions.SerpApiOptions(apiKey = "key"),
        ).forEach { options ->
            val params = serviceParams(options, query = "release", timeRange = "day")
            assertEquals("news", params["topic"]?.jsonPrimitive?.content)
            assertTrue(params["query"]!!.jsonPrimitive.content.contains("today"))
        }
    }

    @Test
    fun tavilyReceivesNativeDateWindowAndExplicitRecencyWithoutQueryDecoration() {
        val options = SearchServiceOptions.TavilyOptions(apiKey = "key")
        val ranged = serviceParams(options, query = "release", timeRange = "week")
        assertEquals("release", ranged["query"]!!.jsonPrimitive.content)
        assertEquals("news", ranged["topic"]!!.jsonPrimitive.content)
        assertEquals("week", ranged["time_range"]?.jsonPrimitive?.content)

        val recent = serviceParams(options, query = "release", timeRange = "month", recencyDays = 3)
        assertEquals("3", recent["recency_days"]?.jsonPrimitive?.content)
        assertEquals("month", recent["time_range"]?.jsonPrimitive?.content)
        val unrestricted = serviceParams(options, query = "release", timeRange = "any")
        assertFalse("any is not a native Tavily time_range", unrestricted.containsKey("time_range"))
        val invalidRange = serviceParams(options, query = "release", timeRange = "invalid")
        assertFalse(invalidRange.containsKey("time_range"))
    }

    @Test
    fun orchestrationPassesNativeNewsParametersAndTheRequestedLocaleToProviders() = runBlocking {
        val configured = listOf(
            SearchServiceOptions.SerperOptions(apiKey = "key"),
            SearchServiceOptions.SerpApiOptions(apiKey = "key"),
            SearchServiceOptions.TavilyOptions(apiKey = "key"),
        )
        listOf(Locale.US, Locale.SIMPLIFIED_CHINESE).forEach { locale ->
            val seen = mutableMapOf<String, JsonObject>()
            SearchOrchestrator.search(
                settings = settings(*configured.toTypedArray()),
                params = buildJsonObject {
                    put("query", "release")
                    put("topic", "news")
                    put("time_range", "day")
                    put("depth", "quick")
                },
                executor = { request, _ ->
                    seen[request.source.id] = SearchOrchestrator.run { request.serviceParams() }
                    Result.success(SearchResult(items = emptyList()))
                },
                locale = locale,
            )

            configured.forEach { options ->
                val params = seen.getValue(options.id.toString())
                assertEquals("news", params["topic"]?.jsonPrimitive?.content)
                if (options is SearchServiceOptions.TavilyOptions) {
                    assertEquals("release", params["query"]!!.jsonPrimitive.content)
                    assertEquals("day", params["time_range"]?.jsonPrimitive?.content)
                } else {
                    val date = LocalDate.now().toLocalString(true, locale)
                    assertEquals("release news today $date", params["query"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun zhipuUsesTheOriginalQueryWhenHostEnrichmentWouldExceedItsLimit() {
        val configured = SearchServiceOptions.ZhipuOptions(apiKey = "key")
        listOf(ZhipuSearchService.MAX_QUERY_LENGTH - 10, ZhipuSearchService.MAX_QUERY_LENGTH).forEach { length ->
            val original = "问".repeat(length)
            val params = serviceParams(configured, query = original)
            assertEquals(original, params["query"]!!.jsonPrimitive.content)
        }
        val short = serviceParams(configured, query = "AI")
        assertTrue(short["query"]!!.jsonPrimitive.content.contains("news this week"))
    }

    @Test
    fun zhipuGeneratedVariantsNeverExceedTheProviderLimit() = runBlocking {
        val configured = SearchServiceOptions.ZhipuOptions(apiKey = "key")
        val brave = SearchServiceOptions.BraveOptions(apiKey = "key")
        val seen = mutableListOf<Pair<SearchOrchestrator.SourceSearchRequest, JsonObject>>()
        SearchOrchestrator.search(
            settings = settings(configured, brave),
            params = buildJsonObject {
                put("query", "AI")
                put("topic", "news")
                put("depth", "deep")
            },
            executor = { request, _ ->
                seen += request to SearchOrchestrator.run { request.serviceParams() }
                Result.success(SearchResult(items = emptyList()))
            },
        )

        val zhipuCalls = seen.filter { it.first.source.id == configured.id.toString() }
        assertTrue(zhipuCalls.isNotEmpty())
        assertTrue("Host-generated invalid query: $zhipuCalls", zhipuCalls.all {
            it.second["query"]!!.jsonPrimitive.content.length <= ZhipuSearchService.MAX_QUERY_LENGTH
        })
        assertTrue(zhipuCalls.any { it.first.query == "AI" })
        assertTrue(zhipuCalls.all { it.first.query.length <= ZhipuSearchService.MAX_QUERY_LENGTH })
        assertTrue(seen.any {
            it.first.source.id == brave.id.toString() && it.first.query.length > ZhipuSearchService.MAX_QUERY_LENGTH
        })
    }

    @Test
    fun zhipuReceivesAnOversizedOriginalQueryUnchangedForAdapterValidation() = runBlocking {
        val original = "问".repeat(ZhipuSearchService.MAX_QUERY_LENGTH + 1)
        val seen = mutableListOf<JsonObject>()
        SearchOrchestrator.search(
            settings = settings(SearchServiceOptions.ZhipuOptions(apiKey = "key")),
            params = buildJsonObject {
                put("query", original)
                put("topic", "news")
                put("depth", "deep")
            },
            executor = { request, _ ->
                seen += SearchOrchestrator.run { request.serviceParams() }
                Result.success(SearchResult(items = emptyList()))
            },
        )

        assertEquals(1, seen.size)
        assertEquals(original, seen.single()["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun configuredAggregationPreservesAnswerOnlySuccessWithSourceAttribution() = runBlocking {
        val configured = SearchServiceOptions.GrokOptions(apiKey = "key")
        val output = SearchAggregator.search(
            settings(configured),
            buildJsonObject { put("query", "grounded answer") },
            executor = { _, _, _ -> Result.success(SearchResult(answer = ANSWER, items = emptyList())) },
        )

        assertAnswer(output, configured)
    }

    @Test
    fun orchestrationPreservesAnswerOnlySuccessAndDoesNotOfferAnEmptyResultMessage() = runBlocking {
        val configured = SearchServiceOptions.GrokOptions(apiKey = "key")
        val output = SearchOrchestrator.search(
            settings(configured),
            buildJsonObject { put("query", "grounded answer"); put("depth", "quick") },
            executor = { _, _ -> Result.success(SearchResult(answer = ANSWER, items = emptyList())) },
        )

        assertAnswer(output, configured)
        assertFalse(output.containsKey("message"))
    }

    @Test
    fun answerOnlySuccessIsPartialWhenAnotherSourceFails() = runBlocking {
        val configured = SearchServiceOptions.GrokOptions(apiKey = "key")
        val failed = SearchServiceOptions.BraveOptions(apiKey = "key")
        searchBoth(listOf(configured, failed)) { options ->
            if (options.id == configured.id) {
                Result.success(SearchResult(answer = ANSWER, items = emptyList()))
            } else {
                Result.failure(IllegalStateException("source failed"))
            }
        }.forEach { output ->
            assertEquals("partial", output["status"]!!.jsonPrimitive.content)
            assertTrue(output["items"]!!.jsonArray.isEmpty())
            assertEquals(ANSWER, output["answers"]!!.jsonArray.single().jsonObject["answer"]!!.jsonPrimitive.content)
            assertFalse(output.containsKey("message"))
        }
    }

    @Test
    fun whitespaceAnswersDoNotTurnAnEmptySearchIntoSuccess() = runBlocking {
        val configured = SearchServiceOptions.GrokOptions(apiKey = "key")
        searchBoth(listOf(configured)) {
            Result.success(SearchResult(answer = " \n\t ", items = emptyList()))
        }.forEach { output ->
            assertEquals("empty", output["status"]!!.jsonPrimitive.content)
            assertTrue(output["items"]!!.jsonArray.isEmpty())
            assertTrue(output["answers"]!!.jsonArray.isEmpty())
        }
    }

    @Test
    fun repeatedVariantAnswersAreBoundedAndKeepEachRealSourceWithoutReplacingSnippets() = runBlocking {
        val configured = listOf(
            SearchServiceOptions.GrokOptions(apiKey = "key"),
            SearchServiceOptions.PerplexityOptions(apiKey = "key"),
        )
        val answer = ANSWER.repeat(150)
        val article = SearchResult.SearchResultItem("Source", "https://example.com/article", "page snippet")
        searchBoth(configured, depth = "deep") {
            Result.success(SearchResult(answer = answer, items = listOf(article)))
        }.forEach { output ->
            val answers = output["answers"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, answers.size)
            assertEquals(
                configured.map { it.id.toString() }.toSet(),
                answers.map { it["service_id"]!!.jsonPrimitive.content }.toSet(),
            )
            assertTrue(answers.all { it["answer"]!!.jsonPrimitive.content == answer.take(4_000) })
            assertTrue(answers.all { it["source_service"]!!.jsonPrimitive.content.isNotBlank() })
            assertTrue(answers.all { it["query"]!!.jsonPrimitive.content.isNotBlank() })
            assertTrue(answers.none { it.containsKey("url") })
            assertEquals("page snippet", output["items"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun differentCanonicalUrlsKeepTheirOwnTextEvenWhenNormalizedTitlesMatch() = runBlocking {
        val configured = SearchServiceOptions.BraveOptions(apiKey = "key")
        val latex = SearchResult.SearchResultItem("Latex", "https://example.com/wiki/Latex", "rubber material")
        val typesetting = SearchResult.SearchResultItem("LaTeX", "https://example.com/wiki/LaTeX", "document typesetting software")
        val output = SearchOrchestrator.search(
            settings(configured),
            buildJsonObject { put("query", "latex"); put("depth", "quick") },
            executor = { _, _ -> Result.success(SearchResult(items = listOf(latex, typesetting))) },
        )

        val textByUrl = output["items"]!!.jsonArray.associate { item ->
            item.jsonObject["url"]!!.jsonPrimitive.content to item.jsonObject["text"]!!.jsonPrimitive.content
        }
        assertEquals(mapOf(latex.url to latex.text, typesetting.url to typesetting.text), textByUrl)
    }

    @Test
    fun distinctCaseSensitiveQueryValuesAreNotMergedByTheirMatchingTitles() = runBlocking {
        val configured = SearchServiceOptions.BraveOptions(apiKey = "key")
        val output = SearchOrchestrator.search(
            settings(configured),
            buildJsonObject { put("query", "case"); put("depth", "quick") },
            executor = { _, _ -> Result.success(SearchResult(items = listOf(
                SearchResult.SearchResultItem("Result", "https://example.com/find?q=Case", "upper"),
                SearchResult.SearchResultItem("Result", "https://example.com/find?q=case", "lower"),
            ))) },
        )

        assertEquals(2, output["items"]!!.jsonArray.size)
    }

    @Test
    fun canonicalIdentityKeepsDistinctPagesAndMergesTrackingDuplicatesWithTheirImages() = runBlocking {
        val configured = SearchServiceOptions.BraveOptions(apiKey = "key")
        val items = listOf(
            SearchResult.SearchResultItem("Latex", "https://example.com/wiki/Latex", "rubber material"),
            SearchResult.SearchResultItem("LaTeX", "https://example.com/wiki/LaTeX", "document typesetting"),
            SearchResult.SearchResultItem("Result", "https://example.com/find?q=Case", "upper"),
            SearchResult.SearchResultItem("Result", "https://example.com/find?q=case", "lower"),
            SearchResult.SearchResultItem(
                "LaTeX", "https://www.example.com/wiki/LaTeX?utm_source=search", "longer document typesetting snippet",
                images = listOf("https://example.com/image.png"),
            ),
        )
        searchBoth(listOf(configured)) {
            Result.success(SearchResult(items = items))
        }.forEach { output ->
            val byUrl = output["items"]!!.jsonArray.associate { it.jsonObject["url"]!!.jsonPrimitive.content to it.jsonObject }
            assertEquals(4, byUrl.size)
            assertEquals("rubber material", byUrl.getValue(items[0].url)["text"]!!.jsonPrimitive.content)
            assertEquals("upper", byUrl.getValue(items[2].url)["text"]!!.jsonPrimitive.content)
            assertEquals("lower", byUrl.getValue(items[3].url)["text"]!!.jsonPrimitive.content)
            val typesetting = byUrl.getValue(items[1].url)
            assertEquals("longer document typesetting snippet", typesetting["text"]!!.jsonPrimitive.content)
            assertEquals("2", typesetting["duplicate_count"]!!.jsonPrimitive.content)
            assertEquals("https://example.com/image.png", typesetting["images"]!!.jsonArray.single().jsonPrimitive.content)
        }
    }

    @Test
    fun differentExplicitPortsKeepTheirOwnUrlsAndTextInBothAggregators() = runBlocking {
        val configured = SearchServiceOptions.BraveOptions(apiKey = "key")
        val items = listOf(
            SearchResult.SearchResultItem("Result", "https://example.com:8443/a?type=Case", "first service"),
            SearchResult.SearchResultItem("Result", "https://example.com:9443/a?type=Case", "second service"),
        )
        searchBoth(listOf(configured)) {
            Result.success(SearchResult(items = items))
        }.forEach { output ->
            val results = output["items"]!!.jsonArray
            assertEquals(2, results.size)
            assertEquals(
                items.associate { it.url to it.text },
                results.associate { it.jsonObject["url"]!!.jsonPrimitive.content to it.jsonObject["text"]!!.jsonPrimitive.content },
            )
        }
    }

    private fun assertAnswer(output: JsonObject, source: SearchServiceOptions) {
        assertEquals("ok", output["status"]!!.jsonPrimitive.content)
        assertTrue(output["items"]!!.jsonArray.isEmpty())
        val answers = output["answers"]?.jsonArray ?: JsonArray(emptyList())
        assertEquals(1, answers.size)
        assertEquals(ANSWER, answers.single().jsonObject["answer"]!!.jsonPrimitive.content)
        assertTrue(answers.single().jsonObject["source_service"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(source.id.toString(), answers.single().jsonObject["service_id"]!!.jsonPrimitive.content)
    }

    private suspend fun searchBoth(
        configured: List<SearchServiceOptions>,
        depth: String = "quick",
        resultForSource: (SearchServiceOptions) -> Result<SearchResult>,
    ): List<JsonObject> {
        val settings = settings(*configured.toTypedArray())
        val params = buildJsonObject {
            put("query", "grounded answer")
            put("depth", depth)
        }
        return listOf(
            SearchAggregator.search(settings, params, executor = { options, _, _ -> resultForSource(options) }),
            SearchOrchestrator.search(settings, params, executor = { request, _ ->
                resultForSource((request.source as SearchOrchestrator.OrchestratorSource.Configured).options)
            }),
        )
    }

    private fun serviceParams(
        options: SearchServiceOptions,
        query: String,
        timeRange: String = "week",
        recencyDays: Int? = null,
    ) = SearchAggregator.buildServiceParams(query, "news", timeRange, recencyDays, options, Locale.ENGLISH)

    private fun settings(vararg configured: SearchServiceOptions) = Settings(
        searchServices = configured.toList(),
        searchEnabledServiceIds = configured.map { it.id },
        searchBuiltinDuckDuckGoEnabled = false,
        searchBuiltinBingEnabled = false,
        searchBuiltinJinaEnabled = false,
        searchBuiltinWikipediaEnabled = false,
        searchBuiltinHackerNewsEnabled = false,
        searchCommonOptions = SearchCommonOptions(resultSize = 10),
    )

    private companion object { const val ANSWER = "Grounded provider answer with no citation snippets." }
}
