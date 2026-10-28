package app.amber.search

import app.amber.search.SearchResult.SearchResultItem
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchResultIdentityTest {
    @After
    fun tearDown() = FreeSearchAggregator.resetCooldowns()

    @Test
    fun freeSearchKeepsDistinctWikipediaPagesWhosePathsDifferInCase() = runBlocking {
        val urls = listOf("https://en.wikipedia.org/wiki/Latex", "https://en.wikipedia.org/wiki/LaTeX")
        val wikipedia = """
            {"query":{"pages":[
              {"index":1,"title":"Latex","fullurl":"${urls[0]}","extract":"A dispersion of polymer particles."},
              {"index":2,"title":"LaTeX","fullurl":"${urls[1]}","extract":"A document preparation system."}
            ]}}
        """.trimIndent()

        val result = FreeSearchAggregator.search("latex", 10, FreeSearchFetcher { request ->
            if (request.url.host == "en.wikipedia.org") FreeSearchResponse(200, wikipedia)
            else FreeSearchResponse(500, "")
        }).getOrThrow()

        assertEquals(urls, result.items.map { it.url })
    }

    @Test
    fun freeSearchMergeKeepsQueryCaseAndTrailingSlashes() {
        val upperQuery = "https://example.com/article?id=Foo"
        val lowerQuery = "https://example.com/article?id=foo"
        val queryWithSlash = "$upperQuery/"

        val merged = FreeSearchAggregator.roundRobinMerge(
            perEngine = mapOf(
                FreeSearchEngine.BING to listOf(item(upperQuery), item(lowerQuery), item(queryWithSlash)),
                FreeSearchEngine.BRAVE to listOf(
                    item("HTTP://WWW.EXAMPLE.COM/article?id=Foo"),
                    item("https://m.example.com/article?id=foo"),
                ),
            ),
            order = listOf(FreeSearchEngine.BING, FreeSearchEngine.BRAVE),
            limit = 10,
        )

        assertEquals(listOf(upperQuery, lowerQuery, queryWithSlash), merged.map { it.url })
    }

    @Test
    fun freeSearchMergeNormalizesHostVariantsAndTrailingPathSlashes() {
        val merged = FreeSearchAggregator.roundRobinMerge(
            perEngine = mapOf(
                FreeSearchEngine.BING to listOf(item("https://example.com/article")),
                FreeSearchEngine.BRAVE to listOf(
                    item("HTTP://WWW.EXAMPLE.COM/article/"),
                    item("https://m.example.com/article/"),
                ),
            ),
            order = listOf(FreeSearchEngine.BING, FreeSearchEngine.BRAVE),
            limit = 10,
        )

        assertEquals(listOf("https://example.com/article"), merged.map { it.url })
    }

    @Test
    fun bingSearchPageReturnsResultsAboutCaptcha() {
        val result = BingSearchService.parseSearchPage(Jsoup.parse(captchaResultsHtml()), 10)

        assertEquals((1..10).map { "https://example.com/captcha/$it" }, result.items.map { it.url })
        assertEquals("CAPTCHA result 1", result.items.first().title)
    }

    @Test
    fun bingSearchPageReportsVerificationWhenThereAreNoResults() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BingSearchService.parseSearchPage(
                Jsoup.parse("<main><h1>Verify you are human</h1><p>Complete the CAPTCHA to continue.</p></main>"),
                10,
            )
        }

        assertEquals("Bing blocked the request with a verification page", error.message)
    }

    @Test
    fun bingSearchPageReportsEmptyResultsWithoutCallingItVerification() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BingSearchService.parseSearchPage(Jsoup.parse("<main>No results found.</main>"), 10)
        }

        assertEquals("Search failed: no results found", error.message)
    }

    @Test
    fun freeBingKeepsResultsAboutCaptcha() = runBlocking {
        val result = FreeSearchAggregator.search("captcha", 10, FreeSearchFetcher { request ->
            if (request.url.host == "www.bing.com") FreeSearchResponse(200, captchaResultsHtml())
            else FreeSearchResponse(500, "")
        }).getOrThrow()

        assertEquals((1..10).map { "https://example.com/captcha/$it" }, result.items.map { it.url })
    }

    private fun captchaResultsHtml() = (1..10).joinToString(
        prefix = "<main><ol id=\"b_results\">",
        postfix = "</ol></main>",
    ) { index ->
        """<li class="b_algo"><h2><a href="https://example.com/captcha/$index">CAPTCHA result $index</a></h2>
            <div class="b_caption"><p>How CAPTCHA verification works.</p></div></li>"""
    }

    private fun item(url: String) = SearchResultItem(title = url, url = url, text = "")
}
