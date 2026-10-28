package app.amber.feature.ui.pages.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import coil3.compose.AsyncImage
import app.amber.feature.board.hotlist.deepread.CorePoint
import app.amber.feature.board.hotlist.deepread.DeepAnalysis
import app.amber.feature.board.hotlist.deepread.DeepReadDiagram
import app.amber.feature.board.hotlist.deepread.Perspective
import app.amber.feature.board.hotlist.deepread.ReadingLink
import app.amber.feature.board.hotlist.deepread.TimelineEvent
import app.amber.agent.R
import app.amber.feature.board.hotlist.deepread.verifiedImageUrls
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Lucide

private fun openHttpUrl(uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()
    if (scheme == "http" || scheme == "https") {
        runCatching { uriHandler.openUri(url) }
    }
}

@Composable
internal fun TimelineSection(
    events: List<TimelineEvent>,
    verifiedImageUrls: Set<String>,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionKicker(stringResource(R.string.deep_read_timeline), palette)
        Column(
            modifier = Modifier.drawBehind {
                val x = 9.dp.toPx()
                drawLine(
                    color = palette.line,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            events.forEachIndexed { index, event ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (index == events.lastIndex) 0.dp else 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    TimelineMarker(highlight = event.isHighlight, palette = palette)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            event.date,
                            style = LocalAmberType.current.meta.copy(fontSize = 11.sp),
                            color = palette.accent,
                        )
                        DeepReadMarkdownText(
                            text = event.event,
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp, color = palette.ink)
                                .withReadingFont(fontFamily),
                        )
                        event.imageUrl?.takeIf { it in verifiedImageUrls }?.let { image ->
                            EditorialImage(
                                imageUrl = image,
                                caption = event.imageCaption,
                                palette = palette,
                                fontFamily = fontFamily,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineMarker(highlight: Boolean, palette: MagazinePalette) {
    Box(Modifier.width(18.dp).heightIn(min = 54.dp), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = if (highlight) palette.accent else palette.line,
                radius = if (highlight) 5.dp.toPx() else 3.dp.toPx(),
                center = Offset(size.width / 2f, 7.dp.toPx()),
            )
        }
    }
}

@Composable
internal fun CorePointsSection(
    type: String,
    points: List<CorePoint>,
    verifiedImageUrls: Set<String>,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    val title = when (type) {
        "product" -> stringResource(R.string.deep_read_core_points_product)
        "person" -> stringResource(R.string.deep_read_core_points_person)
        "opinion" -> stringResource(R.string.deep_read_core_points_opinion)
        else -> stringResource(R.string.deep_read_core_points_default)
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SectionKicker(title, palette)
        points.forEachIndexed { index, point ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
                    .then(
                        if (index == 0) Modifier else Modifier.drawBehind {
                            drawLine(
                                color = palette.line,
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "%02d".format(index + 1),
                    style = LocalAmberType.current.meta.copy(fontSize = 11.sp, letterSpacing = 1.6.sp),
                    color = palette.accent,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeepReadMarkdownText(
                        text = point.point,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Light,
                            color = palette.ink,
                        )
                            .withReadingFont(fontFamily),
                    )
                    val pointSupporting = point.supporting
                    if (!pointSupporting.isNullOrBlank()) {
                        DeepReadMarkdownText(
                            text = pointSupporting,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp, color = palette.muted)
                                .withReadingFont(fontFamily),
                        )
                    }
                    point.imageUrl?.takeIf { it in verifiedImageUrls }?.let { image ->
                        EditorialImage(
                            imageUrl = image,
                            caption = point.imageCaption,
                            palette = palette,
                            fontFamily = fontFamily,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiagramSection(
    diagram: DeepReadDiagram,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    val label = when (diagram.type) {
        "causal_chain" -> stringResource(R.string.deep_read_diagram_causal_chain)
        "process_flow" -> stringResource(R.string.deep_read_diagram_process_flow)
        "stakeholder_map" -> stringResource(R.string.deep_read_diagram_stakeholder_map)
        "system_structure" -> stringResource(R.string.deep_read_diagram_system_structure)
        "comparison_matrix" -> stringResource(R.string.deep_read_diagram_comparison_matrix)
        else -> stringResource(R.string.deep_read_diagram_default)
    }
    val nodeLabels = diagram.nodes.associate { it.id to it.label }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionKicker(label, palette)
        Text(
            diagram.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light, color = palette.ink)
                .withReadingFont(fontFamily),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .border(1.dp, palette.line)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            diagram.nodes.take(8).forEachIndexed { index, node ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "%02d".format(index + 1),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
                        color = palette.accent,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            node.label,
                            style = MaterialTheme.typography.titleSmall.withReadingFont(fontFamily),
                            color = palette.ink,
                        )
                        node.group?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp)
                                    .withReadingFont(fontFamily),
                                color = palette.accent,
                            )
                        }
                        node.note?.takeIf { it.isNotBlank() }?.let {
                            DeepReadMarkdownText(
                                text = it,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp, color = palette.muted)
                                    .withReadingFont(fontFamily),
                            )
                        }
                    }
                }
            }
            diagram.edges.take(12).takeIf { it.isNotEmpty() }?.let { edges ->
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.muted.copy(alpha = 0.18f))
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    edges.forEach { edge ->
                        val from = nodeLabels[edge.from] ?: edge.from
                        val to = nodeLabels[edge.to] ?: edge.to
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "$from -> $to",
                                style = MaterialTheme.typography.labelMedium.withReadingFont(fontFamily),
                                color = palette.ink,
                            )
                            edge.label?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
                                        .withReadingFont(fontFamily),
                                    color = palette.muted,
                                )
                            }
                        }
                    }
                }
            }
        }
        diagram.caption?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.withReadingFont(fontFamily),
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun EditorialImage(
    imageUrl: String,
    caption: String?,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    if (failed) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = caption,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(palette.surface),
        )
        caption?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.withReadingFont(fontFamily),
                color = palette.muted,
            )
        }
    }
}

@Composable
internal fun AnalysisSection(analysis: DeepAnalysis, palette: MagazinePalette, fontFamily: FontFamily?) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionKicker(stringResource(R.string.deep_read_analysis), palette)
        val analysisCoreDispute = analysis.coreDispute
        if (!analysisCoreDispute.isNullOrBlank()) {
            DeepReadMarkdownText(
                text = analysisCoreDispute,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light, color = palette.ink)
                    .withReadingFont(fontFamily),
            )
        }
        analysis.quotes.take(2).forEach { quote ->
            QuoteBlock(text = quote.text, attribution = quote.attribution, palette = palette, fontFamily = fontFamily)
        }
        analysis.perspectives.takeIf { it.isNotEmpty() }?.let { perspectives ->
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                perspectives.forEach { PerspectiveRow(it, palette, fontFamily) }
            }
        }
        val analysisImplications = analysis.implications
        if (!analysisImplications.isNullOrBlank()) {
            DeepReadMarkdownText(
                text = analysisImplications,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = palette.ink)
                    .withReadingFont(fontFamily),
            )
        }
    }
}

@Composable
private fun PerspectiveRow(perspective: Perspective, palette: MagazinePalette, fontFamily: FontFamily?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            perspective.holder ?: stringResource(R.string.deep_read_viewpoint),
            style = MaterialTheme.typography.labelMedium,
            color = palette.accent,
        )
        DeepReadMarkdownText(
            text = perspective.viewpoint,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = palette.ink)
                .withReadingFont(fontFamily),
        )
    }
}

@Composable
private fun QuoteBlock(text: String, attribution: String?, palette: MagazinePalette, fontFamily: FontFamily?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .drawBehind {
                drawLine(
                    color = palette.accent,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(start = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("“", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light, color = palette.accent))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium.copy(
                    lineHeight = 28.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Light,
                    color = palette.ink,
                ).withReadingFont(fontFamily),
            )
            if (!attribution.isNullOrBlank()) {
                Text("— $attribution", style = MaterialTheme.typography.labelSmall.withReadingFont(fontFamily), color = palette.muted)
            }
        }
    }
}

@Composable
internal fun ReadingSection(links: List<ReadingLink>, palette: MagazinePalette, fontFamily: FontFamily?) {
    val uriHandler = LocalUriHandler.current
    val visibleLinks = links.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SectionKicker(stringResource(R.string.deep_read_reading), palette)
        Spacer(Modifier.height(14.dp))
        visibleLinks.forEachIndexed { index, link ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Neutral grey tap highlight (matches the magazine palette)
                    // instead of the theme's default blue ripple.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = palette.muted),
                    ) { openHttpUrl(uriHandler, link.url) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "%02d".format(index + 1),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.5.sp,
                        color = palette.accent,
                    ),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        link.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 20.sp,
                            color = palette.ink,
                        ).withReadingFont(fontFamily),
                    )
                    Text(link.source ?: link.url, style = MaterialTheme.typography.labelSmall, color = palette.muted)
                }
                Icon(
                    imageVector = Lucide.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = palette.muted,
                )
            }
            if (index != visibleLinks.lastIndex) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.line.copy(alpha = 0.55f)),
                )
            }
        }
    }
}
