package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.service.ChatRunPresenter
import app.amber.core.service.ConversationAccess
import app.amber.core.service.ConversationStore
import app.amber.core.service.PendingMessageStore
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.runtime.RunTerminal
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.RunTerminalStore
import java.io.File
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.Uuid

/** Real composer/runner/store paths; only the external OpenAI endpoint is scripted locally. */
@RunWith(AndroidJUnit4::class)
class ModularizationChatRuntimeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val service: ChatService get() = getKoin().get()
    private val repository: ConversationRepository get() = getKoin().get()
    private val pendingStore: PendingMessageStore get() = getKoin().get()
    private val terminals: RunTerminalStore get() = getKoin().get()
    private val server = LocalFixtureClient(
        InstrumentationRegistry.getArguments().getString("amberRuntimeFixturePort")?.toIntOrNull() ?: 18766,
    )

    @Test
    fun realComposerQueuesStopsAndExplicitlyResumesInFifoOrder() = withFixture { fixture, scenario ->
        send(fixture.prompt("FIRST"))
        waitForRequests(fixture, listOf("FIRST"))
        val stoppedRun = awaitActiveRun(fixture, RunTerminalState.RUNNING)
        waitForReply(fixture, "P2_STREAM_FIRST_CASE_${fixture.caseId}")
        // Active streaming has a deliberate frame loop; drive only this test's Compose clock.
        compose.mainClock.autoAdvance = false
        awaitComposerState(generating = true)
        capture("01-running-composer", fixture)

        val firstFollowup = fixture.prompt("FOLLOWUP_1")
        val secondFollowup = fixture.prompt("FOLLOWUP_2")
        send(firstFollowup)
        waitForQueue(fixture, listOf(firstFollowup))
        send(secondFollowup)
        waitForQueue(fixture, listOf(firstFollowup, secondFollowup))
        assertEquals("Queued messages must not start another provider request", listOf("FIRST"), server.phases(fixture.caseId))

        val presenter: ChatRunPresenter = getKoin().get()
        awaitCondition("The actual process and presenter must initially be foreground") {
            presenter.isForeground.value && ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        val queueBeforeBackground = pendingStore.load(fixture.conversationId).map { it.id }
        var activityIdentity = 0
        scenario.onActivity { activityIdentity = System.identityHashCode(it) }
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        awaitCondition("HOME must background the real process and presenter") {
            !presenter.isForeground.value && !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        assertEquals(RunTerminalState.RUNNING, terminal(stoppedRun.runId)?.state)
        assertEquals(stoppedRun.runId, runBlocking { terminals.activeForConversation(fixture.caseId) }?.runId)
        waitForQueue(fixture, listOf(firstFollowup, secondFollowup))
        assertEquals(queueBeforeBackground, pendingStore.load(fixture.conversationId).map { it.id })
        assertEquals(listOf("FIRST"), server.phases(fixture.caseId))
        writeEvidence("${fixture.caseId}-background.json", JSONObject().apply {
            put("presenterForeground", presenter.isForeground.value)
            put("processState", ProcessLifecycleOwner.get().lifecycle.currentState.name)
            put("runId", stoppedRun.runId)
            put("runState", terminal(stoppedRun.runId)?.state?.name)
            put("durableQueuedIds", queueBeforeBackground.joinToString())
            put("providerRequestCount", server.phases(fixture.caseId).size)
        }.toString(2))
        context.startActivity(Intent(context, RouteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("conversationId", fixture.caseId))
        awaitCondition("Warm route must foreground the same process and Activity") {
            presenter.isForeground.value && ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                scenario.state == Lifecycle.State.RESUMED
        }
        scenario.onActivity { assertEquals("Warm return must reuse the original Activity", activityIdentity, System.identityHashCode(it)) }
        assertEquals(stoppedRun.runId, awaitActiveRun(fixture, RunTerminalState.RUNNING).runId)
        waitForQueue(fixture, listOf(firstFollowup, secondFollowup))
        assertEquals(queueBeforeBackground, pendingStore.load(fixture.conversationId).map { it.id })
        capture("02-background-return-same-owner", fixture)

        composer().performClick().performTextReplacement("P2 unsent draft stays editable")
        advanceUiFrames()
        composer().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("P2 unsent draft stays editable")))
        capture("02-queued-input-ime", fixture)
        composer().performTextReplacement("")
        closeSoftKeyboard()
        openQueue(2)
        assertQueueCardActions()
        capture("03-queue-actions", fixture)
        assertSeparateActions(text(R.string.chat_page_queue_move_to_input), text(R.string.chat_page_queue_resume))
        compose.onNodeWithText(text(R.string.chat_page_close)).performClick()

        clickComposerAction(R.string.stop)
        awaitCondition("Stopped run must durably cancel and release its actual dispatch") {
            terminal(stoppedRun.runId)?.state == RunTerminalState.CANCELLED &&
                service.getGenerationJobStateFlow(fixture.conversationId).value == null
        }
        waitForQueue(fixture, listOf(firstFollowup, secondFollowup))
        assertEquals(listOf("FIRST"), server.phases(fixture.caseId))
        awaitComposerState(generating = false)
        capture("04-stopped-queue-preserved", fixture)

        openQueue(2)
        compose.onNodeWithText(text(R.string.chat_page_queue_resume)).performClick()
        // Resuming is a real UI action; the dialog can stay open while the first stream is gated.
        compose.onNodeWithText(text(R.string.chat_page_close)).performClick()
        waitForRequests(fixture, listOf("FIRST", "FOLLOWUP_1"))
        val firstResumedRun = awaitActiveRun(fixture, RunTerminalState.RUNNING)
        assertNotEquals(stoppedRun.runId, firstResumedRun.runId)
        waitForQueue(fixture, listOf(secondFollowup))
        waitForReply(fixture, "P2_STREAM_FOLLOWUP_1_CASE_${fixture.caseId}")
        awaitComposerState(generating = true)
        capture("05-resumed-first-followup", fixture)
        server.release(fixture.caseId, "FOLLOWUP_1")

        waitForRequests(fixture, listOf("FIRST", "FOLLOWUP_1", "FOLLOWUP_2"))
        val secondResumedRun = awaitActiveRun(fixture, RunTerminalState.RUNNING)
        assertNotEquals(firstResumedRun.runId, secondResumedRun.runId)
        waitForQueue(fixture, emptyList())
        server.release(fixture.caseId, "FOLLOWUP_2")
        val finalMarker = "P2_FINISHED_FOLLOWUP_2_CASE_${fixture.caseId}"
        awaitCondition("FIFO successor must finish and persist its final reply") {
            terminal(secondResumedRun.runId)?.state == RunTerminalState.COMPLETED &&
                service.getGenerationJobStateFlow(fixture.conversationId).value == null &&
                persistedReply(fixture).contains(finalMarker)
        }
        val stored = runBlocking { requireNotNull(repository.getConversationById(fixture.conversationId)) }
        val userMessages = stored.currentMessages.filter { it.role == MessageRole.USER }.map { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        }
        assertEquals(listOf(fixture.prompt("FIRST"), firstFollowup, secondFollowup), userMessages)
        assertEquals(RunTerminalState.COMPLETED, terminal(firstResumedRun.runId)?.state)
        assertTrue(persistedReply(fixture).contains("P2_FINISHED_FOLLOWUP_1_CASE_${fixture.caseId}"))
        reveal(finalMarker, substring = true)
        awaitComposerState(generating = false)
        capture("06-fifo-completed", fixture)
    }

    @Test
    fun askUserPersistsPauseAndResumesTheSameRunAfterARealUiAnswer() = withFixture { fixture, scenario ->
        send(fixture.prompt("ASK"))
        waitForRequests(fixture, listOf("ASK"))
        val pausedRun = awaitActiveRun(fixture, RunTerminalState.WAITING_USER)
        reveal(QUESTION)
        awaitCondition("Real ask_user tool must be persisted as pending") {
            tool(fixture)?.approvalState is ToolApprovalState.Pending
        }
        capture("07-approval-pending", fixture)

        scenario.recreate()
        reveal(QUESTION)
        assertEquals(pausedRun.runId, awaitActiveRun(fixture, RunTerminalState.WAITING_USER).runId)
        compose.onNodeWithText(FIRST_OPTION, useUnmergedTree = true).performClick()
        assertEquals("Selecting an answer must not resume the provider", listOf("ASK"), server.phases(fixture.caseId))
        reveal(text(R.string.chat_message_tool_submit))
        compose.onNodeWithText(text(R.string.chat_message_tool_submit)).performClick()

        waitForRequests(fixture, listOf("ASK", "ANSWERED"))
        val finalMarker = "P2_ANSWERED_CASE_${fixture.caseId}"
        awaitCondition("Answered tool must resume the original run and durably complete") {
            terminal(pausedRun.runId)?.state == RunTerminalState.COMPLETED &&
                service.getGenerationJobStateFlow(fixture.conversationId).value == null &&
                persistedReply(fixture).contains(finalMarker)
        }
        val answered = tool(fixture)?.approvalState as? ToolApprovalState.Answered
        assertTrue("The real UI answer must survive persistence", answered?.answer?.contains(FIRST_OPTION) == true)
        val requests = server.snapshot(fixture.caseId).getJSONArray("requests")
        assertTrue(requests.getJSONObject(1).getString("tool_result").contains(FIRST_OPTION))
        assertEquals("No fixture request may lose the real ask_user tool wiring", 0, server.snapshot(fixture.caseId).getJSONArray("errors").length())
        reveal(finalMarker, substring = true)
        awaitComposerState(generating = false)
        capture("08-approval-resumed-same-run", fixture)
    }

    private fun withFixture(block: (RuntimeFixture, ActivityScenario<RouteActivity>) -> Unit) {
        assumeTrue("Explicit local fixture opt-in is required", InstrumentationRegistry.getArguments().getString("amberRuntimeFixture") == "true")
        assumeTrue("Use the real UI smoke runner", context.applicationContext is AmberAgentApp)
        val device = "${Build.MODEL} ${Build.FINGERPRINT}".lowercase()
        assumeTrue("Only the task-owned emulator may receive local fixture settings", device.contains("sdk_gphone") || device.contains("emulator"))
        check(server.health().getString("fixture") == "amber-p2-local-sse") { "Start this task's local SSE fixture first" }
        val flags: CapabilityFlags = getKoin().get()
        check(runBlocking { flags.isEnabled(Capability.DurableToolEffects) && flags.isEnabled(Capability.TypedRunTerminal) }) {
            "This fixture requires the existing durable runtime flags; it does not change capability switches"
        }
        // Resolve the real durable binding before writing fixture settings; no legacy-path substitute.
        terminals
        val settingsStore: SettingsAggregator = getKoin().get()
        val original = runBlocking { withTimeout(10_000) { settingsStore.settingsFlow.first { !it.init } } }
        val originalPersistentSettings = JsonInstant.encodeToJsonElement(original)
        val originalAutoAdvance = compose.mainClock.autoAdvance
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val originalLaunch = preferences.getString(LAUNCH_START_MODE_PREF, null)
        val fixture = RuntimeFixture(Uuid.random())
        val mainModel = Model(modelId = "p2-runtime-main", displayName = "P2 local runtime", type = ModelType.CHAT,
            contextWindowTokens = 64_000, abilities = listOf(ModelAbility.TOOL))
        val auxiliaryModel = Model(modelId = "p2-runtime-aux", displayName = "P2 local auxiliary", type = ModelType.CHAT,
            contextWindowTokens = 16_000)
        val provider = ProviderSetting.OpenAI(name = "P2 local fixture ${fixture.caseId}",
            models = listOf(mainModel, auxiliaryModel), apiKey = "p2-local-fixture-only", baseUrl = server.baseUrl + "/v1",
            authMode = OpenAIAuthMode.API_KEY, brand = OpenAIBrand.GENERIC, useResponseApi = false)
        check(runBlocking { repository.getConversationSummaryById(fixture.conversationId) == null })
        var primaryFailure: Throwable? = null
        try {
            preferences.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            runBlocking {
                settingsStore.update(original.copy(
                    providers = original.providers + provider, chatModelId = mainModel.id,
                    titleModelId = auxiliaryModel.id, suggestionModelId = auxiliaryModel.id, compressModelId = auxiliaryModel.id,
                    streamOutput = true, enableWebSearch = false, enabledMcpServerIds = emptySet(),
                    presetMessages = emptyList(), regexes = emptyList(), maxTokens = 4_096,
                    systemPrompt = "Task-owned local runtime fixture. Follow the scripted local response.",
                    agentRuntime = original.agentRuntime.copy(
                        enableCoreMemory = false, enableShortTermMemory = false, enableLongTermMemory = false,
                        enableRecentChatsReference = false, autoApproveAllToolCalls = false,
                        autoApproveHighRiskToolCalls = false,
                        memoryWorker = original.agentRuntime.memoryWorker.copy(enabled = false),
                        contextCompaction = original.agentRuntime.contextCompaction.copy(enabled = false),
                        generativeUi = original.agentRuntime.generativeUi.copy(enabled = false),
                    ),
                ))
                withTimeout(10_000) { settingsStore.settingsFlow.first { it.getCurrentChatModel()?.id == mainModel.id } }
                repository.insertConversation(Conversation.ofId(fixture.conversationId).copy(title = "P2 runtime ${fixture.caseId}"))
            }
            val conversationAccess: ConversationAccess = getKoin().get()
            val conversationStore: ConversationStore = getKoin().get()
            assertSame("ConversationAccess must resolve the production ConversationStore owner", conversationStore, conversationAccess)
            val serviceConversation = service.getConversationFlow(fixture.conversationId)
            val storeConversation = conversationStore.getConversationFlow(fixture.conversationId)
            assertSame("ChatService must expose its ConversationStore's exact conversation flow", storeConversation, serviceConversation)
            writeEvidence("${fixture.caseId}-owner-identity.json", JSONObject().apply {
                put("conversationId", fixture.caseId)
                put("conversationAccessClass", conversationAccess.javaClass.name)
                put("conversationStoreClass", conversationStore.javaClass.name)
                put("conversationAccessIdentity", System.identityHashCode(conversationAccess))
                put("conversationStoreIdentity", System.identityHashCode(conversationStore))
                put("serviceFlowIdentity", System.identityHashCode(serviceConversation))
                put("storeFlowIdentity", System.identityHashCode(storeConversation))
                put("sameConversationOwner", conversationAccess === conversationStore)
                put("sameConversationFlow", serviceConversation === storeConversation)
            }.toString(2))
            val intent = Intent(context, RouteActivity::class.java).putExtra("conversationId", fixture.caseId)
            ActivityScenario.launch<RouteActivity>(intent).use { scenario ->
                try {
                    awaitCondition("Production chat composer must be ready") {
                        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    block(fixture, scenario)
                } catch (failure: Throwable) {
                    runCatching {
                        val bitmap = instrumentation.uiAutomation.takeScreenshot()
                        try {
                            File(evidenceDirectory(), "${fixture.caseId}-failure.png").outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }.onFailure(failure::addSuppressed)
                    runCatching {
                        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                        writeEvidence("${fixture.caseId}-failure.semantics.txt",
                            roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
                    }.onFailure(failure::addSuppressed)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            println("P2 original failure case=${fixture.caseId}: ${failure.stackTraceToString()}")
            throw failure
        } finally {
            try {
                try {
                    runBlocking {
                        withTimeout(15_000) {
                            val dispatch = service.getGenerationJobStateFlow(fixture.conversationId).value
                            service.stopGeneration(fixture.conversationId)
                            dispatch?.join()
                            repository.getConversationById(fixture.conversationId)?.let { service.deleteConversation(it) }
                        }
                    }
                    check(runBlocking { repository.getConversationSummaryById(fixture.conversationId) == null })
                    check(pendingStore.load(fixture.conversationId).isEmpty())
                } finally {
                    try {
                        server.release(fixture.caseId, "all")
                        writeEvidence("${fixture.caseId}-server-final.json", server.snapshot(fixture.caseId).toString(2))
                    } finally {
                        runBlocking { settingsStore.update(original) }
                        preferences.edit().apply {
                            if (originalLaunch == null) remove(LAUNCH_START_MODE_PREF) else putString(LAUNCH_START_MODE_PREF, originalLaunch)
                        }.apply()
                        try {
                            // Provider descriptions are transient composable lambdas, so object equality
                            // includes identities that are not part of the saved settings contract.
                            runBlocking { withTimeout(10_000) { settingsStore.settingsFlow.first {
                                !it.init && JsonInstant.encodeToJsonElement(it) == originalPersistentSettings
                            } } }
                            assertEquals(originalLaunch, preferences.getString(LAUNCH_START_MODE_PREF, null))
                            writeEvidence("${fixture.caseId}-restore.json", JSONObject().apply {
                                put("allPersistentSettingsRestored", true)
                                put("settingsInitialized", !settingsStore.settingsFlow.value.init)
                                put("launchPreferenceRestored", true)
                            }.toString(2))
                        } catch (restoreFailure: Throwable) {
                            val expected = JsonInstant.encodeToJsonElement(original).jsonObject
                            val actual = JsonInstant.encodeToJsonElement(settingsStore.settingsFlow.value).jsonObject
                            val changedKeys = (expected.keys + actual.keys).filter { expected[it] != actual[it] }
                            writeEvidence("${fixture.caseId}-restore-difference.json", JSONObject().apply {
                                put("changedTopLevelFields", changedKeys.joinToString())
                                put("settingsEqual", settingsStore.settingsFlow.value == original)
                            }.toString(2))
                            throw restoreFailure
                        }
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val originalFailure = primaryFailure
                if (originalFailure == null) throw cleanupFailure
                originalFailure.addSuppressed(cleanupFailure)
            } finally {
                compose.mainClock.autoAdvance = originalAutoAdvance
                try {
                    assertEquals(originalAutoAdvance, compose.mainClock.autoAdvance)
                    writeEvidence("${fixture.caseId}-clock-restored.json", JSONObject().apply {
                        put("testClockAutoAdvanceRestored", compose.mainClock.autoAdvance == originalAutoAdvance)
                    }.toString(2))
                } catch (clockFailure: Throwable) {
                    val originalFailure = primaryFailure
                    if (originalFailure == null) throw clockFailure
                    originalFailure.addSuppressed(clockFailure)
                }
            }
        }
    }

    private fun composer() = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()

    private fun send(value: String) {
        composer().performClick().performTextReplacement(value)
        advanceUiFrames()
        clickComposerAction(R.string.send)
        awaitCondition("Accepted send must consume exactly the submitted draft") {
            composer().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().isEmpty()
        }
    }

    private fun clickComposerAction(labelId: Int) {
        advanceUiFrames()
        val description = text(labelId)
        compose.onAllNodes(
            hasClickAction() and (hasContentDescription(description) or hasAnyDescendant(hasContentDescription(description))),
            useUnmergedTree = true,
        ).onFirst().performClick()
    }

    private fun openQueue(count: Int) {
        val label = context.getString(R.string.chat_page_queued_message_count, count)
        reveal(label)
        compose.onNodeWithText(label).performClick()
        advanceUiFrames()
        compose.onNodeWithText(text(R.string.chat_page_queue_title)).assertIsDisplayed()
    }

    private fun waitForQueue(fixture: RuntimeFixture, expected: List<String>) {
        fun texts(messages: List<app.amber.core.service.PendingUserMessage>) = messages.map { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        }
        awaitCondition("Queue UI owner and durable snapshot must agree: $expected") {
            texts(service.getPendingUserMessagesFlow(fixture.conversationId).value) == expected &&
                texts(pendingStore.load(fixture.conversationId)) == expected
        }
    }

    private fun awaitActiveRun(fixture: RuntimeFixture, state: RunTerminalState): RunTerminal {
        var found: RunTerminal? = null
        awaitCondition("Conversation must own a durable $state run") {
            found = runBlocking { terminals.activeForConversation(fixture.caseId) }
            found?.state == state
        }
        return requireNotNull(found)
    }

    private fun terminal(runId: String): RunTerminal? = runBlocking { terminals.get(runId) }

    private fun waitForRequests(fixture: RuntimeFixture, expected: List<String>) =
        awaitCondition("Real provider request order must be $expected") { server.phases(fixture.caseId) == expected }

    private fun waitForReply(fixture: RuntimeFixture, marker: String) = awaitCondition("Production assistant must show $marker") {
        assistantText(service.getConversationFlow(fixture.conversationId).value).contains(marker)
    }

    private fun persistedReply(fixture: RuntimeFixture): String = runBlocking {
        repository.getConversationById(fixture.conversationId)?.let(::assistantText).orEmpty()
    }

    private fun assistantText(conversation: Conversation): String = conversation.currentMessages
        .filter { it.role == MessageRole.ASSISTANT }
        .flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun tool(fixture: RuntimeFixture): UIMessagePart.Tool? = runBlocking {
        repository.getConversationById(fixture.conversationId)?.currentMessages?.flatMap { it.parts }
            ?.filterIsInstance<UIMessagePart.Tool>()?.singleOrNull { it.toolCallId == "p2-ask-${fixture.caseId}" }
    }

    private fun reveal(value: String, substring: Boolean = false) {
        advanceUiFrames()
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).onFirst()
            .performScrollToNode(hasText(value, substring = substring))
        advanceUiFrames()
        compose.onAllNodesWithText(value, substring = substring, useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    private fun assertSeparateActions(firstLabel: String, secondLabel: String) {
        val first = compose.onNodeWithText(firstLabel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText(secondLabel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse("Queue actions must not overlap", first.overlaps(second))
    }

    private fun assertQueueCardActions() {
        val touchBounds = mutableListOf<androidx.compose.ui.geometry.Rect>()
        for (labelId in listOf(R.string.chat_page_queue_move_up, R.string.chat_page_queue_move_down, R.string.chat_page_cancel)) {
            val label = text(labelId)
            val matches = compose.onAllNodesWithText(label, useUnmergedTree = true)
            assertEquals("Each queue card must expose $label", 2, matches.fetchSemanticsNodes().size)
            repeat(2) { index ->
                val node = matches[index].assertIsDisplayed().fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                instrumentation.runOnMainSync {
                    check(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
                }
                val layout = layouts.single()
                val details = "label=$label card=$index lines=${layout.lineCount} size=${node.size} right=${layout.getLineRight(0)} bottom=${layout.getLineBottom(0)}"
                writeEvidence("queue-label-$labelId-$index.txt", details)
                assertEquals("Queue action label must remain a readable horizontal line: $details", 1, layout.lineCount)
                assertFalse(details, layout.multiParagraph.didExceedMaxLines || layout.isLineEllipsized(0))
                assertTrue(details, layout.getLineLeft(0) >= -1f && layout.getLineRight(0) <= node.size.width + 1f &&
                    layout.getLineBottom(0) <= node.size.height + 1f)
                val button = generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                val bounds = button.boundsInWindow
                assertTrue("Queue action touch target must stay in the window: $label $bounds",
                    bounds.left >= 0 && bounds.top >= 0 && bounds.right <= context.resources.displayMetrics.widthPixels &&
                        bounds.bottom <= context.resources.displayMetrics.heightPixels && bounds.width > 0 && bounds.height > 0)
                touchBounds += bounds
            }
        }
        touchBounds.forEachIndexed { index, first ->
            touchBounds.drop(index + 1).forEach { second ->
                assertFalse("Queue card action touch targets must not overlap: $first / $second", first.overlaps(second))
            }
        }
    }

    private fun awaitComposerState(generating: Boolean) {
        val expected = text(if (generating) R.string.stop else R.string.send)
        val stale = text(if (generating) R.string.send else R.string.stop)
        awaitCondition("Composer must render $expected after the actual owner transition") {
            compose.onAllNodes(hasContentDescription(expected), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasContentDescription(stale), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasContentDescription(expected)), useUnmergedTree = true)
            .onFirst().assertIsDisplayed()
    }

    private fun capture(name: String, fixture: RuntimeFixture) {
        advanceUiFrames()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val width = context.resources.configuration.screenWidthDp
        val screenshotName = "$name-${width}dp"
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(evidenceDirectory(), "$screenshotName.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        val xml = Xml.newSerializer()
        val xmlText = StringWriter()
        xml.setOutput(xmlText)
        xml.startDocument("UTF-8", true)
        fun dumpNode(node: AccessibilityNodeInfo) {
            xml.startTag(null, "node")
            val bounds = Rect().also(node::getBoundsInScreen)
            for ((key, value) in listOf(
                "package" to node.packageName, "class" to node.className,
                "text" to node.text, "content-desc" to node.contentDescription,
                "bounds" to bounds.toShortString(), "clickable" to node.isClickable,
                "enabled" to node.isEnabled, "focused" to node.isFocused,
            )) xml.attribute(null, key, value?.toString().orEmpty())
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    try { dumpNode(child) } finally { child.recycle() }
                }
            }
            xml.endTag(null, "node")
        }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        try { dumpNode(root) } finally { root.recycle() }
        xml.endDocument()
        writeEvidence("$screenshotName.uiautomator.xml", xmlText.toString())
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        writeEvidence("$screenshotName.semantics.txt", roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        val queued = service.getPendingUserMessagesFlow(fixture.conversationId).value
        val activeRun = runBlocking { terminals.activeForConversation(fixture.caseId) }
        writeEvidence("$screenshotName.owner.json", JSONObject().apply {
            put("case", fixture.caseId)
            put("width_dp", width)
            put("density_dpi", context.resources.displayMetrics.densityDpi)
            put("font_scale", context.resources.configuration.fontScale)
            put("active_run", activeRun?.runId)
            put("active_state", activeRun?.state?.name)
            put("queued_ids", queued.joinToString { it.id })
            put("durable_queued_ids", pendingStore.load(fixture.conversationId).joinToString { it.id })
            put("requests", server.snapshot(fixture.caseId).getJSONArray("requests"))
        }.toString(2))
        println("ModularizationChatRuntimeDeviceTest screenshot=${File(evidenceDirectory(), "$screenshotName.png").absolutePath}")
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
                condition()
            }
        } catch (failure: Throwable) {
            throw AssertionError(message, failure)
        }
    }

    private fun advanceUiFrames() {
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(320)
    }

    private fun text(id: Int): String = context.getString(id)
    private fun evidenceDirectory(): File = requireNotNull(context.getExternalFilesDir(SCREENSHOT_DIRECTORY)).also {
        check(it.exists() || it.mkdirs())
    }
    private fun writeEvidence(name: String, body: String) = File(evidenceDirectory(), name).writeText(body)

    private data class RuntimeFixture(val conversationId: Uuid) {
        val caseId: String get() = conversationId.toString()
        fun prompt(phase: String): String = "P2_CASE_${caseId}_$phase"
    }

    private class LocalFixtureClient(port: Int) {
        val baseUrl = "http://10.0.2.2:$port"
        fun health(): JSONObject = request("/health")
        fun snapshot(caseId: String): JSONObject = request("/control/$caseId/snapshot")
        fun phases(caseId: String): List<String> = snapshot(caseId).getJSONArray("requests").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getString("phase") }
        }
        fun release(caseId: String, phase: String) = request("/control/$caseId/release/$phase", post = true)
        private fun request(path: String, post: Boolean = false): JSONObject {
            val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 2_000
                connection.readTimeout = 5_000
                if (post) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write("{}".toByteArray()) }
                }
                check(connection.responseCode == 200) { "Local fixture $path returned ${connection.responseCode}" }
                return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val SCREENSHOT_DIRECTORY = "modularization-phase-2"
        const val PREFERENCES_NAME = "amber_agent.preferences"
        const val LAUNCH_START_MODE_PREF = "launchStartMode"
        const val QUESTION = "下一步先检查哪一条本地运行链路？"
        const val FIRST_OPTION = "先检查排队消息顺序并保留当前草稿"
    }
}
