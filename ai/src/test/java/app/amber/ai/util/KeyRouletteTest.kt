package app.amber.ai.util

import android.content.ContextWrapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeyRouletteTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun lruRotatesKeysWithoutPersistingTheirPlaintext() {
        val cache = temporary.newFolder("cache")
        val roulette = KeyRoulette.lru(object : ContextWrapper(null) {
            override fun getCacheDir(): File = cache
        })
        val keys = "sk-fixture-first,sk-fixture-second"
        assertEquals("sk-fixture-first", roulette.next(keys, "provider-a"))
        assertEquals("sk-fixture-second", roulette.next(keys, "provider-a"))
        assertEquals("sk-fixture-first", roulette.next(keys, "provider-a"))

        val persisted = cache.resolve("lru_key_roulette.json").readText()
        assertFalse(persisted.contains("sk-fixture-first"))
        assertFalse(persisted.contains("sk-fixture-second"))
        assertEquals(2, Json.parseToJsonElement(persisted).jsonObject.getValue("providers")
            .jsonObject.getValue("provider-a").jsonObject.size)
    }

    @Test fun firstNewWriteRemovesLegacyPlaintextForAllProviders() {
        val cache = temporary.newFolder("legacy-cache")
        val file = cache.resolve("lru_key_roulette.json")
        val now = System.currentTimeMillis()
        file.writeText("""{"provider-a":{"sk-legacy-active":$now},"provider-b":{"sk-legacy-other":$now}}""")
        val roulette = KeyRoulette.lru(object : ContextWrapper(null) {
            override fun getCacheDir(): File = cache
        })

        assertEquals("sk-legacy-active", roulette.next("sk-legacy-active", "provider-a"))

        val persisted = file.readText()
        assertFalse("Legacy key for the active provider must be scrubbed", persisted.contains("sk-legacy-active"))
        assertFalse("Legacy key for another provider must be scrubbed too", persisted.contains("sk-legacy-other"))
        assertTrue("Current provider still needs a rotation record", Json.parseToJsonElement(persisted)
            .jsonObject.getValue("providers").jsonObject.containsKey("provider-a"))
    }

    @Test fun legacyHexadecimalKeyIsMigratedRatherThanMistakenForAFingerprint() {
        val cache = temporary.newFolder("hex-key-cache")
        val key = "a".repeat(64)
        val file = cache.resolve("lru_key_roulette.json")
        file.writeText("""{"provider-a":{"$key":${System.currentTimeMillis()}}}""")
        val roulette = KeyRoulette.lru(object : ContextWrapper(null) {
            override fun getCacheDir(): File = cache
        })

        assertEquals(key, roulette.next(key, "provider-a"))
        assertFalse(file.readText().contains(key))
    }
}
