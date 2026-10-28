package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.AgentOperationPreviewMode
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.task.running
import app.amber.feature.terminal.TerminalJobSnapshot
import app.amber.feature.terminal.TerminalJobStatus
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.terminal.TerminalRuntimeKind
import app.amber.feature.workspace.WorkspaceManager
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Real Android shell -> task/output owner -> scoped Chat sandbox; no installed external backend. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalUuidApi::class)
class TerminalWorkspaceModularizationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var evidence: File
    private lateinit var activity: RouteActivity

    @Test
    fun realAndroidShellOutputStopAndFreshJobReleaseTheWorkspaceLease() {
        assumeTrue("Explicit disposable fixture opt-in required", InstrumentationRegistry.getArguments()
            .getString("amberTerminalWorkspaceFixture") == "true")
        check(context.applicationContext is AmberAgentApp)
        check("${Build.MODEL} ${Build.FINGERPRINT}".lowercase().let { it.contains("emulator") || it.contains("sdk_gphone") })
        val runtime: TerminalRuntime = getKoin().get()
        val workspace: WorkspaceManager = getKoin().get()
        val tasks: AgentTaskStore = getKoin().get()
        val activities: AgentToolActivityStore = getKoin().get()
        check(File("/system/bin/sh").canExecute()) { "Existing Android shell backend required; do not install a backend" }
        check(!workspace.state.value.configured) { "Protect an existing SAF binding: this fixture only owns an unbound local mirror" }
        val beforeWorkspace = workspace.state.value
        val beforeTasks = runBlocking { tasks.list().associateBy { it.taskId } }
        check(beforeTasks.values.none { it.status.running }) { "Do not run alongside existing user tasks" }
        val beforeActivity = activities.sandboxActivity.value
        val beforeFiles = mirrorSnapshot(workspace.mirrorDir)
        val repository: ConversationRepository = getKoin().get()
        val dao: ConversationDAO = getKoin().get()
        val beforeConversations = runBlocking { dao.getAll().first().associateBy { it.id } }
        val settings: SettingsAggregator = getKoin().get()
        val original = runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init } } }
        val persistent = JsonInstant.encodeToJsonElement(original)
        val prefs = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val beforePreferences = prefs.all.toMap()
        val id = Uuid.random()
        evidence = File(context.getExternalFilesDir(null), "modularization-phase-4-terminal-workspace/$id").apply { mkdirs() }
        val folder = "p4-terminal-$id"
        val fixtureDirectory = File(workspace.mirrorDir, folder)
        check(!fixtureDirectory.exists())
        val conversation = Conversation(id = id, assistantId = AMBER_AGENT_ID, title = "P4 local native shell ${id.toString().take(8)}", messageNodes = emptyList())
        val ownedJobs = mutableListOf<String>()
        val outputLogs = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            runBlocking {
                repository.insertConversation(conversation)
                settings.update { it.copy(agentRuntime = it.agentRuntime.copy(operationPreviewMode = AgentOperationPreviewMode.ALWAYS)) }
            }
            prefs.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            ActivityScenario.launch<RouteActivity>(Intent(context, RouteActivity::class.java).putExtra("conversationId", id.toString())).use { scenario ->
                scenario.onActivity { activity = it }
                try {
                    compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                    // The native command is intentionally held until Stop, not a test-side settling sleep.
                    // All IO is below the unique UUID mirror directory, with no SAF refresh/flush.
                    val command = "mkdir -p '$folder'\nprintf '%s\\n' '$OUTPUT' > '$folder/output.txt'\ncat '$folder/output.txt'\nwhile [ ! -f '$folder/release' ]; do /system/bin/sleep 0.1; done\n"
                    val first = runBlocking { activities.withConversation(id.toString()) {
                        runtime.startJob(command, runtime = TerminalRuntimeKind.ANDROID_SHELL, timeoutMillis = 120_000,
                            syncWorkspace = false, flushWorkspace = false)
                    } }
                    ownedJobs += first.jobId
                    outputLogs += first.outputLogPath
                    awaitOutput(runtime, first.jobId, OUTPUT)
                    assertEquals(OUTPUT + "\n", File(fixtureDirectory, "output.txt").readText())
                    assertEquals(id.toString(), activities.sandboxActivity.value?.conversationId)
                    capture("01-native-running-peek", first.jobId, runtime, workspace)
                    val actualTitle = requireNotNull(activities.sandboxActivity.value).title
                    openSheetAtTitle(actualTitle, "02-open-running-title-target")
                    awaitSheet()
                    revealSheetText(context.getString(R.string.chat_sandbox_call_input))
                    revealSheetText(context.getString(R.string.chat_sandbox_call_result))
                    revealSheetOutput(OUTPUT, activities)
                    capture("02-native-running-output", first.jobId, runtime, workspace)
                    assertTrue("A running native job must own the real mirror lease", runBlocking {
                        runCatching { workspace.acquireTerminalMirror(false).release(false) }.exceptionOrNull() is IllegalStateException
                    })
                    assertEquals(TerminalJobStatus.CANCELLED, runBlocking { runtime.stopJob(first.jobId) }.status)
                    runBlocking { withTimeout(15_000) { tasks.tasksFlow.first { rows ->
                        rows.any { it.taskId == first.jobId && it.status == AgentTaskStatus.CANCELLED }
                    } } }
                    compose.waitUntil(15_000) {
                        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                            context.getString(R.string.chat_sandbox_status_cancelled)) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    revealSheetOutput("Command cancelled by user.", activities)
                    capture("03-native-stopped-output", first.jobId, runtime, workspace)
                    compose.onNode(isDialog(), useUnmergedTree = true).assertIsDisplayed()
                    pressBack()
                    compose.waitUntil(15_000) { compose.onAllNodes(isDialog(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                    // Closing the actual sheet must keep this conversation, rather than silently pop Home.
                    compose.onNode(hasContentDescription(conversation.title)).assertIsDisplayed()
                    compose.onNode(hasSetTextAction()).assertIsDisplayed()
                    assertEquals(id.toString(), activities.sandboxActivity.value?.conversationId)
                    // Public lease acquisition after actual terminal settlement proves release; no reflection/retry.
                    runBlocking { workspace.acquireTerminalMirror(false).release(false) }
                    val next = runBlocking { activities.withConversation(id.toString()) {
                        runtime.startJob("cat '$folder/output.txt'\nprintf '%s\\n' '$FRESH'\n", runtime = TerminalRuntimeKind.ANDROID_SHELL,
                            syncWorkspace = false, flushWorkspace = false)
                    } }
                    ownedJobs += next.jobId
                    outputLogs += next.outputLogPath
                    val completed = runBlocking { runtime.waitJob(next.jobId, 15_000) }
                    assertEquals(TerminalJobStatus.COMPLETED, completed.status)
                    assertEquals(0, completed.exitCode)
                    assertTrue(completed.outputTail.contains(OUTPUT) && completed.outputTail.contains(FRESH))
                    runBlocking { withTimeout(15_000) { tasks.tasksFlow.first { rows ->
                        rows.any { it.taskId == next.jobId && it.status == AgentTaskStatus.COMPLETED }
                    } } }
                    openSheetAtTitle(requireNotNull(activities.sandboxActivity.value).title, "04-open-fresh-title-target")
                    awaitSheet()
                    revealSheetText(context.getString(R.string.chat_sandbox_call_input))
                    revealSheetText(context.getString(R.string.chat_sandbox_call_result))
                    revealSheetOutput(FRESH, activities)
                    capture("04-fresh-native-completed-output", next.jobId, runtime, workspace)
                    scenario.recreate()
                    scenario.onActivity { activity = it }
                    compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(id.toString(), activities.sandboxActivity.value?.conversationId)
                    awaitSheet()
                    revealSheetOutput(FRESH, activities)
                    capture("05-recreated-native-result-sheet", next.jobId, runtime, workspace)
                } catch (error: Throwable) { runCatching { rawScreenshot("failure") }.onFailure(error::addSuppressed); throw error }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                runBlocking {
                    ownedJobs.forEach { job ->
                        if (runtime.readJob(job).running) runtime.stopJob(job)
                        withTimeout(15_000) { tasks.tasksFlow.first { rows -> rows.any { it.taskId == job && !it.status.running } } }
                    }
                    workspace.acquireTerminalMirror(false).release(false)
                    ownedJobs.forEach { job -> tasks.remove(job); activities.clear(job) }
                    if (beforeActivity != null) activities.start(beforeActivity)
                    repository.deleteConversation(conversation)
                    settings.update(original)
                    withTimeout(10_000) { settings.settingsFlow.first { !it.init && JsonInstant.encodeToJsonElement(it) == persistent } }
                }
                if (fixtureDirectory.exists()) check(fixtureDirectory.deleteRecursively())
                outputLogs.forEach { path ->
                    val log = File(path)
                    check(log.canonicalPath.startsWith(File(context.filesDir, "amberagent/terminal-jobs").canonicalPath + File.separator))
                    if (log.exists()) check(log.delete())
                }
                prefs.edit().apply {
                    listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).forEach { key ->
                        if (beforePreferences.containsKey(key)) putString(key, beforePreferences[key] as String?) else remove(key)
                    }
                }.commit()
                val filesRestored = beforeFiles == mirrorSnapshot(workspace.mirrorDir)
                val taskRowsRestored = beforeTasks == runBlocking { tasks.list().associateBy { it.taskId } }
                val conversationsRestored = beforeConversations == runBlocking { dao.getAll().first().associateBy { it.id } }
                val preferencesRestored = listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).all {
                    prefs.all[it] == beforePreferences[it] && prefs.contains(it) == beforePreferences.containsKey(it)
                }
                File(evidence, "cleanup.json").writeText(JSONObject().put("mirrorFilesIncludingUploadsRestored", filesRestored)
                    .put("originalTaskRowsRestored", taskRowsRestored).put("originalConversationRowsRestored", conversationsRestored)
                    .put("settingsRestored", true).put("launchPreferencesRestored", preferencesRestored)
                    .put("workspaceBindingRestored", beforeWorkspace == workspace.state.value)
                    .put("originalActivityProjectionRestored", beforeActivity == activities.sandboxActivity.value)
                    .put("nativeJobsTerminal", ownedJobs.all { runBlocking { !runtime.readJob(it).running } }).toString(2))
                assertTrue(filesRestored)
                assertTrue(taskRowsRestored)
                assertTrue(conversationsRestored)
                assertTrue(preferencesRestored)
                assertEquals(beforeWorkspace, workspace.state.value)
                assertEquals(beforeActivity, activities.sandboxActivity.value)
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun awaitOutput(runtime: TerminalRuntime, job: String, marker: String) {
        runBlocking { withTimeout(15_000) {
            val tasks: AgentTaskStore = getKoin().get()
            tasks.tasksFlow.first { it.any { row -> row.taskId == job && row.status == AgentTaskStatus.RUNNING } }
        } }
        compose.waitUntil(15_000) { runBlocking { runtime.readJob(job).outputTail.contains(marker) } }
    }
    private fun awaitSheet() = compose.waitUntil(15_000) {
        compose.onAllNodes(isDialog(), useUnmergedTree = true).fetchSemanticsNodes().size == 1
    }
    private fun openSheetAtTitle(title: String, evidenceName: String) {
        val target = compose.onNode(hasText(title), useUnmergedTree = true).assertIsDisplayed()
        val text = target.fetchSemanticsNode()
        val row = generateSequence(text) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        val titleCenter = text.boundsInRoot.center
        val rowCenter = row.boundsInRoot.center
        val nestedTargets = JSONArray()
        fun collectTargets(parent: SemanticsNode) {
            parent.children.forEach { child ->
                if (child.config.contains(SemanticsActions.OnClick)) nestedTargets.put(JSONObject().apply {
                    put("nodeId", child.id)
                    put("disabled", child.config.contains(SemanticsProperties.Disabled))
                    put("visualBoundsInRoot", child.boundsInRoot.toString())
                    put("visualBoundsInWindow", child.boundsInWindow.toString())
                    put("touchBoundsInRoot", child.touchBoundsInRoot.toString())
                    put("visualContainsRowCenter", child.boundsInRoot.contains(rowCenter))
                    put("touchContainsRowCenter", child.touchBoundsInRoot.contains(rowCenter))
                    put("visualContainsTitleCenter", child.boundsInRoot.contains(titleCenter))
                    put("touchContainsTitleCenter", child.touchBoundsInRoot.contains(titleCenter))
                })
                collectTargets(child)
            }
        }
        collectTargets(row)
        File(evidence, "$evidenceName.json").writeText(JSONObject().apply {
            put("title", title)
            put("titleNodeId", text.id)
            put("titleVisualBoundsInRoot", text.boundsInRoot.toString())
            put("titleVisualBoundsInWindow", text.boundsInWindow.toString())
            put("titleTouchBoundsInRoot", text.touchBoundsInRoot.toString())
            put("titleCenterInRoot", titleCenter.toString())
            put("rowNodeId", row.id)
            put("rowVisualBoundsInRoot", row.boundsInRoot.toString())
            put("rowVisualBoundsInWindow", row.boundsInWindow.toString())
            put("rowTouchBoundsInRoot", row.touchBoundsInRoot.toString())
            put("rowCenterInRoot", rowCenter.toString())
            put("nestedClickTargets", nestedTargets)
            put("gesture", "One real touch at the unmerged title Text center")
            put("gestureCount", 1)
        }.toString(2))
        // The merged row's center can fall in a nested arrow's touch bounds on narrow screens.
        // Exercise the actual title hit target, with no semantic OnClick or retry.
        target.performTouchInput { click(center) }
    }
    private fun revealSheetText(value: String) {
        val node = compose.onNode(hasText(value) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed()
    }
    private fun revealSheetOutput(marker: String, activities: AgentToolActivityStore) {
        val output = requireNotNull(activities.sandboxActivity.value).outputTail
        assertTrue("The real activity owner must contain the output marker", output.contains(marker))
        // Exact owner output in the real Dialog cannot match the peek or the command input.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(output) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        revealSheetText(output)
    }
    private fun mirrorSnapshot(root: File): Map<String, String> = root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        .associate { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(8192); var read = input.read(buffer)
                while (read >= 0) { if (read > 0) digest.update(buffer, 0, read); read = input.read(buffer) } }
            file.relativeTo(root).invariantSeparatorsPath to digest.digest().joinToString("") { "%02x".format(it) }
        }
    private fun rawScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun capture(name: String, job: String, runtime: TerminalRuntime, workspace: WorkspaceManager) {
        compose.waitForIdle()
        awaitPresentedWindows()
        rawScreenshot(name)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(evidence, "$name.semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        val snapshot: TerminalJobSnapshot = runBlocking { runtime.readJob(job) }
        var window = ""
        instrumentation.runOnMainSync { window = activity.windowManager.currentWindowMetrics.bounds.toShortString() }
        File(evidence, "$name.owner.json").writeText(JSONObject().put("jobId", snapshot.jobId)
            .put("runtime", snapshot.runtime.wireName).put("status", snapshot.status.name).put("output", snapshot.outputTail)
            .put("workspaceConfigured", workspace.state.value.configured)
            .put("widthDp", context.resources.configuration.screenWidthDp).put("fontScale", context.resources.configuration.fontScale)
            .put("windowBoundsPx", window)
            .put("route", "real TerminalRuntime -> scoped AgentToolActivityStore -> RouteActivity/default ChatVM -> SandboxActivitySheet")
            .put("safPreviewVerified", false).put("externalBackendInstalled", false).toString(2))
    }

    private fun awaitPresentedWindows() {
        // Semantics may already be updated before the corresponding pixels draw. The sheet
        // owns a Dialog window, so fencing only the Activity decor would miss its actual draw.
        val roots = mutableListOf<View>()
        instrumentation.runOnMainSync {
            roots += WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown }
        }
        check(roots.isNotEmpty())
        val presented = CountDownLatch(roots.size)
        val listeners = mutableListOf<Pair<View, ViewTreeObserver.OnDrawListener>>()
        instrumentation.runOnMainSync {
            roots.forEach { root ->
                val observed = AtomicBoolean()
                val listener = object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        if (!observed.compareAndSet(false, true)) return
                        root.post {
                            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(this)
                        }
                        root.postOnAnimation { presented.countDown() }
                    }
                }
                listeners += root to listener
                root.viewTreeObserver.addOnDrawListener(listener)
                root.invalidate()
            }
        }
        try {
            assertTrue("Actual Activity and Dialog windows must draw before capture", presented.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                listeners.forEach { (root, listener) ->
                    if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(listener)
                }
            }
        }
    }
    private companion object {
        const val OUTPUT = "P4_NATIVE_OUTPUT 中文与 emoji 🧭 retained in the task-owned workspace file"
        const val FRESH = "P4_FRESH_JOB_AFTER_STOP completed with the released native workspace lease"
    }
}
