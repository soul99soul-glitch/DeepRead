package app.amber.feature.ui.pages.novel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.agent.R
import app.amber.ai.core.MessageRole
import app.amber.core.ai.RunKernel
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.appLocale
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSettingsStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import app.amber.feature.novel.workspace.NovelTurnLauncher
import app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget
import app.amber.feature.novel.workspace.NovelWorkspaceContinuityAudit
import app.amber.feature.novel.workspace.NovelWorkspaceRuntime
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteController
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteCoordinator
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceFocus
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Experimental workspace-native novel screen: the book is a markdown tree and generation
 * runs through [NovelWorkspaceRuntime] (five workspace tools + the canon write gate).
 */
class NovelMarkdownWorkspaceViewModel(
    projectId: String,
    private val repository: NovelWorkspaceProjectRepository,
    private val settingsAggregator: SettingsAggregator,
    private val ghostwriteController: NovelWorkspaceGhostwriteController,
    private val turnLauncher: NovelTurnLauncher,
    kernel: RunKernel,
    private val context: Context,
    private val requestedFocus: NovelWorkspaceFocus = NovelWorkspaceFocus(),
    private val restoreBridge: app.amber.feature.novel.workspace.NovelWorkspaceRestoreBridge? = null,
) : ViewModel() {

    val projectId: String = projectId
    private val runtime = NovelWorkspaceRuntime(kernel)
    private val _state = MutableStateFlow(NovelMarkdownWorkspaceUiState())
    val state: StateFlow<NovelMarkdownWorkspaceUiState> = _state.asStateFlow()

    private var projectDirectory: File? = null
    private var branchId: String? = null
    private var branchSlug: String? = null
    private var focusedJobId: String? = requestedFocus.jobId
    /** Deep-link identity is applied once; subsequent reloads follow the user's branch choice. */
    private var pendingFocus: NovelWorkspaceFocus? = requestedFocus.takeIf {
        it.branchSlug != null || it.jobId != null
    }

    /** In-flight chat turn; cancellable so the composer's stop button can end it. */
    private var turnJob: kotlinx.coroutines.Job? = null
    private var ghostwriteRefreshJob: kotlinx.coroutines.Job? = null
    private var reloadJob: Job? = null
    // All reload coordination is launched from the ViewModel's main scope; a plain counter
    // is sufficient and keeps this UI-only guard lightweight.
    private var reloadGeneration = 0L
    private val queries = NovelWorkspaceQueries(repository)
    private val interactiveTurns = NovelWorkspaceInteractiveTurns(runtime, turnLauncher, queries)
    private val auditSession = NovelWorkspaceAuditSession(runtime, turnLauncher)
    private var uiCacheGeneration = 0L

    private fun clearUiCache() {
        queries.clearCache()
        uiCacheGeneration++
    }

    /** Stop generation, retaining its output while the runtime rolls back uncommitted writes. */
    fun stopTurn() {
        val stopped = turnJob ?: return
        val directory = projectDirectory
        val branch = branchId
        val expectedEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        stopped.cancel()
        if (directory != null && branch != null) viewModelScope.launch {
            stopped.join()
            val messages = withContext(Dispatchers.IO) {
                NovelWorkspaceRestoreBoundary.write(expectedEpoch) { NovelWorkspaceSessionHistory.recoverAndLoad(directory, branch) }
            }
            if (projectDirectory == directory && branchId == branch && NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) {
                _state.value = _state.value.copy(messages = messages)
            }
        }
    }

    init {
        reload()
        restoreBridge?.let { bridge ->
            viewModelScope.launch {
                var observedEpoch = bridge.state.value.epoch
                bridge.state.collect { restored ->
                    if (restored.restoring || restored.epoch != observedEpoch) {
                        turnJob?.cancel()
                        reloadJob?.cancel()
                        ghostwriteRefreshJob?.cancel()
                        ++reloadGeneration
                        _state.value = _state.value.copy(
                            restoreVersion = if (restored.restoring) _state.value.restoreVersion else restored.epoch,
                            loading = true,
                            busy = restored.restoring,
                            consistencyChecking = false,
                            consistencyCheckedChapters = 0,
                            consistencyTotalChapters = 0,
                            consistencyReport = null,
                            streamingText = "",
                            reasoningText = "",
                            toolActivity = null,
                        )
                        if (!restored.restoring) reload()
                    }
                    observedEpoch = restored.epoch
                }
            }
        }
    }

    fun reload() {
        _state.value = _state.value.copy(loading = true)
        reloadJob?.cancel()
        clearUiCache()
        val generation = ++reloadGeneration
        reloadJob = viewModelScope.launch {
            reloadState(generation)
        }
    }

    private suspend fun reloadState(expectedGeneration: Long) {
        val expectedEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        // Capture mutable navigation state on the ViewModel's main scope before the
        // snapshot work moves to IO. A stale/cancelled reload must never consume a
        // newer branch or deep-link focus and publish it later.
        val focusRequest = pendingFocus
        pendingFocus = null
        try {
            val snapshot = withContext(Dispatchers.IO) {
                NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
                    if (repository.exists(projectId)) runtime.loadProposals(repository.projectDirectory(projectId))
                    queries.readWorkspaceSnapshot(projectId, runtime.pendingProposals.value, focusRequest)
                }
            }
            if (expectedGeneration != reloadGeneration || !NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) return

            projectDirectory = snapshot.projectDirectory
            branchId = snapshot.branchId
            branchSlug = snapshot.branchSlug
            _state.value = _state.value.copy(
                // Keep the page gated until the existing ghostwrite reconciliation finishes;
                // otherwise send() could accept a turn before the durable batch projection arrives.
                loading = true,
                exists = snapshot.projectDirectory != null,
                title = snapshot.title,
                branchSlug = snapshot.branchSlug,
                branches = snapshot.branches,
                messages = snapshot.messages,
                chapters = snapshot.chapters,
                catalog = snapshot.catalog,
                proposals = snapshot.proposals,
                drafts = snapshot.drafts,
                plotStale = snapshot.plotStale,
                unresolvedFromOrdinal = snapshot.unresolvedFromOrdinal,
                writingModelId = snapshot.writingModelId,
                reviewModelId = snapshot.reviewModelId,
                ghostwriteMode = snapshot.ghostwriteMode,
                injection = snapshot.injection,
                canUndo = snapshot.canUndo,
            )
            // Keep the existing WorkManager reconciliation and refresh contract after the
            // base workspace snapshot has been published. Waiting suspends the main scope.
            refreshGhostwrite()
            ghostwriteRefreshJob?.join()
            if (expectedGeneration != reloadGeneration) return
            _state.value = _state.value.copy(loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (expectedGeneration == reloadGeneration) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = error.message ?: text(R.string.error_title_operation),
                )
            }
        }
    }

    private fun turnRequests(
        directory: File,
        branch: String,
        slug: String,
        settings: app.amber.core.settings.Settings,
        model: app.amber.ai.provider.Model,
        epoch: Long,
    ) = NovelWorkspaceTurnRequests(
        directory, branch, slug, settings, model, context.appLocale(), epoch,
        text(R.string.error_title_operation), _state.value.injection,
    )

    private fun beginInteractiveTurn() {
        _state.value = _state.value.copy(
            busy = true,
            errorMessage = null,
            streamingText = "",
            reasoningText = "",
            toolActivity = null,
        )
    }

    private fun finishInteractiveTurn(epoch: Long) {
        if (_state.value.busy && NovelWorkspaceRestoreBoundary.isCurrent(epoch)) {
            _state.value = _state.value.copy(
                busy = false,
                streamingText = "",
                reasoningText = "",
                toolActivity = null,
            )
        }
    }

    private suspend fun collectInteractiveTurn(
        request: NovelWorkspaceRuntime.TurnRequest,
        retainReply: Boolean,
        filesBeforeQuickstart: Int = -1,
    ) {
        interactiveTurns.execute(request, retainReply, filesBeforeQuickstart).collect { event ->
            when (event) {
                is NovelWorkspaceInteractiveTurns.Event.Text -> _state.value = _state.value.copy(streamingText = event.content)
                is NovelWorkspaceInteractiveTurns.Event.Reasoning -> _state.value = _state.value.copy(reasoningText = event.content)
                is NovelWorkspaceInteractiveTurns.Event.Tool -> _state.value = _state.value.copy(toolActivity = toolLabel(event.name))
                is NovelWorkspaceInteractiveTurns.Event.Completed -> _state.value = _state.value.copy(
                    busy = false,
                    streamingText = "",
                    reasoningText = "",
                    toolActivity = null,
                    errorMessage = if (event.producedNothing) text(R.string.novel_no_setting_files) else null,
                    messages = if (retainReply) event.content.messages else _state.value.messages,
                    chapters = event.content.chapters,
                    drafts = event.content.drafts,
                    proposals = event.content.proposals,
                    plotStale = event.content.plotStale,
                    unresolvedFromOrdinal = event.content.unresolvedFromOrdinal,
                    catalog = event.content.catalog,
                    canUndo = event.content.canUndo,
                )
                is NovelWorkspaceInteractiveTurns.Event.Failed -> _state.value = _state.value.copy(
                    messages = event.messages,
                    busy = false,
                    streamingText = "",
                    toolActivity = null,
                    errorMessage = event.message,
                    proposals = event.proposals,
                )
            }
        }
    }

    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return false
        if (_state.value.loading) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_loading))
            return false
        }
        val directory = projectDirectory
        val branch = branchId
        val slug = branchSlug
        if (directory == null || branch == null || slug == null) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_loading))
            return false
        }
        if (hasKnownActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return false
        }
        if (_state.value.composerMode == NovelMarkdownComposerMode.WriteProse) {
            if (_state.value.plotStale) {
                _state.value = _state.value.copy(
                    errorMessage = text(
                        R.string.novel_ghostwrite_error_stale_plot,
                        text(R.string.novel_ghostwrite_task_write),
                    ),
                )
                return false
            }
            if (_state.value.unresolvedFromOrdinal != null) {
                _state.value = _state.value.copy(
                    errorMessage = text(
                        R.string.novel_ghostwrite_error_unresolved_edits,
                        text(R.string.novel_ghostwrite_task_write),
                    ),
                )
                return false
            }
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings)
        if (model == null) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return false
        }
        beginInteractiveTurn()
        turnJob?.cancel()
        val acceptedMessage = NovelWorkspaceSessionMessage(
            id = UUID.randomUUID().toString().uppercase(),
            role = "user",
            kind = "userInput",
            content = trimmed,
            createdAt = Instant.now(),
        )
        val acceptedMessageUi = NovelMarkdownMessageUi(
            id = acceptedMessage.id,
            role = MessageRole.USER,
            content = acceptedMessage.content,
        )
        val turnEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        turnJob = viewModelScope.launch {
            try {
                // Admission is synchronous; the session usecase durably appends accepted input
                // before cancellable filesystem preflight, including an immediate Stop.
                val preparation = try {
                    interactiveTurns.prepareSend(
                        directory, branch, turnEpoch, acceptedMessage,
                        hasChapters = _state.value.chapters.isNotEmpty(),
                    )
                } catch (cancel: CancellationException) {
                    // The accepted user message was already durably appended. Publish that
                    // known message even when Stop cancels the follow-up read; do not continue
                    // into model execution after rethrowing cancellation.
                    if (_state.value.messages.none { it.id == acceptedMessageUi.id }) {
                        _state.value = _state.value.copy(
                            messages = _state.value.messages + acceptedMessageUi,
                        )
                    }
                    throw cancel
                }
                _state.value = _state.value.copy(messages = preparation.messages)
                currentCoroutineContext().ensureActive()
                android.util.Log.i("NovelWorkspace", "send: turn starting (blank=${preparation.isBlankBook})")
                collectInteractiveTurn(
                    turnRequests(directory, branch, slug, settings, model, turnEpoch).discussion(
                        trimmed, preparation, acceptedMessage.id,
                        _state.value.composerMode == NovelMarkdownComposerMode.WriteProse,
                    ),
                    retainReply = true,
                    filesBeforeQuickstart = preparation.filesBeforeQuickstart,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (NovelWorkspaceRestoreBoundary.isCurrent(turnEpoch)) {
                    _state.value = _state.value.copy(
                        errorMessage = localizedRuntimeError(error, R.string.workspace_save_failed),
                    )
                }
            } finally {
                // Stop button / VM clear cancels the collect: reset the busy chrome.
                finishInteractiveTurn(turnEpoch)
            }
        }
        return true
    }

    /** D-D resolution: let the assistant rewrite the chapters a middle edit invalidated.
     *  Rewrites go through the canon gate as proposals; the author approves them, then
     *  confirms 无碍 to clear the gate. */
    fun rewriteLaterChapters() {
        val fromOrdinal = _state.value.unresolvedFromOrdinal ?: return
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return
        }
        beginInteractiveTurn()
        turnJob?.cancel()
        val turnEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        turnJob = viewModelScope.launch {
            try {
                collectInteractiveTurn(
                    turnRequests(directory, branch, slug, settings, model, turnEpoch).rewriteLater(fromOrdinal),
                    retainReply = false,
                )
            } finally {
                finishInteractiveTurn(turnEpoch)
            }
        }
    }

    /**
     * 重写本章（Regenerate）：让模型产出整章替换稿。chapters/ 是受保护路径，模型的写入
     * 经 novel_workspace_write 缓冲为提案，走现有审批卡（MarkdownProposalCard）确认/拒绝，
     * 不新建审批机制；该章若处于中间章未决（unresolved）状态照常允许重写，未决门既有
     * 语义自会处理，这里不特判。
     */
    fun rewriteChapter(ordinal: Int): Boolean = reviseChapter(ordinal, polish = false)

    private fun reviseChapter(ordinal: Int, polish: Boolean): Boolean {
        val directory = projectDirectory ?: return false
        val branch = branchId ?: return false
        val slug = branchSlug ?: return false
        if (_state.value.busy) return false
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return false
        }
        val chapter = _state.value.chapters.firstOrNull { it.ordinal == ordinal } ?: return false
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return false
        }
        val store = NovelWorkspaceStore(directory)
        val currentBody = store.read(chapter.path)
            ?.let { NovelWorkspaceMarkdown.parseFile(it).body }
            .orEmpty()
        beginInteractiveTurn()
        turnJob?.cancel()
        val turnEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        turnJob = viewModelScope.launch {
            try {
                val plan = if (polish) null else pathRead(planPath())
                val writingPreference = readWritingPreference(forGeneration = true)
                collectInteractiveTurn(
                    turnRequests(directory, branch, slug, settings, model, turnEpoch).revision(
                        chapter, currentBody, polish, writingPreference, plan,
                    ),
                    retainReply = false,
                )
            } finally {
                // Stop/cancel mid-turn: reset the busy chrome like send() does.
                finishInteractiveTurn(turnEpoch)
            }
        }
        return true
    }

    /**
     * 创作快捷动作：角色提案。人物卡写入 setting/characters/（自由写路径，novel_workspace_write
     * 直存、无需审批）；目标文件名由宿主按现有 slug 规则净化并对既有卡片去重。
     */
    fun proposeCharacter(name: String, sketch: String) {
        val trimmedName = name.trim()
        val trimmedSketch = sketch.trim()
        if (trimmedName.isEmpty()) return
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return
        }
        val target = NovelWorkspaceTurnRequests.characterTarget(directory, trimmedName)
        NovelWorkspaceSessionHistory.append(directory, branch, NovelWorkspaceSessionMessage(
            id = UUID.randomUUID().toString().uppercase(),
            role = "user",
            kind = "userInput",
            content = localizedPromptText(
                chinese = "提案角色「$trimmedName」：$trimmedSketch",
                english = "Propose character \"$trimmedName\": $trimmedSketch",
            ),
            createdAt = Instant.now(),
        ))
        _state.value = _state.value.copy(
            busy = true,
            errorMessage = null,
            streamingText = "",
            reasoningText = "",
            toolActivity = null,
            messages = NovelWorkspaceSessionHistory.recoverAndLoad(directory, branchId),
        )
        turnJob?.cancel()
        val turnEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        turnJob = viewModelScope.launch {
            try {
                collectInteractiveTurn(
                    turnRequests(directory, branch, slug, settings, model, turnEpoch).character(trimmedName, trimmedSketch, target),
                    retainReply = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = localizedRuntimeError(error, R.string.error_title_operation),
                )
            } finally {
                finishInteractiveTurn(turnEpoch)
            }
        }
    }

    fun approve(proposalId: String, onApproved: () -> Unit = {}) {
        if (proposalsForThisProject().none { it.id == proposalId }) return
        runAuthorEdit(onApproved) { _, _, _ -> runtime.approve(proposalId) }
    }

    fun editProposal(proposalId: String, entries: List<app.amber.feature.novel.workspace.NovelWorkspaceWriteEntry>, onSaved: () -> Unit) {
        if (proposalsForThisProject().none { it.id == proposalId }) return
        runAuthorEdit(onSaved) { _, _, _ -> runtime.editProposal(proposalId, entries) }
    }

    fun reject(proposalId: String) {
        if (proposalsForThisProject().none { it.id == proposalId }) return
        runAuthorEdit({}) { _, _, _ -> runtime.reject(proposalId) }
    }

    /** D-D resolve (确认无碍): clear the unresolved gate for the current branch. */
    fun resolveUnresolved() {
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        runCatching {
            NovelWorkspaceUnresolvedStore.clear(directory, slug)
            _state.value = _state.value.copy(unresolvedFromOrdinal = null)
        }.onFailure { error ->
            _state.value = _state.value.copy(errorMessage = error.message ?: text(R.string.error_title_operation))
        }
    }

    /** Author manual chapter edit; saving commits it (middle edits raise the unresolved gate). */
    fun saveChapterEdit(path: String, title: String, body: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            runtime.saveChapterEdit(directory, branch, slug, path, title, body)
        }
    }

    /** 撤销最近一笔 canon commit (single level, branch-bound). */
    fun undoLast() {
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        val expectedEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            val undone = withContext(Dispatchers.IO) {
                NovelWorkspaceRestoreBoundary.write(expectedEpoch) { runtime.undoLast(directory, slug) }
            }
            NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            if (!undone) {
                _state.value = _state.value.copy(errorMessage = text(R.string.novel_unknown_reason))
                return@launch
            }
            val store = NovelWorkspaceStore(directory)
            _state.value = _state.value.copy(
                chapters = queries.chapters(store, branchSlug),
                drafts = queries.drafts(store, branchId),
                catalog = queries.catalog(directory, slug),
                proposals = proposalsForThisProject(),
                plotStale = NovelWorkspaceLedger.isPlotStale(
                    store,
                    NovelWorkspaceLedger.load(directory),
                    slug,
                ),
                unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                canUndo = runtime.canUndo(directory, slug),
            )
        }
    }

    suspend fun readChapter(path: String): String? = readFileBody(path)

    /** Body of any book file (setting cards, foreshadowing nodes) for the 设定 tab editor. */
    suspend fun readFileBody(path: String): String? {
        val directory = projectDirectory ?: return null
        return withContext(Dispatchers.IO) {
            queries.fileBody(directory, path)
        }
    }

    // ── 多分支：新建 / 切换（branch sheet）────────────────────────────

    /** 从当前活跃分支分叉出新分支；成功后刷新分支列表（不切换）。 */
    fun createBranch(name: String) {
        val directory = projectDirectory ?: return
        val current = branchSlug ?: return
        if (_state.value.busy) return
        val expectedEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
                    NovelWorkspaceBranches.createBranch(
                        directory,
                        current,
                        name,
                        locale = context.appLocale(),
                    )
                    }
                }
                NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                val slug = NovelWorkspaceBranches.activeSlug(directory)
                _state.value = _state.value.copy(
                    errorMessage = null,
                    branches = NovelWorkspaceBranches.list(directory, slug),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message ?: text(R.string.error_title_operation))
            } finally {
                if (NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /**
     * 切换活跃分支：批次进行中被存储层拒绝；成功后清空本项目在途提案/草稿卡（内存态
     * 属于切换前的分支视图）并整页重载（章节/剧情门/undo/会话/注入简报/设定 tab）。
     */
    fun switchBranch(slug: String) {
        val directory = projectDirectory ?: return
        if (_state.value.busy) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_in_progress))
            return
        }
        // Invalidate an in-flight initial/reload snapshot before changing the active branch.
        // Its IO work may finish later, but the generation check in reloadState prevents it
        // from publishing the old branch over the newly selected one.
        reloadJob?.cancel()
        clearUiCache()
        val reloadGeneration = ++this.reloadGeneration
        val expectedEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
                    NovelWorkspaceBranches.switchBranch(
                        directory,
                        slug,
                        locale = context.appLocale(),
                    )
                    }
                }
                NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                // A notification focus belongs to the branch/job the page initially opened;
                // after an explicit switch, neither that job nor its branch should win the
                // next refresh.
                focusedJobId = null
                // Preserve durable proposals on other branches; reload filters this branch.
                _state.value = _state.value.copy(
                    streamingText = "",
                    reasoningText = "",
                    toolActivity = null,
                    consistencyReport = null,
                )
                reloadState(reloadGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = error.message ?: text(R.string.error_title_operation),
                )
            } finally {
                if (NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    // ── 设定 tab：保存（走既有直写+commit+undo 惯例）─────────────────

    /** 保存设定卡/伏笔节点的正文修改：宿主手改 + 「手改」commit + undo 记录。 */
    fun saveFileEdit(path: String, body: String, onSaved: () -> Unit) {
        commitFileEdit(path, body, onSaved)
    }

    fun saveOpenedFileEdit(path: String, body: String, expectedContentRaw: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            checkNotNull(NovelWorkspaceGhostwriteJobs.withNoActiveBranch(directory, slug) {
                check(NovelWorkspaceStore(directory).read(path).orEmpty() == expectedContentRaw) {
                    text(R.string.novel_history_changed)
                }
                if (path == "${NovelWorkspacePaths.branchPrefix(slug)}/plot/current.md") require(body.isNotBlank()) {
                    text(R.string.novel_material_body)
                }
                runtime.saveFileEdit(directory, branch, slug, path, body)
            }) { text(R.string.novel_batch_in_use) }
        }
    }

    /**
     * 写作偏好 = setting/writing 卡（首个文件；首次保存创建）。与设定 tab 的
     * saveFileEdit 走同一提交口径（手改 commit + undo 记录，J7）：面板行为不变
     * （保存即落盘），但与双入口另一侧一样可撤销、进账本。批次进行中面板本就
     * 禁用（branchOwned），runtime 的 owner gate 是第二道。
     */
    fun saveWritingPreference(body: String, onSaved: () -> Unit) {
        val directory = projectDirectory ?: return
        val store = NovelWorkspaceStore(directory)
        val target = app.amber.feature.novelworkspace.NovelWorkspaceEffectiveMaterials
            .writingPreference(store, branchSlug ?: return)?.path
            ?: NovelWorkspacePaths.SETTING_DIR + "/writing/写作要求.md"
        commitFileEdit(target, body, onSaved)
    }

    /** saveFileEdit / saveWritingPreference 共享的宿主手改提交路径（含刷新与 undo）。 */
    private fun commitFileEdit(path: String, body: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            runtime.saveFileEdit(directory, branch, slug, path, body)
        }
    }

    /** Short author writes share one UI busy/refresh boundary; model turns keep their own lifecycle. */
    private fun runAuthorEdit(onSaved: () -> Unit, edit: (File, String, String) -> Unit) {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        val expectedEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        _state.value = _state.value.copy(busy = true, errorMessage = null)
        viewModelScope.launch {
            var saved = false
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
                        edit(directory, branch, slug)
                        queries.contentSnapshot(directory, branch, slug, proposalsForThisProject(directory))
                    }
                }
                NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                _state.value = _state.value.copy(
                    chapters = snapshot.chapters,
                    drafts = snapshot.drafts,
                    catalog = snapshot.catalog,
                    proposals = snapshot.proposals,
                    messages = snapshot.messages,
                    plotStale = snapshot.plotStale,
                    unresolvedFromOrdinal = snapshot.unresolvedFromOrdinal,
                    canUndo = snapshot.canUndo,
                )
                saved = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = localizedRuntimeError(error, R.string.workspace_save_failed))
            } finally {
                if (NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
            }
            if (saved && NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) onSaved()
        }
    }

    suspend fun readFileRaw(path: String): String? {
        val directory = projectDirectory ?: return null
        return withContext(Dispatchers.IO) { NovelWorkspaceStore(directory).read(path) }
    }

    fun createMaterial(kind: String, title: String, body: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            app.amber.feature.novel.workspace.NovelWorkspaceMaterialActions.create(
                directory, branch, slug, kind, title, body,
            )
        }
    }

    fun saveMaterial(path: String, title: String, body: String, expectedContentRaw: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            app.amber.feature.novel.workspace.NovelWorkspaceMaterialActions.save(
                directory, branch, slug, path, title, body, expectedContentRaw,
            )
        }
    }

    fun deleteMaterial(path: String, expectedContentRaw: String, onDeleted: () -> Unit) {
        runAuthorEdit(onDeleted) { directory, branch, slug ->
            app.amber.feature.novel.workspace.NovelWorkspaceMaterialActions.delete(
                directory, branch, slug, path, expectedContentRaw,
            )
        }
    }

    fun archiveDecision(sourceMessageId: String, title: String, body: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            app.amber.feature.novel.workspace.NovelWorkspaceMaterialActions.archiveDecision(
                directory, branch, slug, sourceMessageId, title, body,
            )
        }
    }

    suspend fun loadDiscardedChapters(): NovelMarkdownDiscardedSnapshotUi? {
        val directory = projectDirectory ?: return null
        val branch = branchId ?: return null
        val slug = branchSlug ?: return null
        return withContext(Dispatchers.IO) {
            queries.discarded(directory, branch, slug)
        }
    }

    fun discardChapter(path: String, expectedHeadId: String?, expectedTreeDigest: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            app.amber.feature.novel.workspace.NovelWorkspaceDiscardedChapters.discard(
                directory, branch, slug, path, expectedHeadId, expectedTreeDigest,
            )
        }
    }

    fun restoreDiscardedChapter(path: String, expectedHeadId: String?, expectedTreeDigest: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            app.amber.feature.novel.workspace.NovelWorkspaceDiscardedChapters.restore(
                directory, branch, slug, path, expectedHeadId, expectedTreeDigest,
            )
        }
    }

    suspend fun chapterHistory(path: String): app.amber.feature.novelworkspace.NovelWorkspaceChapterHistorySnapshot? {
        val directory = projectDirectory ?: return null
        val branch = branchId ?: return null
        val slug = branchSlug ?: return null
        return withContext(Dispatchers.IO) {
            app.amber.feature.novelworkspace.NovelWorkspaceChapterHistory.snapshot(directory, branch, slug, path)
        }
    }

    suspend fun readChapterVersion(contentHash: String): String? {
        val directory = projectDirectory ?: return null
        return withContext(Dispatchers.IO) {
            app.amber.feature.novelworkspace.NovelWorkspaceChapterHistory.read(directory, contentHash)
        }
    }

    fun restoreChapterVersion(path: String, contentHash: String, expectedHeadId: String?, expectedCurrentHash: String, onSaved: () -> Unit) {
        runAuthorEdit(onSaved) { directory, branch, slug ->
            checkNotNull(app.amber.feature.novel.workspace.NovelWorkspaceChapterRestore.restore(
                directory, branch, slug, path, contentHash, expectedHeadId, expectedCurrentHash,
            )) { text(R.string.novel_history_changed) }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun setComposerMode(mode: NovelMarkdownComposerMode) {
        _state.value = _state.value.copy(composerMode = mode)
    }

    private fun proposalsForThisProject(directory: File? = projectDirectory): List<NovelWorkspaceWriteProposal> {
        directory ?: return emptyList()
        return runtime.pendingProposals.value.filter {
            it.projectDirectory == directory && it.branchId == branchId && it.branchSlug == branchSlug
        }
    }

    private fun hasActiveGhostwrite(): Boolean {
        if (_state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_FAILED
        ) return true
        val directory = projectDirectory ?: return false
        val slug = branchSlug ?: return false
        return NovelWorkspaceGhostwriteJobs.activeFor(directory, slug) != null
    }

    /** Fast UI callback guard used by send(); the runtime still performs the durable owner CAS. */
    private fun hasKnownActiveGhostwrite(): Boolean =
        _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_FAILED

    /** Author collects a draft into the manuscript and commits (the click is the approval). */
    fun collectDraft(draftPath: String, target: NovelWorkspaceCollectTarget, title: String? = null, onCollected: () -> Unit = {}) {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        if (target !is NovelWorkspaceCollectTarget.ReplaceChapter) {
            val currentLedger = NovelWorkspaceLedger.load(directory)
            if (NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(directory), currentLedger, slug)) {
                _state.value = _state.value.copy(
                    errorMessage = text(
                        R.string.novel_ghostwrite_error_stale_plot,
                        text(R.string.novel_ghostwrite_task_write),
                    ),
                )
                return
            }
            if (NovelWorkspaceUnresolvedStore.entryFor(directory, slug) != null) {
                _state.value = _state.value.copy(
                    errorMessage = text(
                        R.string.novel_ghostwrite_error_unresolved_edits,
                        text(R.string.novel_ghostwrite_task_write),
                    ),
                )
                return
            }
        }
        runAuthorEdit(onCollected) { editDirectory, editBranch, editSlug ->
            runtime.collectDraft(
                projectDirectory = editDirectory,
                branchId = editBranch,
                branchSlug = editSlug,
                draftPath = draftPath,
                target = target,
                chapterTitle = title,
            )
        }
    }

    /** Load the active ghostwrite job + ledger-derived progress into the UI state.
     *  When nothing is active, the latest failed job is shown instead so a dead
     *  batch leaves a visible reason instead of silently disappearing. */
    fun refreshGhostwrite() {
        val expectedRestoreEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val directory = projectDirectory ?: return
        val branch = branchId
        val slug = branchSlug ?: return
        ghostwriteRefreshJob?.cancel()
        val cacheGeneration = uiCacheGeneration
        val cachedChapters = queries.cachedChapters()
        val cachedDrafts = queries.cachedDrafts()
        ghostwriteRefreshJob = viewModelScope.launch {
            try {
                val refresh = withContext(Dispatchers.IO) {
                    queries.ghostwriteRefresh(
                        directory, branch, slug, focusedJobId, expectedRestoreEpoch,
                        ghostwriteController, cachedChapters, cachedDrafts, ::localizedJobReason,
                    )
                }
                NovelWorkspaceRestoreBoundary.write(expectedRestoreEpoch) { Unit }
                if (cacheGeneration != uiCacheGeneration || directory != projectDirectory || slug != branchSlug) {
                    return@launch
                }
                queries.acceptRefresh(refresh)
                _state.value = _state.value.copy(
                    ghostwriteJob = refresh.job,
                    unreadableJobFiles = refresh.unreadableJobFiles,
                    chapters = refresh.chapters,
                    drafts = refresh.drafts,
                    catalog = refresh.catalog,
                    plotStale = refresh.plotStale,
                    unresolvedFromOrdinal = refresh.unresolvedFromOrdinal,
                    canUndo = refresh.canUndo,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = localizedRuntimeError(error, R.string.error_title_operation))
            }
        }
    }

    fun startGhostwriteBatch(targetChapterCount: Int) {
        val expectedRestoreEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        if (targetChapterCount !in 1..NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS) {
            _state.value = _state.value.copy(errorMessage = text(R.string.error_title_operation))
            return
        }
        checkBatchStartReady(polish = false)?.let { blocked ->
            _state.value = _state.value.copy(errorMessage = blocked)
            return
        }
        viewModelScope.launch {
            runCatching {
                ghostwriteController.startBatch(directory, projectId, slug, targetChapterCount, expectedRestoreEpoch = expectedRestoreEpoch)
            }
                .onSuccess { job ->
                    if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onSuccess
                    focusedJobId = job.id
                    _state.value = _state.value.copy(
                        errorMessage = null,
                        ghostwriteJob = novelMarkdownGhostwriteUi(job, written = 0),
                    )
                }
                .onFailure {
                    if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onFailure
                    if (it is CancellationException) throw it
                    _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.error_title_operation))
                    refreshGhostwrite()
                }
        }
    }

    /**
     * Batch polish for the inclusive ordinal range [fromOrdinal, toOrdinal]. Both batch kinds
     * share the durable branch claim and pause/resume/CAS semantics.
     */
    fun startPolish(fromOrdinal: Int, toOrdinal: Int): Boolean {
        val expectedRestoreEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val directory = projectDirectory ?: return false
        val slug = branchSlug ?: return false
        if (fromOrdinal <= 0 || toOrdinal < fromOrdinal) {
            _state.value = _state.value.copy(errorMessage = text(R.string.error_title_operation))
            return false
        }
        val ordinals = _state.value.chapters.map { it.ordinal }.toSet()
        val missing = (fromOrdinal..toOrdinal).firstOrNull { it !in ordinals }
        if (missing != null) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_no_chapters_to_polish))
            return false
        }
        if (fromOrdinal == toOrdinal) return reviseChapter(fromOrdinal, polish = true)
        checkBatchStartReady(polish = true)?.let { blocked ->
            _state.value = _state.value.copy(errorMessage = blocked)
            return false
        }
        viewModelScope.launch {
            runCatching {
                ghostwriteController.startPolishBatch(directory, projectId, slug, fromOrdinal, toOrdinal, expectedRestoreEpoch = expectedRestoreEpoch)
            }
                .onSuccess { job ->
                    if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onSuccess
                    focusedJobId = job.id
                    _state.value = _state.value.copy(
                        errorMessage = null,
                        ghostwriteJob = novelMarkdownGhostwriteUi(job, written = 0),
                    )
                }
                .onFailure {
                    if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onFailure
                    if (it is CancellationException) throw it
                    _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.error_title_operation))
                    refreshGhostwrite()
                }
        }
        return true
    }

    /** Shared pre-flight gates for both batch kinds. */
    private fun checkBatchStartReady(polish: Boolean): String? {
        val directory = projectDirectory
        val slug = branchSlug
        if (directory == null || slug == null) return text(R.string.novel_loading)
        val settings = settingsAggregator.settingsFlow.value
        if (settings.init) return text(R.string.novel_loading)
        if (resolveWritingModel(settings) == null) {
            return text(R.string.novel_ghostwrite_error_model_missing)
        }
        // A failed job remains bound to its frozen plan until explicitly dismissed or retried.
        if (_state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_FAILED
        ) {
            return text(R.string.novel_branch_locked)
        }
        if (NovelWorkspaceUnresolvedStore.entryFor(directory, slug) != null) {
            return text(
                R.string.novel_ghostwrite_error_unresolved_edits,
                text(if (polish) R.string.novel_ghostwrite_task_polish else R.string.novel_ghostwrite_task_write),
            )
        }
        if (NovelWorkspaceLedger.isPlotStale(
                NovelWorkspaceStore(directory),
                NovelWorkspaceLedger.load(directory),
                slug,
            )
        ) {
            _state.value = _state.value.copy(plotStale = true)
            return text(
                R.string.novel_ghostwrite_error_stale_plot,
                text(if (polish) R.string.novel_ghostwrite_task_polish else R.string.novel_ghostwrite_task_write),
            )
        }
        return null
    }

    fun pauseGhostwriteBatch() {
        val expectedRestoreEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            val directory = projectDirectory ?: return@launch
            val current = _state.value.ghostwriteJob ?: return@launch
            runCatching { ghostwriteController.pause(directory, current.jobId, current.executionId, expectedRestoreEpoch = expectedRestoreEpoch) }
                .onFailure {
                    if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onFailure
                    if (it is CancellationException) throw it
                    _state.value = _state.value.copy(errorMessage = it.message)
                }
            refreshGhostwrite()
        }
    }

    fun resumeGhostwriteBatch() {
        val expectedRestoreEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val directory = projectDirectory ?: return
        val current = _state.value.ghostwriteJob ?: return
        val slug = branchSlug ?: return
        if (current.branchSlug != slug) {
            _state.value = _state.value.copy(errorMessage = text(R.string.error_title_operation))
            return
        }
        viewModelScope.launch {
            runCatching {
                ghostwriteController.resume(
                    directory,
                    projectId,
                    current.jobId,
                    current.executionId,
                    expectedBranchSlug = slug,
                    expectedRestoreEpoch = expectedRestoreEpoch,
                )
            }.onSuccess { resumed ->
                if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onSuccess
                if (resumed != null) {
                    _state.value = _state.value.copy(
                        errorMessage = null,
                        ghostwriteJob = current.copy(
                            executionId = resumed.executionKey,
                            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
                            reason = null,
                        ),
                    )
                }
            }.onFailure {
                    if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onFailure
                if (it is CancellationException) throw it
                _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.error_title_operation))
            }
            refreshGhostwrite()
        }
    }

    fun retryFailedGhostwriteBatch() {
        val expectedRestoreEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val directory = projectDirectory ?: return
        val current = _state.value.ghostwriteJob ?: return
        val slug = branchSlug ?: return
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_FAILED) return
        if (current.branchSlug != slug) {
            _state.value = _state.value.copy(errorMessage = text(R.string.error_title_operation))
            return
        }
        viewModelScope.launch {
            runCatching {
                ghostwriteController.retryFailed(
                    directory,
                    projectId,
                    current.jobId,
                    current.executionId,
                    expectedBranchSlug = slug,
                    expectedRestoreEpoch = expectedRestoreEpoch,
                )
            }.onSuccess { resumed ->
                if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onSuccess
                _state.value = _state.value.copy(
                    errorMessage = null,
                    ghostwriteJob = current.copy(
                        executionId = resumed.executionKey,
                        status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
                        reason = null,
                    ),
                )
            }.onFailure {
                    if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onFailure
                if (it is CancellationException) throw it
                _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.error_title_operation))
            }
            refreshGhostwrite()
        }
    }

    fun cancelGhostwriteBatch() {
        val expectedRestoreEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            val directory = projectDirectory ?: return@launch
            val current = _state.value.ghostwriteJob ?: return@launch
            runCatching { ghostwriteController.cancel(directory, current.jobId, current.executionId, expectedRestoreEpoch = expectedRestoreEpoch) }
                .onFailure {
                    if (!NovelWorkspaceRestoreBoundary.isCurrent(expectedRestoreEpoch)) return@onFailure
                    if (it is CancellationException) throw it
                    _state.value = _state.value.copy(errorMessage = it.message)
                }
            refreshGhostwrite()
        }
    }

    /** Drop the read-only failed-batch card so the start form comes back. */
    fun dismissGhostwriteFailure() {
        val directory = projectDirectory ?: return
        val current = _state.value.ghostwriteJob ?: return
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_FAILED) return
        val expectedEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            try {
                val dismissed = withContext(Dispatchers.IO) {
                    NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
                        NovelWorkspaceGhostwriteJobs.dismissFailed(directory, current.jobId, current.executionId)
                    }
                }
                NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                if (dismissed) {
                    focusedJobId = null
                    _state.value = _state.value.copy(ghostwriteJob = null, errorMessage = null)
                } else {
                    refreshGhostwrite()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = localizedRuntimeError(error, R.string.error_title_operation),
                )
            }
        }
    }
    private fun resolveWritingModel(settings: app.amber.core.settings.Settings): app.amber.ai.provider.Model? {
        // Project settings are loaded into the UI snapshot on IO. Reusing that value keeps
        // button callbacks (send, rewrite, batch start) free of synchronous file reads.
        val override = _state.value.writingModelId
        if (override != null) {
            runCatching { Uuid.parse(override) }.getOrNull()?.let { uuid ->
                settings.findModelById(uuid)?.let { return it }
            }
        }
        return settings.getCurrentChatModel()
    }

    /** Review model: project review override → writing override → global chat model. */
    @OptIn(ExperimentalUuidApi::class)
    private fun resolveReviewModel(settings: app.amber.core.settings.Settings): app.amber.ai.provider.Model? {
        val reviewOverride = _state.value.reviewModelId
        if (reviewOverride != null) {
            runCatching { Uuid.parse(reviewOverride) }.getOrNull()?.let { uuid ->
                settings.findModelById(uuid)?.let { return it }
            }
        }
        return resolveWritingModel(settings)
    }

    /** Author-triggered whole-book review. The coordinator only reads captured manuscripts. */
    fun runConsistencyCheck() {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy || _state.value.loading || _state.value.consistencyChecking) return
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveReviewModel(settings)
        if (model == null) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return
        }
        val turnEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val request = NovelWorkspaceContinuityAudit.Request(
            projectDirectory = directory,
            branchId = branch,
            branchSlug = slug,
            settings = settings,
            model = model,
            injection = _state.value.injection,
            locale = context.appLocale(),
            restoreEpoch = turnEpoch,
        )
        val reportId = UUID.randomUUID().toString()
        _state.value = _state.value.copy(
            consistencyChecking = true,
            consistencyCheckedChapters = 0,
            consistencyTotalChapters = 0,
            consistencyReport = null,
            errorMessage = null,
            busy = true,
            streamingText = "",
            reasoningText = "",
            toolActivity = null,
        )
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            auditSession.run(
                request, reportId,
                onProgress = { progress ->
                    _state.value = _state.value.copy(
                        consistencyCheckedChapters = progress.checked,
                        consistencyTotalChapters = progress.total,
                    )
                },
                onFinished = { completed ->
                    val report = completed.report
                    val saveError = completed.saveError?.let { localizedRuntimeError(it, R.string.workspace_save_failed) }
                    val executionError = completed.executionError?.let { localizedRuntimeError(it, R.string.error_title_operation) }
                    _state.value = _state.value.copy(
                        busy = false,
                        consistencyChecking = false,
                        consistencyReport = report?.report,
                        consistencyCheckedChapters = report?.checked ?: 0,
                        consistencyTotalChapters = report?.total ?: 0,
                        messages = completed.messages ?: _state.value.messages,
                        errorMessage = saveError ?: if (completed.stopped) null else report?.error ?: executionError ?: _state.value.errorMessage,
                        streamingText = "",
                        reasoningText = "",
                        toolActivity = null,
                    )
                },
            )
        }
    }

    fun dismissConsistencyReport() {
        _state.value = _state.value.copy(consistencyReport = null)
    }

    /** Persist the selected collaboration mode without starting or cancelling work. */
    fun setGhostwriteMode(enabled: Boolean) {
        val directory = projectDirectory ?: return
        if (_state.value.loading || _state.value.busy || hasActiveGhostwrite()) return
        val epoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        runCatching {
            NovelWorkspaceRestoreBoundary.write(epoch) {
                val current = NovelWorkspaceProjectSettingsStore.load(directory)
                NovelWorkspaceProjectSettingsStore.save(current.copy(ghostwriteMode = enabled), directory)
                _state.value = _state.value.copy(ghostwriteMode = enabled)
            }
        }.onFailure {
            _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed))
        }
    }

    /** Set or clear the per-project writing model override. */
    fun setWritingModel(modelId: String?) {
        val directory = projectDirectory ?: return
        runCatching { NovelWorkspaceRestoreBoundary.write {
            val current = NovelWorkspaceProjectSettingsStore.load(directory)
            NovelWorkspaceProjectSettingsStore.save(
                current.copy(writingModelId = modelId),
                directory,
            )
            _state.value = _state.value.copy(writingModelId = modelId)
        } }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed)) }
    }

    /** Set or clear the per-project review model override (null = follow the writing model). */
    fun setReviewModel(modelId: String?) {
        val directory = projectDirectory ?: return
        runCatching { NovelWorkspaceRestoreBoundary.write {
            val current = NovelWorkspaceProjectSettingsStore.load(directory)
            NovelWorkspaceProjectSettingsStore.save(
                current.copy(reviewModelId = modelId),
                directory,
            )
            _state.value = _state.value.copy(reviewModelId = modelId)
        } }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed)) }
    }

    fun currentWritingModelId(): String? =
        _state.value.writingModelId

    // ── Ghostwrite panel backing: author-editable control files + injected-brief preview.

    private fun planPath(): String? =
        branchSlug?.let { NovelWorkspacePaths.branchPrefix(it) + "/plan/this-chapter.md" }

    private fun upcomingPath(): String? =
        branchSlug?.let { NovelWorkspacePaths.branchPrefix(it) + "/plan/upcoming.md" }

    suspend fun readChapterPlan(): String {
        val directory = projectDirectory ?: return ""
        val slug = branchSlug ?: return ""
        return withContext(Dispatchers.IO) {
            queries.pathBody(directory, NovelWorkspacePaths.branchPrefix(slug) + "/plan/this-chapter.md") ?: ""
        }
    }

    fun saveChapterPlan(body: String): Boolean {
        projectDirectory ?: return false
        branchSlug ?: return false
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return false
        }
        return pathWrite(planPath(), body)
    }

    suspend fun readUpcomingArc(): String {
        val directory = projectDirectory ?: return ""
        val slug = branchSlug ?: return ""
        return withContext(Dispatchers.IO) {
            queries.pathBody(directory, NovelWorkspacePaths.branchPrefix(slug) + "/plan/upcoming.md") ?: ""
        }
    }

    fun saveUpcomingArc(body: String) = pathWrite(upcomingPath(), body)

    /** Writing preference = the setting/writing card (first file; created on save). */
    suspend fun readWritingPreference(forGeneration: Boolean = false): String {
        val directory = projectDirectory ?: return ""
        val slug = branchSlug ?: return ""
        return withContext(Dispatchers.IO) {
            queries.writingPreference(directory, slug, forGeneration)
        }
    }

    // saveWritingPreference 已上移至设定 tab 的 saveFileEdit 旁：两入口共用 commitFileEdit
    // （手改 commit + undo，J7 口径统一）。

    /** What the host will inject as constraints next turn (ghostwrite panel preview). */
    suspend fun briefPreview(): String {
        val directory = projectDirectory ?: return ""
        val slug = branchSlug ?: return ""
        val flags = _state.value.injection
        return withContext(Dispatchers.IO) {
            queries.briefPreview(directory, slug, flags, context.appLocale())
        }
    }

    private fun pathRead(path: String?): String? {
        val directory = projectDirectory ?: return null
        return queries.pathBody(directory, path)
    }

    private fun pathWrite(path: String?, body: String): Boolean {
        val directory = projectDirectory ?: return false
        if (path == null) return false
        return runCatching {
            NovelWorkspaceStore(directory).write(path, body)
            true
        }.getOrElse {
            _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed))
            false
        }
    }

    /** Toggle one injected-brief section; persisted per project and applied to every turn. */
    fun setInjectionFlags(flags: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags) {
        val directory = projectDirectory ?: return
        runCatching { NovelWorkspaceRestoreBoundary.write {
            val current = NovelWorkspaceProjectSettingsStore.load(directory)
            NovelWorkspaceProjectSettingsStore.save(
                current.copy(injection = flags),
                directory,
            )
            _state.value = _state.value.copy(injection = flags)
        } }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed)) }
    }

    /** Ghostwrite panel: model drafts the next chapter's plan into plan/this-chapter.md. */
    fun generateChapterPlan() {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return
        }
        val expectedPlanPath = NovelWorkspacePaths.branchPrefix(slug) + "/plan/this-chapter.md"
        val planBefore = NovelWorkspaceStore(directory).read(expectedPlanPath)
        val headBefore = NovelWorkspaceLedger.load(directory).heads[branch]
        _state.value = _state.value.copy(busy = true, errorMessage = null, streamingText = "", toolActivity = null)
        turnJob?.cancel()
        val turnEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        turnJob = viewModelScope.launch {
            try {
                interactiveTurns.draftPlan(
                    turnRequests(directory, branch, slug, settings, model, turnEpoch).chapterPlan(),
                    expectedPlanPath, planBefore, headBefore,
                ).collect { result ->
                    when (result) {
                        is NovelWorkspaceInteractiveTurns.PlanResult.Completed -> _state.value = _state.value.copy(
                            busy = false,
                            streamingText = "",
                            toolActivity = null,
                            errorMessage = if (result.producedPlan) null else text(R.string.error_title_operation),
                            planAutoTick = if (result.producedPlan) _state.value.planAutoTick + 1 else _state.value.planAutoTick,
                        )
                        is NovelWorkspaceInteractiveTurns.PlanResult.Failed -> _state.value = _state.value.copy(
                            messages = result.messages,
                            busy = false,
                            streamingText = "",
                            toolActivity = null,
                            errorMessage = result.message,
                        )
                    }
                }
            } finally {
                if (_state.value.busy && NovelWorkspaceRestoreBoundary.isCurrent(turnEpoch)) {
                    _state.value = _state.value.copy(busy = false, streamingText = "", toolActivity = null)
                }
            }
        }
    }

    private fun toolLabel(toolName: String): String = context.getString(R.string.novel_thinking)

    private fun localizedPromptText(chinese: String, english: String): String =
        if (context.appLocale().language.equals("zh", ignoreCase = true)) chinese else english

    /** Translate only the fixed owner guard; preserve dynamic exception details verbatim. */
    private fun localizedRuntimeError(error: Throwable, fallbackResId: Int): String =
        if (error.message == OWNER_GUARD_ERROR) {
            text(R.string.novel_batch_in_use)
        } else {
            error.message ?: text(fallbackResId)
        }

    private fun localizedJobReason(reason: String?): String? {
        if (reason == null || context.appLocale().language.equals("zh", ignoreCase = true)) {
            return reason
        }
        val prefix = NovelWorkspaceGhostwriteCoordinator.REASON_POLISH_POINTER_COMMIT_FAILED
        if (!reason.startsWith(prefix)) return reason
        val detail = reason.removePrefix(prefix).removePrefix("：").trim()
        return if (detail.isEmpty()) {
            text(R.string.error_title_operation)
        } else {
            "${text(R.string.error_title_operation)}: $detail"
        }
    }

    private fun text(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        context.getString(id, *args)

    private companion object {
        private const val OWNER_GUARD_ERROR =
            "当前分支仍被代笔批次占用，请先让批次完成或取消后再修改正文"
    }
}
