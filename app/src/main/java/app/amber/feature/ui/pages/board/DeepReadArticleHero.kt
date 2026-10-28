package app.amber.feature.ui.pages.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.displayHeroCaption
import app.amber.feature.board.hotlist.deepread.displayHeroImageUrl
import app.amber.feature.ui.theme.LocalAmberType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MagazineHero(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    val image = remember(output.heroImageUrl, output.heroImageConfidence, output.imageAssets) {
        output.displayHeroImageUrl()
    }
    val imageCaption = remember(output.heroCaption, output.imageAssets, image) {
        output.displayHeroCaption(image)
    }
    var imageFailed by remember(image) { mutableStateOf(false) }
    val showImage = image != null && !imageFailed
    val sourceLabel = remember(output.references, output.extendedReading) {
        deepReadSourceLabel(output)
    }

    if (showImage) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .height(414.dp),
            ) {
                AsyncImage(
                    model = image,
                    contentDescription = imageCaption ?: title,
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(342.dp)
                        .align(Alignment.TopCenter),
                )
                Canvas(Modifier.fillMaxSize()) {
                    val startY = 268.dp.toPx()
                    val endY = 346.dp.toPx()
                    val path = Path().apply {
                        moveTo(0f, startY)
                        lineTo(size.width, endY)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, palette.background)
                }
                SlantedHeroMeta(
                    type = output.topicType,
                    sourceLabel = sourceLabel,
                    palette = palette,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 30.dp, bottom = 38.dp, end = 30.dp),
                )
                imageCaption?.let { caption ->
                    Text(
                        caption,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(start = 30.dp, end = 30.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.labelSmall.withReadingFont(fontFamily),
                        color = palette.muted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            HeroTextBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp),
                title = title,
                output = output,
                palette = palette,
                fontFamily = fontFamily,
                showKicker = false,
            )
        }
    } else {
        TextOnlyHero(
            title = title,
            output = output,
            palette = palette,
            fontFamily = fontFamily,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextOnlyHero(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    val sourceCount = remember(output.references, output.extendedReading) {
        deepReadSourceCount(output)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.heroBackground),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        palette.heroSurface,
                        palette.heroBackground,
                        palette.heroBackground,
                    ),
                    start = Offset(size.width, 0f),
                    end = Offset(0f, size.height),
                ),
            )
            drawCircle(
                color = palette.accent.copy(alpha = if (palette.isDark) 0.16f else 0.12f),
                radius = 118.dp.toPx(),
                center = Offset(size.width * 0.88f, 42.dp.toPx()),
            )
            drawLine(
                color = palette.heroInk.copy(alpha = 0.10f),
                start = Offset(size.width * 0.1f, 0f),
                end = Offset(size.width, size.height * 0.58f),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 30.dp, top = 36.dp, end = 30.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "DEEP READ",
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 11.sp,
                        letterSpacing = 3.2.sp,
                        fontWeight = FontWeight.Light,
                    ),
                    color = palette.accent,
                )
                Text(
                    if (sourceCount > 0) "$sourceCount SOURCES" else "DEEP READ",
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 11.sp,
                        letterSpacing = 2.6.sp,
                        fontWeight = FontWeight.Light,
                    ),
                    color = palette.heroMuted,
                )
            }
            Spacer(
                Modifier
                    .width(126.dp)
                    .height(1.dp)
                    .background(palette.heroInk.copy(alpha = 0.24f)),
            )
            HeroTextBlock(
                modifier = Modifier.fillMaxWidth(),
                title = title,
                output = output,
                palette = palette,
                fontFamily = fontFamily,
                hero = true,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroTextBlock(
    modifier: Modifier,
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    showKicker: Boolean = true,
    hero: Boolean = false,
) {
    val ink = if (hero) palette.heroInk else palette.ink
    val muted = if (hero) palette.heroMuted else palette.muted
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (showKicker) {
            Text(
                output.topicType.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Light,
                    color = muted,
                ),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                color = ink,
            ).withReadingFont(fontFamily),
        )
        DeepReadMarkdownText(
            text = output.summary,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                lineHeight = 23.sp,
                color = ink,
            ).withReadingFont(fontFamily),
        )
        if (output.keyEntities.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                output.keyEntities.take(4).forEach { entity ->
                    EntityPill(entity, palette, hero = hero)
                }
            }
        }
    }
}

@Composable
private fun SlantedHeroMeta(
    type: String,
    sourceLabel: String,
    palette: MagazinePalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            type.uppercase(),
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 3.2.sp,
                fontWeight = FontWeight.Light,
                color = palette.accent,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sourceLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 2.4.sp,
                fontWeight = FontWeight.Light,
                color = palette.muted,
            ),
            maxLines = 1,
        )
    }
}

internal fun deepReadSourceCount(output: DeepReadOutput): Int =
    (output.references.ifEmpty { output.extendedReading })
        .map { it.source ?: it.url }
        .filter { it.isNotBlank() }
        .distinct()
        .size

private fun deepReadSourceLabel(output: DeepReadOutput): String =
    deepReadSourceCount(output)
        .takeIf { it > 0 }
        ?.let { "$it SOURCES" }
        ?: "DEEP READ"
