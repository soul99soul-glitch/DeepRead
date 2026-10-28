package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.data.db.AppDatabase
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.live.LiveAnalysisMode
import app.amber.feature.live.LiveCardStore
import app.amber.feature.live.LiveModeCard
import app.amber.feature.live.LiveModeManager
import app.amber.feature.live.LiveModeUiState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** Production saved-card owner and default Koin VM; no analysis, service or permission mutation. */
@RunWith(AndroidJUnit4::class)
class LiveModularizationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var evidence: File
    private lateinit var activity: RouteActivity

    @Test
    fun realSavedCardHistoryAndConfigurationPersistWithoutStartingCompanion() {
        assumeTrue("Explicit disposable fixture opt-in required", InstrumentationRegistry.getArguments()
            .getString("amberLiveFixture") == "true")
        check(context.applicationContext is AmberAgentApp)
        check("${Build.MODEL} ${Build.FINGERPRINT}".lowercase().let { it.contains("emulator") || it.contains("sdk_gphone") })
        val manager: LiveModeManager = getKoin().get()
        check(!manager.state.value.active && !manager.state.value.analyzing) { "Do not disturb an existing Companion session" }
        val originalManagerState = manager.state.value
        val store: LiveCardStore = getKoin().get()
        val database: AppDatabase = getKoin().get()
        val dao = database.liveCardDao()
        val beforeRows = runBlocking { dao.latestFlow(Int.MAX_VALUE).first().associateBy { it.id } }
        val settings: SettingsAggregator = getKoin().get()
        val original = runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init } } }
        val persistent = JsonInstant.encodeToJsonElement(original)
        val prefs = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val beforePreferences = prefs.all.toMap()
        val id = UUID.randomUUID().toString()
        evidence = File(context.getExternalFilesDir(null), "modularization-phase-4-live/$id").apply { mkdirs() }
        val watching = "P4 saved local result ${id.take(8)}: retained conclusion with a deliberately long observation."
        val seed = LiveModeUiState(currentPackage = "app.amber.fixture.$id", currentAppLabel = "P4 本地长应用名称 Evidence Review",
            currentTitle = "P4 retained result", completedAction = "Local card fixture", cardSignature = id,
            card = LiveModeCard(watching = watching, keyPoints = listOf("A persisted local result", "A separate saved-card owner"),
                suggestions = listOf("Read the saved conclusion without enabling capture")))
        var failure: Throwable? = null
        try {
            check(runBlocking { store.save(seed) }) { "Real saved-card owner must persist the UUID result" }
            val saved = runBlocking { store.savedCards.first { rows -> rows.any { it.screenSignature == id } }.single { it.screenSignature == id } }
            assertEquals(watching, saved.watching)
            assertFalse("Saving the same result must not duplicate it", runBlocking { store.save(seed) })
            prefs.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            ActivityScenario.launch<RouteActivity>(Intent(context, RouteActivity::class.java)
                .putExtra(RouteActivity.EXTRA_OPEN_LIVE_COMPANION, true)).use { scenario ->
                scenario.onActivity { activity = it }
                try {
                    waitText(context.getString(R.string.live_companion_title))
                    waitText(watching)
                    reveal(watching)
                    capture("01-real-saved-result", id, manager)
                    reveal(context.getString(R.string.live_analysis_mode))
                    val nextMode = if (original.agentRuntime.liveMode.analysisMode == LiveAnalysisMode.AGGRESSIVE)
                        LiveAnalysisMode.CONSERVATIVE else LiveAnalysisMode.AGGRESSIVE
                    val modeText = context.getString(if (nextMode == LiveAnalysisMode.AGGRESSIVE)
                        R.string.live_mode_aggressive else R.string.live_mode_conservative)
                    val mode = compose.onNode(hasClickAction() and hasText(modeText))
                    if (!mode.isDisplayed()) mode.performScrollTo()
                    mode.assertIsDisplayed().performClick()
                    runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init && it.agentRuntime.liveMode.analysisMode == nextMode } } }
                    capture("02-real-config-mode", id, manager)
                    scenario.recreate()
                    scenario.onActivity { activity = it }
                    waitText(watching)
                    reveal(watching)
                    capture("03-recreated-saved-result", id, manager)
                    assertEquals(saved, runBlocking { store.savedCards.first().single { it.screenSignature == id } })
                    assertFalse(manager.state.value.active)
                    assertFalse(manager.state.value.analyzing)
                    assertEquals(originalManagerState.card, manager.state.value.card)
                } catch (error: Throwable) { runCatching { rawScreenshot("failure") }.onFailure(error::addSuppressed); throw error }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                runBlocking {
                    dao.latestFlow(Int.MAX_VALUE).first().filter { it.screenSignature == id }.forEach { store.delete(it.id) }
                    settings.update(original)
                    withTimeout(10_000) { settings.settingsFlow.first { !it.init && JsonInstant.encodeToJsonElement(it) == persistent } }
                }
                prefs.edit().apply {
                    listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).forEach { key ->
                        if (beforePreferences.containsKey(key)) putString(key, beforePreferences[key] as String?) else remove(key)
                    }
                }.commit()
                val rowsRestored = runBlocking { dao.latestFlow(Int.MAX_VALUE).first().associateBy { it.id } } == beforeRows
                val preferencesRestored = listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).all {
                    prefs.all[it] == beforePreferences[it] && prefs.contains(it) == beforePreferences.containsKey(it)
                }
                File(evidence, "cleanup.json").writeText(JSONObject().put("savedRowsRestored", rowsRestored)
                    .put("settingsRestored", true).put("launchPreferencesRestored", preferencesRestored)
                    .put("companionStarted", manager.state.value.active).toString(2))
                assertTrue(rowsRestored)
                assertTrue(preferencesRestored)
                assertFalse(manager.state.value.active)
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun waitText(value: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun reveal(value: String) {
        val node = compose.onAllNodesWithText(value, useUnmergedTree = true).onFirst()
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed()
    }
    private fun rawScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun capture(name: String, id: String, manager: LiveModeManager) {
        compose.waitForIdle()
        rawScreenshot(name)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(evidence, "$name.semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        var window = ""
        instrumentation.runOnMainSync { window = activity.windowManager.currentWindowMetrics.bounds.toShortString() }
        File(evidence, "$name.owner.json").writeText(JSONObject().put("fixtureSignature", id)
            .put("widthDp", context.resources.configuration.screenWidthDp).put("fontScale", context.resources.configuration.fontScale)
            .put("windowBoundsPx", window)
            .put("route", "RouteActivity -> LiveCompanionPage -> default Koin LiveCompanionVM -> real LiveCardStore")
            .put("active", manager.state.value.active).put("analyzing", manager.state.value.analyzing)
            .put("savedResultOnly", true).put("currentResultAnalysisVerified", false).toString(2))
    }
}
