package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.toGenerationSettings
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceContextAssembler
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceProjectTitle
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Workspace-native generation runtime: one turn = one agent loop over the five
 * workspace primitives. Free writes land during the turn; canon writes are buffered
 * and surface as a single author-gated proposal when the turn ends.
 */
class NovelWorkspaceRuntime(private val kernel: RunKernel) {

    sealed interface TurnEvent {
        data class Delta(val text: String) : TurnEvent
        data class ReasoningDelta(val text: String) : TurnEvent
        data class ToolActivity(val toolName: String) : TurnEvent
        data class Completed(
            val finalText: String,
            val proposal: NovelWorkspaceWriteProposal?,
            val outputId: String? = null,
        ) : TurnEvent
        data class Failed(val message: String) : TurnEvent
    }

    data class TurnRequest(
        val projectDirectory: File,
        val branchId: String,
        val branchSlug: String,
        val userText: String,
        val systemPrompt: String,
        val settings: Settings,
        val model: Model,
        val maxSteps: Int = 16,
        /** Candidate/review/planning turns may inspect tools but must not write any path. */
        val readOnlyTools: Boolean = false,
        /** Ghostwrite/unattended turns commit canon writes automatically (no approval card). */
        val autoApproveCanon: Boolean = false,
        /** Commit message used when autoApproveCanon commits canon writes. */
        val autoCommitMessage: String = NovelWorkspaceLedger.Message.COLLECTION,
        /** Which sections the constraint brief carries; null = defaults (all on). */
        val injection: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags? = null,
        /** Durable batch owner. Null means an interactive author turn. */
        val ownerJobId: String? = null,
        /** Identifies this specific Worker execution across pause/resume. */
        val ownerExecutionId: String? = null,
        /** Phase-0 binding carried through the unattended commit path. */
        val ghostwriteChapterOrdinal: Int? = null,
        val ghostwritePlanId: String? = null,
        val ghostwritePlanDigest: String? = null,
        /**
         * Polish turn marker + host-locked target: the ONE existing chapter file this
         * turn may rewrite. Locks the write tool to this path; preserving story facts
         * must be reviewed before the host may bypass the unresolved gate.
        */
        val polishChapterPath: String? = null,
        /** Localized fallback used only when an exception has no message. */
        val fallbackErrorMessage: String = "Generation failed",
        /** Locale for host-generated state/error copy and the injected constraint brief. */
        val locale: Locale = Locale.CHINESE,
        /** Reject callbacks and writes from a workspace generation before full restore. */
        val restoreEpoch: Long = NovelWorkspaceRestoreBoundary.currentEpoch(),
        /** Interactive discussion only; specialized review/background callers leave this empty. */
        val history: List<UIMessage> = emptyList(),
    )

    private val proposalOwner = NovelWorkspaceProposalOwner()
    val pendingProposals: StateFlow<List<NovelWorkspaceWriteProposal>> = proposalOwner.proposals

    fun loadProposals(projectDirectory: File) = proposalOwner.load(projectDirectory)

    fun editProposal(proposalId: String, entries: List<NovelWorkspaceWriteEntry>) = proposalOwner.edit(proposalId, entries)

    fun commitAuthorChanges(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        changes: Map<String, String?>,
        expectedHeadId: String?,
        expectedTreeDigest: String,
        message: String = NovelWorkspaceLedger.Message.MANUAL_EDIT,
    ): NovelWorkspaceCommit = NovelWorkspaceAuthorEdits.apply(
        projectDirectory, branchId, branchSlug, changes, expectedHeadId, expectedTreeDigest, message,
    )

    /**
     * Run one workspace turn. [runId] / [events] thread the run scope's
     * identity and protocol event writer (Step 3) so the kernel can emit the
     * durable audit trail — tool lifecycle events and, on the durable path,
     * request snapshots. Emission stays gated by the kernel's durable-path
     * check; null (bare callers) keeps the loop silent.
     */
    fun runTurn(
        request: TurnRequest,
        runId: String? = null,
        events: app.amber.core.agent.runtime.AgentEventWriter? = null,
    ): Flow<TurnEvent> = flow {
        NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) { }
        val store = NovelWorkspaceStore(request.projectDirectory)
        val ownerAtStart = NovelWorkspaceGhostwriteJobs.activeFor(
            request.projectDirectory,
            request.branchSlug,
        )
        if (request.ownerJobId == null && ownerAtStart != null) {
            emit(TurnEvent.Failed(request.localized(
                chinese = "当前分支仍被代笔批次占用，请先让批次完成或取消后再修改正文",
                english = "This branch is occupied by a ghostwrite batch. Let it finish or cancel it before editing the manuscript.",
            )))
            return@flow
        }
        val runningOwnerMatches = request.ownerJobId?.let { ownerJobId ->
            request.ownerExecutionId?.let { executionId ->
                NovelWorkspaceGhostwriteJobs.withRunningOwner(
                    request.projectDirectory,
                    ownerJobId,
                    executionId,
                ) { true }
            }
        }
        if (request.ownerJobId != null && runningOwnerMatches != true) {
            emit(TurnEvent.Failed(request.localized(
                chinese = "代笔已暂停、取消，或正文与确认计划的版本绑定已变化",
                english = "The ghostwrite batch was paused or cancelled, or its manuscript/plan binding changed.",
            )))
            return@flow
        }
        val batch = NovelWorkspaceWriteBatch()
        val polishBaseLedger = request.polishChapterPath?.let { NovelWorkspaceLedger.load(request.projectDirectory) }
        val polishBaseTreeDigest = request.polishChapterPath?.let { NovelWorkspaceLedger.treeSHA256(store.fileTree()) }
        val session = NovelWorkspaceToolSession(
            store = store,
            branchSlug = request.branchSlug,
            projectTitle = readProjectTitle(store),
            batch = batch,
            readOnly = request.readOnlyTools,
            autoApproveCanon = request.autoApproveCanon,
            polishTargetPath = request.polishChapterPath,
            confirmedPlanLocked = request.ghostwritePlanDigest != null,
            restoreEpoch = request.restoreEpoch,
        )
        // Context-engineering core: inject the constraint brief (plot state, open
        // foreshadowing, decisions, and the plan's entity subgraph) every turn.
        val brief = runCatching {
            NovelWorkspaceContextAssembler.assemble(
                store,
                request.branchSlug,
                flags = request.injection ?: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
                locale = request.locale,
            )
        }.getOrDefault("")
        val systemPrompt = if (brief.isBlank()) {
            request.systemPrompt
        } else {
            request.systemPrompt +
                if (request.locale.language.equals("zh", ignoreCase = true)) {
                    "\n\n## 工作区状态简报（host 注入，以下为当前正史约束，不得与之矛盾）\n"
                } else {
                    "\n\n## Workspace state brief (host-injected; the following constraints are current canon)\n"
                } + brief
        }
        var emittedText = ""
        var emittedReasoning = ""
        val seenTools = mutableSetOf<String>()
        var canonLedgerPersisted = false
        var canonAccountingPersisted = false
        var canonRollbackHandled = false
        var freeWritesPersisted = false
        try {
            val messages = buildList {
                if (systemPrompt.isNotBlank()) {
                    add(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(systemPrompt))))
                }
                addAll(request.history)
                add(UIMessage.user(request.userText))
            }
            kernel.run(
                GenerationRunSession(
                    // Workspace callers provide complete manuscript/review inputs, outside chat history limits.
                    settings = request.settings.copy(contextMessageSize = Int.MAX_VALUE).toGenerationSettings(request.model),
                model = request.model,
                messages = messages,
                tools = session.tools(),
                maxSteps = request.maxSteps,
                // The workspace write tool has its own strict path and author gates.
                // Trust only this named tool; do not enable blanket auto-approval.
                autoApprovedToolNames = setOf("novel_workspace_write"),
                // Step 5: thread the run scope's identity and event writer the
                // same way SubAgentRunner does; the kernel's durable-path gate
                // decides whether anything is emitted.
                runId = runId,
                toolLifecycleEvents = events,
                // Step 6: v1 default codifies no new restriction; narrowing
                // arrives via sub-agent payloads.
                executionPolicy = app.amber.feature.runtime.ExecutionPolicy.permissive(),
                ),
            ).collect { chunk ->
                NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) { }
                val messages = (chunk as? GenerationChunk.Messages)?.messages ?: return@collect
                // Initial kernel snapshots can contain the preceding reply in discussion history.
                // Only output after this turn's final user input belongs to this generation.
                val turnMessages = messages.drop(messages.indexOfLast { it.role == MessageRole.USER } + 1)
                val assistantText = assistantTextOf(turnMessages)
                if (assistantText != emittedText) {
                    val delta = if (assistantText.startsWith(emittedText)) {
                        assistantText.substring(emittedText.length)
                    } else {
                        assistantText
                    }
                    emittedText = assistantText
                    if (delta.isNotEmpty()) emit(TurnEvent.Delta(delta))
                }
                val reasoning = reasoningOf(turnMessages)
                if (reasoning.length > emittedReasoning.length && reasoning.startsWith(emittedReasoning)) {
                    emit(TurnEvent.ReasoningDelta(reasoning.substring(emittedReasoning.length)))
                    emittedReasoning = reasoning
                }
                for (tool in messages.lastOrNull()?.getTools().orEmpty()) {
                    if (seenTools.add(tool.toolCallId)) {
                        emit(TurnEvent.ToolActivity(tool.toolName))
                    }
                }
            }
            if (request.ownerJobId != null &&
                NovelWorkspaceGhostwriteJobs.load(request.projectDirectory, request.ownerJobId)?.let {
                    it.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
                        it.executionKey != request.ownerExecutionId
                } != false
            ) {
                rollbackUncommittedWrites(request, batch)
                emit(TurnEvent.Failed(request.localized(
                    chinese = "代笔已暂停或取消",
                    english = "The ghostwrite batch is paused or cancelled.",
                )))
                return@flow
            }
            // Interactive free writes already hit disk. Commit them before any separate
            // canon proposal so rejecting that proposal cannot leave the ledger dirty.
            if (batch.hasFreeWrites()) {
                val committed = NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
                    if (request.ownerJobId != null) {
                        NovelWorkspaceGhostwriteJobs.withRunningOwner(
                            request.projectDirectory,
                            request.ownerJobId,
                            checkNotNull(request.ownerExecutionId),
                        ) {
                            NovelWorkspaceCommitter.commitTree(
                                projectDirectory = request.projectDirectory,
                                branchId = request.branchId,
                                branchSlug = request.branchSlug,
                                message = NovelWorkspaceLedger.Message.GENERIC,
                            )
                        }
                    } else {
                        NovelWorkspaceCommitter.commitTree(
                            projectDirectory = request.projectDirectory,
                            branchId = request.branchId,
                            branchSlug = request.branchSlug,
                            message = NovelWorkspaceLedger.Message.GENERIC,
                        )
                    }
                }
                if (committed == null) {
                    rollbackUncommittedWrites(request, batch)
                    emit(TurnEvent.Failed(request.localized(
                        chinese = "代笔已暂停或取消",
                        english = "The ghostwrite batch is paused or cancelled.",
                    )))
                    return@flow
                }
                freeWritesPersisted = true
            }
            val proposal = if (batch.isEmpty()) {
                val polishPath = request.polishChapterPath
                if (polishPath != null && !request.readOnlyTools && !request.autoApproveCanon && emittedText.isNotBlank()) {
                    if (!polishPath.startsWith(NovelWorkspacePaths.branchPrefix(request.branchSlug) + "/chapters/") ||
                        NovelWorkspacePaths.chapterOrdinalFromPath(polishPath) == null || store.read(polishPath) == null
                    ) throw NovelWorkspaceIoError("润色目标必须是当前分支已有章节")
                    registerProposal(
                        request,
                        listOf(NovelWorkspaceWriteEntry(polishPath, emittedText, reason = "润色候选")),
                        baseHeadId = polishBaseLedger?.headOf(request.branchId)?.id,
                        baseTreeDigest = polishBaseTreeDigest,
                    )
                } else {
                    null
                }
            } else if (request.autoApproveCanon) {
                // Apply buffered unattended writes only while this Worker still owns its
                // execution token, then persist the write and ledger under one lock.
                val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(request.projectDirectory)
                val commitBufferedCanon = {
                    NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
                        val canonStore = NovelWorkspaceStore(request.projectDirectory)
                        try {
                            batch.snapshot().forEach { entry ->
                                batch.rememberPrevious(entry.path, canonStore.read(entry.path))
                                canonStore.write(entry.path, entry.content)
                            }
                            val commit = NovelWorkspaceCommitter.commitTree(
                                projectDirectory = request.projectDirectory,
                                branchId = request.branchId,
                                branchSlug = request.branchSlug,
                                message = if (request.polishChapterPath != null) {
                                    NovelWorkspaceLedger.Message.MANUAL_EDIT
                                } else {
                                    request.autoCommitMessage
                                },
                                // Only the independently reviewed host polish path may
                                // bypass continuity invalidation.
                                onLedgerPersisted = { canonLedgerPersisted = true },
                            )
                            val ownerJob = request.ownerJobId?.let {
                                NovelWorkspaceGhostwriteJobs.load(request.projectDirectory, it)
                            }
                            if (ownerJob?.isVersionBound == true) {
                                checkNotNull(
                                    NovelWorkspaceGhostwriteJobs.recordWriteCommit(
                                        projectDirectory = request.projectDirectory,
                                        jobId = ownerJob.id,
                                        executionId = checkNotNull(request.ownerExecutionId),
                                        commitId = commit.id,
                                        chapterOrdinal = checkNotNull(request.ghostwriteChapterOrdinal),
                                        planId = checkNotNull(request.ghostwritePlanId),
                                        planDigest = checkNotNull(request.ghostwritePlanDigest),
                                    ),
                                ) { "代笔版本绑定已变化" }
                            }
                            canonAccountingPersisted = true
                            commit
                        } catch (error: Exception) {
                            if (!canonLedgerPersisted) {
                                rollbackUncommittedWrites(request, batch)
                                canonRollbackHandled = true
                            }
                            throw error
                        }
                    }
                }
                val committed = if (request.ownerJobId != null) {
                    NovelWorkspaceGhostwriteJobs.withRunningOwner(
                        request.projectDirectory,
                        request.ownerJobId,
                        checkNotNull(request.ownerExecutionId),
                        commitBufferedCanon,
                    )
                } else {
                    commitBufferedCanon()
                }
                if (committed == null) {
                    rollbackUncommittedWrites(request, batch)
                    emit(TurnEvent.Failed(request.localized(
                        chinese = "代笔已暂停或取消",
                        english = "The ghostwrite batch is paused or cancelled.",
                    )))
                    return@flow
                }
                NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
                    NovelWorkspaceUndo.save(
                        NovelWorkspaceUndoRecord(
                            commitId = committed.id,
                            parentCommitId = committed.parentId,
                            files = batch.previousSnapshot(),
                            unresolvedBefore = unresolvedBefore,
                            branchSlug = request.branchSlug,
                        ),
                        request.projectDirectory,
                    )
                }
                null
            } else {
                registerProposal(
                    request,
                    batch.snapshot(),
                    baseHeadId = polishBaseLedger?.headOf(request.branchId)?.id,
                    baseTreeDigest = polishBaseTreeDigest,
                )
            }
            NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) { }
            emit(TurnEvent.Completed(emittedText, proposal))
        } catch (error: CancellationException) {
            // Same orphan risk as a failed turn: writes may already be on disk with no
            // commit. Restore the turn preimages, then propagate cancellation.
            if (NovelWorkspaceRestoreBoundary.isCurrent(request.restoreEpoch) && !canonLedgerPersisted && !freeWritesPersisted && !canonRollbackHandled) {
                rollbackUncommittedWrites(request, batch)
            }
            throw error
        } catch (error: Exception) {
            NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) { }
            if (canonLedgerPersisted && !canonAccountingPersisted && request.ownerJobId != null) {
                emit(TurnEvent.Failed(request.localized(
                    chinese = "本章已提交，但批次记账失败；已停止以避免重复写章",
                    english = "The chapter was committed, but batch accounting failed; the batch stopped to avoid writing a duplicate chapter.",
                )))
            } else if (canonLedgerPersisted) {
                // The chapter is already durable. Reporting a failed turn would make
                // the batch retry the same ordinal after a checkout/undo side-effect error.
                emit(TurnEvent.Completed(emittedText, proposal = null))
            } else {
                if (!freeWritesPersisted && !canonRollbackHandled) {
                    rollbackUncommittedWrites(request, batch)
                }
                emit(TurnEvent.Failed(error.message ?: request.fallbackErrorMessage))
            }
        }
    }

    /**
     * A failed/cancelled turn must not leave files on disk that no commit owns.
     * Interactive free writes land immediately but remember their pre-turn content;
     * unattended canon writes remember it when their buffered batch is applied.
     */
    private fun rollbackUncommittedWrites(request: TurnRequest, batch: NovelWorkspaceWriteBatch) {
        NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
            val store = NovelWorkspaceStore(request.projectDirectory)
            for ((path, previous) in batch.previousSnapshot()) {
                if (previous == null) store.delete(path) else store.write(path, previous)
            }
        }
    }

    private fun TurnRequest.localized(chinese: String, english: String): String =
        if (locale.language.equals("zh", ignoreCase = true)) chinese else english

    /** Author approved the gate: apply every entry, one commit, checkout refreshed. */
    fun approve(proposalId: String) = proposalOwner.approve(proposalId)

    fun collectDraft(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        draftPath: String,
        target: NovelWorkspaceCollectTarget,
        chapterTitle: String? = null,
    ): NovelWorkspaceCommit = NovelWorkspaceAuthorEdits.collectDraft(
        projectDirectory, branchId, branchSlug, draftPath, target, chapterTitle,
    )

    fun commitGhostwrittenChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterOrdinal: Int,
        title: String,
        body: String,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
    ): NovelWorkspaceCommit = NovelWorkspaceChapterCommits.commitGhostwrittenChapter(
        projectDirectory, branchId, branchSlug, chapterOrdinal, title, body, ownerJobId, ownerExecutionId,
    )

    fun commitReviewedChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        ownerJobId: String,
        ownerExecutionId: String,
    ): NovelWorkspaceCommit = NovelWorkspaceReviewedCommits.commitReviewedChapter(
        projectDirectory, branchId, branchSlug, ownerJobId, ownerExecutionId,
    )

    fun reconcileReviewedChapter(
        projectDirectory: File,
        ownerJobId: String,
        ownerExecutionId: String,
    ): Boolean = NovelWorkspaceReviewedCommits.reconcileReviewedChapter(
        projectDirectory, ownerJobId, ownerExecutionId,
    )

    fun commitPolishedChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
        polishedBody: String,
        expectedHeadId: String?,
        expectedTreeDigest: String,
        expectedChapterContent: String,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
    ): NovelWorkspaceCommit = NovelWorkspaceChapterCommits.commitPolishedChapter(
        projectDirectory, branchId, branchSlug, chapterPath,
        polishedBody, expectedHeadId, expectedTreeDigest, expectedChapterContent,
        ownerJobId, ownerExecutionId,
    )

    fun commitPolishPointer(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterOrdinal: Int,
    ): NovelWorkspaceCommit = NovelWorkspaceChapterCommits.commitPolishPointer(
        projectDirectory, branchId, branchSlug, chapterOrdinal,
    )

    fun saveChapterEdit(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
        title: String,
        body: String,
    ): NovelWorkspaceCommit = NovelWorkspaceAuthorEdits.saveChapterEdit(
        projectDirectory, branchId, branchSlug, chapterPath, title, body,
    )

    fun saveFileEdit(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        path: String,
        body: String,
    ): NovelWorkspaceCommit = NovelWorkspaceAuthorEdits.saveFileEdit(
        projectDirectory, branchId, branchSlug, path, body,
    )

    fun reject(proposalId: String) = proposalOwner.reject(proposalId)

    private fun registerProposal(
        request: TurnRequest,
        entries: List<NovelWorkspaceWriteEntry>,
        baseHeadId: String? = null,
        baseTreeDigest: String? = null,
    ): NovelWorkspaceWriteProposal = proposalOwner.register(
        NovelWorkspaceProposalContext(request.projectDirectory, request.branchId, request.branchSlug, request.restoreEpoch),
        entries, baseHeadId, baseTreeDigest,
    )

    // Join only Text parts: tool parts would otherwise leave separator artifacts.
    private fun assistantTextOf(messages: List<UIMessage>): String = messages
        .lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        .orEmpty()

    private fun reasoningOf(messages: List<UIMessage>): String = messages
        .lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Reasoning>()
        ?.lastOrNull()
        ?.reasoning
        .orEmpty()

    fun canUndo(projectDirectory: File): Boolean = NovelWorkspaceUndoActions.canUndo(projectDirectory)

    fun canUndo(projectDirectory: File, branchSlug: String): Boolean =
        NovelWorkspaceUndoActions.canUndo(projectDirectory, branchSlug)

    fun undoLast(projectDirectory: File): Boolean = NovelWorkspaceUndoActions.undoLast(projectDirectory)

    fun undoLast(projectDirectory: File, branchSlug: String): Boolean =
        NovelWorkspaceUndoActions.undoLast(projectDirectory, branchSlug)

    companion object {
        fun readProjectTitle(store: NovelWorkspaceStore): String =
            NovelWorkspaceProjectTitle.read(store)

    }
}
