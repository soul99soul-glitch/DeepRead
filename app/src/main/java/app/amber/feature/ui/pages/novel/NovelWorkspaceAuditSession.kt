package app.amber.feature.ui.pages.novel

import app.amber.feature.novel.workspace.NovelTurnLauncher
import app.amber.feature.novel.workspace.NovelWorkspaceContinuityAudit
import app.amber.feature.novel.workspace.NovelWorkspaceRuntime
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs the existing auditor and durably keeps its last validated report, including Stop. */
internal class NovelWorkspaceAuditSession(runtime: NovelWorkspaceRuntime, launcher: NovelTurnLauncher) {
    private val audit = NovelWorkspaceContinuityAudit(runtime, launcher)

    data class Completion(
        val report: NovelWorkspaceContinuityAudit.Result?,
        val stopped: Boolean,
        val messages: List<NovelMarkdownMessageUi>?,
        val executionError: Exception?,
        val saveError: Exception?,
    )

    suspend fun run(
        request: NovelWorkspaceContinuityAudit.Request,
        reportId: String,
        onProgress: (NovelWorkspaceContinuityAudit.Result) -> Unit,
        onFinished: (Completion) -> Unit,
    ) {
        var result: NovelWorkspaceContinuityAudit.Result? = null
        var stopped = false
        var executionError: Exception? = null
        try {
            result = audit.run(request) { progress ->
                result = progress
                if (NovelWorkspaceRestoreBoundary.isCurrent(request.restoreEpoch)) onProgress(progress)
            }
        } catch (error: CancellationException) {
            stopped = true
            throw error
        } catch (error: Exception) {
            executionError = error
        } finally {
            // Old partial reports must never enter a restored workspace with reused IDs.
            if (NovelWorkspaceRestoreBoundary.isCurrent(request.restoreEpoch)) {
                var messages: List<NovelMarkdownMessageUi>? = null
                var saveError: Exception? = null
                result?.let { report ->
                    try {
                        // Keep the IO return inside NonCancellable: switching directly back
                        // to a stopped parent would discard the successfully saved result.
                        messages = withContext(NonCancellable) {
                            withContext(Dispatchers.IO) {
                                NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
                                    NovelWorkspaceSessionHistory.append(
                                        request.projectDirectory, request.branchId,
                                        NovelWorkspaceSessionMessage(
                                            id = reportId,
                                            role = "assistant",
                                            content = report.report,
                                            createdAt = Instant.now(),
                                            kind = "consistencyAudit",
                                        ),
                                        request.restoreEpoch,
                                    )
                                    NovelWorkspaceSessionHistory.recoverAndLoad(request.projectDirectory, request.branchId)
                                }
                            }
                        }
                    } catch (error: Exception) {
                        if (NovelWorkspaceRestoreBoundary.isCurrent(request.restoreEpoch)) saveError = error
                    }
                }
                if (NovelWorkspaceRestoreBoundary.isCurrent(request.restoreEpoch)) {
                    onFinished(Completion(result, stopped, messages, executionError, saveError))
                }
            }
        }
    }
}
