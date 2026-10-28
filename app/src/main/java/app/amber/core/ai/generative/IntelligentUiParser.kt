package app.amber.core.ai.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parser for `task-ui` / `intelligent-ui` / `adaptive-ui` JSON blocks (complete and streaming).
 */
object IntelligentUiParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val componentKeyRegex = Regex(
        """"(?:type|kind|component)"\s*:\s*"([^"]+)"""",
        RegexOption.IGNORE_CASE,
    )

    fun parseDocument(
        jsonText: String,
        copy: GenerativeWidgetCopy = GenerativeWidgetCopy.DEFAULT,
    ): IntelligentUiDocument? {
        val root = runCatching { json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: return null
        val payload = (root["spec"] as? JsonObject) ?: root

        val explicitKind = TaskUiKind.fromRaw(
            payload.stringOrNull("kind")
                ?: payload.stringOrNull("task_type")
                ?: payload.stringOrNull("taskType")
                ?: payload.stringOrNull("mode")
                ?: payload.stringOrNull("type")
                ?: root.stringOrNull("kind")
                ?: root.stringOrNull("task_type")
                ?: root.stringOrNull("type")
        )

        val style = parseStyle(payload["style"] as? JsonObject ?: payload)
        val topOptions = parseOptionList(payload["options"] ?: payload["choices"])

        val nodes = ArrayList<IntelligentUiComponentNode>()
        var nodeCounter = 0
        fun nextNodeId(prefix: String): String = "${prefix}_${++nodeCounter}"

        // 1. Top-level flashcards (for `study`)
        val flashcardsElement = payload["flashcards"]
            ?: payload["cards"]
            ?: (payload["study"] as? JsonObject)?.get("flashcards")
            ?: (payload["study"] as? JsonObject)?.get("cards")
        (flashcardsElement as? JsonArray)?.forEach { item ->
            parseFlashcardElement(item, ::nextNodeId)?.let(nodes::add)
        }

        // 2. Top-level quiz questions (for `study`)
        val quizElement = payload["quiz"]
            ?: payload["questions"]
            ?: (payload["study"] as? JsonObject)?.get("quiz")
            ?: (payload["study"] as? JsonObject)?.get("questions")
        when (quizElement) {
            is JsonArray -> quizElement.forEach { item ->
                parseQuizElement(item, ::nextNodeId)?.let(nodes::add)
            }
            is JsonObject -> {
                (quizElement["questions"] as? JsonArray ?: quizElement["items"] as? JsonArray)?.forEach { item ->
                    parseQuizElement(item, ::nextNodeId)?.let(nodes::add)
                }
            }
            else -> Unit
        }

        // 3. Top-level form fields
        (payload["fields"] as? JsonArray)?.forEach { fieldEl ->
            parseComponentNode(fieldEl, ::nextNodeId, defaultKind = IntelligentUiComponentKind.TEXT_FIELD)?.let(nodes::add)
        }

        // 4. Top-level tabs
        val topTabs = parseTabsArray(payload["tabs"] as? JsonArray, ::nextNodeId)
        if (topTabs.isNotEmpty()) {
            nodes += IntelligentUiComponentNode(
                id = nextNodeId("tabs"),
                kind = IntelligentUiComponentKind.TABS,
                tabs = topTabs,
            )
        }

        // 5. Top-level sections / components / body / items / children
        listOf("sections", "components", "body", "children").forEach { key ->
            (payload[key] as? JsonArray)?.forEach { childEl ->
                parseComponentNode(childEl, ::nextNodeId)?.let(nodes::add)
            }
        }
        if (nodes.isEmpty() && topOptions.isEmpty()) {
            (payload["items"] as? JsonArray)?.forEach { childEl ->
                parseComponentNode(childEl, ::nextNodeId)?.let(nodes::add)
            }
        }

        val inferredKind = explicitKind ?: inferTaskKind(payload, topOptions, nodes)
        val rawTitle = payload.stringOrNull("title")
            ?: root.stringOrNull("title")
            ?: payload.stringOrNull("heading")
            ?: payload.stringOrNull("question")
            ?: inferredKind.displayLabel
        val subtitle = payload.stringOrNull("subtitle")
            ?: payload.stringOrNull("description")
            ?: root.stringOrNull("subtitle")
        val summary = payload.stringOrNull("summary")
            ?: root.stringOrNull("summary")
        val badge = payload.stringOrNull("badge")
            ?: payload.stringOrNull("eyebrow")
            ?: payload.stringOrNull("category")

        // Must have at least one option or one component node to be a valid task-ui document
        if (topOptions.isEmpty() && nodes.isEmpty()) return null

        val docId = payload.stringOrNull("id")
            ?: root.stringOrNull("id")
            ?: "${inferredKind.wireName}_${rawTitle.hashCode()}_${nodes.size}_${topOptions.size}"

        val defaultSubmitLabel = when (inferredKind) {
            TaskUiKind.DECISION -> "确认方案"
            TaskUiKind.FORM -> "提交信息"
            TaskUiKind.EXPLORE -> "应用参数"
            TaskUiKind.STUDY -> "发送学习结果"
        }

        return IntelligentUiDocument(
            id = docId,
            kind = inferredKind,
            title = rawTitle.trim(),
            subtitle = subtitle?.trim()?.takeIf { it.isNotBlank() },
            summary = summary?.trim()?.takeIf { it.isNotBlank() },
            badge = badge?.trim()?.takeIf { it.isNotBlank() },
            style = style,
            options = topOptions,
            components = nodes,
            submitLabel = (
                payload.stringOrNull("submit_label")
                    ?: payload.stringOrNull("submitLabel")
                    ?: payload.stringOrNull("confirm_label")
                    ?: payload.stringOrNull("confirmLabel")
                )?.trim()?.takeIf { it.isNotBlank() } ?: defaultSubmitLabel,
            resetLabel = (
                payload.stringOrNull("reset_label")
                    ?: payload.stringOrNull("resetLabel")
                )?.trim()?.takeIf { it.isNotBlank() } ?: "重置",
            promptTemplate = (
                payload.stringOrNull("prompt_template")
                    ?: payload.stringOrNull("promptTemplate")
                    ?: payload.stringOrNull("instruction")
                )?.trim()?.takeIf { it.isNotBlank() },
            actions = parseActions(payload["actions"] ?: root["actions"], copy),
        )
    }

    /**
     * Parses a lightweight streaming preview from an incomplete JSON payload.
     */
    fun parseStreamingPreview(partialJson: String): IntelligentUiStreamingPreview {
        val kindRaw = extractJsonString(partialJson, "kind")
            ?: extractJsonString(partialJson, "task_type")
            ?: extractJsonString(partialJson, "taskType")
            ?: extractJsonString(partialJson, "mode")
        val kind = TaskUiKind.fromRaw(kindRaw) ?: when {
            "\"flashcards\"" in partialJson || "\"quiz\"" in partialJson || "\"flashcard\"" in partialJson -> TaskUiKind.STUDY
            "\"fields\"" in partialJson -> TaskUiKind.FORM
            "\"pros\"" in partialJson || "\"cons\"" in partialJson -> TaskUiKind.DECISION
            "\"tabs\"" in partialJson -> TaskUiKind.EXPLORE
            else -> null
        }
        val title = extractJsonString(partialJson, "title")
            ?: extractJsonString(partialJson, "heading")
        val subtitle = extractJsonString(partialJson, "subtitle")
            ?: extractJsonString(partialJson, "description")
            ?: extractJsonString(partialJson, "summary")

        val detectedKinds = componentKeyRegex.findAll(partialJson)
            .mapNotNull { match -> IntelligentUiComponentKind.fromRaw(match.groupValues[1]) }
            .distinct()
            .take(8)
            .toList()
        val itemCount = Regex(""""(?:id|question|front|label)"\s*:""").findAll(partialJson).count()

        return IntelligentUiStreamingPreview(
            kind = kind,
            title = title?.trim()?.takeIf { it.isNotBlank() },
            subtitle = subtitle?.trim()?.takeIf { it.isNotBlank() },
            detectedComponents = detectedKinds,
            itemCount = itemCount,
        )
    }

    private fun inferTaskKind(
        payload: JsonObject,
        topOptions: List<IntelligentUiOptionItem>,
        nodes: List<IntelligentUiComponentNode>,
    ): TaskUiKind {
        val allKinds = buildList {
            fun collect(node: IntelligentUiComponentNode) {
                add(node.kind)
                node.children.forEach(::collect)
                node.secondaryChildren.forEach(::collect)
                node.tabs.forEach { t -> t.children.forEach(::collect) }
            }
            nodes.forEach(::collect)
        }
        return when {
            allKinds.any {
                it == IntelligentUiComponentKind.FLASHCARD ||
                    it == IntelligentUiComponentKind.QUIZ_ITEM ||
                    it == IntelligentUiComponentKind.SCORE_SUMMARY
            } -> TaskUiKind.STUDY
            topOptions.isNotEmpty() || allKinds.any {
                it == IntelligentUiComponentKind.OPTION_CARD ||
                    it == IntelligentUiComponentKind.RECOMMENDATION_HERO
            } -> TaskUiKind.DECISION
            payload.containsKey("fields") || (
                allKinds.any { it.category == IntelligentUiCategory.INPUT } &&
                    allKinds.none {
                        it == IntelligentUiComponentKind.TABS ||
                            it == IntelligentUiComponentKind.BAR_CHART ||
                            it == IntelligentUiComponentKind.LINE_CHART
                    }
                ) -> TaskUiKind.FORM
            else -> TaskUiKind.EXPLORE
        }
    }

    private fun parseComponentNode(
        element: JsonElement?,
        nextId: (String) -> String,
        defaultKind: IntelligentUiComponentKind? = null,
    ): IntelligentUiComponentNode? {
        if (element == null) return null
        if (element is JsonPrimitive) {
            val content = element.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return IntelligentUiComponentNode(
                id = nextId("text"),
                kind = defaultKind ?: IntelligentUiComponentKind.TEXT,
                text = content,
            )
        }
        val obj = element as? JsonObject ?: return null
        val rawKind = obj.stringOrNull("kind")
            ?: obj.stringOrNull("type")
            ?: obj.stringOrNull("component")
        val kind = IntelligentUiComponentKind.fromRaw(rawKind)
            ?: defaultKind
            ?: inferNodeKindFromShape(obj)
            ?: return null

        val id = obj.stringOrNull("id")
            ?: obj.stringOrNull("field_id")
            ?: obj.stringOrNull("fieldId")
            ?: obj.stringOrNull("key")
            ?: obj.stringOrNull("name")
            ?: nextId(kind.canonicalName)

        val style = parseStyle(obj["style"] as? JsonObject ?: obj)
        val title = obj.stringOrNull("title")
            ?: obj.stringOrNull("label")
            ?: obj.stringOrNull("heading")
            ?: obj.stringOrNull("question")
            ?: obj.stringOrNull("front")
            ?: obj.stringOrNull("term")
        val subtitle = obj.stringOrNull("subtitle")
            ?: obj.stringOrNull("sublabel")
            ?: obj.stringOrNull("role")
            ?: obj.stringOrNull("date")
            ?: obj.stringOrNull("time")
        val text = obj.stringOrNull("text")
            ?: obj.stringOrNull("body")
            ?: obj.stringOrNull("content")
            ?: obj.stringOrNull("description")
            ?: obj.stringOrNull("back")
            ?: obj.stringOrNull("definition")
            ?: (if (!kind.isInput && kind != IntelligentUiComponentKind.METRIC && kind != IntelligentUiComponentKind.PROGRESS) {
                obj.stringOrNull("value")
            } else null)

        val valueFieldId = obj.stringOrNull("value_field_id")
            ?: obj.stringOrNull("valueFieldId")
            ?: obj.stringOrNull("bind_field_id")
            ?: obj.stringOrNull("bindFieldId")

        val numericVal = obj.doubleOrNull("value")
            ?: obj.doubleOrNull("numeric_value")
            ?: obj.doubleOrNull("numericValue")
            ?: obj.doubleOrNull("current")
            ?: obj.doubleOrNull("score")
            ?: obj.doubleOrNull("progress")

        val defaultVal = obj.stringOrNull("default_value")
            ?: obj.stringOrNull("defaultValue")
            ?: obj.stringOrNull("initial_value")
            ?: obj.stringOrNull("initialValue")
            ?: (if (kind.isInput) obj.stringOrNull("value") else null)

        val options = parseOptionList(obj["options"] ?: obj["choices"])
        val pros = parseStringList(obj["pros"] ?: obj["advantages"] ?: obj["strengths"])
        val cons = parseStringList(obj["cons"] ?: obj["disadvantages"] ?: obj["weaknesses"])

        val rawItems = obj["items"] ?: obj["bullets"] ?: obj["steps"]
        val stringItems = parseStringList(rawItems)
        val keyValues = parseKeyValues(
            obj["key_values"]
                ?: obj["keyValues"]
                ?: obj["pairs"]
                ?: obj["definitions"]
                ?: obj["metrics"]
                ?: (if (kind == IntelligentUiComponentKind.KEY_VALUE ||
                    kind == IntelligentUiComponentKind.DEFINITION_LIST ||
                    kind == IntelligentUiComponentKind.COMPARISON_ROW
                ) rawItems else null),
            singleKey = obj.stringOrNull("key") ?: obj.stringOrNull("left"),
            singleValue = obj.stringOrNull("value") ?: obj.stringOrNull("right"),
            singleHint = obj.stringOrNull("hint") ?: obj.stringOrNull("delta"),
            singleValueFieldId = valueFieldId,
            singleUnit = obj.stringOrNull("unit"),
        )

        val tableHeaders = parseStringList(obj["headers"] ?: obj["columns"])
        val tableRows = parseTableRows(obj["rows"] ?: (if (kind == IntelligentUiComponentKind.TABLE) obj["data"] else null), tableHeaders)

        val points = parseDataPoints(
            obj["points"]
                ?: obj["series"]
                ?: obj["bars"]
                ?: (if (kind == IntelligentUiComponentKind.BAR_CHART ||
                    kind == IntelligentUiComponentKind.LINE_CHART ||
                    kind == IntelligentUiComponentKind.RANKING_LIST
                ) obj["data"] ?: rawItems else null),
            nextId = nextId,
        )

        val tabs = parseTabsArray(obj["tabs"] as? JsonArray, nextId)

        val children = ArrayList<IntelligentUiComponentNode>()
        listOf("children", "components", "sections", "fields", "left", "primary").forEach { childKey ->
            (obj[childKey] as? JsonArray)?.forEach { childEl ->
                parseComponentNode(childEl, nextId)?.let(children::add)
            }
            (obj[childKey] as? JsonObject)?.let { singleObj ->
                parseComponentNode(singleObj, nextId)?.let(children::add)
            }
        }
        // If `items` or `cards` contains nested component objects (e.g., inside `section`, `grid`, `row`, `column`, `metricGrid`, `accordion`)
        val objectItemsSource = (rawItems as? JsonArray) ?: (obj["cards"] as? JsonArray)
        if (children.isEmpty() &&
            objectItemsSource != null &&
            kind !in setOf(
                IntelligentUiComponentKind.KEY_VALUE,
                IntelligentUiComponentKind.DEFINITION_LIST,
                IntelligentUiComponentKind.COMPARISON_ROW,
                IntelligentUiComponentKind.BAR_CHART,
                IntelligentUiComponentKind.LINE_CHART,
            )
        ) {
            val defaultChildKind = when (kind) {
                IntelligentUiComponentKind.METRIC_GRID -> IntelligentUiComponentKind.METRIC
                IntelligentUiComponentKind.ACCORDION -> IntelligentUiComponentKind.DISCLOSURE
                IntelligentUiComponentKind.GALLERY_STRIP -> IntelligentUiComponentKind.IMAGE_CARD
                IntelligentUiComponentKind.ACTION_BAR -> IntelligentUiComponentKind.ACTION_BUTTON
                else -> null
            }
            objectItemsSource.forEach { itemEl ->
                if (itemEl is JsonObject) {
                    parseComponentNode(itemEl, nextId, defaultKind = defaultChildKind)?.let(children::add)
                }
            }
        }

        val secondaryChildren = ArrayList<IntelligentUiComponentNode>()
        listOf("right", "secondary").forEach { secKey ->
            (obj[secKey] as? JsonArray)?.forEach { childEl ->
                parseComponentNode(childEl, nextId)?.let(secondaryChildren::add)
            }
            (obj[secKey] as? JsonObject)?.let { singleObj ->
                parseComponentNode(singleObj, nextId)?.let(secondaryChildren::add)
            }
        }

        val correctOption = obj.stringOrNull("answer")
            ?: obj.stringOrNull("correct_option")
            ?: obj.stringOrNull("correctOption")
            ?: obj.stringOrNull("correct_answer")
            ?: obj.stringOrNull("correctAnswer")
        val correctIndex = obj.intOrNull("correct_index")
            ?: obj.intOrNull("correctIndex")
            ?: obj.intOrNull("answer_index")
            ?: obj.intOrNull("answerIndex")

        return IntelligentUiComponentNode(
            id = id,
            kind = kind,
            title = title?.trim()?.takeIf { it.isNotBlank() },
            subtitle = subtitle?.trim()?.takeIf { it.isNotBlank() },
            text = text?.trim()?.takeIf { it.isNotBlank() },
            badge = (obj.stringOrNull("badge") ?: obj.stringOrNull("tag") ?: obj.stringOrNull("status") ?: obj.stringOrNull("pill"))
                ?.trim()?.takeIf { it.isNotBlank() },
            icon = obj.stringOrNull("icon")?.trim()?.takeIf { it.isNotBlank() },
            url = (obj.stringOrNull("url") ?: obj.stringOrNull("href") ?: obj.stringOrNull("image_url") ?: obj.stringOrNull("imageUrl"))
                ?.trim()?.takeIf { it.isNotBlank() },
            code = (obj.stringOrNull("code") ?: obj.stringOrNull("snippet"))?.takeIf { it.isNotBlank() },
            language = (obj.stringOrNull("language") ?: obj.stringOrNull("lang"))?.trim()?.takeIf { it.isNotBlank() },
            placeholder = obj.stringOrNull("placeholder")?.trim()?.takeIf { it.isNotBlank() },
            hint = (obj.stringOrNull("hint") ?: obj.stringOrNull("helper") ?: obj.stringOrNull("delta") ?: obj.stringOrNull("trend"))
                ?.trim()?.takeIf { it.isNotBlank() },
            explanation = (obj.stringOrNull("explanation") ?: obj.stringOrNull("rationale") ?: obj.stringOrNull("analysis"))
                ?.trim()?.takeIf { it.isNotBlank() },
            unit = obj.stringOrNull("unit")?.trim(),
            prefix = obj.stringOrNull("prefix")?.trim(),
            suffix = obj.stringOrNull("suffix")?.trim(),
            required = obj.booleanOrNull("required") ?: false,
            defaultValue = defaultVal,
            numericValue = numericVal,
            min = obj.doubleOrNull("min") ?: obj.doubleOrNull("minimum"),
            max = obj.doubleOrNull("max") ?: obj.doubleOrNull("maximum") ?: obj.doubleOrNull("total"),
            step = obj.doubleOrNull("step"),
            scale = obj.doubleOrNull("scale") ?: obj.doubleOrNull("multiplier") ?: 1.0,
            offset = obj.doubleOrNull("offset") ?: 0.0,
            valueFieldId = valueFieldId?.trim()?.takeIf { it.isNotBlank() },
            recommended = obj.booleanOrNull("recommended") ?: false,
            checked = obj.booleanOrNull("checked") ?: obj.booleanOrNull("selected") ?: obj.booleanOrNull("enabled"),
            expandedByDefault = obj.booleanOrNull("expanded") ?: obj.booleanOrNull("open") ?: false,
            columnsCount = (obj.intOrNull("columns") ?: obj.intOrNull("cols") ?: 2).coerceIn(1, 4),
            options = options,
            pros = pros,
            cons = cons,
            items = stringItems,
            keyValues = keyValues,
            tableHeaders = tableHeaders,
            tableRows = tableRows,
            points = points,
            tabs = tabs,
            children = children,
            secondaryChildren = secondaryChildren,
            correctOption = correctOption?.trim()?.takeIf { it.isNotBlank() },
            correctIndex = correctIndex,
            difficulty = obj.stringOrNull("difficulty")?.trim()?.takeIf { it.isNotBlank() },
            actionInstruction = (obj.stringOrNull("instruction") ?: obj.stringOrNull("prompt") ?: obj.stringOrNull("action"))
                ?.trim()?.takeIf { it.isNotBlank() },
            style = style,
        )
    }

    private fun inferNodeKindFromShape(obj: JsonObject): IntelligentUiComponentKind? = when {
        obj.containsKey("front") && obj.containsKey("back") -> IntelligentUiComponentKind.FLASHCARD
        obj.containsKey("question") && (obj.containsKey("options") || obj.containsKey("choices")) &&
            (obj.containsKey("answer") || obj.containsKey("correct_index") || obj.containsKey("correctIndex") || obj.containsKey("explanation")) ->
            IntelligentUiComponentKind.QUIZ_ITEM
        obj.containsKey("pros") || obj.containsKey("cons") ->
            if (obj.containsKey("title") || obj.containsKey("label")) IntelligentUiComponentKind.OPTION_CARD else IntelligentUiComponentKind.PROS_CONS
        obj.containsKey("tabs") -> IntelligentUiComponentKind.TABS
        obj.containsKey("headers") || obj.containsKey("rows") -> IntelligentUiComponentKind.TABLE
        obj.containsKey("min") && obj.containsKey("max") -> IntelligentUiComponentKind.SLIDER
        obj.containsKey("options") || obj.containsKey("choices") -> IntelligentUiComponentKind.RADIO_GROUP
        obj.containsKey("code") -> IntelligentUiComponentKind.CODE_BLOCK
        obj.containsKey("children") || obj.containsKey("components") -> IntelligentUiComponentKind.SECTION
        obj.containsKey("title") || obj.containsKey("text") || obj.containsKey("body") -> IntelligentUiComponentKind.CARD
        else -> null
    }

    private fun parseFlashcardElement(
        element: JsonElement,
        nextId: (String) -> String,
    ): IntelligentUiComponentNode? {
        val obj = element as? JsonObject ?: return null
        val front = obj.stringOrNull("front")
            ?: obj.stringOrNull("question")
            ?: obj.stringOrNull("term")
            ?: obj.stringOrNull("title")
            ?: return null
        val back = obj.stringOrNull("back")
            ?: obj.stringOrNull("answer")
            ?: obj.stringOrNull("definition")
            ?: obj.stringOrNull("text")
            ?: return null
        return IntelligentUiComponentNode(
            id = obj.stringOrNull("id") ?: nextId("flashcard"),
            kind = IntelligentUiComponentKind.FLASHCARD,
            title = front.trim(),
            text = back.trim(),
            badge = (obj.stringOrNull("tag") ?: obj.stringOrNull("badge") ?: obj.stringOrNull("category"))?.trim(),
            hint = obj.stringOrNull("hint")?.trim(),
            explanation = obj.stringOrNull("example") ?: obj.stringOrNull("explanation"),
            style = parseStyle(obj["style"] as? JsonObject ?: obj),
        )
    }

    private fun parseQuizElement(
        element: JsonElement,
        nextId: (String) -> String,
    ): IntelligentUiComponentNode? {
        val obj = element as? JsonObject ?: return null
        val question = obj.stringOrNull("question")
            ?: obj.stringOrNull("title")
            ?: obj.stringOrNull("prompt")
            ?: return null
        val options = parseOptionList(obj["options"] ?: obj["choices"])
        if (options.isEmpty()) return null
        return IntelligentUiComponentNode(
            id = obj.stringOrNull("id") ?: nextId("quiz"),
            kind = IntelligentUiComponentKind.QUIZ_ITEM,
            title = question.trim(),
            subtitle = obj.stringOrNull("subtitle")?.trim(),
            badge = (obj.stringOrNull("badge") ?: obj.stringOrNull("tag") ?: obj.stringOrNull("difficulty"))?.trim(),
            hint = obj.stringOrNull("hint")?.trim(),
            explanation = (obj.stringOrNull("explanation") ?: obj.stringOrNull("rationale") ?: obj.stringOrNull("analysis"))?.trim(),
            options = options,
            correctOption = (
                obj.stringOrNull("answer")
                    ?: obj.stringOrNull("correct_option")
                    ?: obj.stringOrNull("correctOption")
                    ?: obj.stringOrNull("correct_answer")
                    ?: obj.stringOrNull("correctAnswer")
                )?.trim(),
            correctIndex = obj.intOrNull("correct_index")
                ?: obj.intOrNull("correctIndex")
                ?: obj.intOrNull("answer_index")
                ?: obj.intOrNull("answerIndex"),
            difficulty = obj.stringOrNull("difficulty")?.trim(),
            style = parseStyle(obj["style"] as? JsonObject ?: obj),
        )
    }

    private fun parseOptionList(element: JsonElement?): List<IntelligentUiOptionItem> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, item ->
            when (item) {
                is JsonPrimitive -> {
                    val label = item.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    IntelligentUiOptionItem(id = "opt_$index", label = label, value = label)
                }
                is JsonObject -> {
                    val label = item.stringOrNull("label")
                        ?: item.stringOrNull("title")
                        ?: item.stringOrNull("name")
                        ?: item.stringOrNull("text")
                        ?: item.stringOrNull("value")
                        ?: return@mapIndexedNotNull null
                    val id = item.stringOrNull("id")
                        ?: item.stringOrNull("key")
                        ?: item.stringOrNull("value")
                        ?: "opt_${index + 1}_${label.hashCode()}"
                    val value = item.stringOrNull("value") ?: id
                    IntelligentUiOptionItem(
                        id = id,
                        label = label.trim(),
                        value = value.trim(),
                        subtitle = (item.stringOrNull("subtitle") ?: item.stringOrNull("summary") ?: item.stringOrNull("price"))?.trim(),
                        description = (item.stringOrNull("description") ?: item.stringOrNull("body") ?: item.stringOrNull("rationale"))?.trim(),
                        badge = (item.stringOrNull("badge") ?: item.stringOrNull("tag") ?: item.stringOrNull("score"))?.trim(),
                        pros = parseStringList(item["pros"] ?: item["advantages"]),
                        cons = parseStringList(item["cons"] ?: item["disadvantages"]),
                        metrics = parseKeyValues(item["metrics"] ?: item["specs"]),
                        recommended = item.booleanOrNull("recommended") ?: false,
                        tone = IntelligentUiTone.fromRaw(item.stringOrNull("tone")),
                    )
                }
                else -> null
            }
        }
    }

    private fun parseTabsArray(
        array: JsonArray?,
        nextId: (String) -> String,
    ): List<IntelligentUiTabItem> {
        if (array == null) return emptyList()
        return array.mapIndexedNotNull { index, item ->
            val obj = item as? JsonObject ?: return@mapIndexedNotNull null
            val title = obj.stringOrNull("title")
                ?: obj.stringOrNull("label")
                ?: obj.stringOrNull("name")
                ?: "Tab ${index + 1}"
            val id = obj.stringOrNull("id") ?: obj.stringOrNull("key") ?: "tab_${index + 1}"
            val children = ArrayList<IntelligentUiComponentNode>()
            listOf("children", "components", "sections", "items", "body").forEach { key ->
                (obj[key] as? JsonArray)?.forEach { childEl ->
                    parseComponentNode(childEl, nextId)?.let(children::add)
                }
            }
            if (children.isEmpty()) {
                obj.stringOrNull("content")?.takeIf { it.isNotBlank() }?.let { content ->
                    children += IntelligentUiComponentNode(
                        id = nextId("tab_text"),
                        kind = IntelligentUiComponentKind.TEXT,
                        text = content,
                    )
                }
            }
            IntelligentUiTabItem(
                id = id,
                title = title.trim(),
                badge = obj.stringOrNull("badge")?.trim(),
                children = children,
            )
        }
    }

    private fun parseStringList(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                is JsonObject -> (
                    item.stringOrNull("text")
                        ?: item.stringOrNull("label")
                        ?: item.stringOrNull("title")
                    )?.trim()?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    private fun parseKeyValues(
        element: JsonElement?,
        singleKey: String? = null,
        singleValue: String? = null,
        singleHint: String? = null,
        singleValueFieldId: String? = null,
        singleUnit: String? = null,
    ): List<IntelligentUiKeyValue> {
        val list = ArrayList<IntelligentUiKeyValue>()
        when (element) {
            is JsonArray -> element.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val k = obj.stringOrNull("key")
                    ?: obj.stringOrNull("label")
                    ?: obj.stringOrNull("term")
                    ?: obj.stringOrNull("name")
                    ?: obj.stringOrNull("left")
                    ?: return@forEach
                val v = obj.stringOrNull("value")
                    ?: obj.stringOrNull("definition")
                    ?: obj.stringOrNull("right")
                    ?: obj.stringOrNull("text")
                    ?: ""
                list += IntelligentUiKeyValue(
                    key = k.trim(),
                    value = v.trim(),
                    hint = (obj.stringOrNull("hint") ?: obj.stringOrNull("delta") ?: obj.stringOrNull("note"))?.trim(),
                    valueFieldId = obj.stringOrNull("value_field_id") ?: obj.stringOrNull("valueFieldId"),
                    unit = obj.stringOrNull("unit")?.trim(),
                    tone = IntelligentUiTone.fromRaw(obj.stringOrNull("tone")),
                )
            }
            is JsonObject -> element.entries.forEach { (k, v) ->
                val textVal = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                list += IntelligentUiKeyValue(key = k, value = textVal)
            }
            else -> Unit
        }
        if (list.isEmpty() && !singleKey.isNullOrBlank() && (!singleValue.isNullOrBlank() || !singleValueFieldId.isNullOrBlank())) {
            list += IntelligentUiKeyValue(
                key = singleKey.trim(),
                value = singleValue?.trim().orEmpty(),
                hint = singleHint?.trim(),
                valueFieldId = singleValueFieldId,
                unit = singleUnit,
            )
        }
        return list
    }

    private fun parseTableRows(
        element: JsonElement?,
        headers: List<String>,
    ): List<List<String>> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { rowEl ->
            when (rowEl) {
                is JsonArray -> rowEl.map { cell ->
                    (cell as? JsonPrimitive)?.contentOrNull?.trim() ?: cell.toString()
                }
                is JsonObject -> {
                    if (headers.isNotEmpty()) {
                        headers.map { header ->
                            rowEl.stringOrNull(header)
                                ?: rowEl.entries.firstOrNull { it.key.equals(header, ignoreCase = true) }
                                    ?.value?.let { (it as? JsonPrimitive)?.contentOrNull }
                                ?: ""
                        }
                    } else {
                        rowEl.values.map { cell ->
                            (cell as? JsonPrimitive)?.contentOrNull?.trim() ?: cell.toString()
                        }
                    }
                }
                else -> null
            }
        }
    }

    private fun parseDataPoints(
        element: JsonElement?,
        nextId: (String) -> String,
    ): List<IntelligentUiDataPoint> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, item ->
            when (item) {
                is JsonPrimitive -> {
                    val v = item.doubleOrNull ?: return@mapIndexedNotNull null
                    IntelligentUiDataPoint(
                        id = nextId("pt"),
                        label = "#${index + 1}",
                        value = v,
                    )
                }
                is JsonObject -> {
                    val label = item.stringOrNull("label")
                        ?: item.stringOrNull("name")
                        ?: item.stringOrNull("x")
                        ?: item.stringOrNull("title")
                        ?: "#${index + 1}"
                    val value = item.doubleOrNull("value")
                        ?: item.doubleOrNull("y")
                        ?: item.doubleOrNull("score")
                        ?: item.doubleOrNull("amount")
                        ?: 0.0
                    IntelligentUiDataPoint(
                        id = item.stringOrNull("id") ?: nextId("pt"),
                        label = label.trim(),
                        value = value,
                        max = item.doubleOrNull("max") ?: item.doubleOrNull("total"),
                        unit = item.stringOrNull("unit")?.trim(),
                        delta = (item.stringOrNull("delta") ?: item.stringOrNull("badge") ?: item.stringOrNull("subtitle"))?.trim(),
                        valueFieldId = (item.stringOrNull("value_field_id") ?: item.stringOrNull("valueFieldId"))?.trim(),
                        scale = item.doubleOrNull("scale") ?: item.doubleOrNull("multiplier") ?: 1.0,
                        offset = item.doubleOrNull("offset") ?: 0.0,
                        tone = IntelligentUiTone.fromRaw(item.stringOrNull("tone")),
                    )
                }
                else -> null
            }
        }
    }

    private fun parseStyle(obj: JsonObject?): IntelligentUiStyle {
        if (obj == null) return IntelligentUiStyle()
        return IntelligentUiStyle(
            tone = IntelligentUiTone.fromRaw(obj.stringOrNull("tone") ?: obj.stringOrNull("variant") ?: obj.stringOrNull("severity")),
            emphasis = IntelligentUiEmphasis.fromRaw(obj.stringOrNull("emphasis") ?: obj.stringOrNull("prominence")),
            density = IntelligentUiDensity.fromRaw(obj.stringOrNull("density") ?: obj.stringOrNull("size")),
            alignment = IntelligentUiAlignment.fromRaw(obj.stringOrNull("alignment") ?: obj.stringOrNull("align")),
            spacing = IntelligentUiSizeToken.fromRaw(obj.stringOrNull("spacing") ?: obj.stringOrNull("gap")),
            padding = IntelligentUiSizeToken.fromRaw(obj.stringOrNull("padding")),
        )
    }

    private fun parseActions(
        element: JsonElement?,
        copy: GenerativeWidgetCopy,
    ): List<GenerativeWidgetAction> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val label = obj.stringOrNull("label")?.trim()?.takeIf { it.length in 1..24 } ?: return@mapNotNull null
            val instruction = (obj.stringOrNull("instruction") ?: obj.stringOrNull("prompt") ?: label)
                .trim()
                .takeIf { it.length in 1..240 }
                ?: return@mapNotNull null
            val id = (obj.stringOrNull("id") ?: label).trim().take(48)
            GenerativeWidgetAction(
                id = id,
                label = label,
                instruction = instruction,
                promptCopy = copy,
            )
        }.take(3)
    }

    private fun extractJsonString(source: String, key: String): String? {
        val regex = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"?""")
        val match = regex.find(source) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.doubleOrNull(key: String): Double? =
        (this[key] as? JsonPrimitive)?.let { prim ->
            prim.doubleOrNull ?: prim.contentOrNull?.trim()?.toDoubleOrNull()
        }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { prim ->
            prim.intOrNull ?: prim.contentOrNull?.trim()?.toIntOrNull()
        }

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { prim ->
            prim.booleanOrNull ?: prim.contentOrNull?.trim()?.lowercase()?.let {
                when (it) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            }
        }
}
