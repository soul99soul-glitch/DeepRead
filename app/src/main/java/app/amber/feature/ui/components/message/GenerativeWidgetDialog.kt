package app.amber.feature.ui.components.message

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.FileArchive
import app.amber.agent.R
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.ai.generative.GenerativeWidgetSegment
import app.amber.core.settings.GenerativeUiSetting
import app.amber.feature.ui.context.LocalToaster
import app.amber.core.utils.getActivity

@Composable
fun ExpandedGenerativeWidgetDialog(
    widget: GenerativeWidgetSegment.Widget,
    html: String,
    setting: GenerativeUiSetting,
    onDismissRequest: () -> Unit,
    initialFullscreen: Boolean = false,
    generationRevealRequested: Boolean = false,
    onGenerationRevealConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    // No more user-facing fullscreen toggle: slides always open fullscreen (initialFullscreen=true),
    // SVG/HTML widgets always stay in mid-card. The two layouts had inconsistent backgrounds and
    // button orders anyway — collapsing to one mode per renderer keeps things predictable.
    val isFullscreen = initialFullscreen
    val isGuizangRenderer = widget.renderer == GuizangHtmlDeckValidator.RENDERER
    val isRichRenderer = shouldRenderRichWidgetRenderer(widget.renderer, setting)
    val isSlidesRenderer = widget.renderer == "slides"
    val isPresentationRenderer = isSlidesRenderer || isGuizangRenderer

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = !isFullscreen,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        if (isFullscreen) {
            FullscreenDialogWindowEffect()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isFullscreen) MaterialTheme.colorScheme.surface
                    else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.42f)
                )
                .then(if (isFullscreen) Modifier else Modifier.padding(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = if (isFullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.9f)
                        .widthIn(max = 1080.dp)
                },
                shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = if (isFullscreen) 0.dp else 2.dp,
                shadowElevation = if (isFullscreen) 0.dp else 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(if (isFullscreen) 0.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isFullscreen) 0.dp else 10.dp),
                ) {
                    val headerCompact = isFullscreen && isPresentationRenderer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (headerCompact) 8.dp else if (isFullscreen) 12.dp else 0.dp,
                                vertical = if (headerCompact) 2.dp else if (isFullscreen) 6.dp else 0.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = widget.title?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.generative_widget_card_title),
                            style = if (headerCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        if (widget.specJson != null && isPresentationRenderer) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        if (isGuizangRenderer) {
                                            shareGuizangDeckArchive(
                                                context = context,
                                                title = widget.title,
                                                specJson = widget.specJson,
                                                coverHtml = html,
                                                toaster = toaster,
                                            )
                                        } else {
                                            shareSlidesDeckArchive(
                                                context = context,
                                                title = widget.title,
                                                specJson = widget.specJson,
                                                coverHtml = html,
                                                toaster = toaster,
                                            )
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    Lucide.FileArchive,
                                    contentDescription = stringResource(R.string.generative_widget_share_zip),
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val activity = context.getActivity()
                                val target = webView
                                if (activity == null || target == null) {
                                    toaster.show(
                                        context.getString(R.string.generative_widget_save_unavailable),
                                        type = ToastType.Error,
                                    )
                                    return@IconButton
                                }
                                val isPresentation = isPresentationRenderer && widget.specJson != null
                                if (isPresentation) {
                                    scope.launch {
                                        captureSlidesToJpg(
                                            webView = target,
                                            activity = activity,
                                            context = context,
                                            deckTitle = widget.title,
                                            toaster = toaster,
                                            settleDelayMs = if (isGuizangRenderer) 900L else 160L,
                                        )
                                    }
                                } else {
                                    scope.launch {
                                        captureWidgetToJpg(
                                            webView = target,
                                            activity = activity,
                                            context = context,
                                            toaster = toaster,
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Lucide.Download,
                                contentDescription = stringResource(R.string.generative_widget_save_jpg),
                            )
                        }
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                Lucide.X,
                                contentDescription = stringResource(R.string.generative_widget_close),
                            )
                        }
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        if (isRichRenderer && widget.specJson != null) {
                            if (isGuizangRenderer) {
                                GuizangHtmlDeckWebView(
                                    specJson = widget.specJson,
                                    generationRevealRequested = generationRevealRequested,
                                    onGenerationRevealConsumed = onGenerationRevealConsumed,
                                    modifier = Modifier.fillMaxSize(),
                                    onWebViewReady = { webView = it },
                                )
                            } else {
                                RichSandboxWebView(
                                    renderer = widget.renderer,
                                    specJson = widget.specJson,
                                    setting = setting,
                                    streaming = !widget.complete,
                                    modifier = if (widget.renderer == "slides") Modifier.fillMaxSize() else Modifier.align(Alignment.TopCenter),
                                    maxHeightDp = maxHeight.value.toInt().coerceAtLeast(240),
                                    onWebViewReady = { webView = it },
                                )
                            }
                        } else {
                            SafeGenerativeWidgetWebView(
                                html = html,
                                setting = setting,
                                streaming = !widget.complete,
                                widgetKey = "expanded-${widget.title.orEmpty()}-${widget.widgetCode.toStableWidgetKeyFragment()}",
                                minHeightDp = 240,
                                fallbackHeightDp = 520,
                                maxHeightOverrideDp = maxHeight.value.toInt().coerceAtLeast(240),
                                interactive = true,
                                fillContainer = true,
                                onWebViewReady = { webView = it },
                            )
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // This is a borrowed capture handle; its concrete runtime owns disposal.
            webView = null
        }
    }
}

@Composable
private fun FullscreenDialogWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) {
            onDispose { }
        } else {
            val previousStatusBarColor = window.statusBarColor
            val previousNavigationBarColor = window.navigationBarColor
            val previousSoftInputMode = window.attributes.softInputMode
            val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.statusBarColor = previousStatusBarColor
                window.navigationBarColor = previousNavigationBarColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && previousCutoutMode != null) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = previousCutoutMode
                        softInputMode = previousSoftInputMode
                    }
                } else {
                    window.attributes = window.attributes.apply {
                        softInputMode = previousSoftInputMode
                    }
                }
            }
        }
    }
}
