package app.amber.feature.ui.pages.setting

import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.agent.R
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.core.WebMountStatus
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.login.WebMountLoginDetector
import app.amber.feature.webmount.login.WebMountLoginStatus
import app.amber.feature.webmount.login.WebMountLoginTarget
import app.amber.feature.webmount.oauth.OAuthAppCredentials
import app.amber.feature.webmount.oauth.WebMountOAuthClient
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import app.amber.feature.webmount.usersites.AuthKind
import app.amber.feature.webmount.usersites.UserSite
import app.amber.feature.webmount.usersites.UserSiteRegistry
import app.amber.feature.webmount.usersites.userSiteId

internal class WebMountSettingsSession(
    private val context: android.content.Context,
    private val webMountManager: WebMountManager,
    private val oauthClient: WebMountOAuthClient,
    private val oauthStore: WebMountOAuthTokenStore,
    private val userSiteRegistry: UserSiteRegistry,
    private val cookieProvider: WebMountCookieProvider,
    private val profileRegistry: app.amber.feature.webmount.profile.ProfileRegistry,
    private val settingsStore: app.amber.core.settings.prefs.SettingsAggregator,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val notice: (String) -> Unit,
) {
    val states get() = webMountManager.states
    val sites get() = userSiteRegistry.sites
    val globalEnabled get() = webMountManager.globalEnabledFlow
    val evalEnabled get() = webMountManager.evalEnabledFlow

    var busyStations by mutableStateOf<Set<String>>(emptySet())
    var loginDialogSite by mutableStateOf<UserSite?>(null)
    // Pre-login cookie snapshot — captured the moment user taps Sign-in.
    // The dismiss handler diffs against post-snapshot to infer the session
    // cookie name and backfills site.loginCookieName when it was empty.
    var preLoginCookies by mutableStateOf<Map<String, String>>(emptyMap())
    var oauthEditDialog by mutableStateOf<UserSite?>(null)
    var addSiteDialogOpen by mutableStateOf(false)
    // Cookie and OAuth revisions refresh the per-site authentication snapshot.
    var cookieRevision by mutableStateOf(0)

    // Refreshes per-site OAuth state when tokens or credentials change.
    var oauthRevision by mutableStateOf(0)
    private val signedInTemplate get() = context.getString(R.string.setting_webmount_login_dialog_signed_in)
    private val notReadyTemplate get() = context.getString(R.string.setting_webmount_login_dialog_not_ready)
    private val unknownStatusTemplate get() = context.getString(R.string.setting_webmount_login_dialog_unknown_status)
    private val signedOutTemplate get() = context.getString(R.string.setting_webmount_signed_out_toast)
    private val noCookiesMessage get() = context.getString(R.string.setting_webmount_signed_out_nothing)
    private val deletedTemplate get() = context.getString(R.string.setting_webmount_site_deleted_toast)
    private val addedTemplate get() = context.getString(R.string.setting_webmount_site_added_toast)
    private val duplicateMessage get() = context.getString(R.string.setting_webmount_add_site_duplicate)
    private val noNativeTestHint get() = context.getString(R.string.setting_webmount_site_no_native_test_hint)
    private val restoredTemplate get() = context.getString(R.string.setting_webmount_restore_seed_toast)
    private val nothingToRestore get() = context.getString(R.string.setting_webmount_restore_seed_none)
    private val appIdRequiredMessage get() = context.getString(R.string.setting_webmount_oauth_app_id_required)
    private val connectedTemplate get() = context.getString(R.string.setting_webmount_oauth_connected_toast)
    private val disconnectedTemplate get() = context.getString(R.string.setting_webmount_oauth_disconnected_toast)
    private val failedTemplate get() = context.getString(R.string.setting_webmount_oauth_failed_toast)
    private val slashInstalledMessage get() = context.getString(R.string.setting_webmount_slash_installed)
    private val slashAlreadyInstalledMessage get() = context.getString(R.string.setting_webmount_slash_already_installed)
    private val inferredCookieSavedTemplate get() = context.getString(R.string.setting_webmount_inferred_cookie_saved)
    private val cookieValidatingTemplate get() = context.getString(R.string.setting_webmount_cookie_validating)

    suspend fun observeOAuthUpdates() {
        oauthStore.updates.collectLatest { oauthRevision++ }
    }

    fun setGlobalEnabled(enabled: Boolean) = webMountManager.setGlobalEnabled(enabled)
    fun setEvalEnabled(enabled: Boolean) = webMountManager.setEvalEnabled(enabled)
    fun loginTarget(site: UserSite) = WebMountLoginTarget.fromUserSite(site, webMountManager, profileRegistry)
    fun oauthProvider(providerId: String) = oauthClient.provider(providerId)
    fun oauthCredentials(providerId: String) = oauthStore.getCredentials(providerId)

    suspend fun siteAuth(site: UserSite): WebMountSiteAuth = withContext(Dispatchers.IO) {
        val loggedIn = if (site.authKind == AuthKind.ANONYMOUS) {
            null
        } else {
            val loginTarget = WebMountLoginTarget.fromUserSite(site, webMountManager, profileRegistry)
            val urls = loginTarget.urls
            if (urls.isEmpty()) {
                null
            } else {
                val snapshot = cookieProvider.snapshotCookies(urls)
                val requiredSets = loginTarget.requiredCookieSets
                when {
                    requiredSets.any { snapshot.containsAll(it) } -> true
                    requiredSets.isNotEmpty() -> false
                    else -> loginTarget.candidateCookieNames.takeIf { it.isNotEmpty() }
                        ?.any { snapshot.valuesByName(it).isNotEmpty() }
                }
            }
        }
        val oauthProviderIdLocal = site.oauthProviderId ?: site.id
        val oauthToken = if (site.authKind == AuthKind.OAUTH) {
            oauthStore.getToken(oauthProviderIdLocal)
        } else null
        // Keep physical token presence separate from local validity. An expired
        // token can still be refreshed by WebMountOAuthClient when credentials and
        // a refresh token are available; the list must not perform that network
        // operation itself.
        val hasToken = oauthToken != null
        val tokenExpired = oauthToken?.isExpired() == true
        val hasCredentials = if (site.authKind == AuthKind.OAUTH) {
            oauthStore.getCredentials(oauthProviderIdLocal) != null
        } else false

        WebMountSiteAuth(loggedIn, hasToken, tokenExpired, hasCredentials)
    }

    fun installSlash() {
        scope.launch {
            val installed = installWebMountSlashCommand(context, settingsStore)
            notice(
                if (installed) slashInstalledMessage else slashAlreadyInstalledMessage
            )
        }
    }

    fun restoreExamples() {
        val added = userSiteRegistry.restoreMissingSeeds()
        notice(
            if (added > 0) restoredTemplate.format(added)
            else nothingToRestore
        )
    }

    fun signIn(site: UserSite) {
        // Capture pre-login cookies so we can
        // diff after the dialog dismisses and
        // infer the session cookie name.
        val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
        preLoginCookies = cookieProvider.snapshotCookieEntries(urls)
        loginDialogSite = site
    }

    fun signOut(site: UserSite) {
        val oauthProviderId = site.oauthProviderId ?: site.id
        val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
        val cleared = cookieProvider.clearCookiesFor(
            urls,
            WebMountLoginTarget.fromUserSite(site, webMountManager, profileRegistry).manualCookieFields,
        )
        if (site.authKind == AuthKind.OAUTH) {
            oauthClient.disconnect(oauthProviderId)
            oauthRevision++
        }
        cookieRevision++
        notice(
            if (cleared > 0) signedOutTemplate.format(site.displayName, cleared)
            else noCookiesMessage.format(site.displayName)
        )
    }

    fun connect(site: UserSite) {
        val oauthProviderId = site.oauthProviderId ?: site.id
        if (site.id !in busyStations) {
            busyStations = busyStations + site.id
            scope.launch {
                try {
                    val result = oauthClient.connect(oauthProviderId)
                    when (result) {
                        is WebMountOAuthClient.ConnectResult.Success ->
                            notice(connectedTemplate.format(site.displayName))
                        is WebMountOAuthClient.ConnectResult.NotConfigured ->
                            notice(result.reason)
                        is WebMountOAuthClient.ConnectResult.Failed ->
                            notice(failedTemplate.format(result.reason))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    notice(error.message ?: error.toString())
                } finally {
                    busyStations = busyStations - site.id
                }
            }
        }
    }

    fun disconnect(site: UserSite) {
        val oauthProviderId = site.oauthProviderId ?: site.id
        oauthClient.disconnect(oauthProviderId)
        notice(disconnectedTemplate.format(site.displayName))
    }

    fun editOAuth(site: UserSite) {
        oauthEditDialog = site
    }

    fun testSite(site: UserSite) {
        if (site.nativeAdapterId != null) {
            busyStations = busyStations + site.id
            scope.launch {
                runCatching { webMountManager.probe(site.id) }
                    .onFailure { notice(it.message ?: it.toString()) }
                busyStations = busyStations - site.id
            }
        } else {
            notice(noNativeTestHint)
        }
    }

    fun deleteSite(site: UserSite) {
        val oauthProviderId = site.oauthProviderId ?: site.id
        // B-5 fix: drop the authKind guard so a
        // site whose kind flipped during its
        // lifetime (e.g. once OAuth, now COOKIE)
        // doesn't leak OAuth state. clearToken /
        // clearCredentials are no-ops when no
        // entry exists.
        oauthStore.clearToken(oauthProviderId)
        oauthStore.clearCredentials(oauthProviderId)
        val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
        cookieProvider.clearCookiesFor(
            urls,
            WebMountLoginTarget.fromUserSite(site, webMountManager, profileRegistry).manualCookieFields,
        )
        profileRegistry.remove(site.id)
        if (userSiteRegistry.remove(site.id)) {
            cookieRevision++
            notice(deletedTemplate.format(site.displayName))
        }
    }

    fun clearLogin(site: UserSite, target: WebMountLoginTarget) {
        val urls = target.urls
        val cleared = cookieProvider.clearCookiesFor(urls, target.manualCookieFields)
        if (site.authKind == AuthKind.OAUTH) {
            oauthClient.disconnect(site.oauthProviderId ?: site.id)
            oauthRevision++
        }
        cookieRevision++
        preLoginCookies = emptyMap()
        notice(
            if (cleared > 0) signedOutTemplate.format(site.displayName, cleared)
            else noCookiesMessage.format(site.displayName)
        )
    }

    fun verifiedLogin() {
        cookieRevision++
    }

    fun dismissLogin(site: UserSite, target: WebMountLoginTarget) {
        CookieManager.getInstance().flush()
        loginDialogSite = null
        cookieRevision++

        val postCookies = cookieProvider.snapshotCookieEntries(target.urls)
        val newCookies = postCookies.filterKeys { it !in preLoginCookies }
        preLoginCookies = emptyMap()
        val preferredCookieNames = target.candidateCookieNames

        // If the user didn't configure a cookie name AND we detected a
        // newly-set session-like cookie during the login session, save
        // it back so the row's "logged_in" badge starts working without
        // any further intervention. Toast tells the user what happened.
        val autoInferredName: String?
        val savedCookieMissing = site.loginCookieName?.let { postCookies[it] == null } ?: true
        autoInferredName = if (site.loginCookieName.isNullOrBlank() || savedCookieMissing) {
            val guessSourceCookies = if (preferredCookieNames.isNotEmpty()) postCookies else newCookies
            cookieProvider.guessSessionCookieName(
                newCookies = guessSourceCookies,
                preferredNames = preferredCookieNames,
            )
        } else {
            null
        }

        val effectiveCookieNames = buildList {
            autoInferredName?.let { add(it) }
            addAll(preferredCookieNames)
        }.distinct()
        val snapshot = cookieProvider.snapshotCookies(target.urls)
        val status = WebMountLoginDetector.evaluate(target, null, snapshot)
        val captured = when {
            status is WebMountLoginStatus.SignedIn -> true
            target.stationId != null -> false
            effectiveCookieNames.isNotEmpty() -> effectiveCookieNames.any { postCookies[it] != null }
            else -> null
        }

        fun persistInferredCookie() {
            autoInferredName?.let { inferred ->
                userSiteRegistry.update(site.id) { it.copy(loginCookieName = inferred) }
            }
        }

        fun successMessage(): String {
            return if (autoInferredName != null) {
                inferredCookieSavedTemplate.format(autoInferredName, site.displayName)
            } else {
                signedInTemplate.format(site.displayName)
            }
        }

        val stationId = target.stationId
        if (status is WebMountLoginStatus.SignedIn && stationId != null) {
            notice(cookieValidatingTemplate.format(site.displayName))
            scope.launch {
                val state = runCatching { webMountManager.probe(stationId) }.getOrNull()
                cookieRevision++
                if (state == null || state.status == WebMountStatus.ERROR || state.status == WebMountStatus.LOGIN_REQUIRED) {
                    notice(state?.message ?: notReadyTemplate.format(site.displayName))
                } else {
                    persistInferredCookie()
                    notice(successMessage())
                }
            }
        } else {
            if (captured == true) persistInferredCookie()
            val message = when {
                captured == true -> successMessage()
                captured == false -> notReadyTemplate.format(site.displayName)
                else -> unknownStatusTemplate.format(site.displayName)
            }
            notice(message)
        }
    }

    fun saveOAuth(providerId: String, appId: String, appSecret: String, scopeText: String) {
        if (appId.isBlank()) {
            notice(appIdRequiredMessage)
        } else {
            oauthStore.putCredentials(
                providerId,
                OAuthAppCredentials(
                    provider = providerId,
                    appId = appId.trim(),
                    appSecret = appSecret.trim().ifBlank { null },
                    scope = scopeText.trim().ifBlank { null },
                ),
            )
            oauthEditDialog = null
        }
    }

    fun addSite(name: String, url: String, needsLogin: Boolean, cookieName: String?) {
        val ok = userSiteRegistry.add(
            UserSite(
                id = userSiteId(name),
                displayName = name,
                homepageUrl = url,
                authKind = if (needsLogin) AuthKind.COOKIE else AuthKind.ANONYMOUS,
                loginCookieName = cookieName?.takeIf { it.isNotBlank() },
                nativeAdapterId = null,
                iconKey = null,
            )
        )
        if (ok) {
            addSiteDialogOpen = false
            notice(addedTemplate.format(name))
        } else {
            notice(duplicateMessage)
        }
    }
}

private fun collectKnownUrlsFor(
    site: UserSite,
    manager: WebMountManager,
    profileRegistry: app.amber.feature.webmount.profile.ProfileRegistry,
): List<String> = app.amber.feature.webmount.usersites.collectSiteUrls(site, manager, profileRegistry)

internal data class WebMountSiteAuth(
    val loggedIn: Boolean?,
    val hasToken: Boolean,
    val tokenExpired: Boolean,
    val hasCredentials: Boolean,
)
