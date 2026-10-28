package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.provider.ModelType
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.feature.ui.components.ai.TopModelMenu
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Experimental workspace-native novel screen. Chat drives the agent loop over the
 * markdown tree; the manuscript tab reads real chapter files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelMarkdownWorkspacePage(
    projectId: String,
    branchSlug: String? = null,
    jobId: String? = null,
    viewModel: NovelMarkdownWorkspaceViewModel = koinViewModel(
        key = "$projectId:$branchSlug:$jobId",
        parameters = { parametersOf(projectId, app.amber.feature.novelworkspace.NovelWorkspaceFocus(branchSlug, jobId)) },
    ),
) {
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopTurn() }
    }
    val observedState by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.key(observedState.restoreVersion) {
    val state = observedState.copy(busy = observedState.busy || observedState.loading)
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val appSettings = LocalSettings.current
    val navController = LocalNavController.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clearPageFocus = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    var tab by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val settingsTransition = updateTransition(showSettings, label = "novelSettings")
    val tabTransition = updateTransition(tab, label = "novelWorkspaceTab")
    val pageOffsetPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    var composerDraft by remember(projectId, state.branchSlug) { mutableStateOf("") }
    val chatListState = remember(projectId, state.branchSlug) { LazyListState() }
    var openChapterPath by remember(projectId, state.branchSlug) { mutableStateOf<String?>(null) }
    var readerOpen by remember(projectId, state.branchSlug) { mutableStateOf(false) }
    val readerTransition = updateTransition(readerOpen, label = "novelReader")
    val manuscriptListState = remember(projectId, state.branchSlug) { LazyListState() }
    val chapterPreviewScrollState = remember(projectId, state.branchSlug, openChapterPath) { ScrollState(0) }
    var readerRefreshVersion by remember(projectId, state.branchSlug) { mutableStateOf(0) }
    val readerPolishProgress = state.ghostwriteJob
        ?.takeIf { it.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish }
        ?.let { it.jobId to it.written }
    var lastReaderPolishProgress by remember(projectId, state.branchSlug) { mutableStateOf(readerPolishProgress) }
    LaunchedEffect(readerPolishProgress) {
        // Progress comes from committed ledger entries. The final refresh clears a completed job,
        // so that observed transition also refreshes the last chapter, even at the same text length.
        if (lastReaderPolishProgress != null && readerPolishProgress != lastReaderPolishProgress) {
            readerRefreshVersion++
        }
        lastReaderPolishProgress = readerPolishProgress
    }
    var historyChapter by remember(projectId, state.branchSlug) { mutableStateOf<NovelMarkdownChapterUi?>(null) }
    var discardChapter by remember(projectId, state.branchSlug) { mutableStateOf<NovelMarkdownChapterUi?>(null) }
    var catalogCategory by remember(projectId, state.branchSlug) { mutableStateOf(CatalogCategory.Characters) }
    val catalogListState = remember(projectId, state.branchSlug) { LazyListState() }
    var showConsistencyReport by remember(projectId, state.branchSlug) { mutableStateOf(false) }
    var showGhostwrite by remember(projectId, branchSlug, jobId) { mutableStateOf(false) }
    // 批量润色：入口在正文 tab 顶部动作区；进度呈现复用代笔批次的 job 状态槽。
    var showPolish by remember { mutableStateOf(false) }
    // 分支选择留在项目控制面板中，顶栏只保留当前分支摘要。
    var showBranchSheet by remember { mutableStateOf(false) }
    LaunchedEffect(jobId, state.ghostwriteJob?.jobId) {
        if (jobId != null && state.ghostwriteJob?.jobId == jobId) {
            if (state.ghostwriteJob?.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish) {
                showPolish = true
            } else {
                showGhostwrite = true
            }
        }
    }
    // Graphite TopModelMenu：与标准 chat 同款——顶栏下方卷帘下拉（替代 ModelSelector 弹层）。
    var modelMenuOpen by remember { mutableStateOf(false) }
    // 审稿模型菜单：复用同一个 TopModelMenu 组件，两个菜单互斥展开。
    var reviewMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(readerOpen) {
        if (readerOpen) {
            modelMenuOpen = false
            reviewMenuOpen = false
        }
    }
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    val reviewOverrideUuid = state.reviewModelId?.let {
        runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
    }

    // Keep the durable batch and workspace projection live on either tab, even when
    // the sheet is closed. A terminal refresh naturally stops this effect.
    LaunchedEffect(projectId, state.ghostwriteJob?.jobId, state.ghostwriteJob?.status) {
        if (state.ghostwriteJob?.status == "running") {
            while (true) {
                delay(2_500)
                viewModel.refreshGhostwrite()
            }
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    val writingOverrideUuid = state.writingModelId?.let {
        runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
    }
    val currentBranch = state.branches.firstOrNull { it.isCurrent }
    val branchLabel = currentBranch?.title ?: state.branchSlug.orEmpty()
    val writingOverrideModel = writingOverrideUuid?.let(appSettings::findModelById)
    val reviewOverrideModel = reviewOverrideUuid?.let(appSettings::findModelById)
    val effectiveWritingModel = writingOverrideModel ?: appSettings.getCurrentChatModel()
    val writingName = writingOverrideModel?.modelId ?: stringResource(R.string.novel_follow_global)
    val reviewName = reviewOverrideModel?.modelId ?: stringResource(R.string.novel_follow_writing)
    val effectiveWritingName = effectiveWritingModel?.modelId
        ?: stringResource(R.string.novel_ghostwrite_error_model_missing)
    val title = state.title.ifEmpty { stringResource(R.string.novel_workspace_title) }
    val modeLabel = stringResource(if (state.ghostwriteMode) R.string.novel_ghostwrite else R.string.novel_co_creation)
    BackHandler(enabled = showSettings || modelMenuOpen || reviewMenuOpen) {
        when {
            modelMenuOpen -> modelMenuOpen = false
            reviewMenuOpen -> reviewMenuOpen = false
            else -> {
                clearPageFocus()
                showSettings = false
            }
        }
    }

    readerTransition.AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            workspacePageMotion(forward = targetState, offsetPx = pageOffsetPx)
                .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
        },
    ) { readerVisible ->
    Box(Modifier.fillMaxSize().workspaceTransitionInput(active = readerVisible == readerOpen)) {
    val readerPath = openChapterPath
    if (readerVisible && readerPath != null) {
        NovelMarkdownChapterReader(
            chapters = state.chapters,
            chapterPath = readerPath,
            active = readerVisible == readerOpen && !showSettings && !modelMenuOpen && !reviewMenuOpen,
            busy = state.busy,
            writeLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed"),
            errorMessage = state.errorMessage,
            refreshVersion = readerRefreshVersion,
            scrollState = chapterPreviewScrollState,
            readBody = viewModel::readChapter,
            onBack = { clearPageFocus(); readerOpen = false },
            onOpenChapter = { clearPageFocus(); openChapterPath = it },
            onSave = { chapter, chapterTitle, body, onSaved ->
                viewModel.saveChapterEdit(chapter.path, chapterTitle, body, onSaved)
            },
            onRewrite = { viewModel.rewriteChapter(it.ordinal) },
            onHistory = { viewModel.clearError(); historyChapter = it },
            onDiscard = { viewModel.clearError(); discardChapter = it },
            onClearError = viewModel::clearError,
        )
    } else {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = workspace.canvas,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            settingsTransition.AnimatedContent(
                transitionSpec = {
                    workspacePageMotion(forward = targetState, offsetPx = pageOffsetPx / 2)
                        .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
                },
            ) { settingsVisible ->
                Box(Modifier.workspaceTransitionInput(active = settingsVisible == showSettings)) {
                    if (settingsVisible) {
                        NovelWorkspaceSettingsHeader(
                            title = stringResource(R.string.novel_project_settings),
                            onBack = {
                                clearPageFocus()
                                showSettings = false
                                modelMenuOpen = false
                                reviewMenuOpen = false
                            },
                        )
                    } else {
                        NovelWorkspaceHeader(
                            title = title,
                            subtitle = listOf(modeLabel, branchLabel, effectiveWritingName).filter { it.isNotBlank() }.joinToString(" · "),
                            controlsEnabled = state.exists && !state.loading,
                            onOpenControls = {
                                modelMenuOpen = false
                                reviewMenuOpen = false
                                showGhostwrite = true
                            },
                            onOpenSettings = {
                                clearPageFocus()
                                modelMenuOpen = false
                                reviewMenuOpen = false
                                showSettings = true
                            },
                            backButton = { WorkspaceBackButton() },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            settingsTransition.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    workspacePageMotion(forward = targetState, offsetPx = pageOffsetPx)
                        .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
                },
            ) { settingsVisible ->
            Box(Modifier.fillMaxSize().workspaceTransitionInput(active = settingsVisible == showSettings)) {
            when {
                settingsVisible -> NovelWorkspaceSettingsContent(
                    writingModel = writingName,
                    reviewModel = reviewName,
                    writingOverride = state.writingModelId != null,
                    reviewOverride = state.reviewModelId != null,
                    busy = state.busy || !state.exists,
                    errorMessage = state.errorMessage,
                    onClearError = viewModel::clearError,
                    onWritingModel = { modelMenuOpen = true; reviewMenuOpen = false },
                    onReviewModel = { reviewMenuOpen = true; modelMenuOpen = false },
                    onResetWritingModel = { viewModel.setWritingModel(null) },
                    onResetReviewModel = { viewModel.setReviewModel(null) },
                    onManageProjects = { navController.navigate(Screen.NovelProjects) {
                        popUpTo(Screen.NovelProjects) { inclusive = true }
                        launchSingleTop = true
                    } },
                    modifier = Modifier.padding(padding),
                )
                state.loading -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = workspace.ink)
                }
            }
            !state.exists -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        state.errorMessage ?: stringResource(R.string.novel_project_missing),
                        style = type.secondary,
                        color = workspace.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (state.unreadableJobFiles.isNotEmpty()) {
                        Text(
                            stringResource(R.string.parity_novel_unreadable_jobs, state.unreadableJobFiles.joinToString()),
                            color = workspace.red,
                            style = type.meta,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                        NovelWorkspaceOptions(
                            labels = listOf(
                                stringResource(R.string.novel_tab_creation),
                                stringResource(R.string.novel_tab_manuscript),
                                stringResource(R.string.novel_tab_settings),
                            ),
                            selected = tab,
                            onSelect = {
                                if (it != tab) {
                                    clearPageFocus()
                                    tab = it
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    if (tab != 0) {
                        state.errorMessage?.let { message ->
                            Text(
                                message,
                                style = type.meta,
                                color = workspace.red,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.clearError() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    NovelWorkspaceContinuityStatus(
                        checking = state.consistencyChecking,
                        checkedChapters = state.consistencyCheckedChapters,
                        totalChapters = state.consistencyTotalChapters,
                        reportAvailable = !state.consistencyReport.isNullOrBlank(),
                        onStop = viewModel::stopTurn,
                        onViewReport = { showConsistencyReport = true },
                    )
                    tabTransition.AnimatedContent(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            workspacePageMotion(forward = targetState >= initialState, offsetPx = pageOffsetPx)
                                .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
                        },
                            ) { visibleTab ->
                    Box(Modifier.fillMaxSize().workspaceTransitionInput(active = visibleTab == tab)) {
                    when (visibleTab) {
                        0 -> MarkdownWorkspaceChat(
                            state = NovelWorkspaceChatState(
                                branchSlug = state.branchSlug,
                                timeline = novelMarkdownTimeline(state),
                                chapters = state.chapters,
                                ghostwriteJob = state.ghostwriteJob,
                                busy = state.busy,
                                errorMessage = state.errorMessage,
                                plotStale = state.plotStale,
                                unresolvedFromOrdinal = state.unresolvedFromOrdinal,
                                streamingText = state.streamingText,
                                toolActivity = state.toolActivity,
                                composerMode = state.composerMode,
                            ),
                            draft = composerDraft,
                            onDraftChange = { composerDraft = it },
                            listState = chatListState,
                            readFileBody = viewModel::readFileBody,
                            readFileRaw = viewModel::readFileRaw,
                            onSaveFileEdit = viewModel::saveFileEdit,
                            onCollectDraft = { path, target, onCollected ->
                                viewModel.collectDraft(path, target, onCollected = onCollected)
                            },
                            onArchiveDecision = viewModel::archiveDecision,
                            onEditProposal = viewModel::editProposal,
                            onApproveProposal = viewModel::approve,
                            onRejectProposal = viewModel::reject,
                            onSetComposerMode = viewModel::setComposerMode,
                            onSend = viewModel::send,
                            onStop = viewModel::stopTurn,
                            onProposeCharacter = viewModel::proposeCharacter,
                            onRewriteLaterChapters = viewModel::rewriteLaterChapters,
                            onResolveUnresolved = viewModel::resolveUnresolved,
                            onClearError = viewModel::clearError,
                            onOpenGhostwrite = {
                                if (state.ghostwriteJob?.mode ==
                                    app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                                ) {
                                    showPolish = true
                                } else {
                                    showGhostwrite = true
                                }
                            },
                        )
                        1 -> MarkdownWorkspaceManuscript(
                            state = NovelWorkspaceManuscriptState(
                                branchSlug = state.branchSlug,
                                chapters = state.chapters,
                                ghostwriteJob = state.ghostwriteJob,
                                busy = state.busy,
                                errorMessage = state.errorMessage,
                                canUndo = state.canUndo,
                                consistencyChecking = state.consistencyChecking,
                            ),
                            loadDiscardedChapters = viewModel::loadDiscardedChapters,
                            readFileBody = viewModel::readFileBody,
                            onRestoreDiscardedChapter = viewModel::restoreDiscardedChapter,
                            onRewriteChapter = viewModel::rewriteChapter,
                            onUndoLast = viewModel::undoLast,
                            onRunConsistencyCheck = viewModel::runConsistencyCheck,
                            onClearError = viewModel::clearError,
                            onOpenPolish = { showPolish = true },
                            onOpenChapter = {
                                clearPageFocus()
                                openChapterPath = it
                                readerOpen = true
                            },
                            onHistory = { viewModel.clearError(); historyChapter = it },
                            onDiscard = { viewModel.clearError(); discardChapter = it },
                            listState = manuscriptListState,
                            onManageProjects = { navController.navigate(Screen.NovelProjects) {
                                popUpTo(Screen.NovelProjects) { inclusive = true }
                                launchSingleTop = true
                            } },
                        )
                        else -> MarkdownWorkspaceCatalog(
                            state = NovelWorkspaceCatalogState(
                                branchSlug = state.branchSlug,
                                catalog = state.catalog,
                                busy = state.busy,
                                loading = state.loading,
                                writeLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed"),
                                errorMessage = state.errorMessage,
                            ),
                            readFileRaw = viewModel::readFileRaw,
                            onCreateMaterial = viewModel::createMaterial,
                            onSaveMaterial = viewModel::saveMaterial,
                            onSaveOpenedFileEdit = viewModel::saveOpenedFileEdit,
                            onDeleteMaterial = viewModel::deleteMaterial,
                            onClearError = viewModel::clearError,
                            category = catalogCategory,
                            onSelectCategory = { catalogCategory = it },
                            listState = catalogListState,
                        )
                    }
                    }
                    }
                }
            }
        }
        }
        }

            // Graphite TopModelMenu：与 chat 页同款——从顶栏下方卷帘展开的服务商/模型
            // 手风琴（遮罩点击关闭），替代 ModelSelector 的底部弹层。
            val menuProviders = appSettings.providers.filter { p ->
                p.enabled && p.models.any { it.type == ModelType.CHAT }
            }
            // 高亮解析后的实际生效模型（项目覆盖 ?? 全局），选择任一即设为项目覆盖。
            @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
            val resolvedModelId: kotlin.uuid.Uuid? = effectiveWritingModel?.id
            TopModelMenu(
                open = modelMenuOpen,
                providers = menuProviders,
                modelType = ModelType.CHAT,
                currentProviderId = menuProviders.firstOrNull { p ->
                    p.models.any { it.id == resolvedModelId }
                }?.id,
                currentModelId = resolvedModelId,
                onSelect = { model ->
                    viewModel.setWritingModel(model.id.toString())
                    modelMenuOpen = false
                },
                onClose = { modelMenuOpen = false },
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            )
            // 审稿模型菜单：同一组件复用；选中即持久化为项目审稿覆盖（setReviewModel）。
            @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
            val reviewResolvedId: kotlin.uuid.Uuid? = reviewOverrideModel?.id ?: resolvedModelId
            TopModelMenu(
                open = reviewMenuOpen,
                providers = menuProviders,
                modelType = ModelType.CHAT,
                currentProviderId = menuProviders.firstOrNull { p ->
                    p.models.any { it.id == reviewResolvedId }
                }?.id,
                currentModelId = reviewResolvedId,
                onSelect = { model ->
                    viewModel.setReviewModel(model.id.toString())
                    reviewMenuOpen = false
                },
                onClose = { reviewMenuOpen = false },
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            )
        }
    }

    }
    }
    }

    val chapterWriteLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed")
    historyChapter?.let { selected ->
        MarkdownChapterHistorySheet(
            chapter = selected, busy = state.busy, writeLocked = chapterWriteLocked,
            errorMessage = state.errorMessage, loadHistory = viewModel::chapterHistory,
            readVersion = viewModel::readChapterVersion,
            onRestore = { hash, head, currentHash, onSaved ->
                viewModel.restoreChapterVersion(selected.path, hash, head, currentHash) {
                    readerRefreshVersion++
                    onSaved()
                }
            },
            onDismiss = { historyChapter = null },
        )
    }
    discardChapter?.let { selected ->
        MarkdownDiscardChapterDialog(
            chapter = selected, busy = state.busy, writeLocked = chapterWriteLocked,
            errorMessage = state.errorMessage, loadSnapshot = viewModel::loadDiscardedChapters,
            onDiscard = { head, tree, done ->
                viewModel.discardChapter(selected.path, head, tree) {
                    if (openChapterPath == selected.path) readerOpen = false
                    done()
                }
            },
            onDismiss = { discardChapter = null },
        )
    }

    if (showGhostwrite && state.exists && !state.loading) {
        NovelWorkspaceProjectControlsSheet(
            job = state.ghostwriteJob,
            ghostwriteMode = state.ghostwriteMode,
            branchLabel = branchLabel,
            plotStale = state.plotStale,
            unresolvedFromOrdinal = state.unresolvedFromOrdinal,
            onGhostwriteModeChange = viewModel::setGhostwriteMode,
            onBranch = { showGhostwrite = false; showBranchSheet = true },
            onOpenPolish = { showGhostwrite = false; showPolish = true },
            busy = state.busy,
            errorMessage = state.errorMessage,
            injection = state.injection,
            planAutoTick = state.planAutoTick,
            onStart = { viewModel.startGhostwriteBatch(it) },
            onPause = { viewModel.pauseGhostwriteBatch() },
            onResume = { viewModel.resumeGhostwriteBatch() },
            onRetryFailed = { viewModel.retryFailedGhostwriteBatch() },
            onCancel = { viewModel.cancelGhostwriteBatch() },
            onDismissFailure = { viewModel.dismissGhostwriteFailure() },
            onInjectionChange = { viewModel.setInjectionFlags(it) },
            onGeneratePlan = { viewModel.generateChapterPlan() },
            readChapterPlan = { viewModel.readChapterPlan() },
            saveChapterPlan = { viewModel.saveChapterPlan(it) },
            readUpcomingArc = { viewModel.readUpcomingArc() },
            saveUpcomingArc = { viewModel.saveUpcomingArc(it) },
            readWritingPreference = { viewModel.readWritingPreference() },
            saveWritingPreference = { body, onSaved ->
                viewModel.saveWritingPreference(body, onSaved)
            },
            briefPreview = { viewModel.briefPreview() },
            onDismiss = { showGhostwrite = false },
        )
    }

    if (showBranchSheet) {
        BranchSheet(
            branches = state.branches,
            branchLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed"),
            busy = state.busy,
            errorMessage = state.errorMessage,
            onSwitch = {
                showBranchSheet = false
                viewModel.switchBranch(it)
            },
            onCreate = { viewModel.createBranch(it) },
            onDismiss = { showBranchSheet = false },
        )
    }

    if (showPolish) {
        PolishBatchSheet(
            job = state.ghostwriteJob,
            busy = state.busy,
            latestOrdinal = state.chapters.maxOfOrNull { it.ordinal } ?: 0,
            errorMessage = state.errorMessage,
            onStart = { from, to ->
                if (viewModel.startPolish(from, to) && from == to) {
                    showPolish = false
                    tab = 0
                }
            },
            onPause = { viewModel.pauseGhostwriteBatch() },
            onResume = { viewModel.resumeGhostwriteBatch() },
            onRetryFailed = { viewModel.retryFailedGhostwriteBatch() },
            onCancel = { viewModel.cancelGhostwriteBatch() },
            onDismissFailure = { viewModel.dismissGhostwriteFailure() },
            onDismiss = { showPolish = false },
        )
    }

    if (showConsistencyReport && !state.consistencyChecking) state.consistencyReport?.let { report ->
        NovelWorkspaceContinuityReportDialog(report, onDismiss = { showConsistencyReport = false })
    }
    }
}
