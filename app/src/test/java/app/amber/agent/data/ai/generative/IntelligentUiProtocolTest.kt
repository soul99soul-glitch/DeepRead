package app.amber.agent.data.ai.generative

import app.amber.core.ai.generative.FlashcardMastery
import app.amber.core.ai.generative.GenerativeWidgetParser
import app.amber.core.ai.generative.GenerativeWidgetSegment
import app.amber.core.ai.generative.IntelligentUiCardState
import app.amber.core.ai.generative.IntelligentUiCategory
import app.amber.core.ai.generative.IntelligentUiComponentKind
import app.amber.core.ai.generative.IntelligentUiParser
import app.amber.core.ai.generative.IntelligentUiStateStore
import app.amber.core.ai.generative.TaskUiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntelligentUiProtocolTest {

    @Before
    fun setUp() {
        IntelligentUiStateStore.clearAll()
    }

    @Test
    fun `catalog contains exactly 80 component kinds across 8 categories and resolves aliases`() {
        val entries = IntelligentUiComponentKind.entries
        assertEquals(80, entries.size)
        assertEquals(10, entries.count { it.category == IntelligentUiCategory.LAYOUT })
        assertEquals(12, entries.count { it.category == IntelligentUiCategory.TEXT })
        assertEquals(10, entries.count { it.category == IntelligentUiCategory.STATUS })
        assertEquals(14, entries.count { it.category == IntelligentUiCategory.INPUT })
        assertEquals(10, entries.count { it.category == IntelligentUiCategory.DATA })
        assertEquals(8, entries.count { it.category == IntelligentUiCategory.MEDIA })
        assertEquals(8, entries.count { it.category == IntelligentUiCategory.TASK_SEMANTIC })
        assertEquals(8, entries.count { it.category == IntelligentUiCategory.ACTION_CONTAINER })

        entries.forEach { kind ->
            assertEquals(kind, IntelligentUiComponentKind.fromRaw(kind.canonicalName))
            assertEquals(kind, IntelligentUiComponentKind.fromRaw(kind.name))
            kind.aliases.forEach { alias ->
                assertEquals(kind, IntelligentUiComponentKind.fromRaw(alias))
            }
        }
    }

    @Test
    fun `parses decision task-ui and builds submission prompt`() {
        val json = """
            {
              "kind": "decision",
              "title": "数据库选型对比",
              "subtitle": "针对本地高并发读写场景选择存储方案",
              "options": [
                {"id": "sqlite_wal", "label": "SQLite WAL", "description": "轻量内嵌、读并发好", "badge": "推荐", "recommended": true},
                {"id": "duckdb", "label": "DuckDB", "description": "列式分析强"}
              ],
              "components": [
                {
                  "type": "recommendation_hero",
                  "title": "首选 SQLite WAL",
                  "body": "在移动端事务型工作负载下延迟最低",
                  "style": {"tone": "accent", "emphasis": "hero"}
                },
                {
                  "type": "pros_cons",
                  "title": "权衡要点",
                  "pros": ["零外部依赖", "事务 ACID 完整"],
                  "cons": ["单写者锁限制"]
                }
              ]
            }
        """.trimIndent()

        val doc = IntelligentUiParser.parseDocument(json)
        assertNotNull(doc)
        doc!!
        assertEquals(TaskUiKind.DECISION, doc.kind)
        assertEquals("数据库选型对比", doc.title)
        assertEquals(2, doc.options.size)
        assertEquals(2, doc.components.size)

        val state = IntelligentUiStateStore.stateFor("card_decision_1", doc)
        assertEquals("sqlite_wal", state.selectedDecisionOptionId)
        assertTrue(state.validationErrors(doc).isEmpty())

        val prompt = state.buildSubmissionPrompt(doc)
        assertTrue(prompt.contains("SQLite WAL"))
        assertTrue(prompt.contains("数据库选型对比"))
    }

    @Test
    fun `numeric binding via value_field_id updates metric progress and charts live and validates inputs`() {
        val json = """
            {
              "kind": "explore",
              "title": "贷款月供测算",
              "components": [
                {
                  "id": "loan_amount",
                  "type": "number_field",
                  "title": "贷款总额",
                  "value": 100,
                  "min": 10,
                  "max": 500,
                  "step": 10,
                  "unit": "万",
                  "required": true
                },
                {
                  "id": "monthly_payment",
                  "type": "metric",
                  "title": "预计月供",
                  "value_field_id": "loan_amount",
                  "scale": 52.5,
                  "offset": 100,
                  "unit": "元"
                }
              ]
            }
        """.trimIndent()

        val doc = IntelligentUiParser.parseDocument(json)!!
        val state = IntelligentUiCardState(doc)
        val metricNode = doc.components[1]

        // Initial bound value: 100 * 52.5 + 100 = 5350.0
        val initialBound = state.boundNumericValue(
            document = doc,
            valueFieldId = metricNode.valueFieldId,
            fallback = metricNode.numericValue,
            scale = metricNode.scale,
            offset = metricNode.offset,
        )
        assertEquals(5350.0, initialBound!!, 0.001)

        // Update loan_amount to 200 -> 200 * 52.5 + 100 = 10600.0
        state.setNumericFieldValue("loan_amount", 200.0, min = 10.0, max = 500.0, step = 10.0)
        val updatedBound = state.boundNumericValue(
            document = doc,
            valueFieldId = metricNode.valueFieldId,
            fallback = metricNode.numericValue,
            scale = metricNode.scale,
            offset = metricNode.offset,
        )
        assertEquals(10600.0, updatedBound!!, 0.001)
        assertTrue(state.validationErrors(doc).isEmpty())

        // Invalid non-numeric input
        state.setFieldValue("loan_amount", "abc")
        val errorsInvalid = state.validationErrors(doc)
        assertEquals(1, errorsInvalid.size)
        assertTrue(errorsInvalid.first().contains("有效数字"))

        // Out of range input
        state.setFieldValue("loan_amount", "600")
        val errorsMax = state.validationErrors(doc)
        assertEquals(1, errorsMax.size)
        assertTrue(errorsMax.first().contains("不能大于 500万"))

        // Step mismatch input
        state.setFieldValue("loan_amount", "105")
        val errorsStep = state.validationErrors(doc)
        assertEquals(1, errorsStep.size)
        assertTrue(errorsStep.first().contains("步进需为 10 的倍数"))
    }

    @Test
    fun `study task supports flashcards mastery and quiz instant grading plus retry wrong only`() {
        val json = """
            {
              "kind": "study",
              "title": "Kotlin 协程速记与自测",
              "components": [
                {
                  "id": "fc_1",
                  "type": "flashcard",
                  "front": "SupervisorJob 与 Job 的核心区别？",
                  "back": "子协程失败不会取消同级其他子协程。",
                  "hint": "思考异常传播方向"
                },
                {
                  "id": "quiz_1",
                  "type": "quiz_item",
                  "question": "flowOn 操作符影响哪个方向的协程上下文？",
                  "options": ["上游", "下游", "上下游同时"],
                  "correct_index": 0,
                  "explanation": "flowOn 仅改变其上游操作符的 CoroutineContext。"
                },
                {
                  "id": "quiz_2",
                  "type": "quiz_item",
                  "question": "withContext 是否会创建新的协程？",
                  "options": ["会创建独立子协程", "只切换上下文并挂起当前协程"],
                  "correct_index": 1,
                  "explanation": "withContext 在同一协程内切换 Dispatcher。"
                }
              ]
            }
        """.trimIndent()

        val doc = IntelligentUiParser.parseDocument(json)!!
        assertEquals(TaskUiKind.STUDY, doc.kind)
        assertEquals(1, doc.flashcardNodes().size)
        assertEquals(2, doc.quizNodes().size)

        val state = IntelligentUiCardState(doc)
        state.flashcardMastery["fc_1"] = FlashcardMastery.MASTERED

        // Answer quiz_1 right ("opt_0" -> index 0) and quiz_2 wrong ("opt_0" -> index 0, expected 1)
        state.quizSelectedOption["quiz_1"] = "opt_0"
        state.quizSelectedOption["quiz_2"] = "opt_0"

        val quizNodes = doc.quizNodes()
        assertTrue(state.isQuizQuestionCorrect(quizNodes[0]))
        assertFalse(state.isQuizQuestionCorrect(quizNodes[1]))

        // Retry wrong only should clear quiz_2 selection and keep quiz_2 in retryWrongQuestionIds
        state.startRetryWrongQuestions(quizNodes)
        assertTrue(state.retryWrongOnly)
        assertTrue(state.retryWrongQuestionIds.containsKey("quiz_2"))
        assertFalse(state.retryWrongQuestionIds.containsKey("quiz_1"))
        assertNull(state.quizSelectedOption["quiz_2"])

        // Answer quiz_2 right
        state.quizSelectedOption["quiz_2"] = "opt_1"
        assertTrue(state.isQuizQuestionCorrect(quizNodes[1]))

        val summaryPrompt = state.buildSubmissionPrompt(doc)
        assertTrue(summaryPrompt.contains("已掌握 1/1"))
        assertTrue(summaryPrompt.contains("答对 2/2"))
    }

    @Test
    fun `GenerativeWidgetParser splits task-ui finished and streaming segments`() {
        val completedText = """
            先看下面的交互卡片：
            ```task-ui
            {"kind":"form","title":"旅行偏好收集","components":[{"type":"text_field","id":"city","title":"目的地","required":true}]}
            ```
            填写后点确认即可。
        """.trimIndent()

        val completedSegments = GenerativeWidgetParser.split(completedText, isStreaming = false)
        assertEquals(3, completedSegments.size)
        val taskUiSeg = completedSegments[1] as GenerativeWidgetSegment.TaskUi
        assertEquals(TaskUiKind.FORM, taskUiSeg.document.kind)
        assertEquals("旅行偏好收集", taskUiSeg.document.title)

        val streamingText = """
            正在生成学习卡片：
            ```task-ui
            {"kind":"study","title":"古诗词抽考","subtitle":"先翻卡再做题","components":[{"type":"flashcard","title":"静夜思"},{"type":"quiz_item"
        """.trimIndent()

        val streamingSegments = GenerativeWidgetParser.split(streamingText, isStreaming = true)
        assertEquals(2, streamingSegments.size)
        val streamingSeg = streamingSegments[1] as GenerativeWidgetSegment.TaskUiStreaming
        assertEquals(TaskUiKind.STUDY, streamingSeg.preview.kind)
        assertEquals("古诗词抽考", streamingSeg.preview.title)
        assertEquals(2, streamingSeg.preview.detectedComponents.size)
    }
}
