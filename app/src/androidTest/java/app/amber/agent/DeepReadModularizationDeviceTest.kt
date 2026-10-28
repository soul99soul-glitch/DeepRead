package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.amber.agent.data.db.dao.HotListDAO
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.deepread.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
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

/** Real history route and Room article; deliberately never presses retry or a source URL. */
@RunWith(AndroidJUnit4::class)
class DeepReadModularizationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var evidence: File
    private lateinit var activity: RouteActivity

    @Test
    fun realCachedReaderRetainsPartialAndReadyArticlesAcrossReopenWithoutSchedulingWork() {
        assumeTrue("Explicit disposable fixture opt-in required", InstrumentationRegistry.getArguments()
            .getString("amberDeepReadFixture") == "true")
        check(context.applicationContext is AmberAgentApp)
        check("${Build.MODEL} ${Build.FINGERPRINT}".lowercase().let { it.contains("emulator") || it.contains("sdk_gphone") })
        val id = "p4-deepread-${UUID.randomUUID()}"
        val title = "P4 local reader ${id.takeLast(8)} · persisted article"
        evidence = File(context.getExternalFilesDir(null), "modularization-phase-4-deepread/$id").apply { mkdirs() }
        val dao: HotListDAO = getKoin().get()
        val settings: SettingsAggregator = getKoin().get()
        val original = runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init } } }
        val persistent = JsonInstant.encodeToJsonElement(original)
        val beforeRows = runBlocking { dao.observeAllDeepReads().first().associateBy { it.topicId } }
        val prefs = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val beforePreferences = prefs.all.toMap()
        check(runBlocking { dao.getDeepRead(id) } == null)
        val now = System.currentTimeMillis()
        val partial = DeepReadOutput(summary = SUMMARY, keyEntities = listOf("Local Room", "Retained history"),
            sectionStates = mapOf(DeepReadGenerationStage.OVERVIEW to DeepReadSectionState(DeepReadSectionStatus.READY)))
        val row = DeepReadCacheEntity(topicId = id, title = title,
            outputJson = JsonInstant.encodeToString(DeepReadOutput.serializer(), partial), createdAt = now,
            expiresAt = now + 86_400_000, updatedAt = now, templateId = DeepReadTemplateIds.COMPOSE_MAGAZINE)
        var failure: Throwable? = null
        try {
            runBlocking {
                dao.upsertDeepRead(row)
                settings.update { it.copy(agentRuntime = it.agentRuntime.copy(todayBoard = it.agentRuntime.todayBoard.copy(
                    deepReadFirstUseConfirmed = true))) }
            }
            prefs.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            ActivityScenario.launch<RouteActivity>(readerIntent(id, title)).use { scenario ->
                scenario.onActivity { activity = it }
                try {
                    waitText(title)
                    articleTo(SUMMARY)
                    capture("01-partial-readable-overview", id, dao)
                    articleTo(text(R.string.deep_read_narrative_pending))
                    capture("02-partial-retains-missing-section", id, dao)
                    assertNoGenerationWork(id)
                    val complete = partial.copy(generationComplete = true, generationPhase = DeepReadGenerationPhase.COMPLETE,
                        timeline = listOf(TimelineEvent("2026-10-18", TIMELINE)),
                        analysis = DeepAnalysis(coreDispute = ANALYSIS, perspectives = listOf(Perspective("A local source is retained", "Local reviewer")),
                            implications = "Reopening history must not discard the complete article."),
                        extendedReading = listOf(ReadingLink(READING, "https://example.invalid/p4-never-open")),
                        sectionStates = DeepReadGenerationStage.entries.associateWith { DeepReadSectionState(DeepReadSectionStatus.READY) })
                    val readyRow = row.copy(outputJson = JsonInstant.encodeToString(DeepReadOutput.serializer(), complete), updatedAt = now + 1)
                    runBlocking { dao.upsertDeepRead(readyRow) }
                    articleTo(TIMELINE)
                    capture("03-ready-timeline", id, dao)
                    articleTo(ANALYSIS)
                    capture("04-ready-analysis", id, dao)
                    articleTo(READING)
                    capture("05-ready-reading-source", id, dao)
                    compose.onNodeWithContentDescription(text(R.string.back)).assertIsDisplayed()
                    assertNoGenerationWork(id)
                    scenario.recreate()
                    scenario.onActivity { activity = it }
                    // Reader restores its real LazyList position. At narrow widths the hero
                    // title is no longer composed until we explicitly return to that item.
                    compose.waitUntil(15_000) {
                        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                            .fetchSemanticsNodes().size == 1 &&
                            compose.onAllNodes(hasContentDescription(text(R.string.back)))
                                .fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithContentDescription(text(R.string.back)).assertIsDisplayed()
                    articleTo(title)
                    articleTo(SUMMARY)
                    capture("06-recreated-ready-overview", id, dao)
                    assertEquals(readyRow, runBlocking { dao.getDeepRead(id) })
                    assertNoGenerationWork(id)
                } catch (error: Throwable) {
                    runCatching { rawScreenshot("failure") }.onFailure(error::addSuppressed)
                    throw error
                }
            }
            // A new Activity re-enters the production cold history route, rather than a fake pane.
            ActivityScenario.launch<RouteActivity>(readerIntent(id, title)).use { scenario ->
                scenario.onActivity { activity = it }
                try {
                    waitText(title)
                    articleTo(ANALYSIS)
                    capture("07-cold-reopened-ready-analysis", id, dao)
                    assertNoGenerationWork(id)
                } catch (error: Throwable) { runCatching { rawScreenshot("failure-reopen") }.onFailure(error::addSuppressed); throw error }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                runBlocking {
                    dao.deleteDeepRead(id)
                    settings.update(original)
                    withTimeout(10_000) { settings.settingsFlow.first { !it.init && JsonInstant.encodeToJsonElement(it) == persistent } }
                }
                prefs.edit().apply {
                    listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).forEach { key ->
                        if (beforePreferences.containsKey(key)) putString(key, beforePreferences[key] as String?) else remove(key)
                    }
                }.commit()
                val rowsRestored = runBlocking { dao.observeAllDeepReads().first().associateBy { it.topicId } } == beforeRows
                val preferencesRestored = listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).all {
                    prefs.all[it] == beforePreferences[it] && prefs.contains(it) == beforePreferences.containsKey(it)
                }
                File(evidence, "cleanup.json").writeText(JSONObject().put("articleRowsRestored", rowsRestored)
                    .put("settingsRestored", true).put("launchPreferencesRestored", preferencesRestored).toString(2))
                assertTrue(rowsRestored)
                assertTrue(preferencesRestored)
                assertNoGenerationWork(id)
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun readerIntent(id: String, title: String) = Intent(context, RouteActivity::class.java)
        .putExtra(DeepReadNotifier.EXTRA_OPEN_DEEP_READ, true)
        .putExtra(DeepReadNotifier.EXTRA_TOPIC_ID, id)
        .putExtra(DeepReadNotifier.EXTRA_TITLE, title)
        .putExtra(DeepReadNotifier.EXTRA_FROM_HISTORY, true)

    private fun assertNoGenerationWork(id: String) {
        assertTrue("Reading a local history entry must not enqueue its network worker", WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DeepReadScheduler.workName(id)).get(10, TimeUnit.SECONDS).isEmpty())
    }

    private fun articleTo(value: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(value, substring = true))
        compose.onAllNodesWithText(value, substring = true, useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    private fun waitText(value: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun text(id: Int) = context.getString(id)
    private fun rawScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun capture(name: String, id: String, dao: HotListDAO) {
        compose.waitForIdle()
        rawScreenshot(name)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(evidence, "$name.semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        var bounds = ""
        instrumentation.runOnMainSync { bounds = activity.windowManager.currentWindowMetrics.bounds.toShortString() }
        File(evidence, "$name.owner.json").writeText(JSONObject().put("topicId", id)
            .put("outputJson", runBlocking { dao.getDeepRead(id) }!!.outputJson)
            .put("widthDp", context.resources.configuration.screenWidthDp).put("fontScale", context.resources.configuration.fontScale)
            .put("windowBoundsPx", bounds).put("route", "RouteActivity -> Screen.DeepRead(fromHistory=true) -> real HotListRepository/Room")
            .put("generationWorkCount", WorkManager.getInstance(context).getWorkInfosForUniqueWork(DeepReadScheduler.workName(id))
                .get(10, TimeUnit.SECONDS).size).toString(2))
    }
    private companion object {
        const val SUMMARY = "P4 retained overview: this real Room cache remains readable while later article sections are pending."
        const val TIMELINE = "P4 timeline evidence: the locally persisted draft becomes a ready article."
        const val ANALYSIS = "P4 analysis evidence: article identity and completed content survive reopening."
        const val READING = "P4 offline source label — never open this external link"
    }
}
