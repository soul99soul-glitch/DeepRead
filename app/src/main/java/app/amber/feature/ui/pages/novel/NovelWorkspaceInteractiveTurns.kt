package app.amber.feature.ui.pages.novel

import app.amber.feature.novel.workspace.NovelTurnLauncher
import app.amber.feature.novel.workspace.NovelWorkspaceRuntime
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal data class NovelWorkspaceSendPreparation(
    val isBlankBook: Boolean,
    val filesBeforeQuickstart: Int,
    val messages: List<NovelMarkdownMessageUi>,
)

/** A turn's ephemeral output and durable session work. It never owns navigation or UI state. */
internal class NovelWorkspaceInteractiveTurns(
    private val runtime: NovelWorkspaceRuntime,
    private val launcher: NovelTurnLauncher,
    private val queries: NovelWorkspaceQueries,
) {
    sealed interface Event {
        data class Text(val content: String) : Event
        data class Reasoning(val content: String) : Event
        data class Tool(val name: String) : Event
        data class Completed(val content: NovelWorkspaceContentSnapshot, val producedNothing: Boolean) : Event
        data class Failed(
            val message: String,
            val messages: List<NovelMarkdownMessageUi>,
            val proposals: List<NovelWorkspaceWriteProposal>,
        ) : Event
    }

    suspend fun prepareSend(
        directory: File,
        branch: String,
        epoch: Long,
        acceptedMessage: NovelWorkspaceSessionMessage,
        hasChapters: Boolean,
    ): NovelWorkspaceSendPreparation {
        // Once send accepts the input, Stop must not lose it. Only this append is non-cancellable.
        withContext(NonCancellable + Dispatchers.IO) {
            NovelWorkspaceSessionHistory.append(directory, branch, acceptedMessage, epoch)
        }
        val quickstart = withContext(Dispatchers.IO) {
            val store = NovelWorkspaceStore(directory)
            val blank = !hasChapters && store.list(NovelWorkspacePaths.SETTING_DIR).isEmpty()
            blank to if (blank) store.list().size else -1
        }
        val messages = withContext(Dispatchers.IO) {
            NovelWorkspaceSessionHistory.recoverAndLoad(directory, branch)
        }
        return NovelWorkspaceSendPreparation(quickstart.first, quickstart.second, messages)
    }

    fun execute(
        request: NovelWorkspaceRuntime.TurnRequest,
        retainReply: Boolean,
        filesBeforeQuickstart: Int = -1,
    ): Flow<Event> = flow {
        var text = ""
        var reasoning = ""
        launcher.launch(request, runtime).events.collect { event ->
            when (event) {
                is NovelWorkspaceRuntime.TurnEvent.Delta -> {
                    text += event.text
                    emit(Event.Text(text))
                }
                is NovelWorkspaceRuntime.TurnEvent.ReasoningDelta -> {
                    reasoning += event.text
                    emit(Event.Reasoning(reasoning))
                }
                is NovelWorkspaceRuntime.TurnEvent.ToolActivity -> emit(Event.Tool(event.toolName))
                is NovelWorkspaceRuntime.TurnEvent.Completed -> {
                    if (retainReply && event.finalText.isNotBlank()) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            NovelWorkspaceSessionHistory.append(
                                request.projectDirectory,
                                request.branchId,
                                NovelWorkspaceSessionMessage(
                                    id = event.outputId ?: UUID.randomUUID().toString().uppercase(),
                                    role = "assistant",
                                    kind = "discussion",
                                    content = event.finalText,
                                    createdAt = Instant.now(),
                                ),
                                request.restoreEpoch,
                            )
                        }
                    }
                    val completed = withContext(Dispatchers.IO) {
                        val producedNothing = filesBeforeQuickstart >= 0 &&
                            NovelWorkspaceStore(request.projectDirectory).list().size == filesBeforeQuickstart
                        Event.Completed(
                            queries.contentSnapshot(
                                request.projectDirectory, request.branchId, request.branchSlug,
                                runtime.pendingProposals.value, includeMessages = retainReply,
                            ),
                            producedNothing,
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    emit(completed)
                }
                is NovelWorkspaceRuntime.TurnEvent.Failed -> {
                    val messages = NovelWorkspaceSessionHistory.recoverAndLoad(request.projectDirectory, request.branchId)
                    emit(Event.Failed(event.message, messages, runtime.pendingProposals.value.filter {
                        it.projectDirectory == request.projectDirectory &&
                            it.branchId == request.branchId && it.branchSlug == request.branchSlug
                    }))
                }
            }
        }
    }

    /** Planning only succeeds when the expected branch plan was actually committed. */
    fun draftPlan(request: NovelWorkspaceRuntime.TurnRequest, planPath: String, planBefore: String?, headBefore: String?) = flow {
        launcher.launch(request, runtime).events.collect { event ->
            when (event) {
                is NovelWorkspaceRuntime.TurnEvent.Completed -> {
                    val producedPlan = withContext(Dispatchers.IO) {
                        val headAfter = NovelWorkspaceLedger.load(request.projectDirectory).heads[request.branchId]
                        val planAfter = NovelWorkspaceStore(request.projectDirectory).read(planPath)
                        val planBody = planAfter?.let(NovelWorkspaceMarkdown::parseFile)?.body.orEmpty()
                        headAfter != headBefore && planAfter != planBefore && planBody.isNotBlank()
                    }
                    emit(PlanResult.Completed(producedPlan))
                }
                is NovelWorkspaceRuntime.TurnEvent.Failed -> emit(PlanResult.Failed(
                    event.message, NovelWorkspaceSessionHistory.recoverAndLoad(request.projectDirectory, request.branchId),
                ))
                else -> Unit
            }
        }
    }

    sealed interface PlanResult {
        data class Completed(val producedPlan: Boolean) : PlanResult
        data class Failed(val message: String, val messages: List<NovelMarkdownMessageUi>) : PlanResult
    }
}
