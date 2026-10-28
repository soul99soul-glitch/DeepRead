package app.amber.ai.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天
private const val LRU_CACHE_VERSION = 2

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// Keys are one-way fingerprints; provider credentials stay in memory only.
private typealias LruCache = Map<String, Map<String, Long>>

@Serializable
private data class PersistedLruCache(val version: Int, val providers: LruCache)

private fun keyFingerprint(key: String): String =
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        val keysByFingerprint = keyList.associateBy(::keyFingerprint)

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()

            // 取本 provider 的记录，过滤掉已过期条目和不在当前 key 列表中的条目
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (fingerprint, lastUsed) -> fingerprint in keysByFingerprint && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = keysByFingerprint.keys.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache

            // 清理整个 provider 条目均已过期的记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return keysByFingerprint.getValue(selected)
        }
    }

    private fun loadCache(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            val raw = file.readText()
            val version = (Json.parseToJsonElement(raw).jsonObject["version"] as? JsonPrimitive)?.intOrNull
            if (version == LRU_CACHE_VERSION) {
                Json.decodeFromString<PersistedLruCache>(raw).providers
            } else {
                // The legacy format stored raw keys. Migrate every provider together
                // so the next write does not retain another provider's plaintext.
                Json.decodeFromString<LruCache>(raw).mapValues { (_, entries) ->
                    entries.mapKeys { (key, _) -> keyFingerprint(key) }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(
                Json.encodeToString(PersistedLruCache(LRU_CACHE_VERSION, cache))
            )
        } catch (_: Exception) {
        }
    }
}
