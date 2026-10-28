package app.amber.core.settings.prefs

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.infra.AppScope
import app.amber.core.infra.PreferencesKeys
import app.amber.core.settings.Settings
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SettingsSecretMigrator
import app.amber.core.settings.secret.fakeSecretStore
import app.amber.search.SearchServiceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSelectionPersistenceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var scope: AppScope
    private lateinit var secretStore: SecretStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(tempFolder.root, "settings.preferences_pb")
        }
        scope = AppScope()
        secretStore = fakeSecretStore()
    }

    @After
    fun tearDown() {
        runBlocking {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            dataStoreScope.coroutineContext[Job]!!.cancelAndJoin()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `disabling the last configured provider survives saving and a fresh settings reader`() = runBlocking {
        val configured = SearchServiceOptions.TavilyOptions()
        val aggregator = buildAggregator()
        val initial = aggregator.awaitLoaded()
        aggregator.update(
            initial.copy(
                searchServices = listOf(configured),
                searchServiceSelected = 0,
                searchEnabledServiceIds = listOf(configured.id),
            ),
        )

        aggregator.update { it.copy(searchEnabledServiceIds = emptyList()) }

        assertEquals("[]", dataStore.data.first()[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS])
        assertTrue(aggregator.settingsFlow.value.searchEnabledServiceIds.isEmpty())
        val reloaded = buildAggregator().awaitLoaded()
        assertEquals(listOf(configured.id), reloaded.searchServices.map { it.id })
        assertTrue(reloaded.searchEnabledServiceIds.isEmpty())
    }

    @Test
    fun `legacy missing enabled key migrates the same selected service after retired entries are removed`() = runBlocking {
        val retiredId = Uuid.random()
        val first = SearchServiceOptions.BingLocalOptions()
        val selected = SearchServiceOptions.TavilyOptions()
        dataStore.edit {
            it[PreferencesKeys.SEARCH_SERVICES] =
                """[{"type":"amber_agent","id":"$retiredId"},{"type":"bing_local","id":"${first.id}"},{"type":"tavily","id":"${selected.id}"}]"""
            it[PreferencesKeys.SEARCH_SELECTED] = 2
            it[PreferencesKeys.ENABLE_WEB_SEARCH] = true
        }
        assertNull(dataStore.data.first()[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS])

        val migrator = SettingsSecretMigrator(dataStore, secretStore, SecretRedactor(secretStore))
        assertEquals(SettingsSecretMigrator.MIGRATION_VERSION, migrator.migrateIfNeeded())
        assertNull(dataStore.data.first()[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS])

        val aggregator = buildAggregator()
        val loaded = aggregator.awaitLoaded()
        assertEquals(1, loaded.searchServiceSelected)
        assertEquals(listOf(selected.id), loaded.searchEnabledServiceIds)
        assertTrue(loaded.enableWebSearch)

        aggregator.update { it.copy(contextMessageSize = 321) }

        assertEquals(
            listOf(selected.id),
            JsonInstant.decodeFromString<List<Uuid>>(
                dataStore.data.first()[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS]!!,
            ),
        )
    }

    @Test
    fun `retired selection without an enabled key stays disabled after migration and a fresh settings reader`() = runBlocking {
        val retiredId = Uuid.random()
        val retained = SearchServiceOptions.BingLocalOptions()
        dataStore.edit {
            it[PreferencesKeys.SEARCH_SERVICES] =
                """[{"type":"amber_agent","id":"$retiredId"},{"type":"bing_local","id":"${retained.id}"}]"""
            it[PreferencesKeys.SEARCH_SELECTED] = 0
            it[PreferencesKeys.ENABLE_WEB_SEARCH] = true
        }
        assertNull(dataStore.data.first()[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS])

        val migrator = SettingsSecretMigrator(dataStore, secretStore, SecretRedactor(secretStore))
        assertEquals(SettingsSecretMigrator.MIGRATION_VERSION, migrator.migrateIfNeeded())
        assertEquals("[]", dataStore.data.first()[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS])

        val loaded = buildAggregator().awaitLoaded()

        assertEquals(listOf(retained.id), loaded.searchServices.map { it.id })
        assertTrue(loaded.searchEnabledServiceIds.isEmpty())
        assertFalse(loaded.enableWebSearch)
    }

    @Test
    fun `a present enabled key never migrates to the selected provider`() {
        val configured = SearchServiceOptions.TavilyOptions()
        val staleId = Uuid.random()
        val prefs = SearchPrefs(dataStore, scope, secretStore)

        for (storedEnabled in listOf("[]", JsonInstant.encodeToString(listOf(staleId)), "invalid-json")) {
            val stored = mutablePreferencesOf(
                PreferencesKeys.SEARCH_SERVICES to JsonInstant.encodeToString(listOf<SearchServiceOptions>(configured)),
                PreferencesKeys.SEARCH_SELECTED to 0,
                PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS to storedEnabled,
            )

            assertTrue(
                "a stored enabled selection must stay authoritative: $storedEnabled",
                prefs.readFrom(stored).searchEnabledServiceIds.isEmpty(),
            )
        }
    }

    @Test
    fun `a retired selected provider requires reconfiguration with or without an enabled key`() {
        val retiredId = Uuid.random()
        val retained = SearchServiceOptions.BingLocalOptions()
        val stored = mutablePreferencesOf(
            PreferencesKeys.SEARCH_SERVICES to
                """[{"type":"amber_agent","id":"$retiredId"},{"type":"bing_local","id":"${retained.id}"}]""",
            PreferencesKeys.SEARCH_SELECTED to 0,
        )
        val prefs = SearchPrefs(dataStore, scope, secretStore)

        for (enabledKeyPresent in listOf(false, true)) {
            if (enabledKeyPresent) {
                stored[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS] = JsonInstant.encodeToString(listOf(retiredId))
            }
            val loaded = prefs.readFrom(stored)

            assertEquals(listOf(retained.id), loaded.searchServices.map { it.id })
            assertTrue(loaded.searchEnabledServiceIds.isEmpty())
            assertTrue(loaded.retiredServiceRequiresReconfiguration)
        }
    }

    @Test
    fun `enabled selection drops stale and retired ids while retaining a configured provider`() {
        val retiredId = Uuid.random()
        val retained = SearchServiceOptions.TavilyOptions()
        val stored = mutablePreferencesOf(
            PreferencesKeys.SEARCH_SERVICES to
                """[{"type":"amber_agent","id":"$retiredId"},{"type":"tavily","id":"${retained.id}"}]""",
            PreferencesKeys.SEARCH_SELECTED to 1,
            PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS to
                JsonInstant.encodeToString(listOf(Uuid.random(), retiredId, retained.id)),
        )

        val loaded = SearchPrefs(dataStore, scope, secretStore).readFrom(stored)

        assertEquals(listOf(retained.id), loaded.searchEnabledServiceIds)
        assertFalse(loaded.retiredServiceRequiresReconfiguration)
    }

    private fun buildAggregator(): SettingsAggregator = SettingsAggregator(
        dataStore = dataStore,
        uiPrefs = UIPrefs(dataStore, scope),
        searchPrefs = SearchPrefs(dataStore, scope, secretStore),
        agentPrefs = AgentPrefs(dataStore, scope),
        providerPrefs = ProviderPrefs(dataStore, scope, secretStore),
        chatPrefs = ChatPrefs(dataStore, scope, secretStore),
        extensionPrefs = ExtensionPrefs(dataStore, scope, secretStore),
        scope = scope,
        secretRedactor = SecretRedactor(secretStore),
    )

    private suspend fun SettingsAggregator.awaitLoaded(): Settings =
        withTimeout(5_000) { settingsFlow.first { !it.init } }
}
