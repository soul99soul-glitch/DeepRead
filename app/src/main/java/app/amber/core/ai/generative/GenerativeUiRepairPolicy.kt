package app.amber.core.ai.generative

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val GENERATIVE_UI_REASONING_ONLY_FALLBACK_MS = 5_000L
internal const val GENERATIVE_UI_REASONING_ONLY_FALLBACK_CHARS = 800
private const val MEANINGFUL_TEXT_MIN_CHARS = 30

internal class GenerativeUiReasoningOnlyStreamException : RuntimeException(
    "Generative UI stream emitted only hidden reasoning without visible content"
)

internal class GenerativeUiInvalidWidgetStreamException(
    val issue: String,
) : RuntimeException("Generative UI stream completed without a valid widget: $issue")

/** Widget acceptance, visible-output repair prompts and local artifact templates. */
internal object GenerativeUiRepairPolicy {
    fun List<UIMessage>.hasVisibleWidgetFence(copy: GenerativeWidgetCopy): Boolean =
        lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.any { GenerativeWidgetParser.hasRenderableWidget(it.text, copy = copy) } == true

    fun List<UIMessage>.visibleWidgetIssue(
        requirement: GenerativeUiWidgetRequirement,
        copy: GenerativeWidgetCopy,
    ): String? {
        if (!requirement.required) return null
        val content = lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            .orEmpty()
        return GenerativeWidgetParser.widgetQualityIssue(content, requirement, copy = copy)
    }

    /**
     * "Did the retry give a real answer?" — joined visible Text parts of the last
     * assistant message, with the widget fence stripped, must contain non-trivial
     * prose. Used to decide whether the local-fallback skeleton widget should still
     * be appended after the second pass.
     */
    fun List<UIMessage>.hasMeaningfulVisibleAssistantText(): Boolean {
        val text = lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            .orEmpty()
        // Strip any ```show-widget``` or ```task-ui``` fence so the check measures *prose*, not the
        // widget JSON the model may have already produced.
        val withoutWidget = text.replace(
            Regex("```\\s*(?:show-widget|widget|generative-ui|task-ui|task_ui|intelligent-ui|intelligent_ui|adaptive-ui)[\\s\\S]*?```"),
            "",
        ).trim()
        return withoutWidget.length >= MEANINGFUL_TEXT_MIN_CHARS
    }

    fun List<UIMessage>.withGenerativeUiVisibleFallbackPrompt(
        requirement: GenerativeUiWidgetRequirement,
        previousIssue: String?,
    ): List<UIMessage> {
        val instruction = UIMessage.system(prompt = buildGenerativeUiRetryPrompt(requirement, previousIssue))
        val first = firstOrNull()
        return if (first?.role == MessageRole.SYSTEM) {
            listOf(first, instruction) + drop(1)
        } else {
            listOf(instruction) + this
        }
    }

    private fun buildGenerativeUiRetryPrompt(
        requirement: GenerativeUiWidgetRequirement,
        previousIssue: String?,
    ): String {
        val issue = previousIssue?.takeIf { it.isNotBlank() } ?: "missing required show-widget"
        if (requirement.expectTaskUi) {
            return """
                **Visible Task UI Repair**
                The previous output did not produce a valid interactive task card: $issue
                Reply in visible content immediately with one valid fenced `task-ui` JSON block (`kind`: `"decision"`, `"form"`, `"explore"`, or `"study"`).
                Use this exact form:
                ```task-ui
                {"kind":"study","title":"互动练习","subtitle":"翻转闪卡或完成小测验","components":[{"id":"fc_1","kind":"flashcard","front":"核心概念","back":"简明解释","hint":"提示"},{"id":"q_1","kind":"quiz_item","title":"测验题目","options":[{"id":"a","label":"选项 A","value":"A"},{"id":"b","label":"选项 B","value":"B"}],"correct_option":"A","explanation":"解析说明"},{"id":"score_1","kind":"score_summary"}]}
                ```
                Replace the content with the actual requested flashcards, quiz, decision options, form inputs, or parameter explorer.
                Do not put `task-ui` JSON inside hidden reasoning, and do not use `full_html` PPT covers or MiniApp for flashcards/quizzes.
            """.trimIndent()
        }
        if (requirement.expectFullHtmlDeck) {
            return """
                **Visible Full HTML Deck Repair**
                The previous output did not produce a valid full_html deck: $issue
                Reply in visible content immediately with exactly one fenced `show-widget` JSON block, but do not begin the block until the full JSON can be completed in this response. Do not output a MiniApp, standalone webpage, generic HTML app, Markdown-only answer, or hidden-only reasoning.
                Required JSON shape:
                - `renderer` must be `"${GuizangHtmlDeckValidator.RENDERER}"`.
                - `widget_code` is only a static SVG cover preview.
                - `spec.html` is the full live deck HTML as one JSON string.
                - `spec.html` must contain `<div id="deck">` and one or more `<section class="slide ...">` pages.
                - Scripts may only use `${GuizangHtmlDeckValidator.LOCAL_MOTION_URL}`, `${GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL}`, and `${GuizangHtmlDeckValidator.LOCAL_THREE_URL}`. Do not use CDN script URLs.
                - Preserve the requested PPT/deck content and style; keep the JSON valid, complete, and compact enough to fit. Never emit partial spec.html.
            """.trimIndent()
        }
        if (requirement.expectSlides) {
            return """
                **Visible Slide Deck Repair**
                The previous output did not produce a valid deck card: $issue
                Reply in visible content immediately with exactly one fenced `show-widget` JSON block, but do not begin the block until the full JSON can be completed in this response. Do not output a MiniApp, standalone webpage, generic HTML app, Markdown-only answer, or hidden-only reasoning.
                Use renderer `"${GuizangHtmlDeckValidator.RENDERER}"`.
                Put a complete mobile-readable HTML deck in `spec.html` with `<div id="deck">` and `<section class="slide ...">` pages.
                Keep the deck concise and the JSON valid. Never emit partial spec.html.
            """.trimIndent()
        }
        return """
            **Visible Generative UI Retry**
            The previous stream did not produce a visible widget: $issue
            Reply in visible content immediately.
            First output one valid fenced widget block with widget_code, then at most one short sentence.
            Use this exact form:
            ```show-widget
            {"title":"可视化草图","widget_code":"<svg width=\"100%\" viewBox=\"0 0 680 260\" xmlns=\"http://www.w3.org/2000/svg\"><rect x=\"24\" y=\"24\" width=\"632\" height=\"212\" rx=\"18\" fill=\"#ffffff\" stroke=\"#e5e7eb\"/><text x=\"48\" y=\"64\" font-size=\"20\" font-weight=\"700\" fill=\"#111827\">可视化结果</text><rect x=\"48\" y=\"96\" width=\"160\" height=\"74\" rx=\"14\" fill=\"#eff6ff\" stroke=\"#bfdbfe\"/><text x=\"72\" y=\"140\" font-size=\"15\" fill=\"#1e3a8a\">起点</text><path d=\"M220 133 H300\" stroke=\"#94a3b8\" stroke-width=\"3\" marker-end=\"url(#arrow)\"/><rect x=\"312\" y=\"96\" width=\"160\" height=\"74\" rx=\"14\" fill=\"#f0fdf4\" stroke=\"#bbf7d0\"/><text x=\"336\" y=\"140\" font-size=\"15\" fill=\"#166534\">过程</text><path d=\"M484 133 H552\" stroke=\"#94a3b8\" stroke-width=\"3\" marker-end=\"url(#arrow)\"/><circle cx=\"600\" cy=\"133\" r=\"36\" fill=\"#fff7ed\" stroke=\"#fed7aa\"/><text x=\"582\" y=\"140\" font-size=\"15\" fill=\"#9a3412\">结果</text><defs><marker id=\"arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L8,4 L0,8 Z\" fill=\"#94a3b8\"/></marker></defs></svg>"}
            ```
            Replace the labels with the actual answer content.
            Keep the SVG small, declarative, and self-contained (SMIL/CSS animation only when the request asked for motion).
            Do not use renderer/spec in this retry because the timeline needs widget_code for streaming partial render.
            Do not put widget JSON, SVG, HTML, or renderer/spec inside hidden reasoning.
            Do not output Markdown-only prose for this retry.
        """.trimIndent()
    }

    fun List<UIMessage>.withLocalGenerativeUiFallbackWidget(
        baseMessages: List<UIMessage>,
        model: app.amber.ai.provider.Model,
        requirement: GenerativeUiWidgetRequirement = GenerativeUiWidgetRequirement.None,
    ): List<UIMessage> {
        val labels = fallbackWidgetLabels(baseMessages)
        val fallbackText = when {
            requirement.expectTaskUi -> buildLocalTaskUiFallbackWidget(labels)
            requirement.expectFullHtmlDeck || requirement.expectSlides -> buildLocalFullHtmlDeckFallbackWidget(labels)
            else -> buildLocalGenerativeUiFallbackWidget(labels)
        }
        val lastAssistantIndex = indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIndex < 0) {
            return this + UIMessage(
                role = MessageRole.ASSISTANT,
                modelId = model.id,
                parts = listOf(UIMessagePart.Text(fallbackText)),
            )
        }
        return mapIndexed { index, message ->
            if (index == lastAssistantIndex) {
                message.copy(parts = message.parts + UIMessagePart.Text("\n\n$fallbackText"))
            } else {
                message
            }
        }
    }

    private fun List<UIMessage>.fallbackWidgetLabels(baseMessages: List<UIMessage>): List<String> {
        val assistantLines = lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.lineSequence()
            ?.mapNotNull(::cleanFallbackWidgetLine)
            ?.distinct()
            ?.take(4)
            ?.toList()
            .orEmpty()
        if (assistantLines.isNotEmpty()) return assistantLines

        val userHint = baseMessages.lastOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString(" ") { GenerativeUiPlanner.stripVisualRouteTagsForDisplay(it.text) }
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(26)
            ?.takeIf { it.isNotBlank() }
        return listOfNotNull("需求", userHint, "结构化", "结论").take(4)
    }

    private fun cleanFallbackWidgetLine(line: String): String? {
        val compact = line
            .trim()
            .removePrefix("-")
            .removePrefix("*")
            .removePrefix("•")
            .removePrefix("·")
            .replace(Regex("""^\d+[.)、]\s*"""), "")
            .replace(Regex("""[#*_`><]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (compact.length < 2) return null
        if (compact.startsWith("```") || compact.startsWith("{") || compact.startsWith("[")) return null
        if (
            compact.contains("widget_code") ||
            compact.contains("renderer") ||
            compact.contains("\"components\"") ||
            compact.contains("\"kind\"")
        ) {
            return null
        }
        if (GenerativeWidgetParser.containsWidgetFence(compact)) return null
        return compact.take(30)
    }

    private fun buildLocalGenerativeUiFallbackWidget(labels: List<String>): String {
        val nodes = buildJsonArray {
            labels.take(4).forEach { label ->
                add(buildJsonObject { put("label", label) })
            }
        }
        val widget = buildJsonObject {
            put("title", "可视化摘要")
            put("renderer", "diagram")
            put(
                "spec",
                buildJsonObject {
                    put("type", "flow")
                    put("nodes", nodes)
                }
            )
        }
        return """
        ```show-widget
        $widget
        ```
        """.trimIndent()
    }

    private fun buildLocalTaskUiFallbackWidget(labels: List<String>): String {
        val safeLabels = labels.ifEmpty { listOf("核心概念", "关键要点", "重点总结") }.take(4)
        val title = safeLabels.first().take(24).ifBlank { "互动卡片" }
        val components = buildJsonArray {
            safeLabels.forEachIndexed { index, label ->
                add(
                    buildJsonObject {
                        put("id", "opt_${index + 1}")
                        put("kind", "option_card")
                        put("title", label)
                        put("body", "点击选择该项并继续互动")
                        if (index == 0) {
                            put("recommended", true)
                        }
                    }
                )
            }
        }
        val doc = buildJsonObject {
            put("kind", "decision")
            put("title", title)
            put("subtitle", "请选择一项继续深入探讨")
            put("submit_label", "确认选择")
            put("components", components)
        }
        return """
        ```task-ui
        $doc
        ```
        """.trimIndent()
    }


    private fun buildLocalFullHtmlDeckFallbackWidget(labels: List<String>): String {
        val safeLabels = labels.ifEmpty { listOf("演示预览", "结构", "要点", "结论") }.take(4)
        val title = safeLabels.first().take(24).ifBlank { "演示预览" }
        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            appendLine("<style>")
            appendLine("html,body{margin:0;width:100%;height:100%;background:#f8fafc;color:#111827;font-family:-apple-system,BlinkMacSystemFont,'Noto Sans SC',sans-serif;overflow:hidden}")
            appendLine("#deck{width:100vw;height:100vh;display:flex;overflow-x:auto;scroll-snap-type:x mandatory;touch-action:pan-x}")
            appendLine(".slide{min-width:100vw;height:100vh;box-sizing:border-box;padding:9vh 8vw;scroll-snap-align:start;display:flex;flex-direction:column;justify-content:center;gap:22px}")
            appendLine(".slide:nth-child(odd){background:#0f172a;color:#f8fafc}.kicker{font-size:13px;letter-spacing:.18em;text-transform:uppercase;color:#38bdf8}.title{font-size:clamp(34px,9vw,84px);line-height:1.02;font-weight:800}.body{font-size:clamp(18px,4vw,34px);line-height:1.45;max-width:760px}.rule{width:88px;height:6px;background:#ef4444}")
            appendLine("</style></head><body><div id=\"deck\">")
            safeLabels.forEachIndexed { index, label ->
                val safe = escapeHtml(label)
                appendLine("<section class=\"slide ${if (index == 0) "dark" else "light"}\" data-slide=\"${index + 1}\">")
                appendLine("<div class=\"kicker\">FULL HTML DECK</div><div class=\"rule\"></div>")
                appendLine("<div class=\"title\">$safe</div>")
                appendLine("<div class=\"body\">${if (index == 0) "移动端可左右滑动浏览。" else safe}</div>")
                appendLine("</section>")
            }
            appendLine("</div><script>window.__pipeAdvance=function(){return false};window.__setLowPowerMode=function(){}</script></body></html>")
        }
        val cover = """
            <svg width="100%" viewBox="0 0 680 220" xmlns="http://www.w3.org/2000/svg">
              <rect width="680" height="220" rx="16" fill="#0f172a"/>
              <rect x="32" y="30" width="616" height="160" rx="10" fill="none" stroke="#38bdf8" stroke-width="2"/>
              <text x="54" y="72" font-size="13" letter-spacing="3" fill="#7dd3fc">FULL HTML DECK</text>
              <text x="54" y="126" font-size="30" font-weight="800" fill="#f8fafc">${escapeHtml(title)}</text>
              <text x="54" y="164" font-size="15" fill="#cbd5e1">fallback live deck preview</text>
            </svg>
        """.trimIndent()
        val widget = buildJsonObject {
            put("title", title)
            put("renderer", GuizangHtmlDeckValidator.RENDERER)
            put("widget_code", cover)
            put(
                "spec",
                buildJsonObject {
                    put("title", title)
                    put("html", html)
                    put("source", "amberagent-fallback")
                    put("allowRemoteImages", true)
                    put("allowRemoteFonts", true)
                }
            )
        }
        return """
        ```show-widget
        $widget
        ```
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

}
