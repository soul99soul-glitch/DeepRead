package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.X
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Github
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Newspaper
import com.composables.icons.lucide.NotebookPen
import com.composables.icons.lucide.CirclePlay
import app.amber.agent.R
import app.amber.feature.webmount.core.WebMountStationState
import app.amber.feature.webmount.core.WebMountStatus
import app.amber.feature.webmount.usersites.AuthKind
import app.amber.feature.webmount.usersites.UserSite
import app.amber.feature.ui.components.ui.workspaceColors

@Composable
internal fun UserSiteCard(
    site: UserSite,
    stationState: WebMountStationState?,
    session: WebMountSettingsSession,
    busy: Boolean,
    onSignIn: (() -> Unit)?,
    onSignOut: (() -> Unit)?,
    onConnect: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    onEditOAuth: (() -> Unit)?,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val auth by produceState<WebMountSiteAuth?>(
        initialValue = null,
        site,
        session.cookieRevision,
        session.oauthRevision,
    ) {
        value = null
        value = session.siteAuth(site)
    }
    val workspace = workspaceColors()
    val loggedIn = auth?.loggedIn
    val hasToken = auth?.hasToken == true
    val tokenExpired = auth?.tokenExpired == true
    val hasCredentials = auth?.hasCredentials == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(9.dp),
                color = workspace.row,
                contentColor = workspace.muted,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(iconForIconKey(site.iconKey), contentDescription = null)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = site.displayName,
                    style = app.amber.feature.ui.theme.LocalAmberType.current.body,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = site.homepageUrl,
                    style = app.amber.feature.ui.theme.LocalAmberType.current.meta,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SiteStatusPill(
                authKind = site.authKind,
                stationState = stationState,
                loggedIn = loggedIn,
                hasToken = hasToken,
                tokenExpired = tokenExpired,
                hasCredentials = hasCredentials,
                authLoaded = auth != null,
            )
        }

        stationState?.message?.takeIf { it.isNotBlank() }?.let { msg ->
            ExperimentNote(text = msg)
        }
        ExperimentActionRow {
            if (onSignIn != null) {
                ExperimentActionButton(
                    text = stringResource(
                        if (loggedIn == true) R.string.setting_webmount_sign_in_again
                        else R.string.setting_webmount_sign_in
                    ),
                    primary = true,
                    enabled = !busy,
                    onClick = onSignIn,
                )
            }
            val clearState = onSignOut
            val showClearState = clearState != null && (
                loggedIn == true || hasToken || site.authKind == AuthKind.OAUTH
                )
            if (showClearState) {
                ExperimentActionButton(
                    text = stringResource(
                        if (loggedIn == true || hasToken) R.string.setting_webmount_sign_out
                        else R.string.setting_webmount_clear_session
                    ),
                    enabled = !busy,
                    onClick = clearState,
                )
            }
            if (onConnect != null && (!hasToken || tokenExpired)) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_oauth_connect),
                    primary = true,
                    enabled = !busy && auth?.hasCredentials == true,
                    onClick = onConnect,
                )
            }
            if (onDisconnect != null && hasToken) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_oauth_disconnect),
                    enabled = !busy,
                    onClick = onDisconnect,
                )
            }
            if (onEditOAuth != null) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_oauth_edit_credentials),
                    enabled = !busy,
                    onClick = onEditOAuth,
                )
            }
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_test),
                enabled = !busy && site.nativeAdapterId != null,
                onClick = onTest,
            )
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_delete_site),
                enabled = !busy,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun SiteStatusPill(
    authKind: AuthKind,
    stationState: WebMountStationState?,
    loggedIn: Boolean?,
    hasToken: Boolean,
    tokenExpired: Boolean,
    hasCredentials: Boolean,
    authLoaded: Boolean,
) {
    val workspace = workspaceColors()
    // Plan v2 review W-3 fix: operational status (DEGRADED / ERROR) takes
    // precedence over auth state. A signed-in user being rate-limited wants
    // to see "Rate-limited" — sign-in is a prerequisite, not the actionable
    // info. ANONYMOUS sites still surface "Public" because they have no
    // notion of signed-in/out, but their station status (if any) is still
    // surfaced via DEGRADED / ERROR branches below.
    val (color, labelRes) = when {
        stationState?.status == WebMountStatus.ERROR ->
            MaterialTheme.colorScheme.error to R.string.setting_webmount_pill_error
        stationState?.status == WebMountStatus.DEGRADED ->
            workspace.muted to R.string.setting_webmount_pill_rate_limited
        authKind == AuthKind.ANONYMOUS ->
            workspace.blue to R.string.setting_webmount_pill_public
        !authLoaded ->
            workspace.faint to R.string.setting_webmount_pill_unknown
        authKind == AuthKind.OAUTH && hasToken && !tokenExpired ->
            workspace.blue to R.string.setting_webmount_pill_connected
        authKind == AuthKind.OAUTH && hasCredentials ->
            workspace.muted to R.string.setting_webmount_pill_ready
        authKind == AuthKind.OAUTH ->
            workspace.faint to R.string.setting_webmount_pill_needs_setup
        loggedIn == true ->
            workspace.blue to R.string.setting_webmount_pill_signed_in
        loggedIn == false ->
            workspace.muted to R.string.setting_webmount_pill_signed_out
        else ->
            workspace.faint to R.string.setting_webmount_pill_unknown
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Text(
            text = stringResource(labelRes),
            style = app.amber.feature.ui.theme.LocalAmberType.current.meta,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ----------------------------------------------------------------------------
// Dialogs
// ----------------------------------------------------------------------------

/**
 * iCloud-style login card. 16dp-inset Box around a 90%-height Surface
 * with a header row (title + X close) and a WebView filling the rest.
 */
private fun iconForIconKey(iconKey: String?) = when (iconKey) {
    "hackernews" -> Lucide.Newspaper
    "reddit" -> Lucide.MessageSquare
    "github" -> Lucide.Github
    "bilibili" -> Lucide.CirclePlay
    "juejin" -> Lucide.Lightbulb
    "zhihu" -> Lucide.MessageCircleQuestion
    "feishu_docs" -> Lucide.NotebookPen
    else -> Lucide.Globe
}
