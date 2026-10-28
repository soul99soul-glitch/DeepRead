package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.google.AntigravityAuthStatus
import app.amber.ai.provider.providers.google.AntigravityAuthStatusCode
import app.amber.ai.provider.providers.google.AntigravityAuthStore
import app.amber.ai.provider.providers.google.AntigravityOAuthClient
import app.amber.ai.provider.providers.google.AntigravityOAuthTokens
import app.amber.ai.provider.providers.google.defaultAntigravityModels
import app.amber.ai.provider.fixedBaseUrl
import app.amber.agent.R
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderStatusChip
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.pages.setting.components.ProviderTone
import app.amber.feature.ui.pages.setting.provider.ProviderLabeledField
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.provider.providers.google.ANTIGRAVITY_OAUTH_CLIENT_ID
import app.amber.ai.provider.providers.google.ANTIGRAVITY_OAUTH_CLIENT_SECRET
import org.koin.compose.koinInject

/** Antigravity 登录面板与 Code Assist 分离，token/status 绑定 provider UUID。 */
@Composable
internal fun AntigravityOAuthConsole(
    provider: ProviderSetting.Google,
    onCommit: (ProviderSetting.Google) -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val store = koinInject<AntigravityAuthStore>()
    val client = koinInject<AntigravityOAuthClient>()
    val lifecycleOwner = LocalLifecycleOwner.current
    var tokens by remember(provider.id, store) { mutableStateOf<AntigravityOAuthTokens?>(null) }
    var tokensLoaded by remember(provider.id, store) { mutableStateOf(false) }
    val tokenReadGeneration = remember(provider.id, store) { mutableStateOf(0) }
    LaunchedEffect(provider.id, store, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val requestGeneration = tokenReadGeneration.value + 1
            tokenReadGeneration.value = requestGeneration
            val cached = withContext(Dispatchers.IO) { store.get(provider.id) }
            if (tokenReadGeneration.value == requestGeneration) {
                tokens = cached
                tokensLoaded = true
            }
        }
    }
    var busy by remember(provider.id) { mutableStateOf(false) }
    val latestProvider by rememberUpdatedState(provider)
    val status = remember(tokens, provider.id, tokensLoaded) {
        when {
            !tokensLoaded -> null
            ANTIGRAVITY_OAUTH_CLIENT_ID.isBlank() || ANTIGRAVITY_OAUTH_CLIENT_SECRET.isBlank() ->
                AntigravityAuthStatus.clientUnavailable()
            else -> AntigravityAuthStatus.from(tokens, System.currentTimeMillis())
        }
    }
    val signedIn = tokens != null

    suspend fun login() {
        if (busy) return
        tokenReadGeneration.value += 1
        busy = true
        try {
            val result = client.authorize(context, provider.id)
            val loginGeneration = client.sessionGeneration(provider.id)
            val models = try {
                client.listModels(provider.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            // Logout may happen while model discovery is in flight. Keep the
            // durable generation guard before committing the provider snapshot.
            val current = latestProvider
            if (current.id != provider.id || client.sessionGeneration(provider.id) != loginGeneration) {
                return
            }
            val cached = withContext(Dispatchers.IO) { client.cached(provider.id) } ?: return
            if (current.id != provider.id || client.sessionGeneration(provider.id) != loginGeneration) return
            tokenReadGeneration.value += 1
            tokens = cached
            onCommit(
                current.copy(
                    models = when {
                        models.isNotEmpty() -> models
                        current.models.isEmpty() -> defaultAntigravityModels()
                        else -> current.models
                    },
                    name = if (current.name == "Google") "Gemini Antigravity" else current.name,
                )
            )
            toaster.show(
                context.getString(
                    R.string.setting_provider_page_antigravity_oauth_signed_in_toast,
                    result.email ?: context.getString(R.string.setting_provider_page_antigravity_oauth_google_account),
                ),
                type = ToastType.Success,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            toaster.show(
                context.getString(
                    R.string.setting_provider_page_antigravity_oauth_login_failed,
                    (error.message ?: error).toString(),
                ),
                type = ToastType.Error,
            )
        } finally {
            busy = false
        }
    }

    val latestAutoStart by rememberUpdatedState(autoStartOAuth)
    val latestAutoStartConsumed by rememberUpdatedState(onAutoStartConsumed)
    LaunchedEffect(provider.id, provider.authMode, tokensLoaded) {
        if (
            latestAutoStart &&
            provider.authMode == GoogleAuthMode.ANTIGRAVITY_OAUTH &&
            tokensLoaded &&
            tokens == null &&
            !busy
        ) {
            latestAutoStartConsumed()
            login()
        }
    }

    // Rendered inside the config page's 账号 card (parent supplies padding / spacing).
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val t = LocalAmberTokens.current
        val type = LocalAmberType.current
        Text(
            text = "Antigravity OAuth",
            modifier = Modifier.weight(1f, fill = false),
            style = type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = t.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        status?.let {
            AntigravityStatusChip(
                status = it,
                email = tokens?.email,
            )
        }
    }
    ProviderLabeledField(stringResource(R.string.provider_field_base_url_fixed)) {
        ProviderTextField(
            value = checkNotNull(GoogleAuthMode.ANTIGRAVITY_OAUTH.fixedBaseUrl()),
            onValueChange = {},
            mono = true,
            readOnly = true,
        )
    }
    ProviderCommandButton(
        text = if (busy) stringResource(R.string.setting_provider_page_antigravity_oauth_signing_in)
        else if (signedIn) stringResource(R.string.setting_provider_page_antigravity_oauth_relogin)
        else stringResource(R.string.setting_provider_page_antigravity_oauth_login),
        accent = !signedIn,
        enabled = tokensLoaded && !busy,
        onClick = { scope.launch { login() } },
        modifier = Modifier.fillMaxWidth(),
    )
    ProviderCommandButton(
        text = stringResource(R.string.setting_provider_page_antigravity_oauth_logout),
        danger = true,
        enabled = tokensLoaded && signedIn && !busy,
        onClick = {
            tokenReadGeneration.value += 1
            client.logout(provider.id)
            tokens = null
            toaster.show(context.getString(R.string.setting_provider_page_antigravity_oauth_signed_out), type = ToastType.Success)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.setting_provider_page_antigravity_oauth_login_hint),
        style = LocalAmberType.current.secondary,
        color = LocalAmberTokens.current.ink3,
    )
}

@Composable
private fun AntigravityStatusChip(
    status: AntigravityAuthStatus,
    email: String?,
) {
    val connected = status.usable
    val copy = when {
        connected && !email.isNullOrBlank() -> stringResource(R.string.setting_provider_page_antigravity_oauth_signed_in_as, email)
        connected -> stringResource(R.string.setting_provider_page_antigravity_oauth_signed_in)
        status.code == AntigravityAuthStatusCode.NOT_SIGNED_IN -> stringResource(R.string.setting_provider_page_antigravity_oauth_not_signed_in)
        status.code == AntigravityAuthStatusCode.CLIENT_UNAVAILABLE -> stringResource(R.string.setting_provider_page_antigravity_oauth_unavailable)
        else -> stringResource(R.string.setting_provider_page_antigravity_oauth_reauth_required)
    }
    val tone = when {
        connected -> ProviderTone.Ok
        status.code == AntigravityAuthStatusCode.NOT_SIGNED_IN ||
            status.code == AntigravityAuthStatusCode.CLIENT_UNAVAILABLE -> ProviderTone.Neutral
        else -> ProviderTone.Warn
    }
    ProviderStatusChip(text = copy, tone = tone)
}
