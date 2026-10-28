package app.amber.core.ai.generative

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.GenerativeUiSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeUiPlannerTest {
    @Test
    fun visualRequestsKeepTheNormalToolBackedStream() {
        assertFalse(
            GenerativeUiPlanner.needsVisibleStreamingFallback(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("画一个流程图解释这件事")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.needsVisibleStreamingFallback(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("一句话解释")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.needsVisibleStreamingFallback(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("@Designer 用 guizang 的 skill 做一份瑞士国际主义风的 PPT")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.needsVisibleStreamingFallback(
                setting = GenerativeUiSetting(enabled = false),
                messages = listOf(userMessage("画一个流程图")),
            )
        )
    }

    @Test
    fun directDrawingRequestsDoNotDisableToolCatalog() {
        assertFalse(
            GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("画一下唐代的三省六部制")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("搜索资料后画一个流程图")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("@designer 调用 skill 画一个 PPT")),
            )
        )
    }

    @Test
    fun slashRoutesSeparateDrawAndSvg() {
        val setting = GenerativeUiSetting(enabled = true)
        val drawMessages = listOf(userMessage("[ROUTE:image]\n画一只猫"))
        val svgMessages = listOf(userMessage("[ROUTE:svg]\n画一只猫"))

        val drawPrompt = GenerativeUiPlanner.buildPrompt(
            setting = setting,
            messages = drawMessages,
            hasImageGenTool = true,
        )
        val svgPrompt = GenerativeUiPlanner.buildPrompt(
            setting = setting,
            messages = svgMessages,
            hasImageGenTool = true,
        )

        assertTrue(drawPrompt.contains("Call the `generate_image` tool"))
        assertFalse(GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(setting, drawMessages))
        assertTrue(svgPrompt.contains("show-widget SVG"))
        assertFalse(GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(setting, svgMessages))
        assertFalse(GenerativeUiPlanner.stripVisualRouteTagsForDisplay("[ROUTE:svg]\n画一只猫").contains("[ROUTE:"))
    }

    @Test
    fun animatedSvgRequestsRouteToDiagramWidget() {
        val setting = GenerativeUiSetting(enabled = true)
        val messages = listOf(userMessage("画一个SVG动画：一只鹈鹕骑自行车，车轮在转"))

        val prompt = GenerativeUiPlanner.buildPrompt(setting = setting, messages = messages)
        val requirement = GenerativeUiPlanner.widgetRequirement(setting = setting, messages = messages)

        assertTrue(prompt.contains("show-widget SVG"))
        assertTrue(requirement.required)
        assertFalse(requirement.expectSlides)
    }

    @Test
    fun toolMediatedDiagramStillRequiresFinalWidget() {
        val requirement = GenerativeUiPlanner.widgetRequirement(
            setting = GenerativeUiSetting(enabled = true),
            messages = listOf(userMessage("搜索资料后调用工具画一个流程图")),
        )

        assertTrue(requirement.required)
        assertFalse(requirement.expectSlides)
    }

    @Test
    fun diagramPromptRequiresAdaptiveSvgCanvas() {
        val prompt = GenerativeUiPlanner.buildPrompt(
            setting = GenerativeUiSetting(enabled = true),
            messages = listOf(userMessage("画一个中国上下五千年时间线")),
        )

        assertTrue(prompt.contains("Do not default to a square SVG canvas"))
        assertTrue(prompt.contains("viewBox aspect ratio"))
        assertTrue(prompt.contains("long timelines"))
        assertTrue(prompt.contains("""width="100%""""))
    }

    @Test
    fun toolMediatedVisualRequestsWarnAgainstProgressWidgets() {
        val prompt = GenerativeUiPlanner.buildPrompt(
            setting = GenerativeUiSetting(enabled = true),
            messages = listOf(userMessage("@Designer 用 guizang 的 skill 做一份瑞士国际主义风的 PPT")),
        )

        assertTrue(prompt.contains("Do NOT create a widget for routing, progress, plan, or status summaries."))
        assertTrue(prompt.contains("final artifact must be one full_html show-widget deck preview"))
        assertTrue(prompt.contains("Do NOT turn the deck into a MiniApp"))
        assertTrue(prompt.contains("renderer \"full_html\""))
    }

    @Test
    fun guizangRequestsRouteToDeckEvenWithoutPptKeyword() {
        val prompt = GenerativeUiPlanner.buildPrompt(
            setting = GenerativeUiSetting(enabled = true),
            messages = listOf(userMessage("用 guizang skill 做一个演示")),
        )
        val requirement = GenerativeUiPlanner.widgetRequirement(
            setting = GenerativeUiSetting(enabled = true),
            messages = listOf(userMessage("用 guizang skill 做一个演示")),
        )

        assertTrue(prompt.contains("renderer \"full_html\""))
        assertTrue(prompt.contains("Do NOT turn the deck into a MiniApp"))
        assertTrue(requirement.required)
        assertTrue(requirement.expectSlides)
        assertTrue(requirement.expectFullHtmlDeck)
    }

    @Test
    fun slidesRouteDoesNotDependOnStructuredOrChartSwitches() {
        val setting = GenerativeUiSetting(
            enabled = true,
            enableStructuredRenderers = false,
            enableInteractiveCharts = false,
        )
        val messages = listOf(userMessage("做一份 5 页 PPT"))

        val prompt = GenerativeUiPlanner.buildPrompt(setting = setting, messages = messages)
        val requirement = GenerativeUiPlanner.widgetRequirement(setting = setting, messages = messages)

        assertTrue(prompt.contains("renderer \"full_html\""))
        assertTrue(prompt.contains("""<div id="deck">"""))
        assertTrue(requirement.required)
        assertTrue(requirement.expectSlides)
        assertTrue(requirement.expectFullHtmlDeck)
    }

    @Test
    fun studyFlashcardAndQuizRequestsRouteToTaskUi() {
        val setting = GenerativeUiSetting(enabled = true)
        val flashcardMessages = listOf(userMessage("给我做一套英语单词闪卡和 3 道随堂测验"))

        val prompt = GenerativeUiPlanner.buildPrompt(setting = setting, messages = flashcardMessages)
        val requirement = GenerativeUiPlanner.widgetRequirement(setting = setting, messages = flashcardMessages)

        assertTrue(prompt.contains("```task-ui"))
        assertTrue(prompt.contains("\"kind\":\"study\""))
        assertTrue(prompt.contains("flashcard_deck"))
        assertTrue(prompt.contains("quiz_item"))
        assertTrue(prompt.contains("Do NOT use `full_html` PPT covers or MiniApp for study flashcards or quizzes."))
        assertTrue(requirement.required)
        assertTrue(requirement.expectTaskUi)
        assertFalse(requirement.expectSlides)
        assertFalse(requirement.expectFullHtmlDeck)
    }

    @Test
    fun slashRouteTaskUiRoutesAndStripsCleanly() {
        val setting = GenerativeUiSetting(enabled = true)
        val messages = listOf(userMessage("[ROUTE:task-ui]\n做一个房贷月供参数测算卡"))

        val prompt = GenerativeUiPlanner.buildPrompt(setting = setting, messages = messages)
        val requirement = GenerativeUiPlanner.widgetRequirement(setting = setting, messages = messages)

        assertTrue(prompt.contains("task-ui"))
        assertTrue(prompt.contains("value_field_id"))
        assertTrue(requirement.required)
        assertTrue(requirement.expectTaskUi)
        assertFalse(GenerativeUiPlanner.stripVisualRouteTagsForDisplay("[ROUTE:task-ui]\n做一个房贷月供参数测算卡").contains("[ROUTE:"))
    }

    @Test
    fun expectTaskUiQualityCheckAcceptsTaskUiAndRejectsShowWidget() {
        val requirement = GenerativeUiWidgetRequirement(required = true, expectTaskUi = true)
        val validTaskUi = """
            ```task-ui
            {"kind":"study","title":"词汇卡","components":[{"id":"fc1","kind":"flashcard","front":"Apple","back":"苹果"}]}
            ```
        """.trimIndent()
        val wrongShowWidget = """
            ```show-widget
            {"title":"流程","widget_code":"<svg width=\"100%\" viewBox=\"0 0 680 180\" xmlns=\"http://www.w3.org/2000/svg\"><rect x=\"24\" y=\"24\" width=\"200\" height=\"80\"/><text x=\"48\" y=\"70\">A</text></svg>"}
            ```
        """.trimIndent()

        assertTrue(GenerativeWidgetParser.widgetQualityIssue(validTaskUi, requirement) == null)
        val wrongWidgetIssue = GenerativeWidgetParser.widgetQualityIssue(wrongShowWidget, requirement)
        assertTrue(wrongWidgetIssue != null && wrongWidgetIssue.contains("expected task-ui interactive card"))
        val missingIssue = GenerativeWidgetParser.widgetQualityIssue("纯文本回答", requirement)
        assertTrue(missingIssue == "missing required task-ui")
    }

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
