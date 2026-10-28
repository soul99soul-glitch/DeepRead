package app.amber.core.ai

import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.ToolEffect
import app.amber.feature.runtime.ToolEffectStatus
import app.amber.feature.runtime.argsDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * GUI 操控（无障碍/adb 自动化）工具族前缀。这类工具的执行结果取决于外部屏幕状态：
 * 同参数跨步重复是长任务的正常形态（连续滑动、逐屏读取、滚动直到目标出现），
 * 不计入重复守卫的跨步签名计数，失控上界交给 maxSteps 步数预算（用户可配）。
 * 同批双胞胎与已计数 callId 的重发仍照常守卫。
 * （iOS ToolLoopGuard 对齐在 Android 侧放宽，双端契约以本注释为准。）
 */
private const val SCREEN_AUTOMATION_TOOL_PREFIX = "screen_"

// These observations may change between model steps. Keep this explicit:
// the ledger's READ_ONLY class also covers execution tools, not just reads.
private val REPEATABLE_OBSERVATION_TOOLS = setOf(
    "file_read", "file_list", "file_search",
    "terminal_job_wait", "terminal_job_read", "terminal_session_read",
    "subagent_wait", "subagent_read", "model_council_wait", "model_council_read",
    "js_cell_wait", "webview_wait_for_load", "webview_read", "webview_find_text", "webview_links",
    "wm_wait", "wm_observe", "wm_state", "wm_extract", "wm_get", "wm_find", "wm_network_inspect",
    "wm_screenshot", "wm_visual_snapshot", "wm_zcode_read",
)

/** Failure-class tool-output statuses — mirrors the UI failure classifier (ChatMessageTools.toolHasFailure). */
private val FAILURE_TOOL_STATUSES = setOf("failed", "error", "denied", "timed_out", "interrupted", "policy_denied")

/** Duplicate-call history for one kernel invocation, seeded from its durable run. */
internal class ToolCallHistory(
    private val duplicateSameBatchMessage: String,
    private val duplicateExistingMessage: String,
) {
    private val executedSignatures = mutableMapOf<Pair<String, String>, Int>()
    private val countedToolCallIds = mutableSetOf<String>()

    fun seed(effects: List<ToolEffect>) {
        effects.forEach { effect ->
            if (effect.status != ToolEffectStatus.FINISHED) return@forEach
            if (effect.toolCallId in countedToolCallIds) return@forEach
            val output = ledgerEffectOutput(effect)
            if (output.isEmpty() || toolOutputIsFailure(output)) return@forEach
            countedToolCallIds += effect.toolCallId
            executedSignatures.merge(effect.toolName to effect.argsDigest, 1, Int::plus)
        }
    }

    // Feed the duplicate guard table: every settled non-failure
    // result counts as one occurrence — including guard-skipped
    // duplicates (their structured "skipped" output is not
    // failure-class), so the signature table sees execute AND
    // skip as occurrences and the stop gate fires on the third
    // occurrence even when the second was never executed. The
    // same toolCallId re-running (approval resume, ledger reuse)
    // is the same emission, never a second count; failure-class
    // outputs (same standard as the UI failure classifier) and
    // user/policy denials don't count either, so a failed or
    // denied call may retry with identical args.
    fun recordSettled(tools: List<UIMessagePart.Tool>) {
        for (tool in tools) {
            if (tool.toolCallId in countedToolCallIds) continue
            if (tool.output.isEmpty() || toolOutputIsFailure(tool.output)) continue
            countedToolCallIds += tool.toolCallId
            executedSignatures.merge(tool.toolName to argsDigest(tool.input), 1, Int::plus)
        }
    }

    /**
     * Classifies one step's tool calls against the duplicate-signature table,
     * in batch order. The occurrence of each call is its run-level count
     * (counted occurrences of the same signature this run — settled
     * executions AND guard-skipped repeats alike, since a skip is itself an
     * occurrence) plus the same-batch twins already seen, plus one:
     * occurrence 1 goes to [Classification.toExecute], occurrence 2 to
     * [Classification.skipped] with a structured reminder, and occurrence >= 3
     * makes the call [Classification.stopping] and ends the scan — the loop
     * breaks right after, so later calls of the same step stay untouched.
     *
     * The batch-local counter (this step only — never merged into
     * [executedSignatures]) exists because the signature table is fed AFTER
     * executeBatch: two same-signature calls inside one assistant message
     * would both read a null count here and both execute — non-idempotent
     * side effects would run twice. Counting per-occurrence (not just
     * seen/not-seen) makes the third same-signature call inside ONE batch a
     * stop, the same as the third across steps: first executes, second is
     * skipped, third stops the run.
     *
     * Fresh observation callIds may ignore earlier steps' signature counts;
     * screen_* automation calls (fresh callIds) enjoy the same exemption —
     * their outcome depends on the changing screen state, so an identical
     * cross-step repeat is normal for long automation tasks; the runaway
     * bound is the maxSteps budget. Same-batch twins and counted-callId
     * re-emissions stay guarded either way. Approval/crash
     * resume never needs this exception — those
     * flows re-enter run() with PREPARED/RECONCILED effects, which are never
     * counted (only FINISHED, non-failure executions count), so a resumed
     * emission reaches the table with a null count and executes. A FINISHED
     * callId re-emitted by the model is a genuine repeat and must not
     * unconditionally re-execute.
     */
    class Classification(
        val toExecute: List<UIMessagePart.Tool>,
        val skipped: List<UIMessagePart.Tool>,
        val stopping: UIMessagePart.Tool?,
    )

    fun classify(tools: List<UIMessagePart.Tool>): Classification {
        val toExecute = mutableListOf<UIMessagePart.Tool>()
        val skipped = mutableListOf<UIMessagePart.Tool>()
        var stopping: UIMessagePart.Tool? = null
        val batchSeenCounts = mutableMapOf<Pair<String, String>, Int>()
        for (tool in tools) {
            val signature = tool.toolName to argsDigest(tool.input)
            val batchSeen = batchSeenCounts[signature] ?: 0
            val freshObservation = (tool.toolName in REPEATABLE_OBSERVATION_TOOLS ||
                tool.toolName.startsWith(SCREEN_AUTOMATION_TOOL_PREFIX)) &&
                tool.toolCallId !in countedToolCallIds
            val priorOccurrences = if (freshObservation) 0 else executedSignatures[signature] ?: 0
            val occurrence = priorOccurrences + batchSeen + 1
            when {
                occurrence == 1 -> {
                    batchSeenCounts[signature] = batchSeen + 1
                    toExecute += tool
                }
                // Second occurrence: a same-batch twin of a call executing in
                // this very batch, or the cross-step repeat of an already
                // counted execution — either way it never reaches
                // executeBatch.
                occurrence == 2 -> {
                    batchSeenCounts[signature] = batchSeen + 1
                    skipped += tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                duplicateSkipOutput(
                                    occurrence = 2,
                                    message = if (batchSeen > 0) {
                                        duplicateSameBatchMessage
                                    } else {
                                        duplicateExistingMessage
                                    },
                                ),
                            ),
                        ),
                    )
                }
                else -> {
                    stopping = tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                duplicateSkipOutput(
                                    occurrence = occurrence,
                                    message = duplicateExistingMessage,
                                ),
                            ),
                        ),
                    )
                    break
                }
            }
        }
        return Classification(toExecute, skipped, stopping)
    }

    /**
     * Structured output attached to a guard-skipped duplicate call. `skipped`
     * is an established status (RecipeRunner, UI classification) — outside the
     * failure set, so the model reads the reminder and self-corrects.
     * [message] distinguishes the same-batch twin (never executed at all)
     * from the cross-step repeat (already executed with identical args).
     */
    private fun duplicateSkipOutput(
        occurrence: Int,
        message: String,
    ): String = buildJsonObject {
        put("status", "skipped")
        put("reason", "duplicate_tool_call")
        put("occurrence", occurrence)
        put("message", message)
    }.toString()

    /**
     * Best-effort reconstruction of a FINISHED effect's output for the guard
     * rebuild: decode the persisted payload, falling back to the retained
     * summary as plain text (the payload is dropped once the result has landed
     * in the conversation). An empty result counts as "no output" — the same
     * not-counted treatment the in-run rule gives empty outputs.
     */
    private fun ledgerEffectOutput(effect: ToolEffect): List<UIMessagePart> {
        val payload = effect.resultPayload
        if (payload != null) {
            runCatching { Json.decodeFromString<List<UIMessagePart>>(payload) }.getOrNull()?.let { return it }
        }
        return effect.resultSummary?.let { listOf(UIMessagePart.Text(it)) } ?: emptyList()
    }
}

/**
 * Whether a tool's structured output is failure-class — the SAME standard
 * as the UI failure classifier (ChatMessageTools.toolHasFailure): a status
 * in its failure set, or a non-blank `error` field. Plain-text or
 * unparseable output is not failure-class.
 */
internal fun toolOutputIsFailure(output: List<UIMessagePart>): Boolean {
    val obj = output.filterIsInstance<UIMessagePart.Text>()
        .firstNotNullOfOrNull { part ->
            runCatching { Json.parseToJsonElement(part.text).jsonObject }.getOrNull()
        } ?: return false
    val status = runCatching { obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase() }.getOrNull()
    if (status != null && status in FAILURE_TOOL_STATUSES) return true
    val error = runCatching { obj["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    return !error.isNullOrBlank()
}
