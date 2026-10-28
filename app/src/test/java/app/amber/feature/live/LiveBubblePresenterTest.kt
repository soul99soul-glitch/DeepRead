package app.amber.feature.live

import android.app.Application
import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.amber.agent.data.db.dao.ThemePackageDAO
import app.amber.agent.data.db.entity.ThemePackageEntity
import app.amber.agent.data.files.CasTestFixtures
import app.amber.core.automation.AmberAccessibilityService
import app.amber.feature.ui.theme.SettingsAggregatorThemeStore
import app.amber.feature.ui.theme.ThemePackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

/** Exercises the production BubbleWindow and service hosts; it does not grant a device overlay permission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveBubblePresenterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val services = mutableListOf<ServiceController<AmberAccessibilityService>>()
    private val presenters = mutableListOf<LiveBubblePresenter>()
    private val setting = LiveModeSetting(enabled = true, bubbleEnabled = true)

    @Before
    fun themeDependencies() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = CasTestFixtures.settingsAggregator(context, temporary.newFolder())
        val themes = ThemePackageManager(object : ThemePackageDAO {
            override fun observeAll() = flowOf(emptyList<ThemePackageEntity>())
            override suspend fun getById(id: String): ThemePackageEntity? = null
            override suspend fun upsert(entity: ThemePackageEntity) = Unit
            override suspend fun delete(id: String) = 0
        }, SettingsAggregatorThemeStore(settings))
        startKoin { modules(module {
            single { settings }
            single { themes }
        }) }
    }

    @After
    fun cleanTaskOwnedHosts() {
        presenters.forEach { it.stop() }
        services.forEach { it.destroy() }
        stopKoin()
    }

    @Test
    fun `same service is idempotent and task bubble handoff destroys then recreates only the host`() {
        val service = service()
        val state = MutableStateFlow(activeState())
        var viewed = 0
        val presenter = presenter(state, { service }) { viewed++ }
        presenter.sync(setting, taskBubbleVisible = false)
        assertTrue(presenter.isShowing)
        val first = views(service).single()
        val firstOwner = checkNotNull(first.findViewTreeLifecycleOwner())
        assertEquals(Lifecycle.State.RESUMED, firstOwner.lifecycle.currentState)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, (first.layoutParams as WindowManager.LayoutParams).type)
        presenter.sync(setting, taskBubbleVisible = false)
        assertSame(first, views(service).single())
        presenter.setBubbleExpanded(true)
        assertEquals(1, viewed)

        presenter.sync(setting, taskBubbleVisible = true)
        assertFalse(presenter.isShowing)
        assertTrue(views(service).isEmpty())
        assertEquals(Lifecycle.State.DESTROYED, firstOwner.lifecycle.currentState)
        assertTrue(presenter.expanded.value)
        presenter.sync(setting, taskBubbleVisible = false)
        assertNotSame(first, views(service).single())
        assertEquals(Lifecycle.State.RESUMED, views(service).single().findViewTreeLifecycleOwner()!!.lifecycle.currentState)
        presenter.setBubbleExpanded(true)
        assertEquals(1, viewed)
    }

    @Test
    fun `a service reconnect remounts while last seen and expansion survive the presentation change`() {
        val firstService = service()
        val secondService = service()
        var currentService = firstService
        val state = MutableStateFlow(activeState().copy(lastResultAuto = false))
        var viewed = 0
        val presenter = presenter(state, { currentService }) { viewed++ }
        presenter.sync(setting, false)
        val first = views(firstService).single()
        val firstOwner = first.findViewTreeLifecycleOwner()!!
        presenter.setBubbleExpanded(true)
        currentService = secondService
        presenter.sync(setting, false)
        val replacement = views(secondService).single()
        assertNotSame(first, replacement)
        assertEquals(Lifecycle.State.DESTROYED, firstOwner.lifecycle.currentState)
        assertTrue(presenter.expanded.value)
        // A remount must retain lastSeen independently of the automatic-viewed watermark.
        state.value = state.value.copy(lastResultAuto = true)
        presenter.setBubbleExpanded(true)
        assertEquals(0, viewed)
        state.value = state.value.copy(lastUpdatedAtMillis = 200L)
        presenter.setBubbleExpanded(true)
        assertEquals(1, viewed)
    }

    @Test
    fun `disabled service loss and owner stop hide the window and stop resets expansion`() {
        var service: AmberAccessibilityService? = service()
        val state = MutableStateFlow(activeState())
        val presenter = presenter(state, { service }) {}
        presenter.sync(setting, false)
        presenter.setBubbleExpanded(true)
        presenter.sync(setting.copy(bubbleEnabled = false), false)
        assertFalse(presenter.isShowing)
        assertTrue(presenter.expanded.value)
        presenter.sync(setting, false)
        service = null
        presenter.sync(setting, false)
        assertFalse(presenter.isShowing)
        // Manager owns active; stop clears it alongside resetting its process presenter.
        state.value = state.value.copy(active = false)
        presenter.stop()
        assertFalse(presenter.expanded.value)
        presenter.sync(setting, false)
        assertFalse(presenter.isShowing)
    }

    private fun service(): AmberAccessibilityService = Robolectric.buildService(AmberAccessibilityService::class.java)
        .create().also { services += it }.get()

    private fun views(service: AmberAccessibilityService): List<View> =
        Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views

    private fun presenter(
        state: MutableStateFlow<LiveModeUiState>,
        service: () -> AmberAccessibilityService?,
        viewed: () -> Unit,
    ) = LiveBubblePresenter(
        "app.amber.test", state, { LiveFillResult.COPIED }, {}, {}, viewed, service,
    ).also { presenters += it }

    private fun activeState() = LiveModeUiState(
        active = true, card = LiveModeCard(watching = "current result"),
        lastUpdatedAtMillis = 100L, lastResultAuto = true,
    )
}
