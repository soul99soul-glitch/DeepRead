package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import app.amber.agent.R
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.core.WebMountStatus
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.login.WebMountLoginController
import app.amber.feature.webmount.login.WebMountLoginStatus
import app.amber.feature.webmount.login.WebMountLoginTarget
import app.amber.feature.webmount.login.WebMountLoginWebViewState

@Composable
internal fun WebMountLoginDialog(
    target: WebMountLoginTarget,
    cookieProvider: WebMountCookieProvider,
    webMountManager: WebMountManager,
    onClearSession: (() -> Unit)?,
    onVerifiedLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webState by remember(target.id) {
        mutableStateOf(WebMountLoginWebViewState(currentUrl = target.startUrl))
    }
    var loginStatus by remember(target.id) {
        mutableStateOf<WebMountLoginStatus>(WebMountLoginStatus.Waiting)
    }
    var verifying by remember(target.id) { mutableStateOf(false) }
    val controller = remember(target.id, target.startUrl, target.urls) {
        WebMountLoginController(
            context = context,
            target = target,
            cookieProvider = cookieProvider,
            onStateChange = { webState = it },
            onLoginStatus = { loginStatus = it },
        )
    }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.destroy() }
    }

    fun acceptIfSignedIn(
        status: WebMountLoginStatus,
        onAccepted: (Boolean) -> Unit = {},
    ) {
        if (status !is WebMountLoginStatus.SignedIn) {
            loginStatus = status
            onAccepted(false)
            return
        }
        val stationId = target.stationId
        if (stationId == null) {
            onAccepted(true)
            onVerifiedLogin()
            onDismiss()
            return
        }
        verifying = true
        scope.launch {
            val state = runCatching { webMountManager.probe(stationId) }
                .getOrNull()
            verifying = false
            if (state == null || state.status == WebMountStatus.ERROR || state.status == WebMountStatus.LOGIN_REQUIRED) {
                loginStatus = WebMountLoginStatus.Failed(
                    state?.message ?: "Cookie exists, but ${target.displayName} probe did not confirm the session.",
                )
                onAccepted(false)
            } else {
                onAccepted(true)
                onVerifiedLogin()
                onDismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_webmount_login_dialog_title, target.displayName),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (onClearSession != null) {
                            TextButton(
                                onClick = {
                                    onClearSession()
                                    controller.clearSession()
                                },
                            ) {
                                Text(stringResource(R.string.setting_webmount_clear_session))
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Lucide.X,
                                contentDescription = stringResource(R.string.update_card_close),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    WebMountLoginNavigationBar(
                        state = webState,
                        verifying = verifying,
                        onBack = controller::goBack,
                        onForward = controller::goForward,
                        onReload = controller::reload,
                        onDone = {
                            acceptIfSignedIn(controller.manualCheck())
                        },
                    )
                    if (webState.progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { webState.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = loginStatusLabel(loginStatus, verifying),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                    webState.blockedNavigation?.let { blocked ->
                        Text(
                            text = stringResource(R.string.setting_webmount_app_open_blocked, blocked.take(80)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                    }
                    if (webState.renderProcessGone) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_webmount_login_page_crashed),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = controller::reload) {
                                Text(stringResource(R.string.setting_webmount_reload))
                            }
                        }
                    }
                    key(webState.webViewGeneration) {
                        AndroidView(
                            factory = { controller.webView },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                    CookieImportFallback(
                        target = target,
                        enabled = !verifying,
                        onImport = { cookies, onFinished ->
                            controller.importCookies(cookies) { status ->
                                acceptIfSignedIn(status, onAccepted = onFinished)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WebMountLoginNavigationBar(
    state: WebMountLoginWebViewState,
    verifying: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(enabled = state.canGoBack && !verifying, onClick = onBack) { Text("←") }
        TextButton(enabled = state.canGoForward && !verifying, onClick = onForward) { Text("→") }
        TextButton(enabled = !verifying, onClick = onReload) { Text("↻") }
        Text(
            text = state.currentUrl,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = !verifying, onClick = onDone) {
            Text(
                if (verifying) stringResource(R.string.setting_webmount_verifying)
                else stringResource(R.string.webmount_inline_login_done)
            )
        }
    }
}

@Composable
private fun loginStatusLabel(
    status: WebMountLoginStatus,
    verifying: Boolean,
): String {
    if (verifying) return stringResource(R.string.setting_webmount_status_verifying_site)
    return when (status) {
        WebMountLoginStatus.Waiting -> stringResource(R.string.setting_webmount_status_waiting)
        is WebMountLoginStatus.UrlMatched -> stringResource(R.string.setting_webmount_status_url_matched)
        is WebMountLoginStatus.SignedIn -> stringResource(
            R.string.setting_webmount_status_signed_in,
            status.cookieNames.joinToString(),
        )
        is WebMountLoginStatus.MissingCookies -> stringResource(
            R.string.setting_webmount_status_missing_cookies,
            status.missing.joinToString(),
        )
        is WebMountLoginStatus.Unknown -> status.reason
        is WebMountLoginStatus.Failed -> status.reason
    }
}
