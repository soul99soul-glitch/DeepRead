package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Globe
import app.amber.agent.R
import app.amber.core.utils.plus
import app.amber.agent.Screen
import app.amber.feature.ui.components.webmount.WebMountTaskCard
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.oauth.WebMountOAuthClient
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import app.amber.feature.webmount.usersites.AuthKind
import app.amber.feature.webmount.usersites.UserSiteRegistry
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.feature.ui.context.LocalToaster
import org.koin.compose.koinInject

/**
 * Phase 2 Plan v2 — unified WebMount Stations setting page.
 *
 * One concept on screen: **网站**. The old triple of "OAuth providers /
 * Site Profiles / Available stations" has been collapsed into a single
 * editable list sourced from [UserSiteRegistry]. Profile JSON import is
 * gone (no user ever needs it); OAuth credentials are edited via a
 * dialog launched from the relevant site row.
 *
 * Hero toggle controls whether the agent has any WebMount capability at
 * all; per-site rows handle login / sign-out / delete.
 */
@Composable
fun SettingExperimentalWebMountPage() {
    val webMountManager: WebMountManager = koinInject()
    val oauthClient: WebMountOAuthClient = koinInject()
    val oauthStore: WebMountOAuthTokenStore = koinInject()
    val userSiteRegistry: UserSiteRegistry = koinInject()
    val cookieProvider: WebMountCookieProvider = koinInject()
    val profileRegistry: app.amber.feature.webmount.profile.ProfileRegistry = koinInject()
    val settingsStore: app.amber.core.settings.prefs.SettingsAggregator = koinInject()
    val webMountSessionOwner: WebMountSessionOwner = koinInject()
    val webMountSessions by webMountSessionOwner.sessions.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(
        webMountManager, oauthClient, oauthStore, userSiteRegistry,
        cookieProvider, profileRegistry, settingsStore, context, scope, toaster,
    ) {
        WebMountSettingsSession(
            context, webMountManager, oauthClient, oauthStore,
            userSiteRegistry, cookieProvider, profileRegistry, settingsStore, scope,
        ) { toaster.show(it) }
    }
    val states by session.states.collectAsStateWithLifecycle()
    val sites by session.sites.collectAsStateWithLifecycle()
    val globalEnabled by session.globalEnabled.collectAsStateWithLifecycle()
    val evalEnabled by session.evalEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(session) { session.observeOAuthUpdates() }

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_webmount_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(Lucide.Globe, contentDescription = null) },
                    title = stringResource(R.string.setting_webmount_title),
                    description = stringResource(R.string.setting_webmount_desc),
                    trailing = {
                        Switch(
                            checked = globalEnabled,
                            onCheckedChange = { session.setGlobalEnabled(it) },
                        )
                    },
                )
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_webmount_global_section_title),
                ) {
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_webmount_global_status_label),
                        value = if (globalEnabled) {
                            stringResource(R.string.setting_webmount_global_status_on)
                        } else {
                            stringResource(R.string.setting_webmount_global_status_off)
                        },
                    )
                    ExperimentNote(
                        text = if (globalEnabled) {
                            stringResource(R.string.setting_webmount_global_on_note)
                        } else {
                            stringResource(R.string.setting_webmount_global_off_note)
                        },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.setting_webmount_eval_label),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.setting_webmount_eval_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = evalEnabled,
                            enabled = globalEnabled,
                            onCheckedChange = { session.setEvalEnabled(it) },
                        )
                    }
                    // One-tap install for the /webmount slash command. Adds a
                    // globally-available QuickMessage that prefixes whatever
                    // task the user types with a system-style instruction
                    // teaching the agent to use WebMount tools.
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_webmount_install_slash_command),
                            enabled = globalEnabled,
                            primary = true,
                            onClick = { session.installSlash() },
                        )
                    }
                }
            }
            if (webMountSessions.isNotEmpty()) {
                item {
                    WebMountTaskCard(
                        sessions = webMountSessions,
                        onOpenSession = { sessionId, reopen ->
                            navController.navigate(
                                Screen.WebMountSession(
                                    sessionId = sessionId,
                                    reopen = reopen,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_webmount_section_sites),
                ) {
                    // Per-user feedback: keep [+ 添加网站] / [恢复示例] anchored
                    // to the top of the section so they don't get pushed off
                    // screen as the list grows.
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_webmount_add_site),
                            enabled = true,
                            primary = true,
                            onClick = { session.addSiteDialogOpen = true },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_webmount_restore_seed),
                            enabled = true,
                            onClick = { session.restoreExamples() },
                        )
                    }
                    if (sites.isEmpty()) {
                        ExperimentNote(text = stringResource(R.string.setting_webmount_sites_empty))
                    } else {
                        sites.forEachIndexed { index, site ->
                            UserSiteCard(
                                site = site,
                                stationState = states[site.id],
                                session = session,
                                busy = site.id in session.busyStations,
                                onSignIn = if (site.authKind != AuthKind.ANONYMOUS) {
                                    { session.signIn(site) }
                                } else null,
                                onSignOut = if (site.authKind != AuthKind.ANONYMOUS) {
                                    { session.signOut(site) }
                                } else null,
                                onConnect = if (site.authKind == AuthKind.OAUTH) {
                                    { session.connect(site) }
                                } else null,
                                onDisconnect = if (site.authKind == AuthKind.OAUTH) {
                                    { session.disconnect(site) }
                                } else null,
                                onEditOAuth = if (site.authKind == AuthKind.OAUTH) {
                                    { session.editOAuth(site) }
                                } else null,
                                onTest = { session.testSite(site) },
                                onDelete = { session.deleteSite(site) },
                            )
                            if (index != sites.lastIndex) ExperimentDivider(startPadding = 44.dp)
                        }
                    }
                }
            }
        }
    }

    // Dialogs --------------------------------------------------------------

    session.loginDialogSite?.let { site ->
        val target = remember(site, webMountManager, profileRegistry) {
            session.loginTarget(site)
        }
        WebMountLoginDialog(
            target = target,
            cookieProvider = cookieProvider,
            webMountManager = webMountManager,
            onClearSession = { session.clearLogin(site, target) },
            onVerifiedLogin = { session.verifiedLogin() },
            onDismiss = { session.dismissLogin(site, target) },
        )
    }

    session.oauthEditDialog?.let { site ->
        // Bug fix: OAuth providers are keyed by their own id ("feishu"), not
        // the UserSite id ("feishu_docs"). Use the explicit oauthProviderId
        // mapping with site.id as a fallback for sites where they match.
        val providerId = site.oauthProviderId ?: site.id
        val provider = session.oauthProvider(providerId)
        if (provider == null) {
            session.oauthEditDialog = null
        } else {
            @Suppress("UNUSED_EXPRESSION") session.oauthRevision
            val existing = session.oauthCredentials(providerId)
            OAuthEditDialog(
                siteName = site.displayName,
                initialAppId = existing?.appId.orEmpty(),
                initialAppSecret = existing?.appSecret.orEmpty(),
                initialScope = existing?.scope.orEmpty(),
                providerHint = provider.setupHint(context),
                onDismiss = { session.oauthEditDialog = null },
                onSave = { appId, appSecret, scopeText -> session.saveOAuth(providerId, appId, appSecret, scopeText) },
            )
        }
    }

    if (session.addSiteDialogOpen) {
        AddCustomSiteDialog(
            onDismiss = { session.addSiteDialogOpen = false },
            onAdd = { name, url, needsLogin, cookieName -> session.addSite(name, url, needsLogin, cookieName) },
        )
    }
}
