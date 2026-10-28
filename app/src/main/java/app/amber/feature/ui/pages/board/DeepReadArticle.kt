package app.amber.feature.ui.pages.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.board.hotlist.deepread.DeepAnalysis
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.ReadingLink
import app.amber.feature.board.hotlist.deepread.statusOf
import app.amber.feature.board.hotlist.deepread.errorOf
import app.amber.agent.R
import app.amber.feature.board.hotlist.deepread.verifiedImageUrls
import app.amber.feature.ui.components.message.LocalSearchSources
import app.amber.feature.ui.components.message.LocalStripUnverifiedLinks
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeepReadArticle(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    fontScale: Float,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetrySection: (DeepReadGenerationStage) -> Unit,
) {
    val verifiedImageUrls = remember(output.imageAssets) { output.verifiedImageUrls() }
    val sourceRegistry = remember(output.references, output.extendedReading) {
        buildDeepReadSourceRegistry(output)
    }
    CompositionLocalProvider(
        LocalSearchSources provides sourceRegistry,
        // Only strip unverified links when we actually have prefetched sources to match
        // against; an empty registry would otherwise erase every link in the article.
        LocalStripUnverifiedLinks provides sourceRegistry.isNotEmpty,
    ) {
        DeepReadScaledText(fontScale) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.background)
                    .deepReadPaper(
                        night = remember { DeepReadMoments.isNight() },
                        tokens = LocalAmberTokens.current,
                    )
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(
                    // The text hero starts another 36dp below this inset. Keep its
                    // masthead 8dp below the floating actions (10dp top + 38dp tall).
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 20.dp,
                    bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                item {
                    Column {
                        MagazineHeroFrame(
                            title = title,
                            output = output,
                            status = output.statusOf(DeepReadGenerationStage.OVERVIEW),
                            errorMessage = output.errorOf(DeepReadGenerationStage.OVERVIEW),
                            onRetry = { onRetrySection(DeepReadGenerationStage.OVERVIEW) },
                            palette = palette,
                            fontFamily = fontFamily,
                        )
                        DeepReadArticleMeta(output = output, palette = palette)
                    }
                }

                item {
                    ArticleInset {
                        NarrativeFrame(
                            output = output,
                            verifiedImageUrls = verifiedImageUrls,
                            status = output.statusOf(DeepReadGenerationStage.NARRATIVE),
                            errorMessage = output.errorOf(DeepReadGenerationStage.NARRATIVE),
                            onRetry = { onRetrySection(DeepReadGenerationStage.NARRATIVE) },
                            palette = palette,
                            fontFamily = fontFamily,
                        )
                    }
                }

                output.diagram?.takeIf { it.nodes.size >= 2 }?.let { diagram ->
                    item {
                        ArticleInset {
                            DiagramSection(
                                diagram = diagram,
                                palette = palette,
                                fontFamily = fontFamily,
                            )
                        }
                    }
                }

                item {
                    ArticleInset {
                        AnalysisFrame(
                            analysis = output.analysis,
                            status = output.statusOf(DeepReadGenerationStage.ANALYSIS),
                            errorMessage = output.errorOf(DeepReadGenerationStage.ANALYSIS),
                            onRetry = { onRetrySection(DeepReadGenerationStage.ANALYSIS) },
                            palette = palette,
                            fontFamily = fontFamily,
                        )
                    }
                }

                item {
                    ArticleInset {
                        ReadingFrame(
                            links = output.extendedReading,
                            status = output.statusOf(DeepReadGenerationStage.EXTENDED_READING),
                            errorMessage = output.errorOf(DeepReadGenerationStage.EXTENDED_READING),
                            onRetry = { onRetrySection(DeepReadGenerationStage.EXTENDED_READING) },
                            palette = palette,
                            fontFamily = fontFamily,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MagazineHeroFrame(
    title: String,
    output: DeepReadOutput,
    status: DeepReadSectionStatus,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    when (status) {
        DeepReadSectionStatus.READY -> MagazineHero(
            title = title,
            output = output,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.RUNNING -> HeroSkeleton(
            title = title,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.FAILED -> SectionErrorCard(
            label = stringResource(R.string.deep_read_overview_failed),
            errorMessage = errorMessage,
            onRetry = onRetry,
            palette = palette,
            fontFamily = fontFamily,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 36.dp),
        )
        DeepReadSectionStatus.PENDING -> SectionPlaceholder(
            label = stringResource(R.string.deep_read_overview_pending),
            palette = palette,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 36.dp),
        )
    }
}

@Composable
private fun NarrativeFrame(
    output: DeepReadOutput,
    verifiedImageUrls: Set<String>,
    status: DeepReadSectionStatus,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    when (status) {
        DeepReadSectionStatus.READY -> Column(verticalArrangement = Arrangement.spacedBy(40.dp)) {
            output.timeline?.takeIf { it.isNotEmpty() }?.let { timeline ->
                TimelineSection(timeline, verifiedImageUrls, palette, fontFamily)
            }
            output.corePoints?.takeIf { it.isNotEmpty() }?.let { points ->
                CorePointsSection(
                    type = output.topicType,
                    points = points,
                    verifiedImageUrls = verifiedImageUrls,
                    palette = palette,
                    fontFamily = fontFamily,
                )
            }
        }
        DeepReadSectionStatus.RUNNING -> SectionSkeleton(
            label = stringResource(R.string.deep_read_narrative),
            palette = palette,
            fontFamily = fontFamily,
            lineCount = 4,
        )
        DeepReadSectionStatus.FAILED -> SectionErrorCard(
            label = stringResource(R.string.deep_read_narrative_failed),
            errorMessage = errorMessage,
            onRetry = onRetry,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.PENDING -> SectionPlaceholder(
            label = stringResource(R.string.deep_read_narrative_pending),
            palette = palette,
        )
    }
}

@Composable
private fun AnalysisFrame(
    analysis: DeepAnalysis,
    status: DeepReadSectionStatus,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    when (status) {
        DeepReadSectionStatus.READY -> AnalysisSection(analysis, palette, fontFamily)
        DeepReadSectionStatus.RUNNING -> SectionSkeleton(
            label = stringResource(R.string.deep_read_analysis),
            palette = palette,
            fontFamily = fontFamily,
            lineCount = 3,
        )
        DeepReadSectionStatus.FAILED -> SectionErrorCard(
            label = stringResource(R.string.deep_read_analysis_failed),
            errorMessage = errorMessage,
            onRetry = onRetry,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.PENDING -> SectionPlaceholder(
            label = stringResource(R.string.deep_read_analysis_pending),
            palette = palette,
        )
    }
}

@Composable
private fun ReadingFrame(
    links: List<ReadingLink>,
    status: DeepReadSectionStatus,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    when (status) {
        DeepReadSectionStatus.READY -> if (links.isNotEmpty()) {
            ReadingSection(links, palette, fontFamily)
        } else {
            SectionPlaceholder(label = stringResource(R.string.deep_read_reading_empty), palette = palette)
        }
        DeepReadSectionStatus.RUNNING -> SectionSkeleton(
            label = stringResource(R.string.deep_read_reading),
            palette = palette,
            fontFamily = fontFamily,
            lineCount = 3,
        )
        DeepReadSectionStatus.FAILED -> SectionErrorCard(
            label = stringResource(R.string.deep_read_reading_failed),
            errorMessage = errorMessage,
            onRetry = onRetry,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.PENDING -> SectionPlaceholder(
            label = stringResource(R.string.deep_read_reading_pending),
            palette = palette,
        )
    }
}

@Composable
private fun HeroSkeleton(
    title: String,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, top = 36.dp, end = 30.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "DEEP READ",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 3.2.sp,
                fontWeight = FontWeight.Light,
                color = palette.accent,
            ),
        )
        Spacer(
            Modifier
                .width(126.dp)
                .height(1.dp)
                .background(palette.line),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
            ).withReadingFont(fontFamily),
        )
        SkeletonLine(palette = palette, widthFraction = 1f)
        SkeletonLine(palette = palette, widthFraction = 0.92f)
        SkeletonLine(palette = palette, widthFraction = 0.75f)
        Text(
            stringResource(
                R.string.deep_read_writing,
                stringResource(R.string.deep_read_overview),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun SectionSkeleton(
    label: String,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    lineCount: Int = 3,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionKicker(label, palette)
        repeat(lineCount) { index ->
            val fraction = when (index) {
                lineCount - 1 -> 0.6f
                else -> 0.94f - index * 0.05f
            }
            SkeletonLine(palette = palette, widthFraction = fraction)
        }
        Text(
            stringResource(R.string.deep_read_writing, label),
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun SkeletonLine(palette: MagazinePalette, widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(14.dp)
            .background(palette.surface, RoundedCornerShape(7.dp)),
    )
}

@Composable
private fun SectionErrorCard(
    label: String,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.accent.copy(alpha = 0.42f)),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                label,
                style = LocalAmberType.current.sessionTitle.withReadingFont(fontFamily),
                color = palette.ink,
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    errorMessage,
                    style = LocalAmberType.current.secondary.withReadingFont(fontFamily),
                    color = palette.muted,
                )
            }
            androidx.compose.material3.TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.deep_read_retry_section), color = tokens.accent)
            }
        }
    }
}

@Composable
private fun SectionPlaceholder(
    label: String,
    palette: MagazinePalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = palette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.line),
        tonalElevation = 0.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = palette.muted,
        )
    }
}

@Composable
private fun ArticleInset(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp),
    ) {
        content()
    }
}

@Composable
private fun DeepReadArticleMeta(
    output: DeepReadOutput,
    palette: MagazinePalette,
) {
    val sourceCount = remember(output.references, output.extendedReading) {
        deepReadSourceCount(output)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, top = 26.dp, end = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            output.topicType.uppercase(),
            style = LocalAmberType.current.meta.copy(fontSize = 11.sp),
            color = palette.muted,
        )
        Box(Modifier.size(3.dp).background(palette.line, CircleShape))
        Text(
            if (sourceCount > 0) "$sourceCount SOURCES" else "DEEP READ",
            style = LocalAmberType.current.meta.copy(fontSize = 11.sp),
            color = palette.muted,
        )
    }
}
