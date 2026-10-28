package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import app.amber.core.settings.Settings
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Best-effort topic classification and roster design; no room or task state is retained. */
internal class CouncilRoomPlanner(
    private val modelRunner: ModelCouncilTextRunner,
    private val json: Json,
) {
    /**
     * Ask the host model to classify the topic as EXPLORE (open / divergent /
     * brainstorm) or DEBATE (clear stances / decision / stress-test). Returns null
     * on any failure (no host model, timeout, unparseable) → caller keeps the
     * current mode, so auto-detect is a best-effort enhancement, never a blocker.
     */
    suspend fun classifyMode(room: CouncilRoom, settings: Settings): CouncilRoomMode? {
        val hostModelId = resolveHostModelId(room, settings) ?: return null
        val prompt = buildString {
            appendLine("Choose the more suitable multi-model discussion mode for the topic below. Reply with one English word only:")
            appendLine("- explore: open and divergent; brainstorm when the direction is not settled and more angles or possibilities are needed.")
            appendLine("- debate: clear disagreement or a decision is required; challenge positions and pressure-test the options.")
            appendLine()
            appendLine("Topic: ${room.objective}")
            appendLine()
            append("Reply with explore or debate only; do not explain.")
        }
        val result = runCatching {
            withTimeoutOrNull(20_000L) {
                modelRunner.generate(
                    settings = settings,
                    modelId = hostModelId,
                    systemPrompt = "You are a concise classifier. Output one English word only.",
                    userPrompt = prompt,
                    outputBudgetChars = 50,
                    reasoningLevel = ReasoningLevel.OFF,
                    temperature = 0f,
                    onChunk = {},
                )
            }
        }.getOrElse { error ->
            // Let close()'s cancellation propagate; only soft-fail real errors.
            if (error is CancellationException) throw error
            null
        } ?: return null
        val text = result.text.lowercase()
        return when {
            "debate" in text -> CouncilRoomMode.DEBATE
            "explore" in text -> CouncilRoomMode.EXPLORE
            else -> null
        }
    }

    /**
     * Let the host model DESIGN the council roster for [objective] — bespoke roles
     * tailored to this specific topic (a short role name + a one-line perspective),
     * NOT a fixed preset list. Returns (name, perspective) pairs. Best-effort:
     * empty on any failure so the caller falls back to a sensible default. Mirrors
     * [classifyMode]'s lightweight call.
     */
    suspend fun planRoles(
        room: CouncilRoom,
        objective: String,
        settings: Settings,
        maxGuests: Int,
    ): List<Pair<String, String>> {
        val hostModelId = resolveHostModelId(room, settings) ?: return emptyList()
        val prompt = buildString {
            appendLine("You are the host of a multi-model council. For the topic below, design the best 2-$maxGuests participant roles independently.")
            appendLine("Do not reuse a fixed template. Tailor the roles to this topic: decisions or disputes may need opposing or review roles, while exploration, creative, or factual topics need the perspectives or expertise they actually require.")
            appendLine("Give each role two fields:")
            appendLine("- name: a short role name (4-12 words) that conveys its position, expertise, or perspective")
            appendLine("- perspective: one sentence describing the angle this role brings and what it should focus on")
            appendLine()
            appendLine("Topic: $objective")
            append(CouncilRoomTurnPolicy.userClarificationContext(room))
            // FULL mode: if the host already ran a pre-topic research turn, the
            // gathered facts live in room.context. Feed them to the roster planner so
            // the bespoke roles are tailored to what was actually found (e.g. once the
            // host has glm5.2 benchmark data, "代码推理手"/"中文体验派" become grounded
            // choices instead of generic "基准评测派"/"产品落地官").
            if (room.context.isNotBlank()) {
                appendLine()
                appendLine("Key facts already gathered (use them to choose the most relevant analytical perspectives rather than generic roles):")
                appendLine(room.context.take(2000))
            }
            appendLine()
            append("Output a JSON array only, for example: [{\"name\":\"Cost Analyst\",\"perspective\":\"Evaluate whether the option is worthwhile over the long term based on inputs, outputs, and total cost.\"}]. Do not explain or use a code block.")
        }
        val result = runCatching {
            withTimeoutOrNull(25_000L) {
                modelRunner.generate(
                    settings = settings,
                    modelId = hostModelId,
                    systemPrompt = "Output a JSON array only, with no extra text.",
                    userPrompt = prompt,
                    outputBudgetChars = 800,
                    reasoningLevel = ReasoningLevel.OFF,
                    temperature = 0.4f,
                    onChunk = {},
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        } ?: return emptyList()
        return parseGeneratedRoles(result.text, maxGuests)
    }

    /** Tolerant parse of the host's role JSON — strips surrounding prose / fences. */
    private fun parseGeneratedRoles(raw: String, maxGuests: Int): List<Pair<String, String>> = runCatching {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        json.parseToJsonElement(raw.substring(start, end + 1)).jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val perspective = obj["perspective"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) null else name to perspective
        }.take(maxGuests)
    }.getOrElse { emptyList() }

}
