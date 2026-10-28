package app.amber.core.jev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * 判断编排：模式/范围/凭据/预算/并发/重试/冷却/缓存全在此收口。
 * 任何失败形态都返回 Skipped/Failed，调用方一律走原路径，不抛业务异常；
 * 用户取消（CancellationException）原样传播。
 */
class JevDecisionCoordinator(
    private val client: JevClient,
    private val apiKeyProvider: () -> String?,
    usageStore: JevUsageStore = JevUsageStore.IN_MEMORY,
    private val clock: () -> Long = System::currentTimeMillis,
    val metrics: JevMetrics = JevMetrics(),
) {
    private val budget = JevBudget(usageStore, clock)
    private val globalInflight = Semaphore(JevLimits.MAX_INFLIGHT_GLOBAL)
    private val runLocks = ConcurrentHashMap<String, Mutex>()
    private val cache = HashMap<String, CacheEntry>()

    @Volatile
    private var configurationRevision = 0L

    @Volatile
    private var authPaused = false

    @Volatile
    private var cooldownUntil = 0L

    private var consecutiveTransientFailures = 0

    @Synchronized
    private fun recordTransientFailure(requestRevision: Long): Boolean {
        if (requestRevision != configurationRevision) return false
        if (++consecutiveTransientFailures >= JevLimits.COOLDOWN_AFTER_CONSECUTIVE_FAILURES) {
            cooldownUntil = clock() + JevLimits.COOLDOWN_MS
            consecutiveTransientFailures = 0
            return false
        }
        return true
    }

    @Synchronized
    private fun resetTransientFailures() {
        consecutiveTransientFailures = 0
    }

    private class CacheEntry(
        val answers: Map<String, JevAnswer>,
        val usage: JevUsage?,
        val model: String?,
        val storedAt: Long,
    )

    /** Key 变化或收紧范围时失效缓存并解除认证暂停（由设置页调用）。 */
    @Synchronized
    fun onCredentialOrScopeChanged() {
        configurationRevision++
        synchronized(cache) { cache.clear() }
        authPaused = false
    }

    fun dailyUsage(): JevDailyUsage = budget.dailyUsage()

    /** 只读运行态快照（供设置页/诊断工具）；不含凭据与业务原文。 */
    fun statusSnapshot(): JevCoordinatorStatus = JevCoordinatorStatus(
        authPaused = authPaused,
        cooldownRemainingMs = (cooldownUntil - clock()).coerceAtLeast(0),
    )

    suspend fun decide(
        purpose: JevPurpose,
        config: JevRuntimeConfig,
        runKey: String?,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
        requiredScopes: Set<JevDataScope>,
        cacheAnchor: String? = null,
        assets: Map<String, JevImageAsset> = emptyMap(),
    ): JevDecision {
        val requestRevision = configurationRevision
        if (config.mode == JevMode.OFF) return JevDecision.Skipped(JevSkipReason.OFF)
        if (!config.allowedScopes.containsAll(requiredScopes)) {
            return skipped(purpose, config, JevSkipReason.SCOPE_NOT_ALLOWED)
        }
        if (questions.isEmpty()) return skipped(purpose, config, JevSkipReason.EMPTY_QUESTIONS)
        if (questions.size > JevLimits.MAX_QUESTIONS_PER_REQUEST) {
            return skipped(purpose, config, JevSkipReason.TOO_MANY_QUESTIONS)
        }
        if (state.toString().toByteArray(Charsets.UTF_8).size > JevLimits.MAX_STATE_BYTES) {
            return skipped(purpose, config, JevSkipReason.STATE_TOO_LARGE)
        }
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) return skipped(purpose, config, JevSkipReason.NO_KEY)
        if (config.model.isBlank()) return skipped(purpose, config, JevSkipReason.NO_MODEL)
        if (authPaused) return skipped(purpose, config, JevSkipReason.AUTH_PAUSED)
        if (clock() < cooldownUntil) return skipped(purpose, config, JevSkipReason.COOLDOWN)

        val body = try {
            client.encodeRequestBody(config.model, state, questions, config.apiMode, assets)
        } catch (e: IllegalArgumentException) {
            // 能力不匹配（图片/Box）或参考标记缺失：确定性错误，回退原路径。
            return failed(purpose, config, JevDecision.Failed(JevFailureReason.CLIENT_ERROR, e.message), clock(), 0)
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8).size.toLong()
        val bodyLimit = if (assets.isEmpty()) {
            JevLimits.MAX_REQUEST_BODY_BYTES
        } else {
            JevLimits.MAX_IMAGE_REQUEST_BODY_BYTES
        }
        if (bodyBytes > bodyLimit) {
            return skipped(purpose, config, JevSkipReason.REQUEST_TOO_LARGE)
        }
        // 预算按"状态字节"记：图片是载荷而不是状态，计入会让 512 KiB 的
        // screen 预算把任何带图请求直接判成 BUDGET_EXHAUSTED。
        val imageBytes = assets.values.sumOf { (it.data.size.toLong() * 4 + 2) / 3 }
        val stateChargeBytes = if (assets.isEmpty()) bodyBytes else (bodyBytes - imageBytes).coerceAtLeast(0)

        // 图片判断不缓存：复用旧画面等于用过期观察做决策。
        val cacheKey = cacheAnchor?.takeIf { assets.isEmpty() }?.let { anchor ->
            "${config.apiMode.name}|${config.endpoint}|${purpose.name}|${config.model}|v${config.policyVersion}|${config.allowedScopes.joinToString(",")}|$anchor"
        }
        if (cacheKey != null) {
            val cached = synchronized(cache) {
                val entry = cache[cacheKey]
                when {
                    entry == null -> null
                    clock() - entry.storedAt <= JevLimits.CACHE_TTL_MS -> entry
                    else -> {
                        cache.remove(cacheKey)
                        null
                    }
                }
            }
            if (cached != null) {
                if (requestRevision != configurationRevision) {
                    return skipped(purpose, config, JevSkipReason.CONFIGURATION_CHANGED)
                }
                metrics.record(
                    metric(purpose, config, JevMetricEntry.OUTCOME_CACHE_HIT, 0, bodyBytes, cached.usage, cached.model),
                )
                return JevDecision.Evaluated(cached.answers, cached.model, cached.usage, 0, fromCache = true)
            }
        }

        val startedAt = clock()
        val deadline = startedAt + jevDeadlineMs(config.apiMode)
        val runMutex = runKey?.let { key -> runLocks.computeIfAbsent(key) { Mutex() } }
        var attempts = 0
        var lastFailure: JevDecision.Failed = JevDecision.Failed(JevFailureReason.TIMEOUT)

        // 并发上限：全局 3 个（硬上限）；每 run 1 个为尽力串行——unlock 后的
        // remove 窗口里，等待方持有旧锁实例而新调用可建新锁，最坏同 run 短暂
        // 双在途，仍受全局信号量与预算约束，无状态破坏。
        try {
            withTimeout((deadline - clock()).coerceAtLeast(1)) { globalInflight.acquire() }
        } catch (e: TimeoutCancellationException) {
            return failed(purpose, config, lastFailure, startedAt, bodyBytes)
        } catch (e: CancellationException) {
            throw e
        }
        if (runMutex != null) {
            try {
                withTimeout((deadline - clock()).coerceAtLeast(1)) { runMutex.lock() }
            } catch (e: TimeoutCancellationException) {
                globalInflight.release()
                return failed(purpose, config, JevDecision.Failed(JevFailureReason.TIMEOUT), startedAt, bodyBytes)
            } catch (e: CancellationException) {
                globalInflight.release()
                throw e
            }
        }
        try {
            while (true) {
                // 排队和重试期间撤回授权后，不再发送旧配置下的业务数据。
                if (requestRevision != configurationRevision) {
                    return skipped(purpose, config, JevSkipReason.CONFIGURATION_CHANGED)
                }
                if (clock() >= deadline) {
                    lastFailure = JevDecision.Failed(JevFailureReason.TIMEOUT)
                    break
                }
                if (!budget.consume(runKey, 1, stateChargeBytes, purpose)) {
                    metrics.record(metric(purpose, config, "fallback:budget_exhausted", clock() - startedAt, bodyBytes, null, null))
                    return JevDecision.Skipped(JevSkipReason.BUDGET_EXHAUSTED)
                }
                attempts++
                val remaining = deadline - clock()
                val result = try {
                    withTimeout(remaining.coerceAtLeast(1)) {
                        client.call(config.apiMode, config.endpoint, config.model, apiKey, state, questions, assets)
                    }
                } catch (e: TimeoutCancellationException) {
                    JevCallResult.Transport("deadline")
                } catch (e: CancellationException) {
                    throw e
                }
                if (requestRevision != configurationRevision) {
                    return skipped(purpose, config, JevSkipReason.CONFIGURATION_CHANGED)
                }
                when (result) {
                    is JevCallResult.Success -> {
                        synchronized(this) {
                            // 与配置变更共用锁，避免旧响应在 clear 后重新填回缓存。
                            if (requestRevision != configurationRevision) {
                                return skipped(purpose, config, JevSkipReason.CONFIGURATION_CHANGED)
                            }
                            resetTransientFailures()
                            if (cacheKey != null) {
                                synchronized(cache) {
                                    if (cache.size >= JevLimits.CACHE_MAX_ENTRIES) {
                                        cache.remove(cache.keys.first())
                                    }
                                    cache[cacheKey] = CacheEntry(result.answers, result.usage, result.model, clock())
                                }
                            }
                        }
                        val outcome = if (config.mode == JevMode.ACTIVE) {
                            JevMetricEntry.OUTCOME_APPLIED
                        } else {
                            JevMetricEntry.OUTCOME_SHADOW
                        }
                        metrics.record(metric(purpose, config, outcome, clock() - startedAt, bodyBytes, result.usage, result.model))
                        return JevDecision.Evaluated(
                            answers = result.answers,
                            model = result.model,
                            usage = result.usage,
                            latencyMs = clock() - startedAt,
                            fromCache = false,
                        )
                    }
                    is JevCallResult.HttpError -> when {
                        result.code == 401 -> {
                            synchronized(this) {
                                if (requestRevision != configurationRevision) {
                                    return skipped(purpose, config, JevSkipReason.CONFIGURATION_CHANGED)
                                }
                                authPaused = true
                            }
                            lastFailure = JevDecision.Failed(JevFailureReason.AUTH, result.bodySnippet)
                            break
                        }
                        result.code == 429 -> {
                            val retryAfter = result.retryAfterMs
                            if (attempts <= JevLimits.TRANSIENT_RETRY_LIMIT &&
                                retryAfter != null &&
                                clock() + retryAfter < deadline
                            ) {
                                delay(retryAfter)
                                continue
                            }
                            lastFailure = JevDecision.Failed(JevFailureReason.RATE_LIMITED, result.bodySnippet)
                            break
                        }
                        result.code in 400..499 -> {
                            lastFailure = JevDecision.Failed(JevFailureReason.CLIENT_ERROR, result.bodySnippet)
                            break
                        }
                        else -> {
                            if (!retryTransientOrBreak(attempts, deadline, requestRevision)) {
                                lastFailure = JevDecision.Failed(JevFailureReason.SERVER_ERROR, result.bodySnippet)
                                break
                            }
                        }
                    }
                    is JevCallResult.Transport -> {
                        if (result.message == "deadline") {
                            lastFailure = JevDecision.Failed(JevFailureReason.TIMEOUT)
                            break
                        }
                        if (!retryTransientOrBreak(attempts, deadline, requestRevision)) {
                            lastFailure = JevDecision.Failed(JevFailureReason.NETWORK, result.message)
                            break
                        }
                    }
                    is JevCallResult.Decode -> {
                        // 协议漂移/校验失败是确定性错误：不重试，直接失败回退。
                        lastFailure = JevDecision.Failed(JevFailureReason.INVALID_RESPONSE, result.message)
                        break
                    }
                }
            }
        } finally {
            runMutex?.unlock()
            if (runKey != null && runMutex != null) runLocks.remove(runKey, runMutex)
            globalInflight.release()
        }

        return if (requestRevision != configurationRevision) {
            skipped(purpose, config, JevSkipReason.CONFIGURATION_CHANGED)
        } else {
            failed(purpose, config, lastFailure, startedAt, bodyBytes)
        }
    }

    /** 暂时性失败：预算与剩余时间内重试一次；连续 3 次进入 60s 冷却。 */
    private suspend fun retryTransientOrBreak(attempts: Int, deadline: Long, requestRevision: Long): Boolean {
        if (!recordTransientFailure(requestRevision)) return false
        if (attempts > JevLimits.TRANSIENT_RETRY_LIMIT) return false
        val remaining = deadline - clock()
        if (remaining <= JevLimits.RETRY_BACKOFF_MS) return false
        delay(JevLimits.RETRY_BACKOFF_MS)
        return true
    }

    /** 连接测试：独立请求身份，只用合成公开数据；成功解除认证暂停。 */
    suspend fun connectionTest(
        apiMode: JevApiMode = JevApiMode.TYPESAFE,
        baseUrl: String? = null,
        model: String? = null,
    ): JevConnectionTestResult {
        val requestRevision = configurationRevision
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) return JevConnectionTestResult(keyMissing = true)
        val testModel = model?.takeIf { it.isNotBlank() } ?: jevConnectionTestModelFor(apiMode)
        val question = JevQuestion.Noul(
            instructions = "Is 7 a prime number? Answer strictly from arithmetic.",
        )
        val state = buildJsonObject {
            put("task", "connection test with synthetic public data")
            put("data", "A prime number is a natural number greater than 1 whose only positive divisors are 1 and itself.")
        }
        val body = client.encodeRequestBody(testModel, state, mapOf("prime" to question), apiMode)
        val bodyBytes = body.toByteArray(Charsets.UTF_8).size.toLong()
        if (!budget.consume(null, 1, bodyBytes)) {
            return JevConnectionTestResult(budgetExhausted = true)
        }
        val startedAt = clock()
        val result = try {
            withTimeout(jevDeadlineMs(apiMode) * 2) {
                client.call(apiMode, jevEndpointFor(apiMode, baseUrl), testModel, apiKey, state, mapOf("prime" to question))
            }
        } catch (e: TimeoutCancellationException) {
            return JevConnectionTestResult(error = "timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return JevConnectionTestResult(error = e.message ?: e.javaClass.simpleName)
        }
        if (requestRevision != configurationRevision) {
            return JevConnectionTestResult(error = "configuration changed")
        }
        return when (result) {
            is JevCallResult.Success -> {
                val probability = (result.answers["prime"] as? JevAnswer.Noul)?.probability
                if (probability == null) {
                    JevConnectionTestResult(error = "unexpected answer shape")
                } else {
                    synchronized(this) {
                        if (requestRevision != configurationRevision) {
                            JevConnectionTestResult(error = "configuration changed")
                        } else {
                            authPaused = false
                            resetTransientFailures()
                            JevConnectionTestResult(
                                ok = true,
                                model = result.model ?: testModel,
                                latencyMs = clock() - startedAt,
                            )
                        }
                    }
                }
            }
            is JevCallResult.HttpError -> JevConnectionTestResult(
                error = "HTTP ${result.code}${result.bodySnippet?.take(80)?.let { ": $it" } ?: ""}",
            )
            is JevCallResult.Transport -> JevConnectionTestResult(error = result.message)
            is JevCallResult.Decode -> JevConnectionTestResult(error = "invalid response: ${result.message}")
        }
    }

    private fun skipped(purpose: JevPurpose, config: JevRuntimeConfig, reason: JevSkipReason): JevDecision {
        metrics.record(metric(purpose, config, "fallback:${reason.name.lowercase()}", 0, 0, null, null))
        return JevDecision.Skipped(reason)
    }

    private fun failed(
        purpose: JevPurpose,
        config: JevRuntimeConfig,
        failure: JevDecision.Failed,
        startedAt: Long,
        bodyBytes: Long,
    ): JevDecision.Failed {
        metrics.record(
            metric(
                purpose, config, JevMetricEntry.OUTCOME_FAILED, clock() - startedAt, bodyBytes, null, null,
                reason = failure.reason.name.lowercase(),
            ),
        )
        return failure
    }

    private fun metric(
        purpose: JevPurpose,
        config: JevRuntimeConfig,
        outcome: String,
        latencyMs: Long,
        requestBytes: Long,
        usage: JevUsage?,
        model: String?,
        reason: String? = null,
    ) = JevMetricEntry(
        timestamp = clock(),
        purpose = purpose,
        mode = config.mode,
        outcome = outcome,
        latencyMs = latencyMs,
        requestBytes = requestBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        usage = usage,
        model = model,
        reason = reason,
    )
}

/** 协调器运行态的只读快照；字段刻意保持元数据级别。 */
data class JevCoordinatorStatus(
    /** 最近一次 401 后进入认证暂停，直到凭据/范围变化或连接测试成功。 */
    val authPaused: Boolean,
    /** 连续暂时性失败后的冷却剩余毫秒；0 表示未在冷却。 */
    val cooldownRemainingMs: Long,
)

data class JevConnectionTestResult(
    val ok: Boolean = false,
    val keyMissing: Boolean = false,
    val budgetExhausted: Boolean = false,
    val model: String? = null,
    val latencyMs: Long = 0,
    val error: String? = null,
) {
    companion object {
        /** VERCEL 模式未配置网关模型 id 时的错误码（UI 据此显示专用文案）。 */
        const val ERROR_MODEL_REQUIRED = "model_required"
    }
}
