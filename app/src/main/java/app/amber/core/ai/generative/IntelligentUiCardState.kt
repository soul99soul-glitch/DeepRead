package app.amber.core.ai.generative

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.roundToInt

enum class FlashcardMastery(val label: String) {
    UNREVIEWED("未标记"),
    MASTERED("已掌握"),
    REVIEW_AGAIN("需复习");

    companion object {
        val UNSEEN: FlashcardMastery get() = UNREVIEWED
        val REVIEW: FlashcardMastery get() = REVIEW_AGAIN
    }
}

/**
 * Interactive state for a single `IntelligentUiDocument` instance, preserved across LazyColumn scroll
 * and recomposition via [IntelligentUiStateStore].
 */
@Stable
class IntelligentUiCardState(
    val cardKey: String,
    document: IntelligentUiDocument,
) {
    constructor(document: IntelligentUiDocument) : this(cardKey = document.id, document = document)
    private var boundDocumentId: String = document.id

    var selectedDecisionOptionId by mutableStateOf<String?>(null)
    val fieldValues = mutableStateMapOf<String, String>()
    val multiFieldValues = mutableStateMapOf<String, Set<String>>()
    val activeTabByNodeId = mutableStateMapOf<String, String>()
    val expandedByNodeId = mutableStateMapOf<String, Boolean>()

    // Study state
    var currentFlashcardIndex by mutableStateOf(0)
    val flippedFlashcards = mutableStateMapOf<String, Boolean>()
    val flashcardMastery = mutableStateMapOf<String, FlashcardMastery>()
    val quizSelectedOption = mutableStateMapOf<String, String>()
    var retryWrongOnly by mutableStateOf(false)
    val retryWrongQuestionIds = mutableStateMapOf<String, Boolean>()

    // Submission state
    var submittedSummary by mutableStateOf<String?>(null)

    init {
        seedDefaults(document, overwriteExisting = true)
    }

    fun syncWithDocument(document: IntelligentUiDocument) {
        val changedDoc = boundDocumentId != document.id
        boundDocumentId = document.id
        seedDefaults(document, overwriteExisting = changedDoc)
    }

    fun seedDefaults(document: IntelligentUiDocument, overwriteExisting: Boolean = false) {
        val recommendedOption = document.options.firstOrNull { it.recommended }
            ?: document.allNodes()
                .firstOrNull { it.kind == IntelligentUiComponentKind.OPTION_CARD && it.recommended }
                ?.let { IntelligentUiOptionItem(id = it.id, label = it.title ?: it.id) }
        if (overwriteExisting || selectedDecisionOptionId == null) {
            selectedDecisionOptionId = recommendedOption?.id ?: document.options.firstOrNull()?.id
        }

        document.allNodes().forEach { node ->
            when (node.kind) {
                IntelligentUiComponentKind.TABS -> {
                    val firstTabId = node.tabs.firstOrNull()?.id
                    if (firstTabId != null && (overwriteExisting || !activeTabByNodeId.containsKey(node.id))) {
                        activeTabByNodeId[node.id] = firstTabId
                    }
                }
                IntelligentUiComponentKind.ACCORDION,
                IntelligentUiComponentKind.DISCLOSURE -> {
                    if (overwriteExisting || !expandedByNodeId.containsKey(node.id)) {
                        expandedByNodeId[node.id] = node.expandedByDefault
                    }
                }
                IntelligentUiComponentKind.MULTI_SELECT,
                IntelligentUiComponentKind.CHIP_GROUP -> {
                    if (overwriteExisting || !multiFieldValues.containsKey(node.id)) {
                        val initialSet = node.defaultValue
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() }
                            ?.toSet()
                            .orEmpty()
                        multiFieldValues[node.id] = initialSet
                    }
                }
                IntelligentUiComponentKind.TOGGLE,
                IntelligentUiComponentKind.CHECKBOX,
                IntelligentUiComponentKind.CHECKLIST_ITEM -> {
                    if (overwriteExisting || !fieldValues.containsKey(node.id)) {
                        val initialBool = node.checked
                            ?: node.defaultValue?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }
                            ?: false
                        fieldValues[node.id] = initialBool.toString()
                    }
                }
                IntelligentUiComponentKind.NUMBER_FIELD,
                IntelligentUiComponentKind.SLIDER,
                IntelligentUiComponentKind.STEPPER,
                IntelligentUiComponentKind.RATING -> {
                    if (overwriteExisting || !fieldValues.containsKey(node.id)) {
                        val initialNumber = node.numericValue
                            ?: node.defaultValue?.toDoubleOrNull()
                            ?: node.min
                            ?: if (node.kind == IntelligentUiComponentKind.RATING) 4.0 else 0.0
                        fieldValues[node.id] = formatNumber(initialNumber)
                    }
                }
                else -> {
                    if (node.kind.isInput && (overwriteExisting || !fieldValues.containsKey(node.id))) {
                        val initial = node.defaultValue
                            ?: node.options.firstOrNull { it.recommended }?.value
                            ?: if (node.kind == IntelligentUiComponentKind.RADIO_GROUP ||
                                node.kind == IntelligentUiComponentKind.SEGMENTED_CONTROL ||
                                node.kind == IntelligentUiComponentKind.SELECT
                            ) {
                                node.options.firstOrNull()?.value.orEmpty()
                            } else {
                                ""
                            }
                        fieldValues[node.id] = initial
                    }
                }
            }
        }
    }

    fun reset(document: IntelligentUiDocument) {
        selectedDecisionOptionId = null
        fieldValues.clear()
        multiFieldValues.clear()
        flippedFlashcards.clear()
        flashcardMastery.clear()
        quizSelectedOption.clear()
        retryWrongOnly = false
        retryWrongQuestionIds.clear()
        currentFlashcardIndex = 0
        submittedSummary = null
        seedDefaults(document, overwriteExisting = true)
    }

    fun setFieldValue(fieldId: String, value: String) {
        fieldValues[fieldId] = value
        submittedSummary = null
    }

    fun setNumericFieldValue(fieldId: String, value: Double, min: Double? = null, max: Double? = null, step: Double? = null) {
        var clamped = value
        if (min != null) clamped = clamped.coerceAtLeast(min)
        if (max != null) clamped = clamped.coerceAtMost(max)
        if (step != null && step > 0.0) {
            val base = min ?: 0.0
            val steps = ((clamped - base) / step).roundToInt()
            clamped = base + steps * step
            if (min != null) clamped = clamped.coerceAtLeast(min)
            if (max != null) clamped = clamped.coerceAtMost(max)
        }
        fieldValues[fieldId] = formatNumber(clamped)
        submittedSummary = null
    }

    fun toggleMultiOption(fieldId: String, optionValue: String) {
        val current = multiFieldValues[fieldId].orEmpty().toMutableSet()
        if (!current.add(optionValue)) {
            current.remove(optionValue)
        }
        multiFieldValues[fieldId] = current
        submittedSummary = null
    }

    /**
     * Resolves a bound numeric value for `value_field_id` (`valueFieldId`) across the document.
     */
    fun boundNumericValue(
        document: IntelligentUiDocument,
        valueFieldId: String?,
        fallback: Double? = null,
        scale: Double = 1.0,
        offset: Double = 0.0,
    ): Double? {
        if (valueFieldId.isNullOrBlank()) return fallback
        val rawText = fieldValues[valueFieldId]?.trim()
        val parsed = rawText?.toDoubleOrNull()
            ?: document.allNodes().firstOrNull { it.id == valueFieldId }?.let { source ->
                source.numericValue ?: source.defaultValue?.toDoubleOrNull() ?: source.min
            }
            ?: fallback
            ?: return null
        return parsed * scale + offset
    }

    /**
     * Validates a single input node; returns a non-null human-readable error message when invalid.
     */
    fun validationErrorFor(node: IntelligentUiComponentNode): String? {
        if (!node.kind.isInput) return null
        val label = node.title?.takeIf { it.isNotBlank() } ?: node.id
        return when {
            node.kind == IntelligentUiComponentKind.MULTI_SELECT ||
                node.kind == IntelligentUiComponentKind.CHIP_GROUP -> {
                val selected = multiFieldValues[node.id].orEmpty()
                if (node.required && selected.isEmpty()) "请选择「$label」" else null
            }
            node.kind.isNumericInput -> {
                val raw = fieldValues[node.id]?.trim().orEmpty()
                if (raw.isEmpty()) {
                    return if (node.required) "请填写「$label」" else null
                }
                val num = raw.toDoubleOrNull()
                if (num == null || !num.isFinite()) {
                    return "「$label」请输入有效数字"
                }
                val min = node.min
                val max = node.max
                if (min != null && num < min - 1e-9) {
                    return "「$label」不能小于 ${formatNumber(min)}${node.unit.orEmpty()}"
                }
                if (max != null && num > max + 1e-9) {
                    return "「$label」不能大于 ${formatNumber(max)}${node.unit.orEmpty()}"
                }
                val step = node.step
                if (step != null && step > 0.0 && node.kind == IntelligentUiComponentKind.NUMBER_FIELD) {
                    val base = min ?: 0.0
                    val quotient = (num - base) / step
                    if (abs(quotient - quotient.roundToInt()) > 1e-5) {
                        return "「$label」步进需为 ${formatNumber(step)} 的倍数"
                    }
                }
                null
            }
            node.kind == IntelligentUiComponentKind.TOGGLE ||
                node.kind == IntelligentUiComponentKind.CHECKBOX ||
                node.kind == IntelligentUiComponentKind.CHECKLIST_ITEM -> {
                val checked = fieldValues[node.id] == "true"
                if (node.required && !checked) "请确认「$label」" else null
            }
            else -> {
                val value = fieldValues[node.id]?.trim().orEmpty()
                if (node.required && value.isEmpty()) "请填写「$label」" else null
            }
        }
    }

    /**
     * Returns all validation errors across the document's input nodes (and decision selection).
     */
    fun validationErrors(document: IntelligentUiDocument): List<String> {
        val errors = ArrayList<String>()
        if (document.kind == TaskUiKind.DECISION) {
            val allDecisionOptions = buildList {
                addAll(document.options)
                document.allNodes()
                    .filter { it.kind == IntelligentUiComponentKind.OPTION_CARD }
                    .forEach { node ->
                        add(IntelligentUiOptionItem(id = node.id, label = node.title ?: node.id))
                    }
            }
            if (allDecisionOptions.isNotEmpty() && selectedDecisionOptionId.isNullOrBlank()) {
                errors += "请先选择一个方案"
            }
        }
        document.inputNodes().forEach { node ->
            validationErrorFor(node)?.let(errors::add)
        }
        return errors
    }

    // Study helpers
    fun isQuizQuestionAnswered(questionNode: IntelligentUiComponentNode): Boolean =
        !quizSelectedOption[questionNode.id].isNullOrBlank()

    fun isQuizQuestionCorrect(questionNode: IntelligentUiComponentNode): Boolean {
        val selected = quizSelectedOption[questionNode.id]?.trim() ?: return false
        val correctIndex = questionNode.correctIndex
        if (correctIndex != null && correctIndex in questionNode.options.indices) {
            val expectedOption = questionNode.options[correctIndex]
            if (selected.equals(expectedOption.id, ignoreCase = true) ||
                selected.equals(expectedOption.value, ignoreCase = true) ||
                selected.equals(expectedOption.label, ignoreCase = true)
            ) {
                return true
            }
        }
        val expected = questionNode.correctOption?.trim() ?: return false
        if (selected.equals(expected, ignoreCase = true)) return true
        val matchedOption = questionNode.options.firstOrNull { opt ->
            opt.id.equals(selected, ignoreCase = true) ||
                opt.value.equals(selected, ignoreCase = true) ||
                opt.label.equals(selected, ignoreCase = true)
        }
        return matchedOption != null && (
            matchedOption.id.equals(expected, ignoreCase = true) ||
                matchedOption.value.equals(expected, ignoreCase = true) ||
                matchedOption.label.equals(expected, ignoreCase = true)
            )
    }

    fun startRetryWrongQuestions(quizNodes: List<IntelligentUiComponentNode>) {
        val wrongIds = quizNodes
            .filter { isQuizQuestionAnswered(it) && !isQuizQuestionCorrect(it) }
            .map { it.id }
        if (wrongIds.isEmpty()) return
        retryWrongQuestionIds.clear()
        wrongIds.forEach { id ->
            retryWrongQuestionIds[id] = true
            quizSelectedOption.remove(id)
        }
        retryWrongOnly = true
    }

    fun exitRetryWrongQuestions() {
        retryWrongOnly = false
        retryWrongQuestionIds.clear()
    }

    /**
     * Builds a clean user prompt representing the user's selections/inputs/study results
     * to send back into the conversation via `onGenerativeWidgetAction`.
     */
    fun buildSubmissionPrompt(document: IntelligentUiDocument): String {
        val lines = ArrayList<String>()
        val header = "【${document.kind.displayLabel} · ${document.title}】"
        lines += header

        when (document.kind) {
            TaskUiKind.DECISION -> {
                val optionPool = buildList {
                    addAll(document.options)
                    document.allNodes()
                        .filter { it.kind == IntelligentUiComponentKind.OPTION_CARD }
                        .forEach { node ->
                            if (none { it.id == node.id }) {
                                add(
                                    IntelligentUiOptionItem(
                                        id = node.id,
                                        label = node.title ?: node.id,
                                        subtitle = node.subtitle,
                                    )
                                )
                            }
                        }
                }
                val chosen = optionPool.firstOrNull { it.id == selectedDecisionOptionId }
                if (chosen != null) {
                    lines += "- 已选方案：${chosen.label}${chosen.subtitle?.let { "（$it）" }.orEmpty()}"
                }
                appendInputSummaryLines(document, lines)
            }
            TaskUiKind.FORM,
            TaskUiKind.EXPLORE -> {
                appendInputSummaryLines(document, lines)
                // Also include any bound metric/progress snapshots so the model sees computed results
                document.allNodes()
                    .filter {
                        (it.kind == IntelligentUiComponentKind.METRIC ||
                            it.kind == IntelligentUiComponentKind.PROGRESS ||
                            it.kind == IntelligentUiComponentKind.STEP_PROGRESS) &&
                            !it.valueFieldId.isNullOrBlank()
                    }
                    .forEach { metricNode ->
                        val resolved = boundNumericValue(
                            document = document,
                            valueFieldId = metricNode.valueFieldId,
                            fallback = metricNode.numericValue,
                            scale = metricNode.scale,
                            offset = metricNode.offset,
                        )
                        if (resolved != null) {
                            val label = metricNode.title ?: metricNode.id
                            lines += "- 联动指标「$label」：${formatNumber(resolved)}${metricNode.unit.orEmpty()}"
                        }
                    }
            }
            TaskUiKind.STUDY -> {
                val flashcards = document.flashcardNodes()
                if (flashcards.isNotEmpty()) {
                    val mastered = flashcards.count { flashcardMastery[it.id] == FlashcardMastery.MASTERED }
                    val review = flashcards.count { flashcardMastery[it.id] == FlashcardMastery.REVIEW_AGAIN }
                    lines += "- 闪卡复习：已掌握 $mastered/${flashcards.size} 张，待重练 $review 张"
                }
                val quizzes = document.quizNodes()
                if (quizzes.isNotEmpty()) {
                    val answered = quizzes.filter { isQuizQuestionAnswered(it) }
                    val correct = answered.count { isQuizQuestionCorrect(it) }
                    val wrong = answered.filterNot { isQuizQuestionCorrect(it) }
                    lines += "- 测验成绩：已答 ${answered.size}/${quizzes.size} 题，答对 $correct/${quizzes.size} 题，答错 ${wrong.size} 题"
                    if (wrong.isNotEmpty()) {
                        val wrongTitles = wrong.mapNotNull { it.title?.takeIf(String::isNotBlank) }.take(5)
                        if (wrongTitles.isNotEmpty()) {
                            lines += "- 错题列表：${wrongTitles.joinToString("；")}"
                        }
                    }
                }
            }
        }

        document.promptTemplate?.takeIf { it.isNotBlank() }?.let { instruction ->
            lines += "请根据以上结果继续：$instruction"
        }
        return lines.joinToString("\n")
    }

    private fun appendInputSummaryLines(
        document: IntelligentUiDocument,
        lines: MutableList<String>,
    ) {
        document.inputNodes().forEach { node ->
            val label = node.title?.takeIf { it.isNotBlank() } ?: node.id
            when (node.kind) {
                IntelligentUiComponentKind.MULTI_SELECT,
                IntelligentUiComponentKind.CHIP_GROUP -> {
                    val selected = multiFieldValues[node.id].orEmpty()
                    if (selected.isNotEmpty()) {
                        val displayLabels = selected.map { sel ->
                            node.options.firstOrNull { it.value == sel || it.id == sel }?.label ?: sel
                        }
                        lines += "- $label：${displayLabels.joinToString("、")}"
                    }
                }
                IntelligentUiComponentKind.TOGGLE,
                IntelligentUiComponentKind.CHECKBOX,
                IntelligentUiComponentKind.CHECKLIST_ITEM -> {
                    val checked = fieldValues[node.id] == "true"
                    lines += "- $label：${if (checked) "是" else "否"}"
                }
                else -> {
                    val raw = fieldValues[node.id]?.trim().orEmpty()
                    if (raw.isNotEmpty()) {
                        val display = node.options.firstOrNull { it.value == raw || it.id == raw }?.label
                            ?: "$raw${node.unit.orEmpty()}"
                        lines += "- $label：$display"
                    }
                }
            }
        }
    }

    companion object {
        fun formatNumber(value: Double): String {
            if (!value.isFinite()) return "0"
            val roundedInt = value.roundToInt()
            return if (abs(value - roundedInt) < 1e-6) {
                roundedInt.toString()
            } else {
                "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
            }
        }
    }
}
