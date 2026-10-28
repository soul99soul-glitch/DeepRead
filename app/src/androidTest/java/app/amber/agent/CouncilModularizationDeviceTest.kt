package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.modelcouncil.*
import java.io.File
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Real home Continue -> RouteActivity -> default CouncilRoomVM -> real room store. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalUuidApi::class)
class CouncilModularizationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var evidence: File
    private lateinit var activity: RouteActivity

    @Test
    fun realCouncilHistoryRetainsTimelineReferencesAttachmentsAndReadonlyQuestion() {
        assumeTrue("Explicit disposable fixture opt-in required", InstrumentationRegistry.getArguments()
            .getString("amberCouncilFixture") == "true")
        check(context.applicationContext is AmberAgentApp)
        check("${Build.MODEL} ${Build.FINGERPRINT}".lowercase().let { it.contains("emulator") || it.contains("sdk_gphone") })
        val id = Uuid.random()
        evidence = File(context.getExternalFilesDir(null), "modularization-phase-4-council/$id").apply { mkdirs() }
        val dao: ConversationDAO = getKoin().get()
        val repository: ConversationRepository = getKoin().get()
        val store: CouncilRoomStore = getKoin().get()
        val settings: SettingsAggregator = getKoin().get()
        val original = runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init } } }
        val persistent = JsonInstant.encodeToJsonElement(original)
        val beforeRows = runBlocking { dao.getAll().first().associateBy { it.id } }
        val prefs = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val preferenceBefore = prefs.all.toMap()
        val title = "P4 Council ${id.toString().take(8)}"
        val conversation = Conversation(id = id, assistantId = AMBER_AGENT_ID, title = "P4 local conversation $id", messageNodes = emptyList())
        val attachment = File(evidence, FILE_NAME).apply { writeText("P4 local attachment retained by the real Council history fixture.") }
        val seeded = fixture(id, title, attachment.toURI().toString())
        var failure: Throwable? = null
        try {
            runBlocking {
                repository.insertConversation(conversation)
                store.upsertRoom(seeded)
                store.flush(id)
            }
            prefs.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            ActivityScenario.launch<RouteActivity>(Intent(context, RouteActivity::class.java)).use { scenario ->
                scenario.onActivity { activity = it }
                try {
                    waitText(title)
                    // Home exposes the newest real durable Council Continue candidate.
                    compose.onNode(hasClickAction() and hasText(title)).performClick()
                    compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.setting_model_council_title),
                        useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    // Terminal rooms deliberately disappear from Continue. Publish this task's historical
                    // snapshot after entering, through the same store the real VM observes; no runner starts.
                    val historical = seeded.copy(status = CouncilRoomStatus.FINALIZED, finishedAtMs = seeded.updatedAtMs)
                    runBlocking { store.upsertRoom(historical); store.flush(id) }
                    timelineTo(QUESTION)
                    assertEquals("Historical ASK_USER must have no editable recipient",
                        0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
                    capture("01-readonly-question", id, store)
                    timelineTo("P4 attachment evidence")
                    compose.onAllNodesWithText(context.getString(R.string.council_room_file_attachment, FILE_NAME),
                        useUnmergedTree = true).onFirst().assertIsDisplayed()
                    capture("02-document-attachment", id, store)
                    timelineTo("P4 reference continuation")
                    compose.onAllNodesWithText(context.getString(R.string.council_room_reply_reference, HOST),
                        useUnmergedTree = true).onFirst().assertIsDisplayed()
                    capture("03-reply-continuation", id, store)
                    timelineTo("P4 opening evidence")
                    capture("04-long-timeline-start", id, store)
                    val members = compose.onNodeWithContentDescription(text(R.string.council_room_members_and_synthesis))
                    members.assertIsDisplayed().performClick()
                    waitText(title)
                    sheetTo(GUEST)
                    capture("05-long-member-roster", id, store)
                    sheetTo(SYNTHESIS)
                    capture("06-synthesis", id, store)
                    sheetTo(title)
                    compose.onNodeWithContentDescription(text(R.string.cancel)).assertIsDisplayed().performClick()
                    scenario.recreate()
                    scenario.onActivity { activity = it }
                    timelineTo(QUESTION)
                    assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
                    assertEquals(historical, runBlocking { store.observeRoom(id).value })
                    capture("07-recreated-history", id, store)
                } catch (error: Throwable) {
                    runCatching { rawScreenshot("failure") }.onFailure(error::addSuppressed)
                    throw error
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                runBlocking {
                    store.deleteRoom(id)
                    repository.deleteConversation(conversation)
                    settings.update(original)
                    withTimeout(10_000) { settings.settingsFlow.first { !it.init && JsonInstant.encodeToJsonElement(it) == persistent } }
                }
                // Route writes last-conversation as well as launch mode; preserve both presence and value.
                prefs.edit().apply {
                    listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).forEach { key ->
                        if (preferenceBefore.containsKey(key)) putString(key, preferenceBefore[key] as String?) else remove(key)
                    }
                }.commit()
                val dataRestored = runBlocking { dao.getAll().first().associateBy { it.id } } == beforeRows
                val prefsRestored = listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).all {
                    prefs.all[it] == preferenceBefore[it] && prefs.contains(it) == preferenceBefore.containsKey(it)
                }
                File(evidence, "cleanup.json").writeText(JSONObject().put("conversationRowsRestored", dataRestored)
                    .put("fixtureRoomRemoved", store.peekRoom(id) == null).put("settingsRestored", true)
                    .put("launchPreferencesRestored", prefsRestored).toString(2))
                assertTrue(dataRestored)
                assertTrue(prefsRestored)
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun fixture(id: Uuid, title: String, attachmentUri: String): CouncilRoom {
        val now = System.currentTimeMillis()
        fun message(index: Int, text: String, author: String = COUNCIL_ROOM_HOST_ID) = CouncilMessage(
            id = "p4-$index", authorId = author, authorName = if (author == COUNCIL_ROOM_HOST_ID) HOST else GUEST,
            role = "Local evidence reviewer", round = 1, mode = CouncilRoomMode.EXPLORE, text = text,
            createdAtMs = now + index,
        )
        val messages = listOf(message(0, "P4 opening evidence")) +
            (1..10).map { message(it, "P4 timeline $it: A retained local discussion entry for chronology and scrolling.") } +
            listOf(message(11, "P4 attachment evidence", COUNCIL_ROOM_USER_ID).copy(
                authorName = "You", attachments = listOf(UIMessagePart.Document(attachmentUri, FILE_NAME))),
                message(12, "P4 reference continuation", "guest-local").copy(replyToMessageId = "p4-0", continuesFromMessageId = "p4-10"),
                message(13, QUESTION).copy(kind = CouncilMessageKind.ASK_USER))
        return CouncilRoom(id = id.toString(), conversationId = id, hostAssistantId = AMBER_AGENT_ID,
            objective = title, status = CouncilRoomStatus.INTERRUPTED, participants = listOf(
                CouncilParticipant(COUNCIL_ROOM_HOST_ID, HOST, "Host", CouncilParticipantKind.HOST, status = CouncilParticipantStatus.SPOKEN),
                CouncilParticipant("guest-local", GUEST, "Evidence / risk reviewer", CouncilParticipantKind.GUEST,
                    status = CouncilParticipantStatus.SPOKEN, providerName = "Offline local fixture", modelName = "Long local model display label")),
            messages = messages, synthesis = SYNTHESIS, round = 1, createdAtMs = now, updatedAtMs = now + 20)
    }

    private fun timelineTo(value: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(value))
        compose.onAllNodesWithText(value, useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    private fun sheetTo(value: String) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onLast()
            .performScrollToNode(hasText(value))
        compose.onAllNodesWithText(value, useUnmergedTree = true).onFirst().assertIsDisplayed()
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

    private fun capture(name: String, id: Uuid, store: CouncilRoomStore) {
        compose.waitForIdle()
        rawScreenshot(name)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(evidence, "$name.semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        var window = ""
        instrumentation.runOnMainSync { window = activity.windowManager.currentWindowMetrics.bounds.toShortString() }
        File(evidence, "$name.owner.json").writeText(JSONObject().put("conversationId", id.toString())
            .put("room", JsonInstant.encodeToString(CouncilRoom.serializer(), store.peekRoom(id)!!))
            .put("widthDp", context.resources.configuration.screenWidthDp).put("fontScale", context.resources.configuration.fontScale)
            .put("windowBoundsPx", window).put("route", "RouteActivity -> HomeContinue -> CouncilRoomPage -> default Koin CouncilRoomVM")
            .put("providerInvoked", false).toString(2))
    }

    private companion object {
        const val HOST = "P4 Local Host"
        const val GUEST = "P4 Multilingual Evidence Reviewer · 本地长成员名称"
        const val FILE_NAME = "p4-local-evidence.txt"
        const val QUESTION = "P4 historical question: which local evidence should be retained? This finished room must remain read-only."
        const val SYNTHESIS = "P4 synthesis: local evidence is retained; no provider generation or CLI has been requested."
    }
}
