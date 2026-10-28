package app.amber.feature.ui.pages.board

import android.content.Intent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MAX
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MIN
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.DeepReadHistoryItem
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadMarkdownExporter
import app.amber.feature.board.hotlist.deepread.DeepReadScheduler
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.isComplete
import app.amber.feature.board.hotlist.deepread.hasAnyReadySection
import app.amber.feature.board.hotlist.deepread.sectionFailureMessage
import app.amber.feature.board.hotlist.deepread.withInferredSectionStates
import app.amber.agent.R
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateArticle
import app.amber.feature.board.hotlist.deepread.template.DeepReadSynthesisWriter
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.theme.LocalDarkMode
import app.amber.feature.ui.context.LocalNavController
import app.amber.core.utils.appLocale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject

@Composable
fun DeepReadScreen(
    topicId: String,
    title: String,
    sourceUrl: String? = null,
    initialForceRegenerate: Boolean = false,
    fromHistory: Boolean = false,
) {

    val deepReadScheduler: DeepReadScheduler = koinInject()
    val settingsStore: SettingsAggregator = koinInject()
    val hotListRepository: HotListRepository = koinInject()
    val fontRepository: SlidesFontRepository = koinInject()
    val templateRepository: DeepReadTemplateRepository = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val customTemplates by templateRepository.observeTemplates().collectAsStateWithLifecycle()
    val invalidTemplateCount by templateRepository.observeInvalidTemplateCount().collectAsStateWithLifecycle()
    val confirmed = settings.agentRuntime.todayBoard.deepReadFirstUseConfirmed
    val board = settings.agentRuntime.todayBoard
    val deepReadFontScale = board.deepReadFontScale.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX)
    val appLocale = LocalView.current.context.appLocale()
    val readingFontFamily = rememberBoardReadingFontFamily(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
    )
    val templateFontCss = rememberDeepReadTemplateFontCss(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
        fontScale = deepReadFontScale,
    )

    val cacheEntryFlow = remember(topicId, fromHistory) {
        hotListRepository.observeDeepReadEntry(topicId, includeExpired = fromHistory)
    }
    // Latched on the first persisted snapshot: whether the entry already carried
    // readable content when we arrived. Entries that did can never earn a
    // milestone on this visit (iOS parity: wasFirstDraft = old content empty).
    var entryHadReady by remember(topicId) {
        mutableStateOf<Boolean?>(if (fromHistory || initialForceRegenerate) true else null)
    }
    val initialCacheState = remember(topicId, fromHistory) {
        val preview = hotListRepository.deepReadHistoryPreview(topicId).takeIf { fromHistory }
        DeepReadCacheState(
            loaded = !fromHistory || preview != null,
            entry = preview,
        )
    }
    val cacheState by produceState(
        initialValue = initialCacheState,
        key1 = cacheEntryFlow,
    ) {
        cacheEntryFlow.collect { entry ->
            if (entryHadReady == null) {
                entryHadReady = entry?.output?.withInferredSectionStates()?.hasAnyReadySection() == true
            }
            value = DeepReadCacheState(loaded = true, entry = entry)
        }
    }
    val cacheEntry = cacheState.entry
    val output = remember(cacheEntry) { cacheEntry?.output?.withInferredSectionStates() }
    val historyExpired = cacheEntry?.expired == true
    val historyLoading = fromHistory && !cacheState.loaded
    // The entry's stored template wins over the board default (iOS task.templateId);
    // synthesis entries keep `deepread_auto`/`deepread_*` while their structured
    // payload rides in structured_json.
    val entryTemplateId = DeepReadTemplateIds.normalize(cacheEntry?.templateId ?: board.deepReadTemplateId)
    val templateArticle = remember(cacheEntry?.structuredJson) {
        DeepReadTemplateArticle.decode(cacheEntry?.structuredJson)
    }
    val customTemplate = customTemplates.firstOrNull { it.id == entryTemplateId }
    val selectedCustomMissing = entryTemplateId.startsWith(DeepReadTemplateIds.CUSTOM_PREFIX) &&
        customTemplate == null
    val templateSelected = templateArticle != null ||
        entryTemplateId == DeepReadTemplateIds.EDITORIAL_SLANT || customTemplate != null
    // A synthesis entry whose stored payload can't decode would otherwise
    // render as a hollow "complete" page — surface it as a retryable error.
    val synthesisPayloadBroken = DeepReadTemplateIds.isSynthesis(entryTemplateId) &&
        !cacheEntry?.structuredJson.isNullOrBlank() && templateArticle == null
    var runError by remember(topicId) { mutableStateOf<String?>(null) }
    var initialForceConsumed by rememberSaveable(topicId, sourceUrl) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.deep_read_share_chooser)

    fun shareDeepRead(data: DeepReadOutput) {
        // Synthesis articles export their structured markdown (iOS markdown(article)).
        val markdown = templateArticle?.let { DeepReadSynthesisWriter.markdown(it) }
            ?: DeepReadMarkdownExporter.toMarkdown(title, data)
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, markdown)
                    },
                    shareChooserTitle,
                )
            )
        }
    }

    val listState = rememberLazyListState()
    val darkTheme = LocalDarkMode.current
    DeepReadImmersiveWindowEffect(darkTheme = darkTheme)

    val backgroundRunning by deepReadScheduler
        .observeRunning(topicId)
        .collectAsStateWithLifecycle(initialValue = false)
    val lifecycleRunning = backgroundRunning
    val anySectionRunning = lifecycleRunning &&
        output?.sectionStates.orEmpty().values.any { it.status == DeepReadSectionStatus.RUNNING }
    val phaseRunning = lifecycleRunning && output?.generationPhase?.isActiveDeepReadPhase() == true
    val generating = lifecycleRunning || anySectionRunning || phaseRunning
    val complete = output?.isComplete() == true

    // 付印 moment: a seal slams down when a run finishes while the reader watches
    // (iOS DeepReadMoment parity). Entries that arrived already-complete never
    // re-print; retries that kept old content stamp 付印, not a milestone.
    var pressSeal by remember(topicId) { mutableStateOf<DeepReadMoments.PressSeal?>(null) }
    var pressSealFire by remember(topicId) { mutableIntStateOf(0) }
    var sawGenerating by remember(topicId) { mutableStateOf(false) }
    var prevComplete by remember(topicId) { mutableStateOf<Boolean?>(null) }
    // Order matters: `sawGenerating` must latch before `complete` flips in the
    // same frame, so this effect is declared ahead of the seal effect.
    LaunchedEffect(generating) { if (generating) sawGenerating = true }
    LaunchedEffect(complete) {
        val prev = prevComplete
        prevComplete = complete
        if (prev == false && complete && sawGenerating) {
            val count = withContext(Dispatchers.IO) {
                runCatching { hotListRepository.countCompletedDeepReads() }.getOrDefault(0)
            }
            pressSeal = DeepReadMoments.pressSeal(
                finishedWithError = output.sectionFailureMessage() != null,
                wasFirstDraft = entryHadReady != true,
                completedCount = count,
            )
            if (pressSeal != null) pressSealFire += 1
        }
    }
    LaunchedEffect(pressSeal) {
        val seal = pressSeal ?: return@LaunchedEffect
        // 付印 fades fast; milestone seals linger a little longer — then both go,
        // or a tap dismisses them early.
        kotlinx.coroutines.delay(if (seal.milestone == null) 1400 else 2600)
        if (pressSeal == seal) pressSeal = null
    }

    fun runAll(force: Boolean = false) {
        if (!confirmed) return
        if (backgroundRunning && !force) return
        runError = null
        deepReadScheduler.run(topicId = topicId, title = title, sourceUrl = sourceUrl, force = force)
    }

    fun runOne(stage: DeepReadGenerationStage) {
        if (backgroundRunning) return
        runError = null
        deepReadScheduler.runSection(topicId = topicId, title = title, sourceUrl = sourceUrl, stage = stage)
    }

    LaunchedEffect(topicId, confirmed, initialForceRegenerate, fromHistory) {
        if (confirmed && !fromHistory) {
            val shouldForce = initialForceRegenerate && !initialForceConsumed
            if (shouldForce) initialForceConsumed = true
            runAll(force = shouldForce)
        }
    }

    LaunchedEffect(Unit) {
        templateRepository.reload()
    }

    val palette = magazinePalette()
    val sectionFailureMessage = output?.sectionFailureMessage()
    val firstFailedStage = output?.firstFailedStage()
    val failureRetryLabel = when {
        firstFailedStage != null -> stringResource(R.string.deep_read_retry_section)
        else -> stringResource(R.string.retry)
    }
    fun retryFirstFailure() {
        firstFailedStage?.let(::runOne) ?: runAll(force = false)
    }
    fun retryInitialFailure() {
        firstFailedStage?.let(::runOne) ?: runAll(force = true)
    }
    val initialDisplayError = (runError ?: sectionFailureMessage)
        ?.takeIf { !generating && (output == null || !output.hasAnyReadySection()) }

    DeepReadReaderContent(
        title = title,
        palette = palette,
        confirmed = confirmed,
        fromHistory = fromHistory,
        historyLoading = historyLoading,
        output = output,
        generating = generating,
        complete = complete,
        historyExpired = historyExpired,
        entryTemplateId = entryTemplateId,
        templateArticle = templateArticle,
        customTemplateHtml = customTemplate?.html,
        templateSelected = templateSelected,
        selectedCustomMissing = selectedCustomMissing,
        synthesisPayloadBroken = synthesisPayloadBroken,
        invalidTemplateCount = invalidTemplateCount,
        runError = runError,
        sectionFailureMessage = sectionFailureMessage,
        initialDisplayError = initialDisplayError,
        failureRetryLabel = failureRetryLabel,
        readingFontFamily = readingFontFamily,
        deepReadFontScale = deepReadFontScale,
        templateFontCss = templateFontCss,
        fontRepository = fontRepository,
        listState = listState,
        appLocale = appLocale,
        pressSeal = pressSeal,
        pressSealFire = pressSealFire,
        onDismissSeal = { pressSeal = null },
        onConfirm = {
            scope.launch {
                settingsStore.update { current ->
                    current.copy(
                        agentRuntime = current.agentRuntime.copy(
                            todayBoard = current.agentRuntime.todayBoard.copy(
                                deepReadFirstUseConfirmed = true,
                            )
                        )
                    )
                }
            }
        },
        onRunAll = ::runAll,
        onRunSection = ::runOne,
        onRetryFirstFailure = ::retryFirstFailure,
        onRetryInitialFailure = ::retryInitialFailure,
        onBack = { navController.popBackStack() },
        onShare = ::shareDeepRead,
    )
}

private data class DeepReadCacheState(
    val loaded: Boolean = false,
    val entry: DeepReadHistoryItem? = null,
)

private fun DeepReadOutput.firstFailedStage(): DeepReadGenerationStage? =
    DeepReadGenerationStage.entries.firstOrNull { stage ->
        sectionStates[stage]?.status == DeepReadSectionStatus.FAILED
    }

internal fun DeepReadGenerationPhase.isActiveDeepReadPhase(): Boolean =
    this == DeepReadGenerationPhase.COLLECTING ||
        this == DeepReadGenerationPhase.PLANNING ||
        this == DeepReadGenerationPhase.WRITING ||
        this == DeepReadGenerationPhase.VERIFYING
