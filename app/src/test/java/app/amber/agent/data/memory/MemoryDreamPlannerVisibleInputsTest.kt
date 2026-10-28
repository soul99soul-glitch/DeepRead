package app.amber.core.memory

import android.app.Application
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.files.CasTestFixtures
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.provider.providers.ClaudeProvider
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.core.memory.dream.MemoryDreamPlanner
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger
import app.amber.core.settings.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemoryDreamPlannerVisibleInputsTest {
    @Test
    fun modelPlanCanOnlyTargetRecordsAndCandidatesActuallyIncludedInItsPrompt() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = MemoryRepository(database.memoryDao(), database.memoryCandidateDao(), database.memoryEventDao())
            val now = System.currentTimeMillis()
            repeat(81) { index ->
                repository.upsertRecord(MemoryRecord(
                    id = 0, content = "Planner record ${index + 1}", scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.PROJECT, assistantId = MemoryRepository.SHORT_TERM_MEMORY_ID,
                    updatedAt = now,
                ))
            }
            repeat(51) { index ->
                repository.addCandidate(MemoryCandidate(
                    id = "candidate-$index", content = "Pending fact $index", scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.PROJECT, createdAt = now - index,
                ))
            }
            val root = File(context.cacheDir, "dream-shown-${System.nanoTime()}").apply { mkdirs() }
            val settingsStore = CasTestFixtures.settingsAggregator(context, root)
            withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
            val model = Model(modelId = "dream-visible-inputs")
            val provider = ProviderSetting.OpenAI(models = listOf(model))
            val settings = Settings(providers = listOf(provider), chatModelId = model.id)
            settingsStore.update(settings.copy(agentRuntime = settings.agentRuntime.copy(
                memoryWorker = settings.agentRuntime.memoryWorker.copy(
                    dreamMaintenanceEnabled = false, dreamModelEnabled = true,
                    daydreamModelId = model.id, daydreamFollowCompressModel = false,
                ),
            )))
            var prompt = ""
            val gateway = object : TextModelGateway<ProviderSetting.OpenAI> {
                override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
                override suspend fun stream(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> =
                    error("Planner must use complete")
                override suspend fun complete(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk {
                    prompt = messages.single().toText()
                    val response = """{"archive":[1,81],"promote":[1,81],"merge":[{"target_memory_id":1,"duplicate_memory_ids":[2,81]}],"supersede":[{"old_memory_ids":[81],"new_content":"Replacement fact for unseen row"}],"topics":[{"title":"Shown facts","content":"Group only shown facts","member_memory_ids":[1,2,81]}],"delete_suggestions":["candidate-0","candidate-50"]}"""
                    return MessageChunk("dream-plan", model.modelId, listOf(UIMessageChoice(
                        index = 0, delta = null,
                        message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(response))),
                        finishReason = "stop",
                    )))
                }
            }
            val client = OkHttpClient()
            val catalog = ProviderCatalog(OpenAIProvider(client, context), GoogleProvider(client, context), ClaudeProvider(client, context), openAITextGateway = gateway)
            val plan = MemoryDreamPlanner(settingsStore, catalog, Json, repository, MemoryEventLogger(repository)).plan().model!!

            assertTrue(prompt.contains("- #80 ["))
            assertFalse(prompt.contains("- #81 ["))
            assertTrue(prompt.contains("- #candidate-49 ["))
            assertFalse(prompt.contains("- #candidate-50 ["))
            assertEquals(listOf(1), plan.archiveMemoryIds)
            assertEquals(listOf(1), plan.promoteMemoryIds)
            assertEquals(listOf(2), plan.mergeSuggestions.single().duplicateMemoryIds)
            assertTrue(plan.supersedeSuggestions.isEmpty())
            assertEquals(listOf(1, 2), plan.topicSuggestions.single().memberMemoryIds)
            assertEquals(listOf("candidate-0"), plan.ignoreCandidateIds)
        } finally {
            database.close()
            Dispatchers.resetMain()
        }
    }
}
