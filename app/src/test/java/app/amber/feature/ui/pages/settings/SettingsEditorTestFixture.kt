package app.amber.feature.ui.pages.settings

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.amber.core.infra.AppScope
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A real Preferences file and SettingsAggregator, with one controlled durable write boundary. */
class SettingsEditorTestFixture(val application: Application) {
    private val root = File(application.cacheDir, "settings-editor-${System.nanoTime()}").apply { mkdirs() }
    private val appScope = AppScope()
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore = WriteBarrierDataStore(PreferenceDataStoreFactory.create(scope = dataScope) {
        File(root, "settings.preferences_pb")
    })
    private val secretStore = SecretStore(
        backend = object : SecretStoreBackend {
            private val values = mutableMapOf<String, String>()
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
            override fun keys() = values.keys.toSet()
        },
        cipher = object : SecretCipher {
            override fun encrypt(plaintext: String) = "enc:$plaintext"
            override fun decrypt(stored: String): String = stored.removePrefix("enc:")
        },
    )
    val settings = SettingsAggregator(
        dataStore, UIPrefs(dataStore, appScope), SearchPrefs(dataStore, appScope, secretStore),
        AgentPrefs(dataStore, appScope), ProviderPrefs(dataStore, appScope, secretStore),
        ChatPrefs(dataStore, appScope, secretStore), ExtensionPrefs(dataStore, appScope, secretStore),
        appScope, SecretRedactor(secretStore),
    )
    private val viewModels = ViewModelStore()

    fun <T : ViewModel> retain(viewModel: T): T = viewModel.also {
        viewModels.put("editor-${System.identityHashCode(it)}", it)
    }

    suspend fun awaitReady() { withTimeout(5_000) { settings.settingsFlow.first { !it.init } } }

    suspend fun awaitWrites(viewModel: ViewModel) {
        viewModel.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    suspend fun close() {
        dataStore.releasePendingWrite()
        viewModels.clear()
        appScope.cancel()
        appScope.coroutineContext[Job]!!.children.toList().joinAll()
        dataScope.cancel()
        dataScope.coroutineContext[Job]!!.children.toList().joinAll()
        root.deleteRecursively()
    }

    class WritePause {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    class WriteBarrierDataStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        override val data get() = real.data
        @Volatile private var next: WritePause? = null
        @Volatile private var active: WritePause? = null

        fun pauseNextWrite(): WritePause = WritePause().also { next = it }
        fun releasePendingWrite() { next?.release?.complete(Unit); active?.release?.complete(Unit) }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val pause = next
            if (pause != null) {
                next = null
                active = pause
                pause.entered.complete(Unit)
                try { pause.release.await() } finally { active = null }
            }
            return real.updateData(transform)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
abstract class SettingsEditorTestBase {
    protected lateinit var fixture: SettingsEditorTestFixture
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpEditor(): Unit = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        fixture = SettingsEditorTestFixture(RuntimeEnvironment.getApplication())
        fixture.awaitReady()
    }

    @After
    fun tearDownEditor(): Unit = runBlocking {
        fixture.close()
        Dispatchers.resetMain()
    }
}
