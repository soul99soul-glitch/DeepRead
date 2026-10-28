package app.amber.core.jev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** 一次出站 HTTP 尝试的分类结果；重试与预算由 [JevDecisionCoordinator] 决定。 */
sealed interface JevCallResult {
    data class Success(
        val answers: Map<String, JevAnswer>,
        val model: String?,
        val usage: JevUsage?,
        val responseBytes: Int,
    ) : JevCallResult

    data class HttpError(val code: Int, val retryAfterMs: Long?, val bodySnippet: String?) : JevCallResult

    data class Transport(val message: String?) : JevCallResult

    /** 解码/校验失败：确定性错误，不重试，上报 INVALID_RESPONSE。 */
    data class Decode(val message: String?) : JevCallResult
}

/** 传输抽象：生产为 OkHttp，测试注入桩。 */
fun interface JevTransport {
    suspend fun execute(request: JevHttpRequest): JevTransportResponse
}

data class JevHttpRequest(
    val url: String,
    val apiKey: String,
    val body: String,
    val timeoutMs: Long,
    /** 协议附加头（VERCEL decision 模型走 ai-model-id 等；其余方言为空）。 */
    val headers: Map<String, String> = emptyMap(),
)

sealed interface JevTransportResponse {
    data class Http(val code: Int, val body: ByteArray?, val retryAfterHeader: String?) : JevTransportResponse

    data class Failure(val message: String?) : JevTransportResponse
}

/** 线格式编解码 + 单次调用。字段名以 docs.typesafe.ai 2026-09-17 核对为准。 */
class JevClient(
    private val transport: JevTransport = OkHttpJevTransport(),
    private val json: Json = Json { encodeDefaults = false; ignoreUnknownKeys = true },
) {

    fun encodeRequestBody(
        model: String,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
        apiMode: JevApiMode = JevApiMode.TYPESAFE,
        assets: Map<String, JevImageAsset> = emptyMap(),
    ): String {
        requireJevCapabilities(apiMode, questions, assets)
        return when (apiMode) {
            JevApiMode.VERCEL -> encodeVercelRequestBody(model, state, questions, assets)
            // TypeSafe 原生、Perplexity Decisions、Liquid systemone 三种方言的 body 同构。
            JevApiMode.TYPESAFE, JevApiMode.PERPLEXITY, JevApiMode.LIQUID ->
                encodeTypesafeRequestBody(model, state, questions, assets)
        }
    }

    /**
     * 方言能力前置校验：不支持图片/Box 的方言必须显式拒收，
     * 而不是把资产按 systemone 形状发过去换一个 400。
     */
    private fun requireJevCapabilities(
        apiMode: JevApiMode,
        questions: Map<String, JevQuestion>,
        assets: Map<String, JevImageAsset>,
    ) {
        if (assets.isNotEmpty()) {
            require(jevSupportsImages(apiMode)) { "images unsupported by provider: $apiMode" }
        }
        val hasBox = questions.values.any { it is JevQuestion.Box }
        if (hasBox) {
            require(jevSupportsBox(apiMode)) { "box questions require the native systemone API: $apiMode" }
        }
    }

    /** 原生方言：状态是结构化 JSON，图片以 `assets[reference] = {format, data}` 旁挂。 */
    private fun encodeTypesafeRequestBody(
        model: String,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
        assets: Map<String, JevImageAsset>,
    ): String {
        val body = buildJsonObject {
            put("model", model)
            put("state", state)
            put("questions", buildJsonObject {
                questions.forEach { (id, question) -> put(id, question.toJson()) }
            })
            if (assets.isNotEmpty()) {
                put("assets", buildJsonObject {
                    assets.forEach { (reference, asset) ->
                        put(reference, buildJsonObject {
                            put("format", asset.format)
                            put("data", asset.base64())
                        })
                    }
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    /**
     * VERCEL 方言（decision-model 契约）：body 为 `{stateParts, questions}`——
     * 状态按有序文本块发送（图片在 stateParts 中以 file part 表达），模型走
     * `ai-model-id` header（见 [call]），题型 noul 在线上记作 boolean。
     */
    private fun encodeVercelRequestBody(
        model: String,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
        assets: Map<String, JevImageAsset>,
    ): String {
        // 状态本身是结构化 JSON；网关只收文本块，按 JSON 文本原样下发，不做二次摘要。
        val stateText = json.encodeToString(JsonElement.serializer(), state)
        val body = buildJsonObject {
            put("stateParts", buildJsonArray {
                gatewayStateParts(stateText, assets).forEach { add(it) }
            })
            put("questions", buildJsonObject {
                questions.forEach { (id, question) -> put(id, question.toJson(apiMode = JevApiMode.VERCEL)) }
            })
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    /**
     * 按图片参考标记在 state 文本里的位置切成有序块：文本 / file / 文本 …
     * 参考标记必须出现且不得重叠，否则拒绝——静默丢图会让判断基于错误的输入。
     */
    private fun gatewayStateParts(
        stateText: String,
        assets: Map<String, JevImageAsset>,
    ): List<JsonObject> {
        if (assets.isEmpty()) {
            return listOf(buildJsonObject { put("type", "text"); put("text", stateText) })
        }
        val placements = assets.map { (reference, asset) ->
            val index = stateText.indexOf(reference)
            require(index >= 0) { "image reference not present in state: $reference" }
            Triple(index, reference, asset)
        }.sortedBy { it.first }
        val parts = mutableListOf<JsonObject>()
        var cursor = 0
        placements.forEach { (index, reference, asset) ->
            require(index >= cursor) { "overlapping image references: $reference" }
            if (index > cursor) {
                parts += buildJsonObject {
                    put("type", "text")
                    put("text", stateText.substring(cursor, index))
                }
            }
            parts += buildJsonObject {
                put("type", "file")
                put("mediaType", if (asset.format == "png") "image/png" else "image/jpeg")
                put("data", buildJsonObject {
                    put("type", "data")
                    put("data", asset.base64())
                })
            }
            cursor = index + reference.length
        }
        if (cursor < stateText.length) {
            parts += buildJsonObject {
                put("type", "text")
                put("text", stateText.substring(cursor))
            }
        }
        return parts
    }

    /**
     * 解码并逐题校验：缺题、类型不符、未知候选、非有限数值、超出值域均抛
     * [JevDecodeException]——结构正确不等于事实正确，业务侧仍需自证。
     */
    fun decodeResponse(
        bodyText: String,
        questions: Map<String, JevQuestion>,
        apiMode: JevApiMode = JevApiMode.TYPESAFE,
    ): JevDecodedResponse = when (apiMode) {
        JevApiMode.VERCEL -> decodeVercelResponse(bodyText, questions)
        // 三种 systemone 同构方言共用一套解析（含内嵌 confidence）。
        JevApiMode.TYPESAFE, JevApiMode.PERPLEXITY, JevApiMode.LIQUID ->
            decodeTypesafeResponse(bodyText, questions)
    }

    private fun decodeTypesafeResponse(bodyText: String, questions: Map<String, JevQuestion>): JevDecodedResponse {
        val root = parseObject(bodyText, "response")
        val answersObject = root["answers"] as? JsonObject
            ?: throw JevDecodeException("missing answers")
        val usage = (root["usage"] as? JsonObject)?.let { usageObject ->
            JevUsage(
                inputTokens = usageObject["input_tokens"].intOrNullSafe("usage.input_tokens"),
                outputTokens = usageObject["output_tokens"].intOrNullSafe("usage.output_tokens"),
            )
        }
        val model = (root["model"] as? JsonPrimitive)?.contentOrNull
        return JevDecodedResponse(answers = decodeAnswers(answersObject, questions), usage = usage, model = model)
    }

    /**
     * VERCEL 解码：顶层 `answers` 对象（与原生同构，boolean/probability 字段名不同），
     * confidence 经 `providerMetadata.typesafe.confidence[qid]` 注入，usage 为 camelCase。
     */
    private fun decodeVercelResponse(bodyText: String, questions: Map<String, JevQuestion>): JevDecodedResponse {
        val root = parseObject(bodyText, "response")
        val answersObject = root["answers"] as? JsonObject
            ?: throw JevDecodeException("missing answers")
        val usage = (root["usage"] as? JsonObject)?.let { usageObject ->
            val input = (usageObject["inputTokens"] as? JsonPrimitive)?.intOrNull
            val output = (usageObject["outputTokens"] as? JsonPrimitive)?.intOrNull
            if (input != null && output != null) JevUsage(input, output) else null
        }
        val model = (root["model"] as? JsonPrimitive)?.contentOrNull
        val confidence = ((root["providerMetadata"] as? JsonObject)
            ?.get("typesafe") as? JsonObject)
            ?.get("confidence") as? JsonObject
        return JevDecodedResponse(
            answers = decodeAnswers(answersObject, questions, JevApiMode.VERCEL, confidence),
            usage = usage,
            model = model,
        )
    }

    private fun parseObject(bodyText: String, what: String): JsonObject = try {
        json.parseToJsonElement(bodyText).jsonObject
    } catch (e: Exception) {
        throw JevDecodeException("$what is not a JSON object", e)
    }

    private fun decodeAnswers(
        answersObject: JsonObject,
        questions: Map<String, JevQuestion>,
        apiMode: JevApiMode = JevApiMode.TYPESAFE,
        providerConfidence: JsonObject? = null,
    ): Map<String, JevAnswer> = buildMap {
        questions.forEach { (id, question) ->
            val entry = answersObject[id] ?: throw JevDecodeException("missing answer for question $id")
            put(id, decodeAnswer(id, entry, question, apiMode, providerConfidence))
        }
    }

    private fun decodeAnswer(
        id: String,
        entry: JsonElement,
        question: JevQuestion,
        apiMode: JevApiMode,
        providerConfidence: JsonObject?,
    ): JevAnswer {
        val obj = entry as? JsonObject ?: throw JevDecodeException("answer $id is not an object")
        val type = obj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val vercel = apiMode == JevApiMode.VERCEL
        return when (question) {
            is JevQuestion.Noul -> {
                // 线上字段名：原生 noul ↔ 评估协议 probability
                val expectedType = if (vercel) "boolean" else "noul"
                val field = if (vercel) "probability" else "noul"
                if (type != expectedType) throw JevDecodeException("answer $id type mismatch: $type")
                val noul = obj[field].finiteDouble("answer $id $field")
                    ?: throw JevDecodeException("answer $id missing $field")
                if (noul !in 0.0..1.0) throw JevDecodeException("answer $id $field out of range: $noul")
                JevAnswer.Noul(noul)
            }
            is JevQuestion.Choice -> {
                if (type != "choice") throw JevDecodeException("answer $id type mismatch: $type")
                val selected = obj["choice"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?: throw JevDecodeException("answer $id missing choice")
                if (selected !in question.options) {
                    throw JevDecodeException("answer $id unknown option: $selected")
                }
                // VERCEL 评估协议答案不内嵌 confidence：取自 providerMetadata，
                // 或 probabilities 分布中被选项的概率。
                val confidence = if (vercel) {
                    providerConfidence?.get(id).finiteDouble("confidence $id")
                        ?: ((obj["probabilities"] as? JsonObject)?.get(selected)
                            .finiteDouble("answer $id probabilities"))
                } else {
                    obj.confidenceOrNull("answer $id")
                }
                JevAnswer.Choice(selected, confidence)
            }
            is JevQuestion.Box -> {
                if (type != "box") throw JevDecodeException("answer $id type mismatch: $type")
                val box = obj["box"] as? JsonObject
                    ?: throw JevDecodeException("answer $id missing box")
                fun edge(name: String): Double {
                    val value = box[name].finiteDouble("answer $id box.$name")
                        ?: throw JevDecodeException("answer $id box missing $name")
                    if (value !in 0.0..1.0) throw JevDecodeException("answer $id box.$name out of range: $value")
                    return value
                }
                val left = edge("left")
                val top = edge("top")
                val right = edge("right")
                val bottom = edge("bottom")
                if (right < left || bottom < top) {
                    throw JevDecodeException("answer $id box is inverted")
                }
                JevAnswer.Box(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    confidence = obj.confidenceOrNull("answer $id"),
                )
            }
            is JevQuestion.Score -> {
                if (type != "score") throw JevDecodeException("answer $id type mismatch: $type")
                val score = obj["score"].finiteDouble("answer $id score")
                    ?: throw JevDecodeException("answer $id missing score")
                if (score < 0.0) throw JevDecodeException("answer $id score negative: $score")
                // 评估协议约定 score ∈ [0, levels.size-1]（评分档内插值）。
                if (vercel && score > question.levels.size - 1) {
                    throw JevDecodeException("answer $id score out of range: $score")
                }
                val confidence = if (vercel) {
                    providerConfidence?.get(id).finiteDouble("confidence $id")
                } else {
                    obj.confidenceOrNull("answer $id")
                }
                JevAnswer.Score(score, confidence)
            }
        }
    }

    private fun JsonObject.confidenceOrNull(field: String): Double? {
        val value = this["confidence"] ?: return null
        if (value is JsonNull) return null
        val parsed = value.finiteDouble(field) ?: throw JevDecodeException("$field non-finite")
        if (parsed !in 0.0..1.0) throw JevDecodeException("$field out of range: $parsed")
        return parsed
    }

    private fun JsonElement?.finiteDouble(field: String): Double? =
        (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonElement?.intOrNullSafe(field: String): Int =
        (this as? JsonPrimitive)?.intOrNull
            ?: throw JevDecodeException("$field is not an int")
    private fun JevQuestion.toJson(apiMode: JevApiMode = JevApiMode.TYPESAFE): JsonObject = buildJsonObject {
        put("type", when (this@toJson) {
            // 评估协议线上题型名 boolean；typesafe 原生叫 noul。
            is JevQuestion.Noul -> if (apiMode == JevApiMode.VERCEL) "boolean" else "noul"
            is JevQuestion.Choice -> "choice"
            is JevQuestion.Score -> "score"
            is JevQuestion.Box -> "box"
        })
        put("instructions", instructions)
        when (val q = this@toJson) {
            is JevQuestion.Noul -> {
                if (q.trueCriteria != null || q.falseCriteria != null) {
                    put("criteria", buildJsonObject {
                        q.trueCriteria?.let { put("true", it) }
                        q.falseCriteria?.let { put("false", it) }
                    })
                }
            }
            is JevQuestion.Choice -> put("criteria", buildJsonObject {
                q.options.forEach { (option, description) ->
                    put(option, description)
                }
            })
            is JevQuestion.Score -> put("criteria", JsonArray(q.levels.map { JsonPrimitive(it) }))
            is JevQuestion.Box -> q.targetDescription?.let { put("criteria", buildJsonObject { put("target", it) }) }
        }
    }

    /** 单次出站尝试。deadline 由 [JevDecisionCoordinator] 通过 withTimeout 施加。 */
    suspend fun call(
        apiMode: JevApiMode,
        endpoint: String,
        model: String,
        apiKey: String,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
        assets: Map<String, JevImageAsset> = emptyMap(),
    ): JevCallResult {
        val body = try {
            encodeRequestBody(model, state, questions, apiMode, assets)
        } catch (e: IllegalArgumentException) {
            // 能力不匹配 / 参考标记缺失都是确定性错误，不重试。
            return JevCallResult.Decode(e.message ?: "invalid request")
        }
        val bodyLimit = if (assets.isEmpty()) {
            JevLimits.MAX_REQUEST_BODY_BYTES
        } else {
            JevLimits.MAX_IMAGE_REQUEST_BODY_BYTES
        }
        if (body.toByteArray(Charsets.UTF_8).size > bodyLimit) {
            return JevCallResult.Transport("request body too large")
        }
        val response = try {
            transport.execute(
                JevHttpRequest(
                    url = endpoint,
                    apiKey = apiKey,
                    body = body,
                    timeoutMs = jevDeadlineMs(apiMode),
                    headers = when (apiMode) {
                        // 图片与 Box 属实验能力，必须显式声明危险区头部，否则服务端忽略资产。
                        JevApiMode.TYPESAFE -> if (assets.isEmpty() &&
                            questions.values.none { it is JevQuestion.Box }
                        ) {
                            emptyMap()
                        } else {
                            mapOf("TypeSafe-Danger-Zone-Images" to "true")
                        }
                        JevApiMode.PERPLEXITY, JevApiMode.LIQUID -> emptyMap()
                        // Vercel AI Gateway decision-model 协议头（与 @ai-sdk/gateway 一致）：
                        // 模型 id 与 spec 版本走 header，body 不再携带 model。
                        JevApiMode.VERCEL -> mapOf(
                            "ai-model-id" to model,
                            "ai-gateway-protocol-version" to "0.0.1",
                            "ai-gateway-auth-method" to "api-key",
                            "ai-decision-model-specification-version" to "4",
                        )
                    },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return JevCallResult.Transport(e.message ?: e.javaClass.simpleName)
        }
        return when (response) {
            is JevTransportResponse.Failure -> JevCallResult.Transport(response.message)
            is JevTransportResponse.Http -> {
                val bytes = response.body
                if (response.code !in 200..299) {
                    return JevCallResult.HttpError(
                        code = response.code,
                        retryAfterMs = response.retryAfterHeader?.parseRetryAfterMs(),
                        bodySnippet = bytes?.toString(Charsets.UTF_8)?.take(200),
                    )
                }
                if (bytes == null) return JevCallResult.Transport("empty body")
                if (bytes.size > JevLimits.MAX_RESPONSE_BODY_BYTES) {
                    return JevCallResult.Transport("response body too large")
                }
                try {
                    val decoded = decodeResponse(bytes.toString(Charsets.UTF_8), questions, apiMode)
                    JevCallResult.Success(decoded.answers, decoded.model, decoded.usage, bytes.size)
                } catch (e: JevDecodeException) {
                    JevCallResult.Decode(e.message)
                } catch (e: Exception) {
                    JevCallResult.Decode(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}

class JevDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class JevDecodedResponse(
    val answers: Map<String, JevAnswer>,
    val usage: JevUsage?,
    val model: String?,
)

/** Retry-After: 仅解析整数秒形式；HTTP 日期等无法解析时返回 null（由 deadline 兜底）。 */
internal fun String.parseRetryAfterMs(): Long? = trim().toLongOrNull()?.times(1000)

/** 生产传输：OkHttp enqueue，取消沿协程传播到 Call.cancel。 */
class OkHttpJevTransport(
    client: OkHttpClient = OkHttpClient(),
    private val callFactory: (Request) -> Call = { client.newCall(it) },
) : JevTransport {

    override suspend fun execute(request: JevHttpRequest): JevTransportResponse =
        withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(request.url)
                .header("Authorization", "Bearer ${request.apiKey}")
                .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                // 标记为 Jev 请求：请求日志拦截器据此豁免 body 落盘，
                // 与方言/自定义 baseUrl 无关（typesafe 域名判断会漏 VERCEL）。
                .tag(JevHttpRequest::class.java, request)
                .post(request.body.toRequestBody("application/json".toMediaType()))
                .build()
            callFactory(httpRequest).awaitTransportResponse()
        }
}

/** Keep cancellation attached through bounded body IO, not just until the headers arrive. */
private suspend fun Call.awaitTransportResponse(): JevTransportResponse = suspendCancellableCoroutine { continuation ->
    // Install first: a test/local Call may invoke onResponse synchronously from enqueue.
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            val result = try {
                response.use {
                    val body = it.body
                    val limit = JevLimits.MAX_RESPONSE_BODY_BYTES.toLong()
                    val source = body?.source()
                    if (body != null && (body.contentLength() > limit || source?.request(limit + 1) == true)) {
                        JevTransportResponse.Failure("response body too large")
                    } else {
                        JevTransportResponse.Http(
                            code = it.code,
                            body = source?.readByteArray(),
                            retryAfterHeader = it.header("Retry-After"),
                        )
                    }
                }
            } catch (e: IOException) {
                JevTransportResponse.Failure(e.message ?: e.javaClass.simpleName)
            }
            if (continuation.isActive) continuation.resume(result)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resume(JevTransportResponse.Failure(e.message ?: e.javaClass.simpleName))
        }
    })
}
