package app.amber.feature.board.hotlist.deepread

import app.amber.core.settings.Settings
import app.amber.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadSearchFallbackTest {
    @Test
    fun disabledBuiltinsAreNotReintroducedBesideAnEnabledConfiguredProvider() {
        val configured = SearchServiceOptions.TavilyOptions(apiKey = "configured-key")
        val selected = enabledServices(settings(configured))

        assertEquals(listOf(configured), selected)
    }

    @Test
    fun eachBuiltinFallbackHonorsItsOwnSwitchAndTheMergedFreeSwitch() {
        val configured = SearchServiceOptions.BraveOptions(apiKey = "configured-key")
        val base = settings(configured)
        val freeOnly = enabledServices(base.copy(searchBuiltinBingEnabled = true))
        assertTrue(freeOnly.any { it is SearchServiceOptions.BingLocalOptions })
        assertFalse(freeOnly.any { it is SearchServiceOptions.JinaOptions })

        val duckDuckGoOnly = enabledServices(base.copy(searchBuiltinDuckDuckGoEnabled = true))
        assertTrue(duckDuckGoOnly.any { it is SearchServiceOptions.BingLocalOptions })

        val jinaOnly = enabledServices(base.copy(searchBuiltinJinaEnabled = true))
        assertTrue(jinaOnly.any { it is SearchServiceOptions.JinaOptions })
        assertFalse(jinaOnly.any { it is SearchServiceOptions.BingLocalOptions })
    }

    @Test
    fun explicitlyEnabledConfiguredBingAndJinaRemainAvailableWithBuiltinsOff() {
        val bing = SearchServiceOptions.BingLocalOptions()
        val jina = SearchServiceOptions.JinaOptions(apiKey = "configured-key")
        val selected = enabledServices(settings(bing, jina))

        assertEquals(setOf(bing.id, jina.id), selected.map { it.id }.toSet())
    }

    @Test
    fun builtinsRemainSelectableWhenNoConfiguredProviderIsEnabled() {
        val base = settings()
        assertTrue(enabledServices(base).isEmpty())

        val freeOnly = enabledServices(base.copy(searchBuiltinBingEnabled = true))
        assertEquals(1, freeOnly.size)
        assertTrue(freeOnly.single() is SearchServiceOptions.BingLocalOptions)

        val duckDuckGoOnly = enabledServices(base.copy(searchBuiltinDuckDuckGoEnabled = true))
        assertEquals(1, duckDuckGoOnly.size)
        assertTrue(duckDuckGoOnly.single() is SearchServiceOptions.BingLocalOptions)

        val jinaOnly = enabledServices(base.copy(searchBuiltinJinaEnabled = true))
        assertEquals(1, jinaOnly.size)
        assertTrue(jinaOnly.single() is SearchServiceOptions.JinaOptions)
    }

    @Test
    fun disablingAllConfiguredProvidersDoesNotDisableAllowedBuiltins() {
        val configured = SearchServiceOptions.TavilyOptions(apiKey = "configured-key")
        val selected = enabledServices(settings(configured).copy(
            searchEnabledServiceIds = emptyList(),
            searchBuiltinDuckDuckGoEnabled = true,
            searchBuiltinJinaEnabled = true,
        ))

        assertEquals(2, selected.size)
        assertTrue(selected.any { it is SearchServiceOptions.BingLocalOptions })
        assertTrue(selected.any { it is SearchServiceOptions.JinaOptions })
        assertFalse(selected.any { it.id == configured.id })
    }

    @Test
    fun configuredSearchAndScrapeFallbacksLeaveAllSixProfilesAvailable() {
        val bing = SearchServiceOptions.BingLocalOptions()
        val tavily = SearchServiceOptions.TavilyOptions(apiKey = "configured-key")
        val brave = SearchServiceOptions.BraveOptions(apiKey = "configured-key")
        val exa = SearchServiceOptions.ExaOptions(apiKey = "configured-key")
        val perplexity = SearchServiceOptions.PerplexityOptions(apiKey = "configured-key")
        val zhipu = SearchServiceOptions.ZhipuOptions(apiKey = "configured-key")
        val selected = enabledServices(settings(bing, tavily, brave, exa, perplexity, zhipu).copy(
            searchBuiltinDuckDuckGoEnabled = true,
            searchBuiltinJinaEnabled = true,
        ))

        assertEquals(listOf(perplexity, tavily, zhipu, brave, exa, bing), selected)
    }

    @Test
    fun aSeventhConfiguredReaderCannotSuppressFallbacksMissingFromTheFinalSelection() {
        val perplexity = SearchServiceOptions.PerplexityOptions(apiKey = "configured-key")
        val zhipu = SearchServiceOptions.ZhipuOptions(apiKey = "configured-key")
        val brave = SearchServiceOptions.BraveOptions(apiKey = "configured-key")
        val exa = SearchServiceOptions.ExaOptions(apiKey = "configured-key")
        val serper = SearchServiceOptions.SerperOptions(apiKey = "configured-key")
        val serpApi = SearchServiceOptions.SerpApiOptions(apiKey = "configured-key")
        val jina = SearchServiceOptions.JinaOptions(apiKey = "configured-key")
        val selected = enabledServices(settings(perplexity, zhipu, brave, exa, serper, serpApi, jina).copy(
            searchBuiltinDuckDuckGoEnabled = true,
            searchBuiltinJinaEnabled = true,
        ))

        assertEquals(6, selected.size)
        assertEquals(listOf(perplexity, zhipu, brave, exa), selected.take(4))
        assertEquals(1, selected.count { it is SearchServiceOptions.BingLocalOptions })
        assertEquals(1, selected.count { it is SearchServiceOptions.JinaOptions })
        assertFalse(selected.any { it.id == jina.id })
    }

    @Test
    fun reservingSearchFallbackAlsoReplacesTheScraperItDisplaces() {
        val perplexity = SearchServiceOptions.PerplexityOptions(apiKey = "configured-key")
        val zhipu = SearchServiceOptions.ZhipuOptions(apiKey = "configured-key")
        val brave = SearchServiceOptions.BraveOptions(apiKey = "configured-key")
        val exa = SearchServiceOptions.ExaOptions(apiKey = "configured-key")
        val serper = SearchServiceOptions.SerperOptions(apiKey = "configured-key")
        val firecrawl = SearchServiceOptions.FirecrawlOptions(apiKey = "configured-key")
        val selected = enabledServices(settings(perplexity, zhipu, brave, exa, serper, firecrawl).copy(
            searchBuiltinDuckDuckGoEnabled = true,
            searchBuiltinJinaEnabled = true,
        ))

        assertEquals(6, selected.size)
        assertEquals(listOf(perplexity, zhipu, brave, exa), selected.take(4))
        assertEquals(1, selected.count { it is SearchServiceOptions.BingLocalOptions })
        assertEquals(1, selected.count { it is SearchServiceOptions.JinaOptions })
        assertFalse(selected.any { it.id == firecrawl.id })
    }

    @Test
    fun automaticKeylessJinaReaderIsRetainedForScrapingWithoutKeywordSearch() {
        val settings = settings().copy(
            searchBuiltinDuckDuckGoEnabled = true,
            searchBuiltinJinaEnabled = true,
        )
        val selected = enabledServices(settings)
        assertTrue(selected.any { it is SearchServiceOptions.JinaOptions })

        val keywordServices = keywordSearchServices(settings, selected)
        assertEquals(1, keywordServices.size)
        assertTrue(keywordServices.single() is SearchServiceOptions.BingLocalOptions)
    }

    @Test
    fun explicitlyEnabledKeylessJinaProfileKeepsItsCustomKeywordSearch() {
        val jina = SearchServiceOptions.JinaOptions(searchUrl = "https://example.com/custom-search")
        val settings = settings(jina)
        val selected = enabledServices(settings)

        assertEquals(listOf(jina), selected)
        assertEquals(listOf(jina), keywordSearchServices(settings, selected))
    }

    private fun settings(vararg configured: SearchServiceOptions) = Settings(
        searchServices = configured.toList(),
        searchEnabledServiceIds = configured.map { it.id },
        searchBuiltinDuckDuckGoEnabled = false,
        searchBuiltinBingEnabled = false,
        searchBuiltinJinaEnabled = false,
        searchBuiltinWikipediaEnabled = false,
        searchBuiltinHackerNewsEnabled = false,
    )

    @Suppress("UNCHECKED_CAST")
    private fun enabledServices(settings: Settings): List<SearchServiceOptions> {
        // Invoke the actual private selection used by collect(), avoiding any real search.
        val method = Class.forName(
            "app.amber.feature.board.hotlist.deepread.DeepReadSourcePrefetcherKt",
        ).getDeclaredMethod("enabledDeepReadSearchServices", Settings::class.java)
        method.isAccessible = true
        return method.invoke(null, settings) as List<SearchServiceOptions>
    }

    @Suppress("UNCHECKED_CAST")
    private fun keywordSearchServices(
        settings: Settings,
        selected: List<SearchServiceOptions>,
    ): List<SearchServiceOptions> {
        val method = Class.forName(
            "app.amber.feature.board.hotlist.deepread.DeepReadSourcePrefetcherKt",
        ).getDeclaredMethod("deepReadKeywordSearchServices", Settings::class.java, List::class.java)
        method.isAccessible = true
        return method.invoke(null, settings, selected) as List<SearchServiceOptions>
    }
}
