package app.amber.feature.ui.pages.novel

import app.amber.ai.core.MessageRole
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreCancelled
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceTurnOutput
import app.amber.feature.novelworkspace.NovelWorkspaceTurnOutputs
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceSessionHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `recovery is durable and a completed output replaces its recovered copy once`() {
        val directory = temporary.newFolder()
        val createdAt = Instant.parse("2026-10-18T00:00:00Z")
        NovelWorkspaceTurnOutputs.save(directory, NovelWorkspaceTurnOutput(
            runId = "run-1", projectPath = directory.absolutePath, branchId = "branch-1", branchSlug = "main",
            userText = "continue", content = "partial reply", createdAt = createdAt,
        ))
        val recovered = NovelWorkspaceSessionHistory.recoverAndLoad(directory, "branch-1").single()
        assertEquals(MessageRole.ASSISTANT, recovered.role)
        assertEquals("interrupted", recovered.kind)
        assertEquals("partial reply", NovelWorkspaceSessions.load(directory).sessions["branch-1"]!!.single().content)
        NovelWorkspaceSessionHistory.append(directory, "branch-1", NovelWorkspaceSessionMessage(
            id = "run-1", role = "assistant", kind = "discussion", content = "final reply", createdAt = createdAt,
        ))
        val reopened = NovelWorkspaceSessionHistory.recoverAndLoad(directory, "branch-1")
        assertEquals(listOf("final reply"), reopened.map { it.content })
        assertEquals("discussion", reopened.single().kind)
        assertEquals(createdAt, reopened.single().createdAt)
        assertTrue(NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").isEmpty())
    }

    @Test
    fun `an append accepted before restore cannot alter the imported session`() {
        val directory = temporary.newFolder()
        val epoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        NovelWorkspaceRestoreBoundary.beginRestore()
        NovelWorkspaceRestoreBoundary.finishRestore()
        NovelWorkspaceSessions.save(
            app.amber.feature.novelworkspace.NovelWorkspaceSessionsFile(sessions = mapOf(
                "branch-1" to listOf(NovelWorkspaceSessionMessage(
                    id = "imported", role = "user", kind = "userInput", content = "imported conversation", createdAt = Instant.EPOCH,
                )),
            )), directory,
        )
        try {
            NovelWorkspaceSessionHistory.append(directory, "branch-1", NovelWorkspaceSessionMessage(
                id = "stale", role = "user", kind = "userInput", content = "old turn", createdAt = Instant.EPOCH,
            ), epoch)
            fail("Expected the old restore epoch to be rejected")
        } catch (_: NovelWorkspaceRestoreCancelled) {
            assertEquals(listOf("imported conversation"), NovelWorkspaceSessions.load(directory).sessions["branch-1"]!!.map { it.content })
        }
    }
}
