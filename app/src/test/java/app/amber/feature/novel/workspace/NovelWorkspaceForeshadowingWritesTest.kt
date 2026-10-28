package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import app.amber.feature.novelworkspace.NovelWorkspaceContextAssembler
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceNodes
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceForeshadowingWritesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val path = "branches/主线/plot/foreshadowing/黄龙旗.md"

    @Test
    fun `approved model creation becomes an open foreshadowing node with host identity`() = runTest {
        val directory = project()
        val runtime = runtimeForWrite(content("FORGED", "open", "黄龙旗尚未出现。"))
        val proposal = runtime.runTurn(request(directory)).toList()
            .filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single().proposal!!
        assertFalse(NovelWorkspaceStore(directory).exists(path))
        runtime.approve(proposal.id)

        val store = NovelWorkspaceStore(directory)
        val nodes = NovelWorkspaceNodes.collect(store, "主线")
        assertTrue("an approved foreshadowing write must enter the story graph", nodes.any { it.path == path })
        val node = nodes.single { it.path == path }
        assertEquals("foreshadowing", node.nodeKind)
        assertEquals("open", node.status)
        val fields = NovelWorkspaceMarkdown.parseFile(store.read(path)!!).fields
        assertNotEquals("FORGED", fields["id"])
        assertTrue(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("黄龙旗"))
    }

    @Test
    fun `approved payoff changes only foreshadowing status while keeping host identity`() = runTest {
        val directory = project()
        val store = NovelWorkspaceStore(directory)
        store.write(path, content("HOST-ID", "open", "黄龙旗尚未出现。"))
        val runtime = runtimeForWrite(content("FORGED", "resolved", "黄龙旗已经出现并完成照应。"))
        val proposal = runtime.runTurn(request(directory)).toList()
            .filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single().proposal!!
        runtime.approve(proposal.id)

        val parsed = NovelWorkspaceMarkdown.parseFile(store.read(path)!!)
        assertEquals("HOST-ID", parsed.fields["id"])
        assertEquals("resolved", parsed.fields["status"])
        val catalog = NovelWorkspaceCatalog.load(store, NovelWorkspaceLedger.load(directory), "主线")
        assertTrue(catalog.foreshadowing.single { it.path == path }.resolved)
        assertFalse(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("黄龙旗"))
    }

    @Test
    fun `editing an imported planned node keeps its existing state and identity`() = runTest {
        val directory = project()
        val store = NovelWorkspaceStore(directory)
        store.write(path, content("HOST-ID", "planned", "黄龙旗尚未出现。"))
        val runtime = runtimeForWrite(content("FORGED", "planned", "黄龙旗会在登基时出现。"))
        val proposal = runtime.runTurn(request(directory)).toList()
            .filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single().proposal!!
        runtime.approve(proposal.id)

        val parsed = NovelWorkspaceMarkdown.parseFile(store.read(path)!!)
        assertEquals("HOST-ID", parsed.fields["id"])
        assertEquals("planned", parsed.fields["status"])
        assertTrue(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("黄龙旗"))
    }

    private fun project(): File = NovelWorkspaceProjectRepository(temporaryFolder.newFolder()).createBlank("测试书").projectDirectory

    private fun content(id: String, status: String, body: String) = NovelWorkspaceMarkdown.render(
        listOf("id" to id, "kind" to "foreshadowing", "title" to "黄龙旗", "status" to status), body = body,
    )

    private fun request(directory: File): NovelWorkspaceRuntime.TurnRequest {
        val branchId = NovelWorkspaceLedger.load(directory).heads.keys.single()
        return NovelWorkspaceRuntime.TurnRequest(directory, branchId, "主线", "更新伏笔", "", Settings(), Model())
    }

    private fun runtimeForWrite(content: String) = NovelWorkspaceRuntime(object : RunKernel {
        override fun run(session: GenerationRunSession) = flow {
            session.tools.single { it.name == "novel_workspace_write" }.execute(buildJsonObject {
                put("path", path)
                put("content", content)
            })
            emit(GenerationChunk.Messages(session.messages + UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("等待确认。")),
            )))
        }
    })
}
