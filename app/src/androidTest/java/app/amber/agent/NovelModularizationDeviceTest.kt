package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.novel.persistence.NovelProjectPersisting
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** Real RouteActivity -> Koin VM -> workspace author edits; no generation or substitute VM. */
@RunWith(AndroidJUnit4::class)
class NovelModularizationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var projectTitle: String
    private lateinit var routeActivity: RouteActivity

    @Test
    fun realNovelRouteReadsEditsForksAndPersistsMaterialWithoutCrossBranchWrites() {
        assumeTrue("Explicit task fixture opt-in required",
            InstrumentationRegistry.getArguments().getString("amberNovelFixture") == "true")
        check(context.applicationContext is AmberAgentApp) { "Use the existing real UI smoke runner" }
        check("${Build.MODEL} ${Build.FINGERPRINT}".lowercase().let { it.contains("sdk_gphone") || it.contains("emulator") })
        val repository: NovelWorkspaceProjectRepository = getKoin().get()
        val legacy: NovelProjectPersisting = getKoin().get()
        // The real projects-list VM runs migrateAll on entry. Do not migrate someone else's book.
        check(runBlocking { legacy.listProjects().isEmpty() }) { "Legacy books exist: the real list would migrate non-fixture data" }
        val originalProjectIds = repository.listProjects().map { it.id }.toSet()
        val settings: SettingsAggregator = getKoin().get()
        val originalSettings = runBlocking { withTimeout(10_000) { settings.settingsFlow.first { !it.init } } }
        val originalPersistentSettings = JsonInstant.encodeToJsonElement(originalSettings)
        val prefs = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val originalLaunch = prefs.getString(LAUNCH_START_MODE_PREF, null)
        val projectId = UUID.randomUUID().toString().uppercase()
        projectTitle = "P3 local novel ${projectId.take(8)}"
        val directory = repository.projectDirectory(projectId)
        check(!directory.exists())
        val store = NovelWorkspaceStore(directory)
        var primaryFailure: Throwable? = null
        try {
            repository.install(projectId, fixtureFiles(projectId))
            prefs.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            ActivityScenario.launch<RouteActivity>(Intent(context, RouteActivity::class.java)).use { scenario ->
                scenario.onActivity { routeActivity = it }
                try {
                    val novelEntry = compose.onNodeWithContentDescription(text(R.string.session_home_feature_novel))
                    compose.waitUntil(20_000) { compose.onAllNodes(
                        androidx.compose.ui.test.hasContentDescription(text(R.string.session_home_feature_novel)),
                    ).fetchSemanticsNodes().isNotEmpty() }
                    if (!novelEntry.isDisplayed()) novelEntry.performScrollTo()
                    novelEntry.assertIsDisplayed()
                    novelEntry.performClick()
                    waitText(projectTitle)
                    clickText(projectTitle)
                    waitText(text(R.string.novel_tab_manuscript))
                    clickText(text(R.string.novel_tab_manuscript))
                    waitText(FIRST_TITLE)
                    capture("01-manuscript-directory", projectId, store)

                    clickText(FIRST_TITLE)
                    waitText(FIRST_BODY)
                    assertActionsSeparate(text(R.string.novel_previous_chapter), text(R.string.novel_next_chapter))
                    capture("02-reader-first-chapter", projectId, store)
                    clickText(text(R.string.novel_next_chapter))
                    waitText(SECOND_BODY)
                    clickText(text(R.string.novel_previous_chapter))
                    waitText(FIRST_BODY)
                    val initialHead = NovelWorkspaceLedger.load(directory).head
                    editChapter(FIRST_BODY, MAIN_EDITED_BODY, "03-main-chapter-editor", projectId, store)
                    waitUntil("Main chapter body must reach real persistent storage") {
                        body(store, mainChapterPath()) == MAIN_EDITED_BODY
                    }
                    assertNotEquals(initialHead, NovelWorkspaceLedger.load(directory).head)
                    waitForReader()
                    waitText(MAIN_EDITED_BODY)
                    capture("04-main-reader-saved", projectId, store)
                    compose.onNodeWithContentDescription(text(R.string.novel_return_to_directory)).performClick()
                    waitText(FIRST_TITLE)

                    openControls()
                    waitText(text(R.string.novel_project_controls))
                    assertActionsSeparate(text(R.string.chat_page_close), text(R.string.novel_start))
                    capture("05-project-controls", projectId, store)
                    clickText(text(R.string.novel_branches_title))
                    waitText(text(R.string.novel_new_branch))
                    capture("06-branch-list", projectId, store)
                    clickText(text(R.string.novel_new_branch))
                    compose.onNode(hasSetTextAction()).performTextReplacement(BRANCH_NAME)
                    assertActionsSeparate(text(R.string.cancel), text(R.string.novel_create), inDialog = true)
                    capture("07-new-branch-dialog", projectId, store)
                    closeSoftKeyboard()
                    // The workspace also has a "Create" tab behind this dialog.
                    compose.onNode(hasClickAction() and hasText(text(R.string.novel_create)) and
                        hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
                    compose.waitForIdle()
                    val alternate = NovelWorkspaceSlug.slug(BRANCH_NAME)
                    waitUntil("Actual branch command must persist its fork") {
                        NovelWorkspaceBranches.list(directory, MAIN_SLUG).any { it.slug == alternate }
                    }
                    waitText(alternate)
                    clickText(alternate)
                    waitUntil("Actual branch selection must persist") { NovelWorkspaceBranches.activeSlug(directory) == alternate }
                    waitText(FIRST_TITLE)
                    clickText(FIRST_TITLE)
                    waitText(MAIN_EDITED_BODY)
                    editChapter(MAIN_EDITED_BODY, BRANCH_EDITED_BODY, "08-fork-chapter-editor", projectId, store)
                    val alternatePath = "branches/$alternate/chapters/001-harbor.md"
                    waitUntil("Fork edit must reach its own persistent chapter") { body(store, alternatePath) == BRANCH_EDITED_BODY }
                    assertEquals("Fork edit must preserve main chapter", MAIN_EDITED_BODY, body(store, mainChapterPath()))
                    waitForReader()
                    waitText(BRANCH_EDITED_BODY)
                    capture("09-fork-reader-saved", projectId, store)
                    compose.onNodeWithContentDescription(text(R.string.novel_return_to_directory)).performClick()
                    waitText(FIRST_TITLE)
                    openControls()
                    clickText(text(R.string.novel_branches_title))
                    waitText(MAIN_BRANCH_TITLE)
                    clickText(MAIN_BRANCH_TITLE)
                    waitUntil("Main branch must become active again") { NovelWorkspaceBranches.activeSlug(directory) == MAIN_SLUG }
                    waitText(FIRST_TITLE)
                    clickText(FIRST_TITLE)
                    waitText(MAIN_EDITED_BODY)
                    capture("10-main-return-isolated", projectId, store)
                    compose.onNodeWithContentDescription(text(R.string.novel_return_to_directory)).performClick()

                    clickText(text(R.string.novel_tab_settings))
                    waitText(MATERIAL_TITLE)
                    clickText(MATERIAL_TITLE)
                    compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
                    compose.onNodeWithTag("novel-material-body").assertIsDisplayed().assert(
                        editableTextEquals(MATERIAL_ORIGINAL_BODY),
                    ).performClick()
                    compose.waitUntil(20_000) {
                        compose.onAllNodes(hasTestTag("novel-material-body") and hasSetTextAction() and isFocused(),
                            useUnmergedTree = true).fetchSemanticsNodes().size == 1
                    }
                    compose.onNodeWithTag("novel-material-body").performTextReplacement(MATERIAL_EDITED_BODY)
                    compose.onNodeWithTag("novel-material-body").assert(
                        editableTextEquals(MATERIAL_EDITED_BODY),
                    )
                    assertActionsSeparate(text(R.string.delete), text(R.string.chat_page_save))
                    capture("11-material-editor", projectId, store)
                    closeSoftKeyboard()
                    clickText(text(R.string.chat_page_save))
                    waitUntil("Material save must reach the real file") { body(store, MATERIAL_PATH) == MATERIAL_EDITED_BODY }
                    compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                    clickText(MATERIAL_TITLE)
                    compose.onNodeWithTag("novel-material-body").assertIsDisplayed()
                        .assert(editableTextEquals(MATERIAL_EDITED_BODY))
                    capture("12-material-reopened", projectId, store)
                    clickText(text(R.string.cancel))
                    assertEquals(MAIN_EDITED_BODY, body(store, mainChapterPath()))
                    assertEquals(BRANCH_EDITED_BODY, body(store, alternatePath))
                    write("$projectId-persistence.json", JSONObject().apply {
                        put("projectId", projectId)
                        put("mainBranch", NovelWorkspaceBranches.activeSlug(directory))
                        put("mainChapterSaved", body(store, mainChapterPath()) == MAIN_EDITED_BODY)
                        put("forkChapterSaved", body(store, alternatePath) == BRANCH_EDITED_BODY)
                        put("materialSaved", body(store, MATERIAL_PATH) == MATERIAL_EDITED_BODY)
                        put("commitCount", NovelWorkspaceLedger.load(directory).commits.size)
                    }.toString(2))
                } catch (failure: Throwable) {
                    runCatching { capture("failure", projectId, store) }.onFailure(failure::addSuppressed)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                try {
                    if (directory.exists()) repository.delete(projectId)
                    assertFalse("Only the task project must be removed", directory.exists())
                    assertEquals("Existing workspace project IDs must stay intact", originalProjectIds, repository.listProjects().map { it.id }.toSet())
                } finally {
                    runBlocking { settings.update(originalSettings) }
                    prefs.edit().apply {
                        if (originalLaunch == null) remove(LAUNCH_START_MODE_PREF) else putString(LAUNCH_START_MODE_PREF, originalLaunch)
                    }.apply()
                    runBlocking { withTimeout(10_000) { settings.settingsFlow.first {
                        !it.init && JsonInstant.encodeToJsonElement(it) == originalPersistentSettings
                    } } }
                    assertEquals(originalLaunch, prefs.getString(LAUNCH_START_MODE_PREF, null))
                    write("$projectId-cleanup.json", JSONObject().apply {
                        put("taskProjectRemoved", !directory.exists())
                        put("existingProjectIdsPreserved", repository.listProjects().map { it.id }.toSet() == originalProjectIds)
                        put("allPersistentSettingsRestored", true)
                        put("launchPreferenceRestored", true)
                    }.toString(2))
                }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun editChapter(original: String, edited: String, screenshot: String, projectId: String, store: NovelWorkspaceStore) {
        compose.onNodeWithContentDescription(text(R.string.novel_more_actions)).performClick()
        clickText(text(R.string.edit))
        waitText(text(R.string.novel_edit_chapter))
        val originalText = editableTextEquals(original)
        val originalBody = hasSetTextAction() and originalText
        compose.waitUntil(20_000) {
            compose.onAllNodes(originalBody, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        compose.onNode(originalBody, useUnmergedTree = true).assertIsDisplayed().performClick()
        // Text replacement requests focus before it re-queries SetText. Let the
        // real focus/IME change settle, then select by focus rather than old text.
        val focusedBody = hasSetTextAction() and isFocused()
        compose.waitUntil(20_000) {
            compose.onAllNodes(focusedBody and originalText, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        compose.onNode(focusedBody, useUnmergedTree = true).assertIsDisplayed().assert(originalText)
            .performTextReplacement(edited)
        compose.onNode(focusedBody, useUnmergedTree = true).assert(
            editableTextEquals(edited),
        )
        assertActionsSeparate(text(R.string.cancel), text(R.string.chat_page_save))
        capture(screenshot, projectId, store)
        closeSoftKeyboard()
        clickText(text(R.string.chat_page_save))
    }

    private fun openControls() {
        clickText(projectTitle)
        waitText(text(R.string.novel_branches_title))
    }

    private fun clickText(value: String) {
        val target = compose.onAllNodes(hasClickAction() and hasText(value)).onFirst()
        if (!target.isDisplayed()) target.performScrollTo()
        target.assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun waitText(value: String) = compose.waitUntil(20_000) {
        val nodes = compose.onAllNodesWithText(value, useUnmergedTree = true)
        nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 20_000, condition = condition) } catch (failure: Throwable) { throw AssertionError(message, failure) }
    }

    // IME composition decorates EditableText with spans; the manuscript contract is its exact text.
    private fun editableTextEquals(expected: String) = SemanticsMatcher("EditableText.text equals '$expected'") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
    }

    private fun waitForReader() = compose.waitUntil(20_000) {
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }

    private fun assertActionsSeparate(first: String, second: String, inDialog: Boolean = false) {
        val activityWindow = activityWindowBounds()
        val bounds = listOf(first, second).map { label ->
            val labelMatcher = if (inDialog) hasText(label) and hasAnyAncestor(isDialog()) else hasText(label)
            val textNode = compose.onAllNodes(labelMatcher, useUnmergedTree = true).onFirst().assertIsDisplayed().fetchSemanticsNode()
            // AlertDialog has its own window origin and dimensions, unlike the full-screen sheet.
            val window = if (inDialog) {
                val rootSize = generateSequence(textNode) { it.parent }.last().size
                android.graphics.Rect(0, 0, rootSize.width, rootSize.height)
            } else activityWindow
            val layouts = mutableListOf<TextLayoutResult>()
            instrumentation.runOnMainSync { check(textNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true) }
            val layout = layouts.single()
            val button = generateSequence(textNode) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
            val textBounds = textNode.boundsInWindow
            val buttonBounds = button.boundsInWindow
            // Simple BasicText draws an intrinsic-width Paragraph, while its semantics
            // reconstructs MultiParagraph at the original maxWidth. Centered line and
            // character coordinates therefore need that width's alignment offset removed.
            val paragraphToNodeX = if (layout.layoutInput.style.textAlign == TextAlign.Center) {
                (textNode.size.width - layout.multiParagraph.width) / 2f
            } else 0f
            val glyphs = label.indices.map { index ->
                val raw = layout.getBoundingBox(index)
                index to Rect(raw.left + paragraphToNodeX, raw.top, raw.right + paragraphToNodeX, raw.bottom)
            }
            write("action-layout-${label.hashCode()}-${SystemClock.uptimeMillis()}.json", JSONObject().apply {
                put("label", label)
                put("projectTitle", projectTitle)
                put("layoutSize", layout.size.toString())
                put("nodeSize", textNode.size.toString())
                put("textBoundsInWindow", textNode.boundsInWindow.toString())
                put("buttonBoundsInWindow", button.boundsInWindow.toString())
                put("activityWindowBounds", activityWindow.toShortString())
                put("owningWindowBounds", window.toShortString())
                put("paragraphWidth", layout.multiParagraph.width)
                put("paragraphToNodeOffsetX", paragraphToNodeX)
                put("textAlign", layout.layoutInput.style.textAlign.toString())
                put("paragraphHeight", layout.multiParagraph.height)
                put("lineCount", layout.lineCount)
                put("didOverflowWidth", layout.didOverflowWidth)
                put("didOverflowHeight", layout.didOverflowHeight)
                put("hasVisualOverflow", layout.hasVisualOverflow)
                put("didExceedMaxLines", layout.multiParagraph.didExceedMaxLines)
                put("fontSize", layout.layoutInput.style.fontSize.toString())
                put("lineHeight", layout.layoutInput.style.lineHeight.toString())
                put("fontScale", layout.layoutInput.density.fontScale)
                put("constraints", layout.layoutInput.constraints.toString())
                put("maxLines", layout.layoutInput.maxLines)
                put("overflow", layout.layoutInput.overflow.toString())
                put("lines", JSONArray().apply {
                    repeat(layout.lineCount) { line -> put(JSONObject().apply {
                        put("index", line)
                        put("left", layout.getLineLeft(line))
                        put("right", layout.getLineRight(line))
                        put("top", layout.getLineTop(line))
                        put("bottom", layout.getLineBottom(line))
                        put("baseline", layout.getLineBaseline(line))
                        put("ellipsized", layout.isLineEllipsized(line))
                        put("nodeLeft", layout.getLineLeft(line) + paragraphToNodeX)
                        put("nodeRight", layout.getLineRight(line) + paragraphToNodeX)
                        put("leftFits", layout.getLineLeft(line) + paragraphToNodeX >= -1f)
                        put("rightFits", layout.getLineRight(line) + paragraphToNodeX <= textNode.size.width + 1f)
                        put("bottomFits", layout.getLineBottom(line) <= textNode.size.height + 1f)
                    }) }
                })
                put("glyphs", JSONArray().apply {
                    glyphs.forEach { (index, box) -> put(JSONObject().apply {
                        put("index", index)
                        put("character", label[index].toString())
                        put("rawBounds", layout.getBoundingBox(index).toString())
                        put("boundsInTextNode", box.toString())
                        put("boundsInWindow", Rect(
                            textBounds.left + box.left, textBounds.top + box.top,
                            textBounds.left + box.right, textBounds.top + box.bottom,
                        ).toString())
                    }) }
                })
            }.toString(2))
            assertEquals("Action must contain its complete label", label, layout.layoutInput.text.text)
            assertFalse("Action must not truncate: $label", layout.multiParagraph.didExceedMaxLines)
            repeat(layout.lineCount) { line ->
                assertFalse("Action must not ellipsize: $label", layout.isLineEllipsized(line))
                assertTrue("Action glyph must fit: $label", layout.getLineLeft(line) + paragraphToNodeX >= -1f &&
                    layout.getLineRight(line) + paragraphToNodeX <= textNode.size.width + 1f &&
                    layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= textNode.size.height + 1f)
            }
            glyphs.forEach { (index, box) ->
                assertTrue("Action character $index must fit its text node: $label $box", box.left >= -1f &&
                    box.top >= -1f && box.right <= textNode.size.width + 1f && box.bottom <= textNode.size.height + 1f)
                val inWindow = Rect(textBounds.left + box.left, textBounds.top + box.top,
                    textBounds.left + box.right, textBounds.top + box.bottom)
                assertTrue("Action character $index must fit its button: $label $inWindow", inWindow.left >= buttonBounds.left - 1f &&
                    inWindow.top >= buttonBounds.top - 1f && inWindow.right <= buttonBounds.right + 1f &&
                    inWindow.bottom <= buttonBounds.bottom + 1f)
                assertTrue("Action character $index must fit its owning window: $label $inWindow", inWindow.left >= window.left - 1f &&
                    inWindow.top >= window.top - 1f && inWindow.right <= window.right + 1f && inWindow.bottom <= window.bottom + 1f)
            }
            buttonBounds.also { box ->
                assertTrue("Action must be wholly inside its owning window: $label $box window=$window",
                    box.left >= window.left && box.top >= window.top && box.right <= window.right && box.bottom <= window.bottom)
            }
        }
        assertFalse("Action targets must not overlap: $first / $second", bounds[0].overlaps(bounds[1]))
    }

    private fun capture(name: String, projectId: String, store: NovelWorkspaceStore) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val config = context.resources.configuration
        val suffix = "${config.screenWidthDp}dp-font${(config.fontScale * 100).toInt()}"
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(evidenceDirectory(), "$name-$suffix.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        write("$name-$suffix.semantics.txt", roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        if (name == "failure") {
            val fields = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
                useUnmergedTree = true).fetchSemanticsNodes()
            write("$name-$suffix.editable.json", JSONArray().apply {
                fields.forEach { node ->
                    val editable = node.config[SemanticsProperties.EditableText]
                    val input = node.config.getOrNull(SemanticsProperties.InputText)
                    put(JSONObject().apply {
                        put("text", editable.text)
                        put("equalsPlainAnnotatedString", editable == AnnotatedString(editable.text))
                        put("spanStyles", JSONArray(editable.spanStyles.map { it.toString() }))
                        put("paragraphStyles", JSONArray(editable.paragraphStyles.map { it.toString() }))
                        put("stringAnnotations", JSONArray(editable.getStringAnnotations(0, editable.length).map { it.toString() }))
                        put("inputText", input?.text)
                        put("inputSpanStyles", JSONArray(input?.spanStyles.orEmpty().map { it.toString() }))
                        put("focused", node.config.getOrNull(SemanticsProperties.Focused))
                        put("selection", node.config.getOrNull(SemanticsProperties.TextSelectionRange)?.toString())
                    })
                }
            }.toString(2))
        }
        write("$name-$suffix.owner.json", JSONObject().apply {
            put("projectId", projectId)
            put("widthDp", config.screenWidthDp)
            put("densityDpi", context.resources.displayMetrics.densityDpi)
            put("fontScale", config.fontScale)
            put("activityWindowBoundsPx", activityWindowBounds().toShortString())
            put("activeBranch", NovelWorkspaceBranches.activeSlug(store.rootDirectory))
            put("ledgerHead", NovelWorkspaceLedger.load(store.rootDirectory).head)
            put("treePaths", store.list().joinToString())
        }.toString(2))
    }

    private fun activityWindowBounds(): android.graphics.Rect {
        val result = android.graphics.Rect()
        instrumentation.runOnMainSync {
            val metrics = routeActivity.windowManager.currentWindowMetrics.bounds
            // boundsInWindow uses a local origin; WindowMetrics includes the system-bar area.
            result.set(0, 0, metrics.width(), metrics.height())
        }
        return result
    }

    private fun fixtureFiles(projectId: String): List<NovelWorkspaceFile> {
        fun markdown(path: String, kind: String, title: String, body: String, extra: List<Pair<String, String>> = emptyList()) =
            NovelWorkspaceFile(path, NovelWorkspaceMarkdown.render(
                fields = listOf("id" to UUID.randomUUID().toString().uppercase(), "kind" to kind, "title" to title) + extra,
                body = body,
            ))
        return listOf(
            NovelWorkspaceFile("manifest.yaml", NovelWorkspaceManifestRenderer.render(
                exportedAt = Instant.now(), sourceProjectID = projectId, sourceProjectRevision = 1,
                sourceSchemaVersion = 1, mainBranch = MAIN_SLUG,
            )),
            NovelWorkspaceFile("project.md", NovelWorkspaceMarkdown.render(
                fields = listOf("id" to projectId, "kind" to "project", "title" to projectTitle, "collaborationMode" to "cocreation"), body = "",
            )),
            markdown("branches/$MAIN_SLUG/branch.md", "branch", MAIN_BRANCH_TITLE, "", listOf("syncStatus" to "synchronized")),
            markdown(mainChapterPath(), "chapter", FIRST_TITLE, FIRST_BODY, listOf("ordinal" to "1")),
            markdown("branches/$MAIN_SLUG/chapters/002-lantern.md", "chapter", "Second chapter: Lantern", SECOND_BODY, listOf("ordinal" to "2")),
            markdown("branches/$MAIN_SLUG/plot/current.md", "plot", "Current plot", "Two travelers meet at the harbor."),
            markdown(MATERIAL_PATH, "character", MATERIAL_TITLE, MATERIAL_ORIGINAL_BODY),
        )
    }

    private fun body(store: NovelWorkspaceStore, path: String) = store.read(path)?.let { NovelWorkspaceMarkdown.parseFile(it).body }
    private fun mainChapterPath() = "branches/$MAIN_SLUG/chapters/001-harbor.md"
    private fun text(id: Int) = context.getString(id)
    private fun evidenceDirectory() = checkNotNull(context.getExternalFilesDir("modularization-phase-3-novel")).also { check(it.exists() || it.mkdirs()) }
    private fun write(name: String, value: String) = File(evidenceDirectory(), name).writeText(value)

    companion object {
        private const val MAIN_SLUG = "main"
        private const val MAIN_BRANCH_TITLE = "Main line"
        private const val FIRST_TITLE = "First chapter: Harbor"
        private const val FIRST_BODY = "P3 original manuscript. The cartographer waits by the harbor gate."
        private const val SECOND_BODY = "P3 second manuscript. A lantern lights the returning ship."
        private const val MAIN_EDITED_BODY = "P3 main edit. The cartographer opens the harbor gate."
        private const val BRANCH_NAME = "Alternate path"
        private const val BRANCH_EDITED_BODY = "P3 fork edit. The cartographer leaves the harbor gate closed."
        private const val MATERIAL_PATH = "setting/characters/cartographer.md"
        private const val MATERIAL_TITLE = "The harbor cartographer"
        private const val MATERIAL_ORIGINAL_BODY = "A cartographer keeps the harbor ledger."
        private const val MATERIAL_EDITED_BODY = "P3 material edit. The cartographer preserves both possible paths."
    }
}
