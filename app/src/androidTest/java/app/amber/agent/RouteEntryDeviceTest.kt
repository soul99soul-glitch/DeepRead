package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.feature.webmount.oauth.OAuthCallbackDispatcher
import app.amber.feature.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.Uuid

/** Same-app controlled deliveries through the real Activity, Koin and default pages. */
@RunWith(AndroidJUnit4::class)
class RouteEntryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun actualShareStagesOnceAndRecreationKeepsTheEditedDraft() = withFixture { caseId ->
        val workspace: WorkspaceManager = getKoin().get()
        val source = File(context.cacheDir, "p5-route-$caseId.txt").apply { writeText("P5 route attachment $caseId") }
        fun staged() = workspace.mirrorDir.resolve("uploads").listFiles().orEmpty()
            .filter { it.name.startsWith("p5-route-$caseId") }
        val firstText = "P5 cold share $caseId"
        val editedText = "P5 edited draft $caseId"
        try {
            assertTrue(staged().isEmpty())
            val cold = Intent(context, RouteActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, firstText)
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(source))
            }
            withRouteScenario(cold) { scenario ->
                await("Shared text must reach the actual editable composer") { composerText() == firstText }
                await("Actual ShareHandlerPage must stage exactly one source") { staged().size == 1 }
                assertEquals(source.readText(), staged().single().readText())
                compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(editedText)
                scenario.recreate()
                await("Recreation must restore the edited draft instead of replaying the intent") { composerText() == editedText }
                assertEquals("Recreation must not stage the already consumed share again", 1, staged().size)
                capture("share-recreated-$caseId")

                val secondText = "P5 warm share $caseId"
                deliver(Intent(context, RouteActivity::class.java).apply {
                    action = Intent.ACTION_PROCESS_TEXT
                    putExtra(Intent.EXTRA_PROCESS_TEXT, secondText)
                })
                await("A new warm intent must consume its own delivery") { composerText() == secondText }
                assertEquals(1, staged().size)
                capture("share-warm-$caseId")
            }
        } finally {
            source.delete()
            runBlocking { staged().forEach { workspace.deleteWorkspaceFile("uploads/${it.name}") } }
        }
    }

    @Test
    fun coldAndWarmNotificationKeepExactConversationWhenRunIsMissing() = withFixture { caseId ->
        val repository: ConversationRepository = getKoin().get()
        val first = Conversation.ofId(Uuid.random()).copy(title = "P5 cold target $caseId")
        val second = Conversation.ofId(Uuid.random()).copy(title = "P5 warm target $caseId")
        runBlocking { repository.insertConversation(first); repository.insertConversation(second) }
        try {
            val cold = Intent(context, RouteActivity::class.java)
                .putExtra("conversationId", first.id.toString())
                .putExtra("runId", "p5-absent-$caseId-cold")
            withRouteScenario(cold) { scenario ->
                await("Cold notification must land on its own persisted conversation") { visibleText(first.title) }
                await("Missing cold run must lose focus") {
                    var removed = false
                    scenario.onActivity { removed = !it.intent.hasExtra("runId") }
                    removed
                }
                val warm = Intent(context, RouteActivity::class.java)
                    .putExtra("conversationId", second.id.toString())
                    .putExtra("runId", "p5-absent-$caseId-warm")
                deliver(warm)
                await("Warm notification must land on the new exact conversation") { visibleText(second.title) }
                scenario.onActivity { assertEquals(second.id.toString(), it.intent.getStringExtra("conversationId")) }
                await("Missing warm run must lose focus") {
                    var removed = false
                    scenario.onActivity { removed = !it.intent.hasExtra("runId") }
                    removed
                }
                capture("notification-warm-$caseId")
            }
        } finally {
            runBlocking {
                repository.deleteConversation(first)
                repository.deleteConversation(second)
            }
        }
    }

    @Test
    fun coldAndWarmOAuthCallbacksReachExistingDispatcherWithoutReplacingTheDraft() = withFixture { caseId ->
        val dispatcher: OAuthCallbackDispatcher = getKoin().get()
        fun callback(state: String) = Intent(context, RouteActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("amberagent://oauth/p5-local-fixture?code=local-only&state=$state")
        }
        val coldState = "p5-cold-$caseId"
        withRouteScenario(callback(coldState)) { scenario ->
            // A controlled provider/state has no pending authorization and cannot exchange a token.
            assertEquals(coldState, dispatcher.events.replayCache.lastOrNull()?.state)
            deliver(Intent(context, RouteActivity::class.java).putExtra(RouteActivity.EXTRA_OPEN_CHAT_PROMPT, "P5 OAuth draft $caseId"))
            await("Task prompt must prefill through the actual route") { composerText() == "P5 OAuth draft $caseId" }
            val edited = "P5 OAuth edited $caseId"
            compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(edited)
            val warmState = "p5-warm-$caseId"
            deliver(callback(warmState))
            await("Warm OAuth callback must reach the same dispatcher") { dispatcher.events.replayCache.lastOrNull()?.state == warmState }
            assertEquals("An OAuth URI must not become a file/text share", edited, composerText())
            scenario.onActivity { assertFalse(it.intent.hasExtra(Intent.EXTRA_STREAM)) }
            capture("oauth-warm-$caseId")
        }
    }

    private fun withRouteScenario(launchIntent: Intent, block: (ActivityScenario<RouteActivity>) -> Unit) {
        val initialIntent = Intent(launchIntent)
        ActivityScenario.launch<RouteActivity>(launchIntent).use { scenario ->
            try {
                block(scenario)
            } finally {
                // ActivityScenario matches lifecycle events to its launch intent. Warm
                // OAuth/share changes data/action via setIntent; restore only for close.
                scenario.onActivity { it.intent = Intent(initialIntent) }
            }
        }
    }

    private fun withFixture(block: (String) -> Unit) {
        assumeTrue("Explicit local route fixture opt-in is required", InstrumentationRegistry.getArguments().getString("amberRouteFixture") == "true")
        assumeTrue("Use the production application runner", context.applicationContext is AmberAgentApp)
        assumeTrue("This fixture is restricted to the task emulator", "${Build.MODEL} ${Build.FINGERPRINT}".lowercase().let { it.contains("sdk_gphone") || it.contains("emulator") })
        val preferences = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val keys = listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF)
        val saved = keys.associateWith { preferences.getString(it, null) }
        try {
            preferences.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).commit()
            block(Uuid.random().toString())
        } finally {
            preferences.edit().apply { saved.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
        }
    }

    private fun deliver(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        instrumentation.waitForIdleSync()
    }

    private fun composerText(): String? = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    private fun visibleText(text: String): Boolean = compose.onAllNodes(
        androidx.compose.ui.test.hasText(text), useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    private fun await(message: String, predicate: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 20_000, condition = predicate) }
        catch (failure: Throwable) { throw AssertionError(message, failure) }
    }

    private fun capture(name: String) {
        val directory = File(context.getExternalFilesDir(null), "modularization-phase-5-route").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "$name.semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }
}
