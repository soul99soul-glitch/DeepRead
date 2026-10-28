package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceFileTypeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun unsupportedFreeWritesNeverCreateInvisibleBookFiles() = runTest {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder())
        val batch = NovelWorkspaceWriteBatch()
        val write = NovelWorkspaceToolSession(store, "main", "Book", batch).tools()
            .single { it.name == "novel_workspace_write" }

        for (path in listOf("drafts/scene.txt", "setting/world/rule", "drafts/scene.MD")) {
            runCatching { write.execute(buildJsonObject { put("path", path); put("content", "Author output") }) }
            assertFalse("Unsupported write created an invisible file: $path", store.exists(path))
        }
        assertFalse(batch.hasFreeWrites())
        assertTrue(batch.isEmpty())
    }

    @Test
    fun unsupportedCanonWritesNeverEnterTheApprovalBatch() = runTest {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder())
        val batch = NovelWorkspaceWriteBatch()
        val write = NovelWorkspaceToolSession(store, "main", "Book", batch).tools()
            .single { it.name == "novel_workspace_write" }

        runCatching {
            write.execute(buildJsonObject {
                put("path", "branches/main/chapters/001-scene.txt")
                put("content", "Author output")
            })
        }

        assertTrue("An unsupported canon target must not become an approvable write", batch.isEmpty())
    }

    @Test
    fun unsupportedTargetsCannotBePersistedAsApprovalProposals() {
        val directory = temporaryFolder.newFolder()
        val proposal = NovelWorkspaceWriteProposal(
            id = "proposal", projectDirectory = directory, branchId = "branch", branchSlug = "main",
            baseHeadId = null, baseTreeDigest = "base",
            entries = listOf(NovelWorkspaceWriteEntry("branches/main/chapters/001-scene.txt", "Author output", null)),
            createdAt = Instant.parse("2026-10-25T00:00:00Z"),
        )

        assertTrue(runCatching { NovelWorkspaceProposalStore.add(proposal) }.isFailure)
        assertTrue(NovelWorkspaceProposalStore.load(directory).isEmpty())
    }

    @Test
    fun existingUnsupportedProposalRemainsReadableAndRejectable() {
        val directory = temporaryFolder.newFolder()
        File(directory, ".amber/proposals.json").apply {
            parentFile.mkdirs()
            writeText("""[{"id":"old-proposal","branchId":"branch","branchSlug":"main","baseTreeDigest":"base","entries":[{"path":"branches/main/chapters/001-scene.txt","content":"Previous author output"}],"createdAt":"2026-10-25T00:00:00Z"}]""")
        }
        val owner = NovelWorkspaceProposalOwner()

        owner.load(directory)
        assertEquals("old-proposal", owner.proposals.value.single().id)
        owner.reject("old-proposal")

        assertTrue(owner.proposals.value.isEmpty())
        assertTrue(NovelWorkspaceProposalStore.load(directory).isEmpty())
    }

    @Test
    fun supportedMarkdownAndYamlWritesRemainVisibleAndTracked() = runTest {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder())
        val batch = NovelWorkspaceWriteBatch()
        val write = NovelWorkspaceToolSession(store, "main", "Book", batch).tools()
            .single { it.name == "novel_workspace_write" }
        val paths = listOf("drafts/scene.md", "setting/world/rule.yaml")

        for (path in paths) {
            write.execute(buildJsonObject { put("path", path); put("content", "Author output") })
            assertEquals("Author output", store.read(path))
        }

        assertEquals(paths.sorted(), store.list())
        assertEquals(paths.toSet(), store.fileTree().keys)
        assertTrue(batch.hasFreeWrites())
    }
}
