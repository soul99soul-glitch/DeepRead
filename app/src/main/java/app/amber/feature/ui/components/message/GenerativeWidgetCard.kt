package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.ai.generative.GenerativeWidgetSanitizeStatus
import app.amber.core.ai.generative.GenerativeWidgetSanitizer
import app.amber.core.ai.generative.GenerativeWidgetSegment
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
fun GenerativeWidgetCard(
    widget: GenerativeWidgetSegment.Widget,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = !widget.complete,
    onAction: (String) -> Unit = {},
) {

    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current.agentRuntime.generativeUi
    val fontRepository = koinInject<SlidesFontRepository>()
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val sanitized = remember(widget.widgetCode, settings) {
        GenerativeWidgetSanitizer.sanitize(widget.widgetCode, settings)
    }
    val slidesFontHint = remember(widget.renderer, widget.specJson, settings, fontStates) {
        widget.specJson
            ?.takeIf { widget.renderer == "slides" }
            ?.let { fontRepository.resolveSlidesFontHint(it, settings) }
    }
    val widgetKey = remember(widget.title, widget.widgetCode.take(120)) {
        listOfNotNull(
            widget.title?.takeIf { it.isNotBlank() },
            widget.widgetCode.toStableWidgetKeyFragment(),
        )
            .joinToString("|")
            .ifBlank { "widget-${widget.widgetCode.hashCode()}" }
    }
    val revealIdentity = remember(widget.title, widget.renderer) {
        "${widget.renderer.ifBlank { "widget" }}:${widget.title.orEmpty()}"
    }
    val fullHtmlRevealState = remember(revealIdentity) { UIGenerationRevealState("full-html:$revealIdentity") }
    var fullHtmlRevealPending by remember(revealIdentity) { mutableStateOf(false) }
    LaunchedEffect(isStreaming, widget.complete, widget.renderer) {
        if (widget.renderer == GuizangHtmlDeckValidator.RENDERER &&
            fullHtmlRevealState.update(isStreaming = isStreaming || !widget.complete, complete = widget.complete)
        ) {
            fullHtmlRevealPending = true
        }
    }
    var showExpanded by remember { mutableStateOf(false) }
    val canOpenExpanded = sanitized.status == GenerativeWidgetSanitizeStatus.READY &&
        (widget.complete || sanitized.html.length >= WIDGET_MIN_PARTIAL_RENDER_CHARS)

    if (showExpanded && canOpenExpanded) {
        ExpandedGenerativeWidgetDialog(
            widget = widget,
            html = sanitized.html,
            setting = settings,
            onDismissRequest = { showExpanded = false },
            initialFullscreen = widget.renderer == "slides" || widget.renderer == GuizangHtmlDeckValidator.RENDERER,
            generationRevealRequested = fullHtmlRevealPending,
            onGenerationRevealConsumed = { fullHtmlRevealPending = false },
        )
    }

    // 画板风 widget 卡片: 底色用 chatTheme.widgetCanvas (比 bg 略深, 暗色略浅), 默认无描边;
    // 若主题显式给了 widgetCanvasBorder 才画 1dp 描边.
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val widgetSurfaceColor = chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: MaterialTheme.colorScheme.surface
    val widgetBorder = chatTheme.widgetCanvasBorder
        .takeIf { it.alpha > 0f }
        ?.let { BorderStroke(1.dp, it) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenExpanded) { showExpanded = true },
        shape = RoundedCornerShape(10.dp),
        color = widgetSurfaceColor,
        contentColor = chatTheme.ink,
        border = widgetBorder,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            widget.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = chatTheme.inkSoft,
                )
            }
            slidesFontHint?.let { hint ->
                Surface(
                    onClick = {
                        toaster.show(
                            context.getString(
                                R.string.generative_widget_system_font_hint,
                                hint.pack?.displayName ?: hint.requestedPackId,
                            ),
                        )
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = chatTheme.accentSoft,
                    contentColor = chatTheme.accent,
                ) {
                    Text(
                        text = stringResource(R.string.generative_widget_system_font_download),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            when (sanitized.status) {
                GenerativeWidgetSanitizeStatus.READY -> {
                    if (widget.complete || sanitized.html.length >= WIDGET_MIN_PARTIAL_RENDER_CHARS) {
                        SafeGenerativeWidgetWebView(
                            html = sanitized.html,
                            setting = settings,
                            streaming = !widget.complete,
                            liveStreaming = isStreaming && widget.renderer != GuizangHtmlDeckValidator.RENDERER,
                            complete = widget.complete,
                            widgetKey = widgetKey,
                            onTap = { showExpanded = true },
                        )
                    } else {
                        GenerativeWidgetLoading()
                    }
                }

                GenerativeWidgetSanitizeStatus.EMPTY -> {
                    Text(
                        text = stringResource(R.string.generative_widget_empty_ignored),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                GenerativeWidgetSanitizeStatus.TOO_LARGE -> {
                    Text(
                        text = stringResource(R.string.generative_widget_too_large_code_block),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MarkdownBlock(content = "```html\n${widget.widgetCode.take(4_000)}\n```")
                }

                GenerativeWidgetSanitizeStatus.UNSAFE -> {
                    Text(
                        text = stringResource(R.string.generative_widget_unsafe_code_block),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    MarkdownBlock(content = "```html\n${widget.widgetCode.take(4_000)}\n```")
                }
            }
            if (settings.enableActions && widget.complete && widget.actions.isNotEmpty()) {
                GenerativeWidgetActions(
                    widget = widget,
                    onAction = onAction,
                )
            }
            if (widget.complete && widget.renderer in setOf("vchart", "slides", GuizangHtmlDeckValidator.RENDERER) &&
                widget.specJson != null && canOpenExpanded
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        onClick = { showExpanded = true },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            text = when (widget.renderer) {
                                "slides" -> stringResource(R.string.generative_widget_open_presentation)
                                GuizangHtmlDeckValidator.RENDERER -> stringResource(R.string.generative_widget_open_live_presentation)
                                else -> stringResource(R.string.generative_widget_open_interactive_chart)
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (widget.renderer == "slides" || widget.renderer == GuizangHtmlDeckValidator.RENDERER) {
                        Surface(
                            onClick = {
                                scope.launch {
                                    if (widget.renderer == GuizangHtmlDeckValidator.RENDERER) {
                                        shareGuizangDeckArchive(
                                            context = context,
                                            title = widget.title,
                                            specJson = widget.specJson,
                                            coverHtml = sanitized.html,
                                            toaster = toaster,
                                        )
                                    } else {
                                        shareSlidesDeckArchive(
                                            context = context,
                                            title = widget.title,
                                            specJson = widget.specJson,
                                            coverHtml = sanitized.html,
                                            toaster = toaster,
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.generative_widget_share_zip),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            if (!widget.complete && sanitized.status == GenerativeWidgetSanitizeStatus.READY) {
                Text(
                    text = stringResource(R.string.generative_widget_generating),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

internal fun shouldRenderRichWidgetRenderer(
    renderer: String,
    setting: GenerativeUiSetting,
): Boolean = when (renderer) {
    "slides", GuizangHtmlDeckValidator.RENDERER -> true
    "vchart" -> setting.enableInteractiveCharts
    else -> false
}

@Composable
private fun GenerativeWidgetActions(
    widget: GenerativeWidgetSegment.Widget,
    onAction: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        widget.actions.take(3).forEach { action ->
            AssistChip(
                onClick = { onAction(action.toUserPrompt(widget.title)) },
                label = {
                    Text(
                        text = action.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
fun GenerativeWidgetLoading(modifier: Modifier = Modifier) {
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: chatTheme.surface,
        contentColor = chatTheme.inkSoft,
        border = BorderStroke(1.dp, chatTheme.surfaceEdge),
    ) {
        Text(
            text = stringResource(R.string.generative_widget_generating),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 18.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
