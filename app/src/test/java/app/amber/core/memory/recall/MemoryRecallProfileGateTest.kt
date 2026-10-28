package app.amber.core.memory.recall

import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import app.amber.agent.data.db.entity.MemoryEntity
import app.amber.ai.ui.UIMessage
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryProfile
import app.amber.core.memory.store.MemoryProfileStore
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryRecallProfileGateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun repository(records: List<MemoryEntity> = emptyList()): MemoryRepository = MemoryRepository(
        memoryDAO = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MemoryDAO::class.java)) { _, method, args ->
            when (method.name) {
                "getAllMemories" -> records
                "getActiveMemoriesByScopes" -> {
                    val scopes = args[0] as List<*>
                    val now = args[1] as Long
                    records.filter { it.scope in scopes && !it.archived && (it.expiresAt ?: Long.MAX_VALUE) > now }
                }
                "touchMemories" -> Unit
                else -> error("unexpected memory read: ${method.name}")
            }
        } as MemoryDAO,
        candidateDAO = proxy(MemoryCandidateDAO::class.java) { error("unexpected candidate call: $it") },
        eventDAO = proxy(MemoryEventDAO::class.java) { error("unexpected event call: $it") },
    )

    @Test
    fun disablingEveryMemoryScopeSuppressesTheStoredUserProfile() = runBlocking {
        val profileStore = MemoryProfileStore(folder.newFile())
        profileStore.put(MemoryProfile("用户偏好中文简洁回复。", System.currentTimeMillis()))
        val store = MemoryRecallStore(repository(), profileStore = profileStore)
        val settings = Settings(
            agentRuntime = AgentRuntimeSetting(
                enableCoreMemory = false,
                enableShortTermMemory = false,
                enableLongTermMemory = false,
            ),
        )

        val prompt = store.buildPrompt(settings, listOf(UIMessage.user("你好")))

        assertEquals("", prompt)
    }

    @Test
    fun enabledMemoryStillIncludesAFreshProfileWithoutIndividualRecords() = runBlocking {
        val profileStore = MemoryProfileStore(folder.newFile())
        val profileContent = "用户偏好中文简洁回复。"
        profileStore.put(MemoryProfile(profileContent, System.currentTimeMillis()))
        val store = MemoryRecallStore(repository(), profileStore = profileStore)

        val prompt = store.buildPrompt(Settings(), listOf(UIMessage.user("你好")))

        assertTrue(profileContent in prompt)
        assertTrue("<user_profile>" in prompt)
    }

    @Test
    fun disablingASourceScopeSuppressesTheMixedProfile() = runBlocking {
        val source = MemoryEntity(id = 1, assistantId = "__long_term__", scope = MemoryScope.LONG_TERM.wireName)
        val profileContent = "用户偏好中文简洁回复。"
        val profileStore = MemoryProfileStore(folder.newFile())
        profileStore.put(MemoryProfile(profileContent, System.currentTimeMillis(), listOf(source.id)))
        val store = MemoryRecallStore(repository(listOf(source)), profileStore = profileStore)
        val settings = Settings(agentRuntime = AgentRuntimeSetting(enableLongTermMemory = false))

        val prompt = store.buildPrompt(settings, listOf(UIMessage.user("你好")))

        assertTrue(profileContent !in prompt)
    }

    @Test
    fun sourceLessLegacyProfileRequiresAllScopesToBeEnabled() = runBlocking {
        val profileStore = MemoryProfileStore(folder.newFile())
        profileStore.put(MemoryProfile("用户偏好中文简洁回复。", System.currentTimeMillis()))
        val store = MemoryRecallStore(repository(), profileStore = profileStore)
        val settings = Settings(agentRuntime = AgentRuntimeSetting(enableLongTermMemory = false))

        assertEquals("", store.buildPrompt(settings, listOf(UIMessage.user("你好"))))
    }

    @Test
    fun expiredSourcesCannotKeepAProfileInRecall() = runBlocking {
        val source = MemoryEntity(
            id = 1,
            assistantId = "__long_term__",
            expiresAt = System.currentTimeMillis() - 1,
        )
        val profileStore = MemoryProfileStore(folder.newFile())
        profileStore.put(MemoryProfile("用户偏好中文简洁回复。", System.currentTimeMillis(), listOf(source.id)))
        val store = MemoryRecallStore(repository(listOf(source)), profileStore = profileStore)

        assertEquals("", store.buildPrompt(Settings(), listOf(UIMessage.user("你好"))))
    }

    @Test
    fun aFreshnessQuorumDoesNotPermitFactsFromAnArchivedSource() = runBlocking {
        val active = MemoryEntity(id = 1, assistantId = "__global__", scope = MemoryScope.CORE.wireName)
        val archived = MemoryEntity(id = 2, assistantId = "__long_term__", archived = true)
        val profileContent = "用户偏好中文简洁回复。"
        val profileStore = MemoryProfileStore(folder.newFile())
        profileStore.put(MemoryProfile(profileContent, System.currentTimeMillis(), listOf(active.id, archived.id)))
        val store = MemoryRecallStore(repository(listOf(active, archived)), profileStore = profileStore)

        val prompt = store.buildPrompt(Settings(), listOf(UIMessage.user("你好")))

        assertTrue(profileContent !in prompt)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, invoke: (String) -> Any?): T = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(type),
    ) { _, method, _ -> invoke(method.name) } as T
}
