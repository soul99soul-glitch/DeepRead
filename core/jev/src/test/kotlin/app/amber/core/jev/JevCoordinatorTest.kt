package app.amber.core.jev

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevClientCodecTest {

    private val client = JevClient(transport = { throw IllegalStateException("not under test") })

    @Test
    fun encodeProducesMapKeyedQuestions() {
        val body = client.encodeRequestBody(
            model = "jev-1.14.0",
            state = buildJsonObject { put("task", "t") },
            questions = mapOf(
                "q1" to JevQuestion.Noul("Is it relevant?", trueCriteria = "relevant to the task"),
                "q2" to JevQuestion.Choice("Pick one", options = mapOf("a" to "option a", "b" to null)),
                "q3" to JevQuestion.Score("Grade it", levels = listOf("low", "mid", "high")),
            ),
        )
        assertTrue(body.contains("\"questions\":{\"q1\""))
        assertTrue(body.contains("\"type\":\"noul\""))
        assertTrue(body.contains("\"criteria\":{\"true\":\"relevant to the task\"}"))
        assertTrue(body.contains("\"type\":\"choice\""))
        assertTrue(body.contains("\"criteria\":{\"a\":\"option a\",\"b\":null}"))
        assertTrue(body.contains("\"type\":\"score\""))
        assertTrue(body.contains("\"criteria\":[\"low\",\"mid\",\"high\"]"))
    }

    @Test
    fun decodeValidAnswersOfAllTypes() {
        val body = """
            {"model":"jev-1","usage":{"input_tokens":3,"output_tokens":2},"answers":{
              "q1":{"type":"noul","noul":0.8},
              "q2":{"type":"choice","choice":"a","probabilities":{"a":0.7,"b":0.3},"confidence":0.6},
              "q3":{"type":"score","score":1.5,"confidence":0.9}
            }}
        """.trimIndent()
        val decoded = client.decodeResponse(
            body,
            mapOf(
                "q1" to JevQuestion.Noul("q"),
                "q2" to JevQuestion.Choice("q", mapOf("a" to null, "b" to null)),
                "q3" to JevQuestion.Score("q", listOf("low", "mid", "high")),
            ),
        )
        assertEquals(0.8, (decoded.answers["q1"] as JevAnswer.Noul).probability, 0.0)
        assertEquals("a", (decoded.answers["q2"] as JevAnswer.Choice).selected)
        assertEquals(1.5, (decoded.answers["q3"] as JevAnswer.Score).score, 0.0)
        assertEquals(3, decoded.usage?.inputTokens)
        assertEquals("jev-1", decoded.model)
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsMissingAnswer() {
        client.decodeResponse("""{"answers":{}}""", mapOf("q1" to JevQuestion.Noul("q")))
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsTypeMismatch() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"choice","choice":"a"}}}""",
            mapOf("q1" to JevQuestion.Noul("q")),
        )
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsUnknownChoiceOption() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"choice","choice":"zzz"}}}""",
            mapOf("q1" to JevQuestion.Choice("q", mapOf("a" to null))),
        )
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsNoulOutOfRange() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"noul","noul":1.5}}}""",
            mapOf("q1" to JevQuestion.Noul("q")),
        )
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsNonFiniteNoul() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"noul","noul":1e999}}}""",
            mapOf("q1" to JevQuestion.Noul("q")),
        )
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsConfidenceOutOfRange() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"choice","choice":"a","confidence":1.2}}}""",
            mapOf("q1" to JevQuestion.Choice("q", mapOf("a" to null))),
        )
    }

    @Test
    fun vercelEncodeProducesDecisionModelShape() {
        val body = client.encodeRequestBody(
            model = "typesafe-ai/jev-1.14.0",
            state = buildJsonObject { put("task", "t") },
            questions = mapOf(
                "q1" to JevQuestion.Noul("Is it relevant?"),
                "q2" to JevQuestion.Choice("Pick one", options = mapOf("a" to "option a", "b" to null)),
                "q3" to JevQuestion.Score("Grade it", levels = listOf("low", "high")),
            ),
            apiMode = JevApiMode.VERCEL,
        )
        val root = Json.parseToJsonElement(body).jsonObject
        // decision-model 只吃 {stateParts, questions}——模型走 ai-model-id header，不进 body
        assertEquals(setOf("stateParts", "questions"), root.keys)
        val parts = root.getValue("stateParts").jsonArray
        assertEquals(1, parts.size)
        val part = parts.first().jsonObject
        assertEquals("text", part.getValue("type").jsonPrimitive.content)
        // 结构化状态按 JSON 文本原样下发，不丢字段也不二次摘要
        val stateText = part.getValue("text").jsonPrimitive.content
        assertEquals("t", Json.parseToJsonElement(stateText).jsonObject.getValue("task").jsonPrimitive.content)
        val qs = root.getValue("questions").jsonObject
        // noul 线上记作 boolean；choice/score 题型名与 criteria 形状同原生
        assertEquals("boolean", qs.getValue("q1").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("choice", qs.getValue("q2").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("score", qs.getValue("q3").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "option a",
            qs.getValue("q2").jsonObject.getValue("criteria").jsonObject.getValue("a").jsonPrimitive.content,
        )
        assertEquals("low", qs.getValue("q3").jsonObject.getValue("criteria").jsonArray.first().jsonPrimitive.content)
    }

    /**
     * 状态按 JSON 文本嵌入 stateParts：只允许一层转义。中文、双引号、换行必须
     * 能回读成同一个对象，否则信封会被双重编码“碰巧”看起来正确。
     */
    @Test
    fun vercelStatePartsSurviveEscapeHeavyState() {
        val state = buildJsonObject {
            put("goal", "打开设置里的「快速判断」")
            put("quote", "he said \"ok\"")
            put("multiline", "line1\nline2\tend")
            put("backslash", "C:\\tmp\\x")
        }
        val body = client.encodeRequestBody(
            model = "typesafe-ai/jev-1.14.0",
            state = state,
            questions = mapOf("q1" to JevQuestion.Noul("q")),
            apiMode = JevApiMode.VERCEL,
        )
        val text = Json.parseToJsonElement(body).jsonObject
            .getValue("stateParts").jsonArray.first().jsonObject
            .getValue("text").jsonPrimitive.content
        // 解一层得到的必须是原对象的等价 JSON
        assertEquals(state, Json.parseToJsonElement(text))
    }

    @Test
    fun vercelCallSendsDecisionModelHeadersAndEndpoint() = runTest {
        val transport = FakeTransport { vercelNoulSuccess("q1" to 0.9) }
        val headerClient = JevClient(transport = transport)
        val result = headerClient.call(
            apiMode = JevApiMode.VERCEL,
            endpoint = jevEndpointFor(JevApiMode.VERCEL, null),
            model = "typesafe-ai/jev-1.14.0",
            apiKey = "k",
            state = buildJsonObject { put("task", "t") },
            questions = mapOf("q1" to JevQuestion.Noul("q")),
        )
        assertTrue(result is JevCallResult.Success)
        val request = transport.requests.single()
        assertEquals("https://ai-gateway.vercel.sh/v4/ai/decision-model", request.url)
        assertEquals("typesafe-ai/jev-1.14.0", request.headers["ai-model-id"])
        assertEquals("0.0.1", request.headers["ai-gateway-protocol-version"])
        assertEquals("api-key", request.headers["ai-gateway-auth-method"])
        assertEquals("4", request.headers["ai-decision-model-specification-version"])
        // TYPESAFE 不带协议附加头
        headerClient.call(
            apiMode = JevApiMode.TYPESAFE,
            endpoint = JevLimits.ENDPOINT,
            model = "jev-1.14.0",
            apiKey = "k",
            state = buildJsonObject { put("task", "t") },
            questions = mapOf("q1" to JevQuestion.Noul("q")),
        )
        assertTrue(transport.requests.last().headers.isEmpty())
    }

    @Test
    fun vercelDecodeReadsTopLevelAnswers() {
        val body = """
            {"model":"typesafe-ai/jev-1.14.0","usage":{"inputTokens":3,"outputTokens":2},"answers":{
              "q1":{"type":"boolean","probability":0.8}
            }}
        """.trimIndent()
        val decoded = client.decodeResponse(
            body,
            mapOf("q1" to JevQuestion.Noul("q")),
            apiMode = JevApiMode.VERCEL,
        )
        assertEquals(0.8, (decoded.answers["q1"] as JevAnswer.Noul).probability, 0.0)
        assertEquals(3, decoded.usage?.inputTokens)
        assertEquals(2, decoded.usage?.outputTokens)
        assertEquals("typesafe-ai/jev-1.14.0", decoded.model)
    }

    @Test
    fun vercelDecodeReadsConfidenceFromProviderMetadata() {
        val body = """
            {"answers":{"q2":{"type":"choice","choice":"a","probabilities":{"a":0.7,"b":0.3}}},
             "providerMetadata":{"typesafe":{"confidence":{"q2":0.6}}}}
        """.trimIndent()
        val decoded = client.decodeResponse(
            body,
            mapOf("q2" to JevQuestion.Choice("q", mapOf("a" to null, "b" to null))),
            apiMode = JevApiMode.VERCEL,
        )
        val answer = decoded.answers["q2"] as JevAnswer.Choice
        assertEquals("a", answer.selected)
        assertEquals(0.6, answer.confidence!!, 0.0)
    }

    @Test
    fun vercelDecodeFallsBackToSelectedProbability() {
        // 无 providerMetadata 时，置信取 probabilities 分布中被选项的概率
        val body = """
            {"answers":{"q2":{"type":"choice","choice":"a","probabilities":{"a":0.7,"b":0.3}}}}
        """.trimIndent()
        val decoded = client.decodeResponse(
            body,
            mapOf("q2" to JevQuestion.Choice("q", mapOf("a" to null, "b" to null))),
            apiMode = JevApiMode.VERCEL,
        )
        assertEquals(0.7, (decoded.answers["q2"] as JevAnswer.Choice).confidence!!, 0.0)
    }

    @Test
    fun vercelDecodeToleratesMissingConfidenceAndUsage() {
        // 评估协议置信可缺省（低置信门按 null 放行）；异构网关可能不给 usage 字段
        val body = """{"answers":{"q1":{"type":"boolean","probability":0.5}}}"""
        val decoded = client.decodeResponse(
            body,
            mapOf("q1" to JevQuestion.Noul("q")),
            apiMode = JevApiMode.VERCEL,
        )
        assertNull(decoded.usage)
    }

    @Test(expected = JevDecodeException::class)
    fun vercelDecodeRejectsScoreOutOfRange() {
        // 评估协议 score ∈ [0, levels.size-1]
        client.decodeResponse(
            """{"answers":{"q3":{"type":"score","score":3}}}""",
            mapOf("q3" to JevQuestion.Score("q", listOf("low", "high"))),
            apiMode = JevApiMode.VERCEL,
        )
    }

    @Test(expected = JevDecodeException::class)
    fun vercelDecodeRejectsMissingAnswers() {
        client.decodeResponse(
            """{"model":"x"}""",
            mapOf("q1" to JevQuestion.Noul("q")),
            apiMode = JevApiMode.VERCEL,
        )
    }

    @Test(expected = JevDecodeException::class)
    fun vercelDecodeStillRejectsMissingAnswer() {
        // 客户端逐题校验是最终裁决
        client.decodeResponse(
            """{"answers":{}}""",
            mapOf("q1" to JevQuestion.Noul("q")),
            apiMode = JevApiMode.VERCEL,
        )
    }

    @Test
    fun endpointResolution() {
        assertEquals(JevLimits.ENDPOINT, jevEndpointFor(JevApiMode.TYPESAFE, "https://ignored.example"))
        assertEquals(
            "https://ai-gateway.vercel.sh/v4/ai/decision-model",
            jevEndpointFor(JevApiMode.VERCEL, null),
        )
        assertEquals(
            "https://ai-gateway.vercel.sh/v4/ai/decision-model",
            jevEndpointFor(JevApiMode.VERCEL, "  "),
        )
        assertEquals(
            "https://gw.example/v4/ai/decision-model",
            jevEndpointFor(JevApiMode.VERCEL, "https://gw.example/v4/ai/"),
        )
        assertEquals(
            "https://gw.example/v4/ai/decision-model",
            jevEndpointFor(JevApiMode.VERCEL, "  https://gw.example/v4/ai//  "),
        )
        // 第三方 decision 服务用各自固定端点；baseUrl 只属于 VERCEL 覆盖
        assertEquals(
            JevLimits.PERPLEXITY_ENDPOINT,
            jevEndpointFor(JevApiMode.PERPLEXITY, "https://ignored.example"),
        )
        assertEquals(
            JevLimits.LIQUID_ENDPOINT,
            jevEndpointFor(JevApiMode.LIQUID, "https://ignored.example"),
        )
    }

    /** Perplexity / Liquid 与原生 systemone 同构：body 带 model，只差 endpoint 与模型 id。 */
    @Test
    fun thirdPartyProvidersUseSystemoneEnvelope() {
        listOf(JevApiMode.PERPLEXITY to "pplx-decider-v1.2", JevApiMode.LIQUID to "d1").forEach { (mode, model) ->
            val body = client.encodeRequestBody(
                model = model,
                state = buildJsonObject { put("task", "t") },
                questions = mapOf("q1" to JevQuestion.Noul("Is it relevant?")),
                apiMode = mode,
            )
            val root = Json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("model", "state", "questions"), root.keys)
            assertEquals(model, root.getValue("model").jsonPrimitive.content)
            // 题型名同原生（noul，而不是评估协议的 boolean）
            assertEquals(
                "noul",
                root.getValue("questions").jsonObject.getValue("q1").jsonObject
                    .getValue("type").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun thirdPartyProvidersDecodeSystemoneAnswersAndSendNoExtraHeaders() = runTest {
        listOf(JevApiMode.PERPLEXITY, JevApiMode.LIQUID).forEach { mode ->
            val transport = FakeTransport { noulSuccess("q1" to 0.7) }
            val providerClient = JevClient(transport = transport)
            val result = providerClient.call(
                apiMode = mode,
                endpoint = jevEndpointFor(mode, null),
                model = jevDefaultModelFor(mode),
                apiKey = "k",
                state = buildJsonObject { put("task", "t") },
                questions = mapOf("q1" to JevQuestion.Noul("q")),
            )
            assertTrue("$mode should decode systemone answers", result is JevCallResult.Success)
            assertEquals(
                0.7,
                ((result as JevCallResult.Success).answers.getValue("q1") as JevAnswer.Noul).probability,
                0.0,
            )
            // 只有 Bearer（由传输层附加）；协议附加头只属于 Vercel 网关
            assertTrue("$mode must not send gateway headers", transport.requests.single().headers.isEmpty())
        }
    }

    @Test
    fun defaultAndConnectionTestModelsPerProvider() {
        assertEquals("jev-1.14.0", jevDefaultModelFor(JevApiMode.TYPESAFE))
        assertEquals("typesafe-ai/jev-1.14.0", jevDefaultModelFor(JevApiMode.VERCEL))
        assertEquals("pplx-decider-v1.2", jevDefaultModelFor(JevApiMode.PERPLEXITY))
        assertEquals("d1", jevDefaultModelFor(JevApiMode.LIQUID))
        assertEquals(JevLimits.CONNECTION_TEST_MODEL, jevConnectionTestModelFor(JevApiMode.TYPESAFE))
        assertEquals("pplx-decider-v1.2", jevConnectionTestModelFor(JevApiMode.PERPLEXITY))
        assertEquals("d1", jevConnectionTestModelFor(JevApiMode.LIQUID))
    }

    /** 每家供应商的模型字段互不串味：切换方言不会把上一家的模型 id 带到新家。 */
    @Test
    fun settingKeepsModelPerProvider() {
        val setting = JevSetting()
            .withModel(JevApiMode.TYPESAFE, "jev-1.13.0")
            .withModel(JevApiMode.VERCEL, "typesafe-ai/jev-1.14.0")
            .withModel(JevApiMode.PERPLEXITY, "pplx-decider-v1.2")
            .withModel(JevApiMode.LIQUID, "d1-ultra")
        assertEquals("jev-1.13.0", setting.modelFor(JevApiMode.TYPESAFE))
        assertEquals("typesafe-ai/jev-1.14.0", setting.modelFor(JevApiMode.VERCEL))
        assertEquals("pplx-decider-v1.2", setting.modelFor(JevApiMode.PERPLEXITY))
        assertEquals("d1-ultra", setting.modelFor(JevApiMode.LIQUID))
        assertNull(setting.withModel(JevApiMode.LIQUID, null).modelFor(JevApiMode.LIQUID))
        // 清空一家不影响其他家
        assertEquals("d1-ultra", setting.withModel(JevApiMode.PERPLEXITY, null).modelFor(JevApiMode.LIQUID))
    }

    @Test
    fun parseRetryAfterSecondsOnly() {
        assertEquals(2_000L, "2".parseRetryAfterMs())
        assertNull("Wed, 21 Oct 2015 07:28:00 GMT".parseRetryAfterMs())
    }
}

private class FakeTransport(
    var responder: suspend (JevHttpRequest) -> JevTransportResponse,
) : JevTransport {
    val requests = mutableListOf<JevHttpRequest>()

    override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
        requests += request
        return responder(request)
    }
}

private fun httpOk(body: String) = JevTransportResponse.Http(200, body.toByteArray(Charsets.UTF_8), null)
private fun httpError(code: Int, retryAfter: String? = null) = JevTransportResponse.Http(code, null, retryAfter)

private fun noulSuccess(vararg probabilities: Pair<String, Double>) = httpOk(
    buildString {
        append("""{"model":"jev-test","usage":{"input_tokens":1,"output_tokens":1},"answers":{""")
        probabilities.joinTo(this) { (id, p) -> """"$id":{"type":"noul","noul":$p}""" }
        append("}}")
    },
)

/** decision-model 信封：顶层 answers，noul 题为 {type:boolean, probability}。 */
private fun vercelNoulSuccess(vararg probabilities: Pair<String, Double>) = httpOk(
    buildJsonObject {
        put("model", "typesafe-ai/jev-1.14.0")
        put("usage", buildJsonObject {
            put("inputTokens", 1)
            put("outputTokens", 1)
        })
        put("answers", buildJsonObject {
            probabilities.forEach { (id, p) ->
                put(id, buildJsonObject {
                    put("type", "boolean")
                    put("probability", p)
                })
            }
        })
    }.toString(),
)

private val activeConfig = JevRuntimeConfig(
    mode = JevMode.ACTIVE,
    allowedScopes = JevDataScope.entries.toSet(),
    model = "jev-test",
    policyVersion = 1,
    endpoint = JevLimits.ENDPOINT,
)

private val taskState: JsonElement = buildJsonObject { put("task", "test") }

private fun noulQuestions(vararg ids: String): Map<String, JevQuestion> =
    ids.associateWith { JevQuestion.Noul("relevant?") }

class JevDecisionCoordinatorTest {

    private fun coordinator(
        transport: FakeTransport,
        key: String? = "k",
        clock: () -> Long = { 1_000_000L },
    ) = JevDecisionCoordinator(
        client = JevClient(transport = transport),
        apiKeyProvider = { key },
        usageStore = JevUsageStore.IN_MEMORY,
        clock = clock,
    )

    @Test
    fun offModeShortCircuitsWithoutNetworkOrMetrics() = runTest {
        val transport = FakeTransport { httpOk("{}") }
        val coordinator = coordinator(transport)
        val decision = coordinator.decide(
            purpose = JevPurpose.MEMORY_RECALL,
            config = activeConfig.copy(mode = JevMode.OFF),
            runKey = "run-1",
            state = taskState,
            questions = noulQuestions("q1"),
            requiredScopes = setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Skipped && decision.reason == JevSkipReason.OFF)
        assertEquals(0, transport.requests.size)
        assertEquals(0, coordinator.metrics.snapshot().size)
    }

    @Test
    fun missingScopeSkips() = runTest {
        val transport = FakeTransport { httpOk("{}") }
        val decision = coordinator(transport).decide(
            purpose = JevPurpose.MEMORY_RECALL,
            config = activeConfig.copy(allowedScopes = setOf(JevDataScope.TOOL_METADATA)),
            runKey = null,
            state = taskState,
            questions = noulQuestions("q1"),
            requiredScopes = setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Skipped && decision.reason == JevSkipReason.SCOPE_NOT_ALLOWED)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun successIsEvaluatedAndCached() = runTest {
        val transport = FakeTransport { noulSuccess("q1" to 0.9) }
        val coordinator = coordinator(transport)
        val first = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY), cacheAnchor = "anchor",
        )
        val second = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY), cacheAnchor = "anchor",
        )
        assertTrue(first is JevDecision.Evaluated && !first.fromCache)
        assertTrue(second is JevDecision.Evaluated && second.fromCache)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun queuedRequestDoesNotSendAfterCredentialOrScopeChange() = runTest {
        val firstResponse = CompletableDeferred<Unit>()
        val requests = mutableListOf<JevHttpRequest>()
        val transport = JevTransport { request ->
            requests += request
            if (requests.size == 1) firstResponse.await()
            noulSuccess("q1" to 0.9)
        }
        val coordinator = JevDecisionCoordinator(JevClient(transport), { "key" })
        suspend fun decide() = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "queued-memory", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        val first = async { decide() }
        runCurrent()
        val queued = async { decide() }
        runCurrent()
        assertEquals(1, requests.size)

        coordinator.onCredentialOrScopeChanged()
        firstResponse.complete(Unit)
        first.await()
        val obsolete = queued.await()

        assertEquals("a queued memory request must not send after scope revocation", 1, requests.size)
        assertTrue(obsolete !is JevDecision.Evaluated)
    }

    @Test
    fun retryDoesNotSendAfterCredentialOrScopeChange() = runTest {
        var requests = 0
        lateinit var coordinator: JevDecisionCoordinator
        val transport = JevTransport {
            requests++
            if (requests == 1) {
                coordinator.onCredentialOrScopeChanged()
                JevTransportResponse.Failure("socket reset")
            } else {
                noulSuccess("q1" to 0.9)
            }
        }
        coordinator = JevDecisionCoordinator(JevClient(transport), { "key" })

        val decision = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "retry-memory", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )

        assertEquals("scope revocation must also prevent a retry", 1, requests)
        assertTrue(decision !is JevDecision.Evaluated)
    }

    @Test
    fun lateUnauthorizedResponseFromOldKeyDoesNotPauseNewKey() = runTest {
        var key = "old-key"
        val oldResponse = CompletableDeferred<Unit>()
        val requests = mutableListOf<JevHttpRequest>()
        val transport = JevTransport { request ->
            requests += request
            if (request.apiKey == "old-key") {
                oldResponse.await()
                httpError(401)
            } else {
                noulSuccess("q1" to 0.9)
            }
        }
        val coordinator = JevDecisionCoordinator(JevClient(transport), { key })
        suspend fun decide() = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        val old = async { decide() }
        runCurrent()
        key = "new-key"
        coordinator.onCredentialOrScopeChanged()
        oldResponse.complete(Unit)
        old.await()

        val current = decide()

        assertTrue("an old 401 must not pause the replacement key", current is JevDecision.Evaluated)
        assertTrue(!coordinator.statusSnapshot().authPaused)
        assertEquals(listOf("old-key", "new-key"), requests.map { it.apiKey })
    }

    @Test
    fun lateSuccessCannotRepopulateCacheAfterCredentialOrScopeChange() = runTest {
        val oldResponse = CompletableDeferred<Unit>()
        var requests = 0
        val transport = JevTransport {
            requests++
            if (requests == 1) {
                oldResponse.await()
                noulSuccess("q1" to 0.9)
            } else {
                noulSuccess("q1" to 0.1)
            }
        }
        val coordinator = JevDecisionCoordinator(JevClient(transport), { "key" })
        suspend fun decide() = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY), cacheAnchor = "same-memory",
        )
        val old = async { decide() }
        runCurrent()
        coordinator.onCredentialOrScopeChanged()
        oldResponse.complete(Unit)
        old.await()

        val current = decide()

        assertEquals("invalidated requests must not refill the cleared cache", 2, requests)
        assertTrue(current is JevDecision.Evaluated && !current.fromCache)
        assertEquals(JevAnswer.Noul(0.1), (current as JevDecision.Evaluated).answers["q1"])
    }

    @Test
    fun lateTransientResponseCannotStartCooldownAfterCredentialOrScopeChange() = runTest {
        val oldResponse = CompletableDeferred<Unit>()
        var requests = 0
        val transport = JevTransport {
            when (++requests) {
                1, 2 -> JevTransportResponse.Failure("socket reset")
                3 -> {
                    oldResponse.await()
                    JevTransportResponse.Failure("old endpoint failed")
                }
                else -> noulSuccess("q1" to 0.9)
            }
        }
        val coordinator = JevDecisionCoordinator(JevClient(transport), { "key" })
        suspend fun decide() = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decide() is JevDecision.Failed)
        val old = async { decide() }
        runCurrent()
        coordinator.onCredentialOrScopeChanged()
        oldResponse.complete(Unit)
        old.await()

        assertEquals(0L, coordinator.statusSnapshot().cooldownRemainingMs)
        assertTrue("an obsolete failure must not cool down the current configuration", decide() is JevDecision.Evaluated)
        assertEquals(4, requests)
    }

    @Test
    fun lateConnectionTestCannotClearCurrentKeyAuthenticationPause() = runTest {
        val oldResponse = CompletableDeferred<Unit>()
        var key = "old-key"
        val transport = JevTransport { request ->
            if (request.apiKey == "old-key") {
                oldResponse.await()
                noulSuccess("prime" to 0.99)
            } else {
                httpError(401)
            }
        }
        val coordinator = JevDecisionCoordinator(JevClient(transport), { key })
        val old = async { coordinator.connectionTest() }
        runCurrent()
        key = "new-key"
        coordinator.onCredentialOrScopeChanged()
        val current = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(current is JevDecision.Failed && current.reason == JevFailureReason.AUTH)
        oldResponse.complete(Unit)
        val obsolete = old.await()

        assertTrue("a connection test of an old key cannot validate the replacement key", !obsolete.ok)
        assertTrue(coordinator.statusSnapshot().authPaused)
    }

    @Test
    fun authErrorPausesUntilConnectionTestResets() = runTest {
        val transport = FakeTransport { httpError(401) }
        val coordinator = coordinator(transport)
        val first = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(first is JevDecision.Failed && first.reason == JevFailureReason.AUTH)
        val second = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(second is JevDecision.Skipped && second.reason == JevSkipReason.AUTH_PAUSED)
        transport.responder = { noulSuccess("prime" to 0.99) }
        val test = coordinator.connectionTest()
        assertTrue(test.ok)
        transport.responder = { noulSuccess("q1" to 0.9) }
        val third = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(third is JevDecision.Evaluated)
    }

    @Test
    fun rateLimitWithoutRetryAfterFails() = runTest {
        val transport = FakeTransport { httpError(429) }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Failed && decision.reason == JevFailureReason.RATE_LIMITED)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun transientFailureRetriesOnceThenSucceeds() = runTest {
        var calls = 0
        val transport = FakeTransport { _ ->
            calls++
            if (calls == 1) JevTransportResponse.Failure("socket reset") else noulSuccess("q1" to 0.7)
        }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Evaluated)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun threeConsecutiveTransientFailuresEnterCooldown() = runTest {
        val transport = FakeTransport { JevTransportResponse.Failure("down") }
        val coordinator = coordinator(transport)
        // 第 1 次调用内部重试一次积累 2 个暂时失败；第 2 次调用的首个失败
        // 达到 3 连，进入 60s 冷却并返回失败。
        val first = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(first is JevDecision.Failed)
        val second = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-2", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(second is JevDecision.Failed)
        val cooled = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-3", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(cooled is JevDecision.Skipped && cooled.reason == JevSkipReason.COOLDOWN)
    }

    @Test
    fun decodeFailureIsTerminalWithoutRetry() = runTest {
        // 响应缺题 → 解码异常：确定性失败，不重试，报告 INVALID_RESPONSE
        val transport = FakeTransport { httpOk("""{"model":"jev-test","answers":{}}""") }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Failed && decision.reason == JevFailureReason.INVALID_RESPONSE)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun perRunBudgetExhaustsAfterLimit() = runTest {
        val transport = FakeTransport { noulSuccess("q1" to 0.9) }
        val coordinator = coordinator(transport)
        repeat(JevLimits.PER_RUN_MAX_REQUESTS) {
            val decision = coordinator.decide(
                JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
                noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
            )
            assertTrue(decision is JevDecision.Evaluated)
        }
        val next = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(next is JevDecision.Skipped && next.reason == JevSkipReason.BUDGET_EXHAUSTED)
        assertEquals(JevLimits.PER_RUN_MAX_REQUESTS, transport.requests.size)
    }

    @Test
    fun oversizedStateSkipsWithoutNetwork() = runTest {
        val transport = FakeTransport { httpOk("{}") }
        val bigState = buildJsonObject { put("blob", "x".repeat(JevLimits.MAX_STATE_BYTES + 1)) }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, bigState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Skipped && decision.reason == JevSkipReason.STATE_TOO_LARGE)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun deadlineExpiryFailsWithTimeout() = runTest {
        val transport = FakeTransport { delay(60_000); httpOk("{}") }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Failed && decision.reason == JevFailureReason.TIMEOUT)
    }

    @Test
    fun vercelModeWithoutModelSkipsNoModel() = runTest {
        val transport = FakeTransport { vercelNoulSuccess("q1" to 0.9) }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL,
            activeConfig.copy(
                apiMode = JevApiMode.VERCEL,
                endpoint = "https://ai-gateway.vercel.sh/v4/ai/decision-model",
                model = "",
            ),
            null, taskState, noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Skipped && decision.reason == JevSkipReason.NO_MODEL)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun cacheIsPartitionedByApiModeAndEndpoint() = runTest {
        // 同 anchor 不同方言/端点不得命中彼此的缓存；同配置第二次才命中
        val transport = FakeTransport { request ->
            if (request.url.endsWith("/decision-model")) {
                vercelNoulSuccess("q1" to 0.9)
            } else {
                noulSuccess("q1" to 0.9)
            }
        }
        val coordinator = coordinator(transport)
        val vercelConfig = activeConfig.copy(
            apiMode = JevApiMode.VERCEL,
            endpoint = "https://gw-a.example/v4/ai/decision-model",
        )
        val scopes = setOf(JevDataScope.PERSONAL_MEMORY)
        coordinator.decide(JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState, noulQuestions("q1"), scopes, cacheAnchor = "anchor")
        val otherMode = coordinator.decide(JevPurpose.MEMORY_RECALL, vercelConfig, "run-1", taskState, noulQuestions("q1"), scopes, cacheAnchor = "anchor")
        val otherEndpoint = coordinator.decide(
            JevPurpose.MEMORY_RECALL,
            vercelConfig.copy(endpoint = "https://gw-b.example/v4/ai/decision-model"),
            "run-1", taskState, noulQuestions("q1"), scopes, cacheAnchor = "anchor",
        )
        val cached = coordinator.decide(JevPurpose.MEMORY_RECALL, vercelConfig, "run-1", taskState, noulQuestions("q1"), scopes, cacheAnchor = "anchor")
        assertTrue(otherMode is JevDecision.Evaluated && !otherMode.fromCache)
        assertTrue(otherEndpoint is JevDecision.Evaluated && !otherEndpoint.fromCache)
        assertTrue(cached is JevDecision.Evaluated && cached.fromCache)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun vercelConnectionTestDefaultsModelAndHitsDecisionEndpoint() = runTest {
        // 未指定模型时连接测试用已验收固定版本探活
        val transport = FakeTransport { vercelNoulSuccess("prime" to 0.99) }
        val coordinator = coordinator(transport)
        val ok = coordinator.connectionTest(apiMode = JevApiMode.VERCEL)
        assertTrue(ok.ok)
        val request = transport.requests.single()
        assertTrue(request.url.endsWith("/decision-model"))
        assertEquals("typesafe-ai/jev-1.14.0", request.headers["ai-model-id"])
    }
}

/** 图片判断与 Box：能力面、两套信封的编码、以及 Box 的数值校验。 */
class JevMultimodalTest {

    private val client = JevClient(transport = { throw IllegalStateException("not under test") })

    private fun asset(reference: String = "<<IMAGE:1>>") = JevImageAsset(
        reference = reference,
        format = "jpeg",
        data = byteArrayOf(1, 2, 3),
        widthPx = 900,
        heightPx = 1200,
    )

    @Test
    fun nativeEncodeCarriesAssetsAndDangerZoneHeader() = runTest {
        val transport = FakeTransport { noulSuccess("q1" to 0.9) }
        val result = JevClient(transport = transport).call(
            apiMode = JevApiMode.TYPESAFE,
            endpoint = JevLimits.ENDPOINT,
            model = "jev-1.14.0",
            apiKey = "k",
            state = buildJsonObject { put("screen", "<<IMAGE:1>>") },
            questions = mapOf("q1" to JevQuestion.Noul("q")),
            assets = mapOf("<<IMAGE:1>>" to asset()),
        )
        assertTrue(result is JevCallResult.Success)
        val request = transport.requests.single()
        assertEquals("true", request.headers["TypeSafe-Danger-Zone-Images"])
        val body = Json.parseToJsonElement(request.body).jsonObject
        val assets = body.getValue("assets").jsonObject
        assertEquals(setOf("<<IMAGE:1>>"), assets.keys)
        assertEquals("jpeg", assets.getValue("<<IMAGE:1>>").jsonObject.getValue("format").jsonPrimitive.content)
        // 状态里的结构化字段保持原样：图片只是旁挂资产
        assertTrue(body.getValue("state").jsonObject.getValue("screen").jsonPrimitive.content.contains("IMAGE"))
    }

    @Test
    fun gatewayEncodeSplitsStatePartsAroundTheReference() {
        val body = client.encodeRequestBody(
            model = "typesafe-ai/jev-1.14.0",
            state = buildJsonObject { put("screen", "before<<IMAGE:1>>after") },
            questions = mapOf("q1" to JevQuestion.Noul("q")),
            apiMode = JevApiMode.VERCEL,
            assets = mapOf("<<IMAGE:1>>" to asset()),
        )
        val parts = Json.parseToJsonElement(body).jsonObject.getValue("stateParts").jsonArray
        // 文本 / file / 文本，顺序与参考标记在 state 中的位置一致
        assertEquals(3, parts.size)
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("file", parts[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("image/jpeg", parts[1].jsonObject.getValue("mediaType").jsonPrimitive.content)
        assertEquals("text", parts[2].jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("before"))
        assertTrue(parts[2].jsonObject.getValue("text").jsonPrimitive.content.contains("after"))
    }

    @Test
    fun providersWithoutImageSupportRejectAssets() {
        listOf(JevApiMode.PERPLEXITY, JevApiMode.LIQUID).forEach { mode ->
            val error = runCatching {
                client.encodeRequestBody(
                    model = jevDefaultModelFor(mode),
                    state = buildJsonObject { put("screen", "<<IMAGE:1>>") },
                    questions = mapOf("q1" to JevQuestion.Noul("q")),
                    apiMode = mode,
                    assets = mapOf("<<IMAGE:1>>" to asset()),
                )
            }.exceptionOrNull()
            assertTrue("$mode must reject images", error is IllegalArgumentException)
        }
    }

    @Test
    fun boxQuestionOnlyOnNativeApi() {
        val questions = mapOf("where" to JevQuestion.Box("Locate the button", "primary button"))
        val error = runCatching {
            client.encodeRequestBody(
                model = "typesafe-ai/jev-1.14.0",
                state = buildJsonObject { put("screen", "x") },
                questions = questions,
                apiMode = JevApiMode.VERCEL,
            )
        }.exceptionOrNull()
        assertTrue("gateway has no box type", error is IllegalArgumentException)

        val native = client.encodeRequestBody(
            model = "jev-1.14.0",
            state = buildJsonObject { put("screen", "x") },
            questions = questions,
            apiMode = JevApiMode.TYPESAFE,
        )
        assertEquals(
            "box",
            Json.parseToJsonElement(native).jsonObject.getValue("questions").jsonObject
                .getValue("where").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun missingReferenceInStateIsRejected() {
        val error = runCatching {
            client.encodeRequestBody(
                model = "typesafe-ai/jev-1.14.0",
                state = buildJsonObject { put("screen", "no marker here") },
                questions = mapOf("q1" to JevQuestion.Noul("q")),
                apiMode = JevApiMode.VERCEL,
                assets = mapOf("<<IMAGE:1>>" to asset()),
            )
        }.exceptionOrNull()
        assertTrue("a dropped asset must not silently degrade the judgement", error is IllegalArgumentException)
    }

    @Test
    fun boxAnswerIsDecodedWithRangeValidation() {
        val questions = mapOf("where" to JevQuestion.Box("Locate"))
        val ok = client.decodeResponse(
            """{"answers":{"where":{"type":"box","box":{"left":0.1,"top":0.2,"right":0.6,"bottom":0.8},"confidence":0.9}}}""",
            questions,
        )
        val box = ok.answers.getValue("where") as JevAnswer.Box
        assertEquals(0.1, box.left, 1e-9)
        assertEquals(0.8, box.bottom, 1e-9)
        assertEquals(0.9, box.confidence!!, 1e-9)
    }

    @Test
    fun boxAnswerRejectsOutOfRangeAndInvertedEdges() {
        val questions = mapOf("where" to JevQuestion.Box("Locate"))
        val outOfRange = runCatching {
            client.decodeResponse(
                """{"answers":{"where":{"type":"box","box":{"left":1.2,"top":0.2,"right":0.6,"bottom":0.8}}}}""",
                questions,
            )
        }.exceptionOrNull()
        assertTrue(outOfRange is JevDecodeException)

        val inverted = runCatching {
            client.decodeResponse(
                """{"answers":{"where":{"type":"box","box":{"left":0.6,"top":0.8,"right":0.2,"bottom":0.1}}}}""",
                questions,
            )
        }.exceptionOrNull()
        assertTrue(inverted is JevDecodeException)
    }

    @Test
    fun imageEdgeRangeIsEnforcedWhenKnown() {
        val tooSmall = runCatching { asset().copy(widthPx = 100) }
        assertTrue(tooSmall.exceptionOrNull() is IllegalArgumentException)
    }
}
