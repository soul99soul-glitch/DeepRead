package app.amber.core.ai.generative

/**
 * Top-level task archetypes for Amber's declarative `task-ui` protocol.
 */
enum class TaskUiKind(val wireName: String, val displayLabel: String) {
    DECISION("decision", "方案决策"),
    FORM("form", "信息收集"),
    EXPLORE("explore", "交互探索"),
    STUDY("study", "互动学习");

    companion object {
        fun fromRaw(raw: String?): TaskUiKind? {
            val normalized = raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
            return when (normalized) {
                "decision", "decide", "compare", "comparison", "choice", "options" -> DECISION
                "form", "input", "survey", "questionnaire", "collect" -> FORM
                "explore", "explorer", "browse", "inspect", "dashboard", "parameters", "simulator" -> EXPLORE
                "study", "learn", "quiz", "flashcard", "flashcards", "review", "practice", "exam" -> STUDY
                else -> null
            }
        }
    }
}

/**
 * Category grouping for the 80 native `task-ui` components.
 */
enum class IntelligentUiCategory {
    LAYOUT,
    TEXT,
    STATUS,
    INPUT,
    DATA,
    MEDIA,
    TASK_SEMANTIC,
    ACTION_CONTAINER,
}

/**
 * Complete 80-component catalog aligned across iOS, HarmonyOS, and Android:
 * - Layout (10)
 * - Text (12)
 * - Status (10)
 * - Input (14)
 * - Data (10)
 * - Media (8)
 * - Task Semantic (8)
 * - Action & Container (8)
 */
enum class IntelligentUiComponentKind(
    val canonicalName: String,
    val category: IntelligentUiCategory,
    val aliases: Set<String> = emptySet(),
) {
    // 1. Layout (10)
    SECTION("section", IntelligentUiCategory.LAYOUT, setOf("group", "block", "panel")),
    ROW("row", IntelligentUiCategory.LAYOUT, setOf("hstack", "horizontal")),
    COLUMN("column", IntelligentUiCategory.LAYOUT, setOf("vstack", "vertical", "stack")),
    GRID("grid", IntelligentUiCategory.LAYOUT, setOf("bento", "columns")),
    CARD("card", IntelligentUiCategory.LAYOUT, setOf("box", "surface", "container")),
    SPLIT("split", IntelligentUiCategory.LAYOUT, setOf("two_column", "twocolumn", "split_view")),
    TABS("tabs", IntelligentUiCategory.LAYOUT, setOf("tab_view", "tabview", "tab_group")),
    ACCORDION("accordion", IntelligentUiCategory.LAYOUT, setOf("collapse_group", "faq")),
    DISCLOSURE("disclosure", IntelligentUiCategory.LAYOUT, setOf("collapsible", "details", "expandable")),
    DIVIDER("divider", IntelligentUiCategory.LAYOUT, setOf("separator", "hr", "line")),

    // 2. Text (12)
    HEADING("heading", IntelligentUiCategory.TEXT, setOf("title", "h1", "h2", "header")),
    SUBHEADING("subheading", IntelligentUiCategory.TEXT, setOf("subtitle", "h3", "h4")),
    TEXT("text", IntelligentUiCategory.TEXT, setOf("paragraph", "body", "p", "prose", "markdown")),
    CAPTION("caption", IntelligentUiCategory.TEXT, setOf("small", "helper_text", "subtext")),
    EYEBROW("eyebrow", IntelligentUiCategory.TEXT, setOf("kicker", "overline", "section_label")),
    CALLOUT("callout", IntelligentUiCategory.TEXT, setOf("tip", "notice", "aside")),
    QUOTE("quote", IntelligentUiCategory.TEXT, setOf("blockquote", "citation")),
    CODE_BLOCK("codeBlock", IntelligentUiCategory.TEXT, setOf("code_block", "codeblock", "code", "pre")),
    BULLET_LIST("bulletList", IntelligentUiCategory.TEXT, setOf("bullet_list", "bulletlist", "ul", "unordered_list", "list")),
    NUMBERED_LIST("numberedList", IntelligentUiCategory.TEXT, setOf("numbered_list", "numberedlist", "ol", "ordered_list")),
    KEY_VALUE("keyValue", IntelligentUiCategory.TEXT, setOf("key_value", "keyvalue", "kv", "property_row")),
    DEFINITION_LIST("definitionList", IntelligentUiCategory.TEXT, setOf("definition_list", "definitionlist", "dl", "kv_list", "specs")),

    // 3. Status (10)
    BADGE("badge", IntelligentUiCategory.STATUS, setOf("status_badge")),
    TAG("tag", IntelligentUiCategory.STATUS, setOf("label_tag")),
    PILL("pill", IntelligentUiCategory.STATUS, setOf("chip_pill", "capsule")),
    STATUS_DOT("statusDot", IntelligentUiCategory.STATUS, setOf("status_dot", "statusdot", "indicator")),
    ALERT("alert", IntelligentUiCategory.STATUS, setOf("warning_box", "error_box", "info_box")),
    BANNER("banner", IntelligentUiCategory.STATUS, setOf("top_banner", "notice_banner")),
    EMPTY_STATE("emptyState", IntelligentUiCategory.STATUS, setOf("empty_state", "emptystate", "placeholder")),
    SKELETON_NOTE("skeletonNote", IntelligentUiCategory.STATUS, setOf("skeleton_note", "skeletonnote", "loading_note")),
    TIMELINE_ITEM("timelineItem", IntelligentUiCategory.STATUS, setOf("timeline_item", "timelineitem", "timeline")),
    STEP_ITEM("stepItem", IntelligentUiCategory.STATUS, setOf("step_item", "stepitem", "step")),

    // 4. Input (14)
    TEXT_FIELD("textField", IntelligentUiCategory.INPUT, setOf("text_field", "textfield", "text_input", "input")),
    TEXT_AREA("textArea", IntelligentUiCategory.INPUT, setOf("text_area", "textarea", "multiline_input", "long_text")),
    NUMBER_FIELD("numberField", IntelligentUiCategory.INPUT, setOf("number_field", "numberfield", "number_input", "number", "numeric_field")),
    SLIDER("slider", IntelligentUiCategory.INPUT, setOf("range", "range_slider")),
    STEPPER("stepper", IntelligentUiCategory.INPUT, setOf("counter", "number_stepper")),
    TOGGLE("toggle", IntelligentUiCategory.INPUT, setOf("switch", "boolean")),
    CHECKBOX("checkbox", IntelligentUiCategory.INPUT, setOf("check")),
    RADIO_GROUP("radioGroup", IntelligentUiCategory.INPUT, setOf("radio_group", "radiogroup", "radio", "single_choice")),
    SEGMENTED_CONTROL("segmentedControl", IntelligentUiCategory.INPUT, setOf("segmented_control", "segmentedcontrol", "segmented")),
    SELECT("select", IntelligentUiCategory.INPUT, setOf("dropdown", "picker", "single_select")),
    MULTI_SELECT("multiSelect", IntelligentUiCategory.INPUT, setOf("multi_select", "multiselect", "checkbox_group")),
    CHIP_GROUP("chipGroup", IntelligentUiCategory.INPUT, setOf("chip_group", "chipgroup", "chips", "tag_select")),
    DATE_FIELD("dateField", IntelligentUiCategory.INPUT, setOf("date_field", "datefield", "date", "date_picker")),
    RATING("rating", IntelligentUiCategory.INPUT, setOf("stars", "star_rating", "score_input")),

    // 5. Data (10)
    METRIC("metric", IntelligentUiCategory.DATA, setOf("stat", "kpi", "stat_card")),
    METRIC_GRID("metricGrid", IntelligentUiCategory.DATA, setOf("metric_grid", "metricgrid", "stats_grid", "kpi_grid")),
    PROGRESS("progress", IntelligentUiCategory.DATA, setOf("progress_bar", "progressbar", "meter")),
    STEP_PROGRESS("stepProgress", IntelligentUiCategory.DATA, setOf("step_progress", "stepprogress", "stage_progress")),
    COMPARISON_ROW("comparisonRow", IntelligentUiCategory.DATA, setOf("comparison_row", "comparisonrow", "compare_row")),
    PROS_CONS("prosCons", IntelligentUiCategory.DATA, setOf("pros_cons", "proscons", "tradeoffs")),
    TABLE("table", IntelligentUiCategory.DATA, setOf("data_table", "datatable")),
    RANKING_LIST("rankingList", IntelligentUiCategory.DATA, setOf("ranking_list", "rankinglist", "leaderboard", "rank_list")),
    BAR_CHART("barChart", IntelligentUiCategory.DATA, setOf("bar_chart", "barchart", "column_chart")),
    LINE_CHART("lineChart", IntelligentUiCategory.DATA, setOf("line_chart", "linechart", "trend_chart", "sparkline")),

    // 6. Media (8)
    ICON_LABEL("iconLabel", IntelligentUiCategory.MEDIA, setOf("icon_label", "iconlabel", "icon_row")),
    AVATAR_ITEM("avatarItem", IntelligentUiCategory.MEDIA, setOf("avatar_item", "avataritem", "avatar", "profile_row")),
    IMAGE_CARD("imageCard", IntelligentUiCategory.MEDIA, setOf("image_card", "imagecard", "image", "photo_card")),
    GALLERY_STRIP("galleryStrip", IntelligentUiCategory.MEDIA, setOf("gallery_strip", "gallerystrip", "gallery", "image_strip")),
    LINK_CARD("linkCard", IntelligentUiCategory.MEDIA, setOf("link_card", "linkcard", "link", "url_card")),
    FILE_CARD("fileCard", IntelligentUiCategory.MEDIA, setOf("file_card", "filecard", "file", "attachment_card")),
    CODE_PREVIEW("codePreview", IntelligentUiCategory.MEDIA, setOf("code_preview", "codepreview", "snippet_card")),
    MAP_PIN("mapPin", IntelligentUiCategory.MEDIA, setOf("map_pin", "mappin", "location", "place_card")),

    // 7. Task Semantic (8)
    OPTION_CARD("optionCard", IntelligentUiCategory.TASK_SEMANTIC, setOf("option_card", "optioncard", "option", "decision_option")),
    RECOMMENDATION_HERO("recommendationHero", IntelligentUiCategory.TASK_SEMANTIC, setOf("recommendation_hero", "recommendationhero", "recommendation", "verdict_card")),
    CONSTRAINT_ITEM("constraintItem", IntelligentUiCategory.TASK_SEMANTIC, setOf("constraint_item", "constraintitem", "constraint", "requirement_item")),
    RISK_ITEM("riskItem", IntelligentUiCategory.TASK_SEMANTIC, setOf("risk_item", "riskitem", "risk", "hazard_item")),
    CHECKLIST_ITEM("checklistItem", IntelligentUiCategory.TASK_SEMANTIC, setOf("checklist_item", "checklistitem", "checklist", "todo_item")),
    FLASHCARD("flashcard", IntelligentUiCategory.TASK_SEMANTIC, setOf("flash_card", "study_card", "flip_card")),
    QUIZ_ITEM("quizItem", IntelligentUiCategory.TASK_SEMANTIC, setOf("quiz_item", "quizitem", "quiz", "question", "quiz_question")),
    SCORE_SUMMARY("scoreSummary", IntelligentUiCategory.TASK_SEMANTIC, setOf("score_summary", "scoresummary", "study_summary")),

    // 8. Action & Container (8)
    ACTION_BUTTON("actionButton", IntelligentUiCategory.ACTION_CONTAINER, setOf("action_button", "actionbutton", "button")),
    ACTION_BAR("actionBar", IntelligentUiCategory.ACTION_CONTAINER, setOf("action_bar", "actionbar", "button_row")),
    CONFIRM_BAR("confirmBar", IntelligentUiCategory.ACTION_CONTAINER, setOf("confirm_bar", "confirmbar", "submit_bar")),
    FILTER_BAR("filterBar", IntelligentUiCategory.ACTION_CONTAINER, setOf("filter_bar", "filterbar", "filters")),
    SEARCH_FILTER("searchFilter", IntelligentUiCategory.ACTION_CONTAINER, setOf("search_filter", "searchfilter", "search_box")),
    FOOTNOTE("footnote", IntelligentUiCategory.ACTION_CONTAINER, setOf("note", "disclaimer")),
    SUMMARY_BOX("summaryBox", IntelligentUiCategory.ACTION_CONTAINER, setOf("summary_box", "summarybox", "summary", "takeaway")),
    CTA_CARD("ctaCard", IntelligentUiCategory.ACTION_CONTAINER, setOf("cta_card", "ctacard", "cta", "next_step_card"));

    val isNumericInput: Boolean
        get() = this == NUMBER_FIELD || this == SLIDER || this == STEPPER || this == RATING

    val isInput: Boolean
        get() = category == IntelligentUiCategory.INPUT || this == CHECKLIST_ITEM || this == SEARCH_FILTER || this == FILTER_BAR

    companion object {
        private val lookupMap: Map<String, IntelligentUiComponentKind> = buildMap {
            IntelligentUiComponentKind.entries.forEach { entry ->
                put(normalizeToken(entry.canonicalName), entry)
                put(normalizeToken(entry.name), entry)
                entry.aliases.forEach { alias ->
                    put(normalizeToken(alias), entry)
                }
            }
        }

        private fun normalizeToken(raw: String): String =
            raw.trim()
                .lowercase()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")

        fun fromRaw(raw: String?): IntelligentUiComponentKind? {
            if (raw.isNullOrBlank()) return null
            return lookupMap[normalizeToken(raw)]
        }
    }
}

enum class IntelligentUiTone {
    NEUTRAL,
    ACCENT,
    POSITIVE,
    WARNING,
    DANGER,
    INFO;

    companion object {
        fun fromRaw(raw: String?): IntelligentUiTone = when (raw?.trim()?.lowercase()) {
            "accent", "primary", "brand", "highlight" -> ACCENT
            "positive", "success", "good", "green", "ok", "safe" -> POSITIVE
            "warning", "warn", "caution", "amber", "yellow", "medium" -> WARNING
            "danger", "error", "critical", "negative", "red", "high" -> DANGER
            "info", "informative", "blue", "tip" -> INFO
            else -> NEUTRAL
        }
    }
}

enum class IntelligentUiEmphasis {
    SUBTLE,
    NORMAL,
    STRONG,
    HERO;

    companion object {
        fun fromRaw(raw: String?): IntelligentUiEmphasis = when (raw?.trim()?.lowercase()) {
            "subtle", "muted", "low", "secondary", "quiet" -> SUBTLE
            "strong", "high", "bold", "prominent" -> STRONG
            "hero", "display", "featured" -> HERO
            else -> NORMAL
        }
    }
}

enum class IntelligentUiDensity {
    COMPACT,
    COMFORTABLE,
    SPACIOUS;

    companion object {
        fun fromRaw(raw: String?): IntelligentUiDensity = when (raw?.trim()?.lowercase()) {
            "compact", "tight", "dense", "sm", "small" -> COMPACT
            "spacious", "relaxed", "loose", "lg", "large" -> SPACIOUS
            else -> COMFORTABLE
        }
    }
}

enum class IntelligentUiAlignment {
    START,
    CENTER,
    END;

    companion object {
        fun fromRaw(raw: String?): IntelligentUiAlignment = when (raw?.trim()?.lowercase()) {
            "center", "middle", "centered" -> CENTER
            "end", "right", "trailing" -> END
            else -> START
        }
    }
}

enum class IntelligentUiSizeToken {
    NONE,
    XS,
    SM,
    MD,
    LG;

    companion object {
        fun fromRaw(raw: String?, default: IntelligentUiSizeToken = MD): IntelligentUiSizeToken =
            when (raw?.trim()?.lowercase()) {
                "none", "0", "zero" -> NONE
                "xs", "extra_small", "tiny", "4" -> XS
                "sm", "small", "compact", "8" -> SM
                "md", "medium", "normal", "default", "12" -> MD
                "lg", "large", "spacious", "16", "20" -> LG
                else -> default
            }
    }
}

data class IntelligentUiStyle(
    val tone: IntelligentUiTone = IntelligentUiTone.NEUTRAL,
    val emphasis: IntelligentUiEmphasis = IntelligentUiEmphasis.NORMAL,
    val density: IntelligentUiDensity = IntelligentUiDensity.COMFORTABLE,
    val alignment: IntelligentUiAlignment = IntelligentUiAlignment.START,
    val spacing: IntelligentUiSizeToken = IntelligentUiSizeToken.MD,
    val padding: IntelligentUiSizeToken = IntelligentUiSizeToken.MD,
) {
    val spacingDp: Int?
        get() = when (spacing) {
            IntelligentUiSizeToken.NONE -> 0
            IntelligentUiSizeToken.XS -> 4
            IntelligentUiSizeToken.SM -> 6
            IntelligentUiSizeToken.MD -> null
            IntelligentUiSizeToken.LG -> 14
        }

    val paddingDp: Int?
        get() = when (padding) {
            IntelligentUiSizeToken.NONE -> 0
            IntelligentUiSizeToken.XS -> 6
            IntelligentUiSizeToken.SM -> 9
            IntelligentUiSizeToken.MD -> null
            IntelligentUiSizeToken.LG -> 16
        }
}

data class IntelligentUiKeyValue(
    val key: String,
    val value: String,
    val hint: String? = null,
    val valueFieldId: String? = null,
    val unit: String? = null,
    val tone: IntelligentUiTone = IntelligentUiTone.NEUTRAL,
)

data class IntelligentUiOptionItem(
    val id: String,
    val label: String,
    val value: String = label,
    val subtitle: String? = null,
    val description: String? = null,
    val badge: String? = null,
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val metrics: List<IntelligentUiKeyValue> = emptyList(),
    val recommended: Boolean = false,
    val tone: IntelligentUiTone = IntelligentUiTone.NEUTRAL,
)

data class IntelligentUiDataPoint(
    val id: String = "",
    val label: String,
    val value: Double,
    val max: Double? = null,
    val unit: String? = null,
    val delta: String? = null,
    val valueFieldId: String? = null,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val tone: IntelligentUiTone = IntelligentUiTone.NEUTRAL,
)

data class IntelligentUiTabItem(
    val id: String,
    val title: String,
    val badge: String? = null,
    val children: List<IntelligentUiComponentNode> = emptyList(),
) {
    val label: String
        get() = title
}

data class IntelligentUiComponentNode(
    val id: String,
    val kind: IntelligentUiComponentKind,
    val title: String? = null,
    val subtitle: String? = null,
    val text: String? = null,
    val badge: String? = null,
    val icon: String? = null,
    val url: String? = null,
    val code: String? = null,
    val language: String? = null,
    val placeholder: String? = null,
    val hint: String? = null,
    val explanation: String? = null,
    val unit: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val numericValue: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val valueFieldId: String? = null,
    val recommended: Boolean = false,
    val checked: Boolean? = null,
    val expandedByDefault: Boolean = false,
    val columnsCount: Int = 2,
    val options: List<IntelligentUiOptionItem> = emptyList(),
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    val keyValues: List<IntelligentUiKeyValue> = emptyList(),
    val tableHeaders: List<String> = emptyList(),
    val tableRows: List<List<String>> = emptyList(),
    val points: List<IntelligentUiDataPoint> = emptyList(),
    val tabs: List<IntelligentUiTabItem> = emptyList(),
    val children: List<IntelligentUiComponentNode> = emptyList(),
    val secondaryChildren: List<IntelligentUiComponentNode> = emptyList(),
    val correctOption: String? = null,
    val correctIndex: Int? = null,
    val difficulty: String? = null,
    val actionInstruction: String? = null,
    val style: IntelligentUiStyle = IntelligentUiStyle(),
) {
    val body: String? get() = text
    val front: String? get() = title
    val back: String? get() = text
    val valueText: String? get() = text
    val columns: Int? get() = columnsCount
    val headers: List<String> get() = tableHeaders
    val rows: List<List<String>> get() = tableRows
    val dataPoints: List<IntelligentUiDataPoint> get() = points
    val delta: String? get() = hint
    val leftLabel: String? get() = keyValues.getOrNull(0)?.key
    val rightLabel: String? get() = keyValues.getOrNull(0)?.value
    val imageUrl: String? get() = url
    val actionLabel: String? get() = title
    val actionPrompt: String? get() = actionInstruction
}

data class IntelligentUiDocument(
    val id: String,
    val kind: TaskUiKind,
    val title: String,
    val subtitle: String? = null,
    val summary: String? = null,
    val badge: String? = null,
    val style: IntelligentUiStyle = IntelligentUiStyle(),
    val options: List<IntelligentUiOptionItem> = emptyList(),
    val components: List<IntelligentUiComponentNode> = emptyList(),
    val submitLabel: String = "确认发送",
    val resetLabel: String = "重置",
    val promptTemplate: String? = null,
    val actions: List<GenerativeWidgetAction> = emptyList(),
) {
    /**
     * Flattens all component nodes in tree order (including tab children and split secondary children).
     */
    fun allNodes(): List<IntelligentUiComponentNode> {
        val result = ArrayList<IntelligentUiComponentNode>()
        fun visit(node: IntelligentUiComponentNode) {
            result += node
            node.children.forEach(::visit)
            node.secondaryChildren.forEach(::visit)
            node.tabs.forEach { tab -> tab.children.forEach(::visit) }
        }
        components.forEach(::visit)
        return result
    }

    fun inputNodes(): List<IntelligentUiComponentNode> =
        allNodes().filter { it.kind.isInput }

    fun flashcardNodes(): List<IntelligentUiComponentNode> =
        allNodes().filter { it.kind == IntelligentUiComponentKind.FLASHCARD }

    fun quizNodes(): List<IntelligentUiComponentNode> =
        allNodes().filter { it.kind == IntelligentUiComponentKind.QUIZ_ITEM }
}

/**
 * Lightweight streaming preview extracted while a `task-ui` fence is still streaming.
 */
data class IntelligentUiStreamingPreview(
    val kind: TaskUiKind?,
    val title: String?,
    val subtitle: String?,
    val detectedComponents: List<IntelligentUiComponentKind> = emptyList(),
    val itemCount: Int = 0,
)
