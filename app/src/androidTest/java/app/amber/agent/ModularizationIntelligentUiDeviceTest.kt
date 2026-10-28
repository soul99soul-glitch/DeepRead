package app.amber.agent

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.ai.generative.IntelligentUiComponentKind
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.core.ai.generative.IntelligentUiDocument
import app.amber.core.ai.generative.IntelligentUiOptionItem
import app.amber.core.ai.generative.IntelligentUiStateStore
import app.amber.core.ai.generative.IntelligentUiTabItem
import app.amber.core.ai.generative.TaskUiKind
import app.amber.feature.ui.components.message.IntelligentUiCard
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.pages.chat.toChatTheme
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production card behavior and narrow-screen contracts; no provider or durable fixture is used. */
@RunWith(AndroidJUnit4::class)
class ModularizationIntelligentUiDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun clearFixtureCacheBeforeTest() {
        // This test owns its instrumentation process and never opens a product conversation.
        IntelligentUiStateStore.clearAll()
    }

    @After
    fun clearFixtureCacheAfterTest() {
        IntelligentUiStateStore.clearAll()
    }

    @Test
    fun recursiveTabsSplitAndNumericBindingAtLargeFont() {
        val prompts = mutableListOf<String>()
        showCard(exploreDocument(), "p1-device-binding", prompts::add)

        compose.onNodeWithText("每周分钟", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("21", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("6")
        closeSoftKeyboard()
        compose.onNode(hasSetTextAction()).assertTextEquals("6")
        compose.onNodeWithText("42", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue("Editing must not emit an action prompt", prompts.isEmpty()) }

        capture("01-recursive-split-numeric-320dp-font150")
        assertTextFits(FIELD_LABEL)
        assertInsideViewport(compose.onNode(hasSetTextAction()))
        val field = rawBounds(compose.onNode(hasSetTextAction()))
        val metric = rawBounds(compose.onNodeWithText("42", useUnmergedTree = true))
        assertTrue("Split columns must not overlap", field.right < metric.left)

        compose.onNodeWithText("说明").performClick()
        compose.onNodeWithText(REVIEW_TEXT, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("参数").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("6")
        compose.onNodeWithText("42", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun actionReturnsPromptAndLongFooterLabelsRemainReachable() {
        val prompts = mutableListOf<String>()
        val document = IntelligentUiDocument(
            id = "p1-device-action-document",
            kind = TaskUiKind.FORM,
            title = "确认阅读安排并回填对话草稿",
            summary = "先查看和编辑，再由对话中的用户决定是否发送。",
            components = listOf(
                IntelligentUiComponentNode(
                    id = "draft-action",
                    kind = IntelligentUiComponentKind.ACTION_BUTTON,
                    title = ACTION_LABEL,
                    actionInstruction = ACTION_PROMPT,
                ),
            ),
            resetLabel = RESET_LABEL,
            submitLabel = SUBMIT_LABEL,
        )
        showCard(document, "p1-device-action", prompts::add)

        compose.runOnIdle { assertTrue("Composition must not execute actions", prompts.isEmpty()) }
        compose.onNodeWithText(ACTION_LABEL).performClick()
        compose.runOnIdle { assertEquals(listOf(ACTION_PROMPT), prompts) }
        compose.onNodeWithText("已同步到对话").assertDoesNotExist()
        capture("02-action-footer-320dp-font150")
        assertTextFits(RESET_LABEL)
        assertTextFits(SUBMIT_LABEL)
        val reset = compose.onNode(hasClickAction() and hasText(RESET_LABEL))
        val submit = compose.onNode(hasClickAction() and hasText(SUBMIT_LABEL))
        reset.assertIsDisplayed()
        submit.assertIsDisplayed()
        assertInsideViewport(reset)
        assertInsideViewport(submit)
        assertFalse("Reset and submit hit areas must not overlap", rawBounds(reset).overlaps(rawBounds(submit)))

        submit.performClick()
        compose.runOnIdle {
            assertEquals(2, prompts.size)
            assertTrue(prompts.last().contains("【信息收集 · ${document.title}】"))
            assertEquals(prompts.last(), IntelligentUiStateStore.peek("p1-device-action")?.submittedSummary)
        }
        compose.onNodeWithText("已同步到对话").assertIsDisplayed()
        capture("03-submission-feedback-320dp-font150")
    }

    @Test
    fun recycledCardKeepsInputAndSelectedTabForTheSameCardKey() {
        val cardKey = "p1-device-recycling"
        val compositions = AtomicInteger()
        val disposals = AtomicInteger()
        showCard(exploreDocument(), cardKey, onPrompt = {}, compositions = compositions, disposals = disposals)

        compose.onNode(hasSetTextAction()).performTextReplacement("25")
        closeSoftKeyboard()
        compose.onNodeWithText("说明").performClick()
        compose.onNodeWithText(REVIEW_TEXT, useUnmergedTree = true).assertIsDisplayed()
        val originalState = IntelligentUiStateStore.peek(cardKey)
        assertNotNull(originalState)

        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(40)
        compose.onNodeWithTag(CARD_TAG).assertDoesNotExist()
        compose.runOnIdle { assertTrue("Fixture must actually leave composition", disposals.get() > 0) }
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(0)
        compose.onNodeWithText(REVIEW_TEXT, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("The card must be composed again", compositions.get() > 1)
            assertSame("A recycled card must keep its sole state owner", originalState, IntelligentUiStateStore.peek(cardKey))
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("参数").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("25")
        compose.onNodeWithText("175", useUnmergedTree = true).assertIsDisplayed()
        capture("04-recycled-card-restored-320dp-font150")
    }

    @Test
    fun decisionSelectionUsesTheCardOwnerAndOnlySubmitsOnExplicitAction() {
        val cardKey = "p1-device-decision"
        val firstLabel = "先阅读背景材料并记录问题"
        val secondLabel = "先运行本地示例再比较结果"
        val prompts = mutableListOf<String>()
        val document = IntelligentUiDocument(
            id = "p1-device-decision-document",
            kind = TaskUiKind.DECISION,
            title = "选择下一步学习方式",
            options = listOf(
                IntelligentUiOptionItem(id = "read", label = firstLabel, recommended = true, badge = "建议"),
                IntelligentUiOptionItem(id = "try", label = secondLabel),
            ),
        )
        showCard(document, cardKey, prompts::add)
        val originalState = IntelligentUiStateStore.peek(cardKey)
        assertNotNull(originalState)
        compose.runOnIdle {
            assertEquals("read", originalState?.selectedDecisionOptionId)
            assertTrue("Rendering the recommendation must not emit a prompt", prompts.isEmpty())
        }

        val first = compose.onNode(hasClickAction() and hasText(firstLabel))
        val second = compose.onNode(hasClickAction() and hasText(secondLabel))
        second.performClick()
        compose.runOnIdle {
            assertSame(originalState, IntelligentUiStateStore.peek(cardKey))
            assertEquals("try", originalState?.selectedDecisionOptionId)
            assertTrue("Selecting an option must only edit card state", prompts.isEmpty())
        }
        capture("05-decision-selected-320dp-font150")
        listOf(document.title, firstLabel, secondLabel, "建议").forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            assertTextFits(label)
        }
        assertInsideViewport(first)
        assertInsideViewport(second)
        assertFalse("Decision option hit areas must not overlap", rawBounds(first).overlaps(rawBounds(second)))

        compose.onNode(hasClickAction() and hasText(document.submitLabel)).performClick()
        compose.runOnIdle {
            assertEquals(1, prompts.size)
            assertTrue("The draft must contain the selected option", prompts.single().contains(secondLabel))
            assertEquals(prompts.single(), originalState?.submittedSummary)
            assertSame(originalState, IntelligentUiStateStore.peek(cardKey))
        }
        compose.onNodeWithText("已同步到对话").assertIsDisplayed()
    }

    @Test
    fun studyAnswerUsesTheCardOwnerAndUpdatesVisibleProgressWithoutSending() {
        val cardKey = "p1-device-study"
        val questionId = "state-owner-question"
        val question = "卡片输入与学习进度应由谁保存？"
        val wrongLabel = "由每个页面另存一份副本"
        val correctLabel = "由同一张卡片的状态统一保存"
        val prompts = mutableListOf<String>()
        val document = IntelligentUiDocument(
            id = "p1-device-study-document",
            kind = TaskUiKind.STUDY,
            title = "学习卡片状态归属",
            components = listOf(
                IntelligentUiComponentNode(
                    id = questionId,
                    kind = IntelligentUiComponentKind.QUIZ_ITEM,
                    title = question,
                    options = listOf(
                        IntelligentUiOptionItem(id = "duplicate", label = wrongLabel, value = "duplicate"),
                        IntelligentUiOptionItem(id = "shared", label = correctLabel, value = "shared"),
                    ),
                    correctOption = "shared",
                ),
            ),
        )
        showCard(document, cardKey, prompts::add)
        val originalState = IntelligentUiStateStore.peek(cardKey)
        assertNotNull(originalState)
        compose.onNodeWithText("待作答 1 题", useUnmergedTree = true).assertIsDisplayed()
        val wrong = compose.onNode(hasClickAction() and hasText(wrongLabel))
        val correct = compose.onNode(hasClickAction() and hasText(correctLabel))
        correct.performClick()

        compose.runOnIdle {
            assertSame(originalState, IntelligentUiStateStore.peek(cardKey))
            assertEquals("shared", originalState?.quizSelectedOption?.get(questionId))
            assertTrue("Answering a quiz must not send a prompt", prompts.isEmpty())
            assertNull(originalState?.submittedSummary)
        }
        capture("06-study-answer-progress-320dp-font150")
        listOf(
            document.title, question, wrongLabel, correctLabel, "A", "B", "正确",
            "学习进度汇总", "正确率 100%", "测验答对 1/1（共 1 题）",
        ).forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            assertTextFits(label)
        }
        assertInsideViewport(wrong)
        assertInsideViewport(correct)
        assertFalse("Quiz option hit areas must not overlap", rawBounds(wrong).overlaps(rawBounds(correct)))
        val progressTitle = compose.onNodeWithText("学习进度汇总", useUnmergedTree = true)
        val accuracy = compose.onNodeWithText("正确率 100%", useUnmergedTree = true)
        assertInsideViewport(progressTitle)
        assertInsideViewport(accuracy)
        assertFalse("Progress heading and accuracy must not overlap", rawBounds(progressTitle).overlaps(rawBounds(accuracy)))

        compose.onNode(hasClickAction() and hasText(document.submitLabel)).performClick()
        compose.runOnIdle {
            assertEquals(1, prompts.size)
            assertTrue("The draft must include the study result", prompts.single().contains("答对 1/1 题"))
            assertEquals(prompts.single(), originalState?.submittedSummary)
            assertSame(originalState, IntelligentUiStateStore.peek(cardKey))
        }
    }

    private fun showCard(
        document: IntelligentUiDocument,
        cardKey: String,
        onPrompt: (String) -> Unit,
        compositions: AtomicInteger = AtomicInteger(),
        disposals: AtomicInteger = AtomicInteger(),
    ) {
        compose.setContent {
            FixtureTheme {
                LazyColumn(Modifier.fillMaxSize().testTag(LIST_TAG)) {
                    item(key = cardKey) {
                        DisposableEffect(cardKey) {
                            compositions.incrementAndGet()
                            onDispose { disposals.incrementAndGet() }
                        }
                        IntelligentUiCard(
                            document = document,
                            cardKey = cardKey,
                            onActionPrompt = onPrompt,
                            modifier = Modifier.testTag(CARD_TAG),
                        )
                    }
                    items(45) { index ->
                        Text(
                            text = "回收验证占位 $index",
                            modifier = Modifier.fillMaxWidth().height(96.dp).padding(12.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val tokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale = 1.5f),
            LocalAmberTokens provides tokens,
            LocalChatTheme provides tokens.toChatTheme(),
        ) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(tokens.bg), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag(VIEWPORT_TAG)) { content() }
                }
            }
        }
    }

    private fun exploreDocument() = IntelligentUiDocument(
        id = "p1-device-explore-document",
        kind = TaskUiKind.EXPLORE,
        title = "阅读安排",
        components = listOf(
            IntelligentUiComponentNode(
                id = "reading-tabs",
                kind = IntelligentUiComponentKind.TABS,
                tabs = listOf(
                    IntelligentUiTabItem(
                        id = "parameters",
                        title = "参数",
                        children = listOf(
                            IntelligentUiComponentNode(
                                id = "reading-split",
                                kind = IntelligentUiComponentKind.SPLIT,
                                children = listOf(
                                    IntelligentUiComponentNode(
                                        id = "input-section",
                                        kind = IntelligentUiComponentKind.SECTION,
                                        children = listOf(
                                            IntelligentUiComponentNode(
                                                id = "minutes",
                                                kind = IntelligentUiComponentKind.NUMBER_FIELD,
                                                title = FIELD_LABEL,
                                                defaultValue = "3",
                                                min = 0.0,
                                                max = 60.0,
                                                step = 1.0,
                                                unit = "分",
                                            ),
                                        ),
                                    ),
                                ),
                                secondaryChildren = listOf(
                                    IntelligentUiComponentNode(
                                        id = "metric-column",
                                        kind = IntelligentUiComponentKind.COLUMN,
                                        children = listOf(
                                            IntelligentUiComponentNode(
                                                id = "weekly-minutes",
                                                kind = IntelligentUiComponentKind.METRIC,
                                                title = "每周分钟",
                                                valueFieldId = "minutes",
                                                scale = 7.0,
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    IntelligentUiTabItem(
                        id = "review",
                        title = "说明",
                        children = listOf(
                            IntelligentUiComponentNode(
                                id = "review-text",
                                kind = IntelligentUiComponentKind.TEXT,
                                text = REVIEW_TEXT,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun assertTextFits(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.runOnIdle {
            assertTrue("Text must expose its actual layout: $text", node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        }
        val layout = layouts.single()
        val details = buildString {
            appendLine("text=$text")
            appendLine("didOverflowWidth=${layout.didOverflowWidth}")
            appendLine("didOverflowHeight=${layout.didOverflowHeight}")
            appendLine("hasVisualOverflow=${layout.hasVisualOverflow}")
            appendLine("size=${layout.size}")
            appendLine("node.size=${node.size}")
            appendLine("multiParagraph.width=${layout.multiParagraph.width}")
            appendLine("multiParagraph.height=${layout.multiParagraph.height}")
            appendLine("lineCount=${layout.lineCount}")
            appendLine("constraints=${layout.layoutInput.constraints}")
            appendLine("maxLines=${layout.layoutInput.maxLines}")
            appendLine("overflow=${layout.layoutInput.overflow}")
            appendLine("fontSize=${layout.layoutInput.style.fontSize}")
            appendLine("fontScale=${layout.layoutInput.density.fontScale}")
            repeat(layout.lineCount) { line ->
                appendLine("line[$line].left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} bottom=${layout.getLineBottom(line)} ellipsized=${layout.isLineEllipsized(line)}")
            }
        }
        val directory = instrumentation.targetContext.getExternalFilesDir(SCREENSHOT_DIRECTORY)
            ?: error("Target app has no external files directory")
        check(directory.exists() || directory.mkdirs())
        File(directory, "layout-${Integer.toHexString(text.hashCode())}.txt").writeText(details)
        if (layout.hasVisualOverflow) println("IntelligentUi text layout overflow:\n$details")
        // Paragraph width can retain the available constraint after Text shrinks to its content.
        // Check actual line extents against the measured text node, rather than that container width.
        assertFalse("Text must not discard lines: $text\n$details", layout.multiParagraph.didExceedMaxLines)
        repeat(layout.lineCount) { line ->
            assertFalse("Text must not ellipsize line $line: $text\n$details", layout.isLineEllipsized(line))
            assertTrue("Text begins outside its node: $text\n$details", layout.getLineLeft(line) >= -1f)
            assertTrue("Text ends outside its node: $text\n$details", layout.getLineRight(line) <= node.size.width + 1f)
            assertTrue("Text descends outside its node: $text\n$details", layout.getLineBottom(line) <= node.size.height + 1f)
        }
    }

    private fun rawBounds(target: SemanticsNodeInteraction): Rect = target.fetchSemanticsNode().let { node ->
        Rect(node.positionInRoot, node.size.toSize())
    }

    private fun assertInsideViewport(target: SemanticsNodeInteraction) {
        val viewport = rawBounds(compose.onNodeWithTag(VIEWPORT_TAG))
        val bounds = rawBounds(target)
        assertTrue("Target begins outside viewport: $bounds / $viewport", bounds.left >= viewport.left - 1f)
        assertTrue("Target ends outside viewport: $bounds / $viewport", bounds.right <= viewport.right + 1f)
        assertTrue("Target must have visible width", bounds.width > 0f)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val directory = instrumentation.targetContext.getExternalFilesDir(SCREENSHOT_DIRECTORY)
            ?: error("Target app has no external files directory")
        check(directory.exists() || directory.mkdirs())
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(directory, "$name.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        File(directory, "$name.semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        println("ModularizationIntelligentUiDeviceTest evidence=${File(directory, name).absolutePath}")
    }

    private companion object {
        const val FIELD_LABEL = "本周每日阅读时长（修改后即时预估）"
        const val REVIEW_TEXT = "参数仅用于预估，确认后回填对话草稿。"
        const val ACTION_LABEL = "先放入草稿"
        const val ACTION_PROMPT = "请根据当前表单生成阅读安排，先让我确认。"
        const val RESET_LABEL = "恢复当前表单默认值"
        const val SUBMIT_LABEL = "确认并回填当前对话草稿"
        const val VIEWPORT_TAG = "p1-intelligent-ui-viewport"
        const val LIST_TAG = "p1-intelligent-ui-list"
        const val CARD_TAG = "p1-intelligent-ui-card"
        const val SCREENSHOT_DIRECTORY = "modularization-phase-1"
    }
}
