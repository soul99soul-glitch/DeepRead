package app.amber.ai.ui

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val REASONING_CONTENT_PRESENT_METADATA_KEY = "reasoning_content_present"
internal const val STREAM_REASONING_BLOCK_ID_METADATA_KEY = "stream_reasoning_block_id"

fun reasoningContentPresentMetadata() = buildJsonObject {
    put(REASONING_CONTENT_PRESENT_METADATA_KEY, true)
}

fun UIMessagePart.Reasoning.hasExplicitReasoningContentField(): Boolean =
    metadata?.get(REASONING_CONTENT_PRESENT_METADATA_KEY)
        ?.jsonPrimitive
        ?.booleanOrNull == true

internal fun UIMessagePart.Reasoning.withStreamReasoningId(id: String): UIMessagePart.Reasoning =
    copy(metadata = buildJsonObject {
        metadata?.forEach { (key, value) -> put(key, value) }
        put(STREAM_REASONING_BLOCK_ID_METADATA_KEY, id)
    })

internal fun JsonObject?.reasoningBlockId(): String? =
    this?.get(STREAM_REASONING_BLOCK_ID_METADATA_KEY)?.jsonPrimitive?.contentOrNull
        ?: this?.get("reasoning_id")?.jsonPrimitive?.contentOrNull

internal fun JsonObject?.hasThoughtSignature(): Boolean =
    this?.get("thoughtSignature")?.jsonPrimitive?.contentOrNull != null

internal fun UIMessagePart.Reasoning.hasReasoningReplayMetadata(): Boolean =
    listOf("signature", "redacted_thinking_data", "encrypted_content", "thoughtSignature").any { key ->
        !metadata?.get(key)?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
    }

internal fun mergeReasoningMetadata(previous: JsonObject?, incoming: JsonObject?): JsonObject? {
    if (incoming == null) return previous
    if (previous == null) return incoming
    return buildJsonObject {
        previous.forEach { (key, value) -> put(key, value) }
        incoming.forEach { (key, value) -> put(key, value) }
        val oldSignature = previous["signature"]?.jsonPrimitive?.contentOrNull
        val signatureDelta = incoming["signature"]?.jsonPrimitive?.contentOrNull
        if (oldSignature != null && signatureDelta != null) {
            put("signature", oldSignature + signatureDelta)
        }
    }
}
