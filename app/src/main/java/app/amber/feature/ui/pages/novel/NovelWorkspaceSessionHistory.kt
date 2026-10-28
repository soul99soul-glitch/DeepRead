package app.amber.feature.ui.pages.novel

import app.amber.ai.core.MessageRole
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import java.io.File

/** Owns session persistence and recovery of interrupted turn output, without UI state. */
internal object NovelWorkspaceSessionHistory {
    fun recoverAndLoad(directory: File, branch: String?): List<NovelMarkdownMessageUi> {
        branch ?: return emptyList()
        app.amber.feature.novelworkspace.NovelWorkspaceTurnOutputs.recoverToSessions(directory, branch)
        return NovelWorkspaceSessions.load(directory).sessions[branch].orEmpty().map { message ->
            NovelMarkdownMessageUi(
                id = message.id,
                role = when (message.role) {
                    "assistant" -> MessageRole.ASSISTANT
                    "system" -> MessageRole.SYSTEM
                    else -> MessageRole.USER
                },
                content = message.content,
                kind = message.kind,
                createdAt = message.createdAt,
            )
        }
    }

    fun append(directory: File, branch: String, message: NovelWorkspaceSessionMessage, expectedEpoch: Long? = null) = NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
        val sessions = NovelWorkspaceSessions.load(directory)
        val existing = sessions.sessions[branch].orEmpty()
        NovelWorkspaceSessions.save(
            sessions.copy(sessions = sessions.sessions + (branch to (existing.filterNot { it.id == message.id } + message))),
            directory,
        )
        app.amber.feature.novelworkspace.NovelWorkspaceTurnOutputs.acknowledge(directory, message.id)
    }

}
