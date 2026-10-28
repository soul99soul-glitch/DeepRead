package app.amber.core.service.tools

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.settings.Settings
import app.amber.feature.runtime.DurableRuntimeTestBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the run-bound tools against Room, including their repository CAS boundary. */
class MemoryRunToolFactoryTest : DurableRuntimeTestBase() {
    private fun repository() = MemoryRepository(
        memoryDAO = database.memoryDao(),
        candidateDAO = database.memoryCandidateDao(),
        eventDAO = database.memoryEventDao(),
        appDatabase = database,
    )

    private fun tools(repository: MemoryRepository, settings: Settings = Settings()): List<Tool> =
        MemoryRunToolFactory(context, Json, repository).createTools(settings, runId = "catalog-run")

    private suspend fun List<Tool>.execute(name: String, input: String): JsonObject {
        val output = single { it.name == name }.execute(Json.parseToJsonElement(input))
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test
    fun `tool write binds source and run while stale edit never overwrites Room`() = runBlocking {
        val repository = repository()
        val tools = tools(repository)
        val created = tools.execute(
            "memory_write",
            """{"type":"short_term","content":"Release owner","source":"conversation note"}""",
        )
        val id = created.getValue("id").jsonPrimitive.int
        val row = repository.getMemoryById(id)!!
        assertEquals(MemoryScope.SHORT_TERM, row.scope)
        assertEquals("Release owner\nSource: conversation note", row.content)
        assertEquals("catalog-run", row.sourceRunId)
        assertEquals(MemoryRepository.TRIGGER_TOOL, row.sourceTrigger)
        assertEquals(1L, row.revision)

        repository.updateContentCas(id, "Newer user edit", expectedRevision = 1L)
        val conflict = tools.execute(
            "memory_tool",
            """{"action":"edit","id":$id,"revision":1,"content":"Stale tool edit"}""",
        )
        assertEquals("conflict", conflict.getValue("status").jsonPrimitive.content)
        assertEquals("Newer user edit", repository.getMemoryById(id)!!.content)
        assertEquals(2L, repository.getMemoryById(id)!!.revision)

        tools.execute(
            "memory_tool",
            """{"action":"edit","id":$id,"revision":2,"content":"Accepted tool edit"}""",
        )
        val accepted = repository.getMemoryById(id)!!
        assertEquals("Accepted tool edit", accepted.content)
        assertEquals(3L, accepted.revision)
        assertEquals("catalog-run", accepted.sourceRunId)
        assertEquals(MemoryRepository.TRIGGER_TOOL, accepted.sourceTrigger)
    }

    @Test
    fun `listing hides expired and archived records without losing other scopes`() = runBlocking {
        val repository = repository()
        val core = repository.addMemory(MemoryScope.CORE, MemoryKind.NOTE, "Visible core")
        val short = repository.addMemory(MemoryScope.SHORT_TERM, MemoryKind.PROJECT, "Visible project")
        repository.addMemory(
            MemoryScope.LONG_TERM,
            MemoryKind.NOTE,
            "Expired",
            expiresAt = System.currentTimeMillis() - 1_000,
        )
        val archived = repository.addMemory(MemoryScope.LONG_TERM, MemoryKind.NOTE, "Archived")
        repository.archiveMemoryCas(archived.id, archived.revision)

        val result = tools(repository).execute("memory_list", """{"type":"all"}""")
        val ids = result.getValue("memories").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.int }
        assertEquals(listOf(core.id, short.id), ids)
        assertEquals(2, result.getValue("total_count").jsonPrimitive.int)
        assertEquals(4, repository.getAllRecords().size)
    }

    @Test
    fun `disabling all memory scopes removes the writable tool surface`() {
        val settings = Settings().let {
            it.copy(agentRuntime = it.agentRuntime.copy(
                enableCoreMemory = false,
                enableShortTermMemory = false,
                enableLongTermMemory = false,
            ))
        }
        assertTrue(tools(repository(), settings).isEmpty())
    }
}
