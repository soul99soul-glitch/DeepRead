package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.amber.agent.R
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h720dp-xxhdpi")
class NovelWorkspacePaneInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composerRetainsRejectedDraftClearsAcceptedDraftAndRoutesStop() {
        var draft by mutableStateOf("")
        var busy by mutableStateOf(false)
        var acceptsSend = false
        var stopCalls = 0
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    ChatUnderTest(
                        state = chatState(busy), draft = draft, onDraftChange = { draft = it },
                        onSend = { sent += it; acceptsSend }, onStop = { stopCalls++ },
                    )
                }
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("Keep this draft")
        compose.onNodeWithContentDescription(label(R.string.send)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Keep this draft")
        assertEquals(listOf("Keep this draft"), sent)

        acceptsSend = true
        compose.onNodeWithContentDescription(label(R.string.send)).performClick()
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNode(hasSetTextAction()).performTextInput("Stop keeps this")
        compose.runOnIdle { busy = true }
        compose.onNodeWithContentDescription(label(R.string.stop)).performClick()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Stop keeps this")),
        ).assertIsDisplayed().assertIsNotEnabled()
        assertEquals(1, stopCalls)
        assertEquals(listOf("Keep this draft", "Keep this draft"), sent)
    }

    @Test
    fun manuscriptOpensChapterAndHistoryWhileOwnedBatchBlocksRewriteAndDiscard() {
        val chapter = NovelMarkdownChapterUi("chapters/001-opening.md", "Opening chapter", 1, 48)
        var openedPath: String? = null
        var historyChapter: NovelMarkdownChapterUi? = null
        compose.setContent {
            MaterialTheme {
                MarkdownWorkspaceManuscript(
                    state = NovelWorkspaceManuscriptState(
                        branchSlug = "actual-branch", chapters = listOf(chapter),
                        ghostwriteJob = NovelMarkdownGhostwriteUi(
                            jobId = "owned-job", executionId = "owned-execution", branchSlug = "actual-branch",
                            target = 2, written = 1, startOrdinal = 1, status = "paused",
                        ),
                        busy = false, errorMessage = null, canUndo = true, consistencyChecking = false,
                    ),
                    onOpenPolish = {}, onOpenChapter = { openedPath = it },
                    onHistory = { historyChapter = it }, onDiscard = { error("owned batch must block discard") },
                    listState = rememberLazyListState(), onManageProjects = {},
                    loadDiscardedChapters = { null }, readFileBody = { null },
                    onRestoreDiscardedChapter = { _, _, _, _ -> },
                    onRewriteChapter = { error("owned batch must block rewrite") },
                    onUndoLast = {}, onRunConsistencyCheck = {}, onClearError = {},
                )
            }
        }

        compose.onNodeWithText(chapter.title).performClick()
        assertEquals(chapter.path, openedPath)
        compose.onAllNodesWithContentDescription(label(R.string.novel_more_actions)).onLast().performClick()
        compose.onNodeWithText(label(R.string.novel_rewrite_chapter)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.novel_discard_chapter)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.novel_chapter_history)).assertIsEnabled().performClick()
        assertEquals(chapter, historyChapter)
    }

    @Test
    fun catalogMaterialSaveForwardsOriginalSnapshotAndClosesOnlyOnOwnerSuccess() {
        val path = "setting/characters/lead.md"
        val title = "Lead character"
        val raw = "---\nid: lead\ntitle: $title\naliases:\n  - preserved-alias\n---\n\nOriginal character body"
        var saved: List<String>? = null
        var completeSave: (() -> Unit)? = null
        compose.setContent {
            MaterialTheme {
                MarkdownWorkspaceCatalog(
                    state = catalogState(
                        catalog = NovelWorkspaceCatalog.NovelWorkspaceCatalogData(
                            settingGroups = listOf(NovelWorkspaceCatalog.NovelWorkspaceSettingGroup(
                                "characters", listOf(NovelWorkspaceCatalog.NovelWorkspaceSettingEntry(path, title, null)),
                            )), foreshadowing = emptyList(), decisions = emptyList(),
                        ),
                    ),
                    category = CatalogCategory.Characters, onSelectCategory = {}, listState = rememberLazyListState(),
                    readFileRaw = { requested -> assertEquals(path, requested); raw },
                    onCreateMaterial = { _, _, _, _ -> },
                    onSaveMaterial = { savedPath, savedTitle, body, expectedRaw, done ->
                        saved = listOf(savedPath, savedTitle, body, expectedRaw)
                        completeSave = done
                    },
                    onSaveOpenedFileEdit = { _, _, _, _ -> error("material rename/save uses the material command") },
                    onDeleteMaterial = { _, _, _ -> }, onClearError = {},
                )
            }
        }

        compose.onNodeWithText(title).performClick()
        compose.onNodeWithTag("novel-material-body").performTextReplacement("Edited character body")
        compose.onNodeWithText(label(R.string.chat_page_save)).performClick()
        assertEquals(listOf(path, title, "Edited character body", raw), saved)
        compose.onNodeWithTag("novel-material-body").assertIsDisplayed()
        compose.runOnIdle { requireNotNull(completeSave).invoke() }
        compose.onNodeWithTag("novel-material-body").assertDoesNotExist()
    }

    @Test
    fun catalogCurrentPlotUsesActualBranchAndOpenedFileSnapshotCommand() {
        val path = "branches/actual-branch/plot/current.md"
        val raw = "---\ntitle: Current plot\n---\n\nOriginal plot body"
        var saved: List<String>? = null
        compose.setContent {
            MaterialTheme {
                MarkdownWorkspaceCatalog(
                    state = catalogState(catalog = null),
                    category = CatalogCategory.Plot, onSelectCategory = {}, listState = rememberLazyListState(),
                    readFileRaw = { requested -> assertEquals(path, requested); raw },
                    onCreateMaterial = { _, _, _, _ -> },
                    onSaveMaterial = { _, _, _, _, _ -> error("plot does not use material rename/save") },
                    onSaveOpenedFileEdit = { savedPath, body, expectedRaw, done ->
                        saved = listOf(savedPath, body, expectedRaw)
                        done()
                    },
                    onDeleteMaterial = { _, _, _ -> }, onClearError = {},
                )
            }
        }

        compose.onNodeWithText(label(R.string.novel_catalog_current_plot)).performClick()
        compose.onNodeWithTag("novel-material-body").performTextReplacement("Updated actual-branch plot")
        compose.onNodeWithText(label(R.string.chat_page_save)).performClick()
        assertEquals(listOf(path, "Updated actual-branch plot", raw), saved)
        compose.onNodeWithTag("novel-material-body").assertDoesNotExist()
    }

    @Composable
    private fun ChatUnderTest(
        state: NovelWorkspaceChatState,
        draft: String,
        onDraftChange: (String) -> Unit,
        onSend: (String) -> Boolean,
        onStop: () -> Unit,
    ) {
        MarkdownWorkspaceChat(
            state = state, draft = draft, onDraftChange = onDraftChange, listState = rememberLazyListState(),
            onOpenGhostwrite = {}, readFileBody = { null }, readFileRaw = { null },
            onSaveFileEdit = { _, _, _ -> }, onCollectDraft = { _, _, _ -> },
            onArchiveDecision = { _, _, _, _ -> }, onEditProposal = { _, _, _ -> },
            onApproveProposal = { _, _ -> }, onRejectProposal = {}, onSetComposerMode = {},
            onSend = onSend, onStop = onStop, onProposeCharacter = { _, _ -> },
            onRewriteLaterChapters = {}, onResolveUnresolved = {}, onClearError = {},
        )
    }

    private fun chatState(busy: Boolean) = NovelWorkspaceChatState(
        branchSlug = "actual-branch", timeline = emptyList(), chapters = emptyList(), ghostwriteJob = null,
        busy = busy, errorMessage = null, plotStale = false, unresolvedFromOrdinal = null,
        streamingText = "", toolActivity = null, composerMode = NovelMarkdownComposerMode.Discuss,
    )

    private fun catalogState(catalog: NovelWorkspaceCatalog.NovelWorkspaceCatalogData?) = NovelWorkspaceCatalogState(
        branchSlug = "actual-branch", catalog = catalog, busy = false, loading = false,
        writeLocked = false, errorMessage = null,
    )

    private fun label(id: Int) = compose.activity.getString(id)
}
