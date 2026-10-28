package app.amber.core.settings.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.amber.core.infra.AppScope
import app.amber.core.infra.PreferencesKeys
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.shareSettingsRawFlow
import app.amber.core.settings.toMutableStateFlow
import app.amber.search.SearchCommonOptions
import app.amber.search.SearchServiceOptions
import kotlin.uuid.Uuid

data class SearchPrefsData(
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val searchEnabledServiceIds: List<Uuid> = emptyList(),
    val searchBuiltinDuckDuckGoEnabled: Boolean = true,
    val searchBuiltinBingEnabled: Boolean = true,
    val searchBuiltinJinaEnabled: Boolean = true,
    val searchBuiltinWikipediaEnabled: Boolean = true,
    val searchBuiltinHackerNewsEnabled: Boolean = true,
    val searchGoogleWebViewFallbackEnabled: Boolean = true,
    val retiredServiceRequiresReconfiguration: Boolean = false,
)

class SearchPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
    private val secretStore: SecretStore,
) {
    private val redactor = SecretRedactor(secretStore)

    internal val rawFlow: SharedFlow<SearchPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()
        .shareSettingsRawFlow(scope)

    val flow: StateFlow<SearchPrefsData> = rawFlow
        .toMutableStateFlow(scope, SearchPrefsData())

    suspend fun update(transform: (SearchPrefsData) -> SearchPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    internal fun readFrom(p: Preferences): SearchPrefsData {
        val refs = redactor.readRefs(p)
        val decodedServices = p[PreferencesKeys.SEARCH_SERVICES]
            ?.decodeSearchServicesDroppingLegacy()
        val searchServices = decodedServices?.services?.let {
            redactor.rehydrateSearchServices(it, refs)
        } ?: listOf(SearchServiceOptions.DEFAULT)
        val storedSelected = p[PreferencesKeys.SEARCH_SELECTED] ?: 0
        val adjustedSelected = decodedServices?.adjustedSelected(storedSelected) ?: storedSelected
        val selected = if (searchServices.isEmpty()) 0 else adjustedSelected.coerceIn(0, searchServices.lastIndex)
        val selectedWasRetired = storedSelected in decodedServices?.legacyIndices.orEmpty()
        val rawEnabled = p[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS]
        val storedEnabled = rawEnabled?.let {
            it.decodeJsonOrNull<List<Uuid>>()
        }.orEmpty()
        val legacyIds = decodedServices?.legacyServiceIds.orEmpty()
        // Only versions without this key need selected-service migration.
        // A present empty list is the user's choice to disable every profile.
        val retainedEnabled = if (rawEnabled == null && !selectedWasRetired) {
            searchServices.getOrNull(selected)?.let { listOf(it.id) }.orEmpty()
        } else {
            storedEnabled.filter { id ->
                id !in legacyIds && searchServices.any { it.id == id }
            }
        }
        return SearchPrefsData(
            searchServices = searchServices,
            searchCommonOptions = (p[PreferencesKeys.SEARCH_COMMON]?.let {
                it.decodeJsonOrNull<SearchCommonOptions>()
            } ?: SearchCommonOptions()).let { options ->
                options.copy(resultSize = options.resultSize.coerceIn(1, 30))
            },
            searchServiceSelected = selected,
            searchEnabledServiceIds = retainedEnabled,
            searchBuiltinDuckDuckGoEnabled = p[PreferencesKeys.SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] != false,
            searchBuiltinBingEnabled = p[PreferencesKeys.SEARCH_BUILTIN_BING_ENABLED] != false,
            searchBuiltinJinaEnabled = p[PreferencesKeys.SEARCH_BUILTIN_JINA_ENABLED] != false,
            searchBuiltinWikipediaEnabled = p[PreferencesKeys.SEARCH_BUILTIN_WIKIPEDIA_ENABLED] != false,
            searchBuiltinHackerNewsEnabled = p[PreferencesKeys.SEARCH_BUILTIN_HACKERNEWS_ENABLED] != false,
            searchGoogleWebViewFallbackEnabled = p[PreferencesKeys.SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] != false,
            retiredServiceRequiresReconfiguration = decodedServices?.let { decoded ->
                decoded.removedLegacy && (
                    selectedWasRetired ||
                        (storedEnabled.any { it in decoded.legacyServiceIds } && retainedEnabled.isEmpty())
                    )
            } == true,
        )
    }

    private fun writeTo(p: MutablePreferences, data: SearchPrefsData) {
        // P1-01: redaction 由 SettingsAggregator.writeSettings 统一执行
        p[PreferencesKeys.SEARCH_SERVICES] = JsonInstant.encodeToString(data.searchServices)
        p[PreferencesKeys.SEARCH_COMMON] = JsonInstant.encodeToString(
            data.searchCommonOptions.copy(resultSize = data.searchCommonOptions.resultSize.coerceIn(1, 30))
        )
        p[PreferencesKeys.SEARCH_SELECTED] = data.searchServiceSelected
        p[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS] =
            JsonInstant.encodeToString(data.searchEnabledServiceIds)
        p[PreferencesKeys.SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] = data.searchBuiltinDuckDuckGoEnabled
        p[PreferencesKeys.SEARCH_BUILTIN_BING_ENABLED] = data.searchBuiltinBingEnabled
        p[PreferencesKeys.SEARCH_BUILTIN_JINA_ENABLED] = data.searchBuiltinJinaEnabled
        p[PreferencesKeys.SEARCH_BUILTIN_WIKIPEDIA_ENABLED] = data.searchBuiltinWikipediaEnabled
        p[PreferencesKeys.SEARCH_BUILTIN_HACKERNEWS_ENABLED] = data.searchBuiltinHackerNewsEnabled
        p[PreferencesKeys.SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] =
            data.searchGoogleWebViewFallbackEnabled
    }
}
