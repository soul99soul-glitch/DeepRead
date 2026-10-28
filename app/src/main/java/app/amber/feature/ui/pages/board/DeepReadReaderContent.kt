package app.amber.feature.ui.pages.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionState
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.deepReadProgressSnapshot
import app.amber.feature.board.hotlist.deepread.hasAnyReadySection
import app.amber.feature.board.hotlist.deepread.sectionFailureMessage
import app.amber.feature.board.hotlist.deepread.withInferredSectionStates
import app.amber.agent.R
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateArticle
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.appLocale

@Composable
internal fun DeepReadReaderContent(
    title: String,
    palette: MagazinePalette,
    confirmed: Boolean,
    fromHistory: Boolean,
    historyLoading: Boolean,
    output: DeepReadOutput?,
    generating: Boolean,
    complete: Boolean,
    historyExpired: Boolean,
    entryTemplateId: String,
    templateArticle: DeepReadTemplateArticle?,
    customTemplateHtml: String?,
    templateSelected: Boolean,
    selectedCustomMissing: Boolean,
    synthesisPayloadBroken: Boolean,
    invalidTemplateCount: Int,
    runError: String?,
    sectionFailureMessage: String?,
    initialDisplayError: String?,
    failureRetryLabel: String,
    readingFontFamily: FontFamily?,
    deepReadFontScale: Float,
    templateFontCss: String,
    fontRepository: SlidesFontRepository,
    listState: androidx.compose.foundation.lazy.LazyListState,
    appLocale: java.util.Locale,
    pressSeal: DeepReadMoments.PressSeal?,
    pressSealFire: Int,
    onDismissSeal: () -> Unit,
    onConfirm: () -> Unit,
    onRunAll: (force: Boolean) -> Unit,
    onRunSection: (DeepReadGenerationStage) -> Unit,
    onRetryFirstFailure: () -> Unit,
    onRetryInitialFailure: () -> Unit,
    onBack: () -> Unit,
    onShare: (DeepReadOutput) -> Unit,
) {
    val amberTokens = LocalAmberTokens.current
    // Warm-paper grain; past 22:00 a desk-lamp glow fades in (iOS parity).
    val night = remember { DeepReadMoments.isNight() }
    Box(Modifier.fillMaxSize().amberCanvas().deepReadPaper(night = night, tokens = amberTokens)) {
        when {
            !confirmed -> DeepReadConfirmation(
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                palette = palette,
                fontFamily = readingFontFamily,
                fontScale = deepReadFontScale,
                onConfirm = onConfirm,
            )

            historyLoading -> Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())

            initialDisplayError != null -> DeepReadError(
                error = initialDisplayError,
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                onRetry = onRetryInitialFailure,
                retryLabel = failureRetryLabel,
            )

            fromHistory && output == null -> DeepReadError(
                error = stringResource(R.string.deep_read_history_unavailable),
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                onRetry = { onRunAll(true) },
                retryLabel = stringResource(R.string.regenerate),
            )

            fromHistory && output?.hasAnyReadySection() != true -> DeepReadError(
                error = stringResource(R.string.deep_read_history_empty_content),
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                onRetry = { onRunAll(true) },
                retryLabel = stringResource(R.string.regenerate),
            )

            synthesisPayloadBroken -> DeepReadError(
                error = stringResource(
                    R.string.deep_read_synthesis_template_failed,
                    DeepReadTemplateCatalog.name(entryTemplateId),
                ),
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                onRetry = { onRunAll(true) },
                retryLabel = stringResource(R.string.regenerate),
            )

            templateSelected && !selectedCustomMissing -> {
                val data = remember(output, generating) {
                    (output?.withInferredSectionStates() ?: DeepReadOutput()).asVisibleGeneratingOutput(generating)
                }
                Box(Modifier.fillMaxSize()) {
                    DeepReadTemplateArticleView(
                        title = title,
                        output = data,
                        palette = palette,
                        fontCss = templateFontCss,
                        templateArticle = templateArticle,
                        customTemplateHtml = customTemplateHtml,
                        fontRepository = fontRepository,
                        fontScale = deepReadFontScale,
                        fallback = {
                            DeepReadArticle(
                                title = title,
                                output = data,
                                palette = palette,
                                fontFamily = readingFontFamily,
                                fontScale = deepReadFontScale,
                                listState = listState,
                                onRetrySection = onRunSection,
                            )
                        },
                    )

                    val noticeModifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 64.dp, vertical = 10.dp)
                    when {
                        generating -> RunningStageNotice(
                            progress = data.deepReadProgressSnapshot(running = true, locale = appLocale),
                            modifier = noticeModifier,
                        )
                        runError != null && !complete -> DeepReadPartialErrorNotice(
                            error = runError.orEmpty(),
                            onRetry = { onRunAll(true) },
                            modifier = noticeModifier,
                        )
                        sectionFailureMessage != null && !complete -> DeepReadPartialErrorNotice(
                            error = sectionFailureMessage,
                            onRetry = onRetryFirstFailure,
                            retryLabel = failureRetryLabel,
                            modifier = noticeModifier,
                        )
                        historyExpired -> TemplateFallbackNotice(
                            message = stringResource(R.string.deep_read_history_expired_notice),
                            modifier = noticeModifier,
                        )
                    }
                    DeepReadBackButton(
                        onClick = { onBack() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 18.dp, top = 10.dp),
                    )
                    if (data.hasAnyReadySection()) {
                        DeepReadShareButton(
                            onClick = { onShare(data) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(end = 18.dp, top = 10.dp),
                        )
                    }
                }
            }

            // Nothing usable yet — only initial fetch banner before first section persists.
            output == null || !output.hasAnyReadySection() -> {
                val displayError = runError ?: sectionFailureMessage?.takeIf { !generating }
                if (displayError != null) {
                    DeepReadError(
                        error = displayError,
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        onRetry = onRetryInitialFailure,
                        retryLabel = failureRetryLabel,
                    )
                } else {
                    val data = remember(output, generating) {
                        (output?.withInferredSectionStates() ?: DeepReadOutput())
                            .asVisibleGeneratingOutput(generating = true)
                    }
                    Box(Modifier.fillMaxSize()) {
                        if (generating) {
                            // iOS DeepReadNewsroomView: while nothing is readable
                            // yet, the press room takes the whole page — stage
                            // rail, type tray, rotating desk notes.
                            DeepReadNewsroomView(
                                title = title,
                                stage = NewsroomStage.from(output?.generationPhase),
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            DeepReadArticle(
                                title = title,
                                output = data,
                                palette = palette,
                                fontFamily = readingFontFamily,
                                fontScale = deepReadFontScale,
                                listState = listState,
                                onRetrySection = onRunSection,
                            )
                            RunningStageNotice(
                                progress = data.deepReadProgressSnapshot(running = true, locale = appLocale),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(horizontal = 64.dp, vertical = 10.dp),
                            )
                        }
                        DeepReadBackButton(
                            onClick = { onBack() },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(start = 18.dp, top = 10.dp),
                        )
                    }
                }
            }

            else -> {
                val data = output
                Box(Modifier.fillMaxSize()) {
                    if (complete && templateSelected) {
                        DeepReadTemplateArticleView(
                            title = title,
                            output = data,
                            palette = palette,
                            fontCss = templateFontCss,
                            templateArticle = templateArticle,
                            customTemplateHtml = customTemplateHtml,
                            fontRepository = fontRepository,
                            fontScale = deepReadFontScale,
                            fallback = {
                                DeepReadArticle(
                                    title = title,
                                    output = data,
                                    palette = palette,
                                    fontFamily = readingFontFamily,
                                    fontScale = deepReadFontScale,
                                    listState = listState,
                                    onRetrySection = onRunSection,
                                )
                            },
                        )
                    } else {
                        DeepReadArticle(
                            title = title,
                            output = data,
                            palette = palette,
                            fontFamily = readingFontFamily,
                            fontScale = deepReadFontScale,
                            listState = listState,
                            onRetrySection = onRunSection,
                        )
                    }

                    val noticeModifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 64.dp, vertical = 10.dp)

                    when {
                        generating -> RunningStageNotice(
                            progress = data.deepReadProgressSnapshot(running = true, locale = appLocale),
                            modifier = noticeModifier,
                        )
                        runError != null && !complete -> DeepReadPartialErrorNotice(
                            error = runError.orEmpty(),
                            onRetry = { onRunAll(true) },
                            modifier = noticeModifier,
                        )
                        sectionFailureMessage != null && !complete -> DeepReadPartialErrorNotice(
                            error = sectionFailureMessage,
                            onRetry = onRetryFirstFailure,
                            retryLabel = failureRetryLabel,
                            modifier = noticeModifier,
                        )
                        selectedCustomMissing -> TemplateFallbackNotice(
                            message = stringResource(R.string.deep_read_template_unavailable),
                            modifier = noticeModifier,
                        )
                        invalidTemplateCount > 0 && !templateSelected -> TemplateFallbackNotice(
                            message = stringResource(R.string.deep_read_invalid_template_count, invalidTemplateCount),
                            modifier = noticeModifier,
                        )
                        historyExpired -> TemplateFallbackNotice(
                            message = stringResource(R.string.deep_read_history_expired_notice),
                            modifier = noticeModifier,
                        )
                    }
                    DeepReadBackButton(
                        onClick = { onBack() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 18.dp, top = 10.dp),
                    )
                    DeepReadShareButton(
                        onClick = { onShare(data) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(end = 18.dp, top = 10.dp),
                    )
                }
            }
        }

        pressSeal?.let { seal ->
            // Transient flourish: auto-dismisses in ~1.4–2.6 s, so it stays
            // invisible to accessibility services rather than announcing an
            // unlabeled full-screen button.
            Box(
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismissSeal() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DeepReadInkStamp(
                        text = seal.inscription,
                        size = 112.dp,
                        modifier = Modifier.deepReadStampSlam(pressSealFire),
                    )
                    Text(
                        text = seal.milestone?.let {
                            stringResource(R.string.deep_read_seal_caption_milestone, it)
                        } ?: stringResource(R.string.deep_read_seal_caption_done),
                        style = LocalAmberType.current.meta,
                        color = LocalAmberTokens.current.ink2,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
    }
}

private fun DeepReadOutput.asVisibleGeneratingOutput(generating: Boolean): DeepReadOutput {
    if (sectionStates.isNotEmpty() || !generating) return this
    val states = DeepReadGenerationStage.entries.associateWith { stage ->
        DeepReadSectionState(
            status = if (stage == DeepReadGenerationStage.OVERVIEW) {
                DeepReadSectionStatus.RUNNING
            } else {
                DeepReadSectionStatus.PENDING
            }
        )
    }
    return copy(
        generationPhase = generationPhase.takeIf { it.isActiveDeepReadPhase() }
            ?: DeepReadGenerationPhase.COLLECTING,
        sectionStates = states,
    )
}
