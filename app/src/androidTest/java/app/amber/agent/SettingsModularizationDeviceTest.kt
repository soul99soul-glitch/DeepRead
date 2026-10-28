package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.webmount.usersites.UserSiteRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** Actual settings routes/default Koin owners, local drafts and cancellation; never restore/connect. */
@RunWith(AndroidJUnit4::class)
class SettingsModularizationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var settings: SettingsAggregator
    private lateinit var baseline: Settings
    private lateinit var evidence: File
    private lateinit var taskId: String

    @Test
    fun backupSourcePickersCancelWithoutVerifyingOrRestoring() {
        withFixture("backup", prepareBackup = true) { scenario ->
            openSettings()
            clickText(R.string.setting_page_backup)
            waitText(R.string.backup_page_title)
            revealText(R.string.backup_import)
            assertTextFits(text(R.string.backup_import))
            capture("01-backup-local-source")
            clickText(R.string.backup_import)
            awaitDocumentsPicker()
            rawScreenshot("02-backup-import-picker")
            assertTrue(systemBack())
            waitText(R.string.backup_page_title)
            assertSettingsUnchanged()
            clickText(R.string.backup_export)
            awaitDocumentsPicker()
            rawScreenshot("03-backup-export-picker")
            assertTrue(systemBack())
            waitText(R.string.backup_page_title)
            scenario.recreate()
            waitText(R.string.backup_page_title)
            revealText(R.string.backup_import)
            capture("04-backup-returned-source")
            assertSettingsUnchanged()
        }
    }

    @Test
    fun mcpAndPromptEditorsCancelTheirOwnedDrafts() {
        withFixture("mcp-prompt") { scenario ->
            openSettings()
            clickText(R.string.setting_page_agent_extensions)
            clickText(R.string.setting_page_mcp)
            waitText(R.string.setting_mcp_page_title)
            clickText(R.string.add)
            awaitDialogs(1)
            replaceField(text(R.string.setting_mcp_page_name_placeholder), "P5 local draft $taskId")
            capture("05-mcp-draft-ime")
            closeSoftKeyboard()
            assertTextFits(text(R.string.setting_mcp_page_save), inDialog = true)
            pressBack()
            awaitDialogs(0)
            waitText(R.string.setting_mcp_page_title)
            assertSettingsUnchanged()
            clickText(R.string.setting_mcp_page_import_confirm)
            awaitDialogs(1)
            val json = """{"mcpServers":{"p5-local-$taskId":{"type":"streamable_http","url":"https://example.invalid/p5-no-connect"}}}"""
            val importField = compose.onNode(hasSetTextAction() and topDialog(), useUnmergedTree = true)
            importField.performClick().performTextReplacement(json)
            closeSoftKeyboard()
            clickText(R.string.setting_mcp_page_import_preview, inDialog = true)
            assertTextFits(text(R.string.setting_mcp_page_import_desc), inDialog = true)
            assertActions(R.string.cancel, R.string.setting_mcp_page_import_preview)
            capture("06-mcp-local-preview-no-apply")
            clickText(R.string.cancel, inDialog = true)
            awaitDialogs(0)
            assertSettingsUnchanged()
            backTo(R.string.setting_agent_extensions_page_title)
            clickText(R.string.setting_page_extensions)
            clickText(R.string.extensions_page_prompts)
            waitText(R.string.prompt_page_title)
            clickText(R.string.prompt_page_add_mode_injection)
            awaitDialogs(1)
            replaceField(text(R.string.prompt_page_name), "P5 mode draft $taskId")
            replaceField(text(R.string.prompt_page_injection_content), "P5 local multiline draft.\nCancel must leave the actual settings collection unchanged.")
            capture("07-mode-draft-ime")
            closeSoftKeyboard()
            assertActions(R.string.prompt_page_cancel, R.string.prompt_page_confirm)
            clickText(R.string.prompt_page_cancel, inDialog = true)
            awaitDialogs(0)
            assertSettingsUnchanged()
            clickText(R.string.prompt_page_lorebook_tab)
            clickText(R.string.prompt_page_add_lorebook)
            awaitDialogs(1)
            replaceField(text(R.string.prompt_page_name), "P5 book draft $taskId")
            closeSoftKeyboard()
            val addEntry = compose.onNode(hasContentDescription(text(R.string.prompt_page_add_entry)) and topDialog(), useUnmergedTree = true)
            if (!addEntry.isDisplayed()) addEntry.performScrollTo()
            addEntry.assertIsDisplayed().performClick()
            awaitDialogs(2)
            replaceField(text(R.string.prompt_page_name), "P5 nested entry $taskId")
            replaceField(text(R.string.prompt_page_new_keyword), "p5-local-keyword")
            closeSoftKeyboard()
            val addKeyword = compose.onNode(hasContentDescription(text(R.string.prompt_page_add)) and topDialog(), useUnmergedTree = true)
            if (!addKeyword.isDisplayed()) addKeyword.performScrollTo()
            addKeyword.assertIsDisplayed().assertIsEnabled().performClick()
            replaceField(text(R.string.prompt_page_injection_content), "P5 local entry body; parent Cancel must discard this draft.")
            closeSoftKeyboard()
            assertActions(R.string.prompt_page_cancel, R.string.prompt_page_confirm)
            capture("08-lorebook-nested-entry")
            clickText(R.string.prompt_page_confirm, inDialog = true)
            awaitDialogs(1)
            compose.onNode(hasText(context.getString(R.string.prompt_page_entries_format, 1)) and topDialog(), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            assertSettingsUnchanged()
            clickText(R.string.prompt_page_cancel, inDialog = true)
            awaitDialogs(0)
            assertSettingsUnchanged()
            scenario.recreate()
            waitText(R.string.prompt_page_title)
            awaitDialogs(0)
            capture("09-prompt-recreated-cancelled")
            assertSettingsUnchanged()
        }
    }

    @Test
    fun sshAndWebMountEditorsCancelWithoutPublishingOrConnecting() {
        withFixture("ssh-webmount") { scenario ->
            val ssh: SshProfileStore = getKoin().get()
            val sites: UserSiteRegistry = getKoin().get()
            val sshBefore = runBlocking { ssh.snapshot() }
            val sitesBefore = sites.current.toList()
            openSettings()
            clickText(R.string.setting_page_agent_execution)
            clickText(R.string.setting_page_agent_sandbox)
            clickText(R.string.setting_sandbox_ssh_add)
            awaitDialogs(1)
            waitText(R.string.setting_sandbox_ssh_editor_new)
            replaceField(text(R.string.setting_sandbox_ssh_name), "P5 local SSH $taskId")
            replaceField(text(R.string.setting_sandbox_ssh_host), "example.invalid")
            replaceField(text(R.string.setting_sandbox_ssh_username), "p5-local-user")
            capture("10-ssh-draft-ime")
            closeSoftKeyboard()
            assertTextFits(text(R.string.setting_sandbox_ssh_cancel), inDialog = true)
            clickText(R.string.setting_sandbox_ssh_cancel, inDialog = true)
            awaitDialogs(0)
            assertEquals(sshBefore, runBlocking { ssh.snapshot() })
            backTo(R.string.setting_agent_execution_page_title)
            backTo(R.string.settings)
            clickText(R.string.setting_page_webmount)
            clickText(R.string.setting_webmount_add_site)
            awaitDialogs(1)
            waitText(R.string.setting_webmount_custom_dialog_title)
            replaceField(text(R.string.setting_webmount_custom_name_label), "P5 local site $taskId")
            replaceField(text(R.string.setting_webmount_custom_url_label), "https://example.invalid/p5-never-open")
            capture("11-webmount-local-draft-ime")
            closeSoftKeyboard()
            assertTextFits(text(R.string.setting_webmount_profile_import_dialog_cancel), inDialog = true)
            clickText(R.string.setting_webmount_profile_import_dialog_cancel, inDialog = true)
            awaitDialogs(0)
            scenario.recreate()
            revealText(R.string.setting_webmount_add_site)
            capture("12-webmount-recreated-cancelled")
            assertEquals(sshBefore, runBlocking { ssh.snapshot() })
            assertEquals(sitesBefore, sites.current)
            assertSettingsUnchanged()
            File(evidence, "owner-metadata.json").writeText(JSONObject().put("sshMetadataUnchanged", true)
                .put("siteRegistryUnchanged", true).put("connected", false).put("savedSecret", false).toString(2))
        }
    }

    private fun withFixture(name: String, prepareBackup: Boolean = false, block: (ActivityScenario<RouteActivity>) -> Unit) {
        assumeTrue("Explicit disposable settings fixture opt-in required", InstrumentationRegistry.getArguments().getString("amberSettingsFixture") == "true")
        check(context.applicationContext is AmberAgentApp)
        check("${Build.MODEL} ${Build.FINGERPRINT}".lowercase().let { it.contains("emulator") || it.contains("sdk_gphone") })
        settings = getKoin().get()
        val beforeLaunch = runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init } } }
        if (prepareBackup) check(!beforeLaunch.syncSettings.googleEnabled) { "Do not restore a user's Google authorization session" }
        var original = beforeLaunch
        taskId = UUID.randomUUID().toString()
        evidence = File(context.getExternalFilesDir(null), "modularization-phase-5-settings/$name-$taskId").apply { mkdirs() }
        val prefs = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val beforePreferences = prefs.all.toMap()
        val dao: ConversationDAO = getKoin().get()
        val beforeConversations = runBlocking { dao.getAll().first().associateBy { it.id } }
        val ownsDeviceId = prepareBackup && beforeLaunch.syncSettings.deviceId.isBlank()
        var failure: Throwable? = null
        try {
            if (ownsDeviceId) runBlocking { settings.update { current ->
                check(current.syncSettings.deviceId.isBlank())
                current.copy(syncSettings = current.syncSettings.copy(deviceId = taskId))
            } }
            prefs.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            ActivityScenario.launch<RouteActivity>(Intent(context, RouteActivity::class.java)).use { scenario ->
                try {
                    compose.waitUntil(15_000) {
                        compose.onAllNodes(hasContentDescription(text(R.string.settings))).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithContentDescription(text(R.string.settings)).assertIsDisplayed()
                    compose.waitForIdle()
                    // The editor contract starts at a visible Home, after app startup work.
                    baseline = runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init } } }
                    original = if (ownsDeviceId) baseline.copy(syncSettings = baseline.syncSettings.copy(
                        deviceId = beforeLaunch.syncSettings.deviceId)) else baseline
                    block(scenario)
                } catch (error: Throwable) {
                    runCatching { rawScreenshot("failure") }.onFailure(error::addSuppressed)
                    throw error
                }
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try {
                if (ownsDeviceId) runBlocking { settings.update { current ->
                    if (current.syncSettings.deviceId == taskId) current.copy(syncSettings = current.syncSettings.copy(deviceId = original.syncSettings.deviceId)) else current
                } }
                prefs.edit().apply {
                    listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).forEach { key ->
                        if (beforePreferences.containsKey(key)) putString(key, beforePreferences[key] as String?) else remove(key)
                    }
                }.commit()
                val restored = awaitCanonicalSettings(original, "cleanup-settings-diff")
                val preferencesRestored = listOf(LAUNCH_START_MODE_PREF, LAST_CONVERSATION_ID_PREF).all {
                    prefs.all[it] == beforePreferences[it] && prefs.contains(it) == beforePreferences.containsKey(it)
                }
                val rowsRestored = beforeConversations == runBlocking { dao.getAll().first().associateBy { it.id } }
                File(evidence, "cleanup.json").writeText(JSONObject().put("settingsCanonicalUnchanged", JsonInstant.encodeToJsonElement(restored) == JsonInstant.encodeToJsonElement(original))
                    .put("launchPreferencesRestored", preferencesRestored).put("conversationRowsUnchanged", rowsRestored)
                    .put("onlyOwnedDeviceIdRestored", ownsDeviceId).put("restoreApplied", false).toString(2))
                assertTrue(preferencesRestored)
                assertTrue(rowsRestored)
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun assertSettingsUnchanged() {
        awaitCanonicalSettings(baseline, "business-settings-diff")
    }
    private fun awaitCanonicalSettings(expected: Settings, evidenceName: String): Settings {
        val expectedJson = JsonInstant.encodeToJsonElement(expected)
        try {
            return runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init && JsonInstant.encodeToJsonElement(it) == expectedJson } } }
        } catch (error: Throwable) {
            val actual = settings.settingsFlow.value
            // Diagnose only field paths; Settings may contain existing account secrets.
            val changed = mutableListOf<String>()
            fun compare(before: JsonElement?, after: JsonElement?, path: String) {
                if (before == after) return
                when {
                    before is JsonObject && after is JsonObject -> (before.keys + after.keys).forEach { key ->
                        compare(before[key], after[key], if (path.isEmpty()) key else "$path.$key")
                    }
                    before is JsonArray && after is JsonArray && before.size == after.size -> before.indices.forEach { index ->
                        compare(before[index], after[index], "$path[$index]")
                    }
                    else -> changed += path
                }
            }
            compare(expectedJson, JsonInstant.encodeToJsonElement(actual), "")
            File(evidence, "$evidenceName.json").writeText(JSONObject().put("changedFieldPaths", JSONArray(changed))
                .put("actualInit", actual.init).put("expectedInit", expected.init).toString(2))
            throw error
        }
    }
    private fun openSettings() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(text(R.string.settings))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(text(R.string.settings)).assertIsDisplayed().performClick()
        waitText(R.string.settings)
    }
    private fun waitText(id: Int) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text(id), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitDialogs(count: Int) = compose.waitUntil(15_000) {
        compose.onAllNodes(isDialog(), useUnmergedTree = true).fetchSemanticsNodes().size == count
    }
    private fun topDialog(): SemanticsMatcher {
        val id = compose.onAllNodes(isDialog(), useUnmergedTree = true).fetchSemanticsNodes().last().id
        return hasAnyAncestor(SemanticsMatcher("Current top Dialog") { it.id == id })
    }
    private fun revealText(id: Int) {
        val label = text(id)
        if (compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onFirst().performScrollToNode(hasText(label))
        }
        val node = compose.onAllNodesWithText(label, useUnmergedTree = true).onFirst()
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed()
    }
    private fun clickText(id: Int, inDialog: Boolean = false) {
        if (!inDialog) revealText(id)
        val matcher = hasClickAction() and hasText(text(id))
        val node = compose.onNode(if (inDialog) matcher and topDialog() else matcher)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun replaceField(label: String, value: String) {
        val field = compose.onNode(hasSetTextAction() and hasText(label) and topDialog())
        if (!field.isDisplayed()) field.performScrollTo()
        val fieldId = field.fetchSemanticsNode().id
        field.assertIsDisplayed().performClick().performTextReplacement(value)
        // A placeholder selector no longer matches after typing; retain the actual field identity.
        compose.onNode(SemanticsMatcher("Previously matched editable field") { it.id == fieldId } and hasSetTextAction() and topDialog())
            .assert(SemanticsMatcher("Editable plain string retained") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == value
        })
    }
    private fun backTo(title: Int) { pressBack(); waitText(title) }
    private fun awaitDocumentsPicker() = compose.waitUntil(15_000) {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        try { root?.packageName?.toString()?.contains("documentsui") == true }
        finally { root?.recycle() }
    }
    private fun systemBack(): Boolean {
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = android.view.KeyEvent(downTime, downTime, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK, 0)
        val up = android.view.KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK, 0)
        return instrumentation.uiAutomation.injectInputEvent(down, true) && instrumentation.uiAutomation.injectInputEvent(up, true)
    }
    private fun text(id: Int) = context.getString(id)

    private fun assertActions(first: Int, second: Int) {
        val a = assertTextFits(text(first), inDialog = true)
        val b = assertTextFits(text(second), inDialog = true)
        assertFalse("Actual action touch targets must not overlap", a.overlaps(b))
    }
    private fun assertTextFits(label: String, inDialog: Boolean = false): Rect {
        val matcher = if (inDialog) hasText(label) and topDialog() else hasText(label)
        val node = compose.onNode(matcher, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        instrumentation.runOnMainSync { check(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true) }
        val layout = layouts.single()
        val root = generateSequence(node) { it.parent }.last()
        val window = Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat())
        val action = generateSequence(node) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) } ?: node
        val glyphs = label.indices.map { index -> layout.getBoundingBox(index) }
        File(evidence, "layout-${label.hashCode()}-${System.nanoTime()}.json").writeText(JSONObject().put("label", label)
            .put("nodeBoundsInWindow", node.boundsInWindow.toString()).put("actionBoundsInWindow", action.boundsInWindow.toString())
            .put("actionTouchBoundsInRoot", action.touchBoundsInRoot.toString())
            .put("nodeSize", node.size.toString()).put("layoutSize", layout.size.toString()).put("owningWindow", window.toString())
            .put("paragraphWidth", layout.multiParagraph.width)
            .put("glyphBoxes", JSONArray(glyphs.map { it.toString() })).toString(2))
        assertFalse("Complete label must not truncate: $label", layout.multiParagraph.didExceedMaxLines)
        repeat(layout.lineCount) { assertFalse("Complete label must not ellipsize: $label", layout.isLineEllipsized(it)) }
        glyphs.forEach { box -> assertTrue("Label glyph must fit actual Text: $label $box", box.left >= -1f && box.top >= -1f &&
            box.right <= node.size.width + 1f && box.bottom <= node.size.height + 1f) }
        val bounds = action.boundsInWindow
        assertTrue("Visible action must fit its actual window", bounds.left >= window.left && bounds.top >= window.top && bounds.right <= window.right && bounds.bottom <= window.bottom)
        return action.touchBoundsInRoot
    }
    private fun rawScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(evidence, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val presented = CountDownLatch(1)
        var removeListener: (() -> Unit)? = null
        instrumentation.runOnMainSync {
            val root = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.isShown }
            val observed = AtomicBoolean()
            val listener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (!observed.compareAndSet(false, true)) return
                    root.post { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(this) }
                    root.postOnAnimation { presented.countDown() }
                }
            }
            root.viewTreeObserver.addOnDrawListener(listener)
            removeListener = { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(listener) }
            root.invalidate()
        }
        try { assertTrue("Actual top window must draw before screenshot", presented.await(10, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { removeListener?.invoke() } }
        rawScreenshot(name)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(evidence, "$name.semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        File(evidence, "$name.config.json").writeText(JSONObject().put("widthDp", context.resources.configuration.screenWidthDp)
            .put("fontScale", context.resources.configuration.fontScale).put("densityDpi", context.resources.displayMetrics.densityDpi)
            .put("taskId", taskId).put("actualRouteActivity", true).put("defaultKoinOwners", true)
            .put("networkActionTriggered", false).put("backupVerifyPassed", false).put("restoreApplied", false).toString(2))
    }
}
