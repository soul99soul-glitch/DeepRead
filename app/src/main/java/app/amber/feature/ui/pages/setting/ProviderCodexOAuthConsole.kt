package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.isCodexOAuthReviewModel
import app.amber.ai.provider.providers.openai.OPENAI_CODEX_BACKEND_BASE_URL
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexOAuthClient
import app.amber.agent.R
import app.amber.core.utils.openUrl
import app.amber.core.utils.writeClipboardText
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderTextField

import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

import app.amber.feature.ui.pages.setting.provider.ProviderLabeledField

@Composable
internal fun CodexOAuthConsole(
    provider: ProviderSetting.OpenAI,
    onCommit: (ProviderSetting.OpenAI) -> Unit,
    onModelsFetched: (ProviderModelCandidates) -> Unit,
    onModelCandidatesInvalidated: () -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val httpClient = koinInject<OkHttpClient>()
    val providerCatalog = koinInject<ProviderCatalog>()
    val authStore = remember(context) { OpenAICodexAuthStore(context) }
    val oauthClient = remember(httpClient, authStore) { OpenAICodexOAuthClient(httpClient, authStore) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var oauthTokens by remember(provider.id, authStore) { mutableStateOf<app.amber.ai.provider.providers.openai.OpenAICodexAuthTokens?>(null) }
    var oauthTokensLoaded by remember(provider.id, authStore) { mutableStateOf(false) }
    val tokenReadGeneration = remember(provider.id, authStore) { mutableStateOf(0) }
    LaunchedEffect(provider.id, authStore, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val requestGeneration = tokenReadGeneration.value + 1
            tokenReadGeneration.value = requestGeneration
            val cached = withContext(Dispatchers.IO) { authStore.get(provider.id) }
            if (tokenReadGeneration.value == requestGeneration) {
                oauthTokens = cached
                oauthTokensLoaded = true
            }
        }
    }
    var oauthBusy by remember(provider.id) { mutableStateOf(false) }
    var oauthDeviceCode by remember(provider.id) { mutableStateOf<String?>(null) }
    var oauthVerificationUrl by remember(provider.id) { mutableStateOf<String?>(null) }
    var oauthGeneration by remember(provider.id) { mutableStateOf(0) }
    val latestProvider by rememberUpdatedState(provider)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestOnModelsFetched by rememberUpdatedState(onModelsFetched)
    val latestOnModelCandidatesInvalidated by rememberUpdatedState(onModelCandidatesInvalidated)

    suspend fun runCodexLogin() {
        if (oauthBusy || !oauthTokensLoaded) return
        tokenReadGeneration.value += 1
        oauthGeneration += 1
        val loginGeneration = oauthGeneration
        latestOnModelCandidatesInvalidated()
        oauthBusy = true
        try {
            val authorization = oauthClient.requestDeviceCode()
            oauthDeviceCode = authorization.userCode
            oauthVerificationUrl = authorization.verificationUrl
            context.writeClipboardText(authorization.userCode)
            context.openUrl(authorization.verificationUrl)
            toaster.show(
                context.getString(R.string.setting_provider_page_codex_oauth_code_copied, authorization.userCode),
                type = ToastType.Info,
            )
            val tokens = oauthClient.pollDeviceCode(provider.id, authorization)
            if (loginGeneration != oauthGeneration) return
            tokenReadGeneration.value += 1
            oauthTokens = tokens
            oauthDeviceCode = null
            oauthVerificationUrl = null
            val readyProvider = provider.codexOAuthReadyCopy()
            val fetchedModels = providerCatalog.text(provider)
                .listModels(readyProvider)
                .sortedBy { it.modelId }
            if (loginGeneration != oauthGeneration) return
            val currentProvider = latestProvider
            if (codexOAuthResultIsCurrent(
                    requestGeneration = loginGeneration,
                    currentGeneration = oauthGeneration,
                    requestKey = readyProvider.modelListRequestKey(),
                    currentProvider = currentProvider,
                )
            ) {
                latestOnModelsFetched(
                    ProviderModelCandidates(
                        requestKey = readyProvider.modelListRequestKey(),
                        models = fetchedModels,
                    )
                )
                val newSelection = currentProvider.codexOAuthLoginSelection(fetchedModels)
                latestOnCommit(currentProvider.copy(models = newSelection))
                toaster.show(
                    context.getString(R.string.setting_provider_page_codex_oauth_login_success_with_models, fetchedModels.size),
                    type = ToastType.Success,
                )
            } else {
                // The user changed the provider while the catalog request was in
                // flight. Keep the OAuth result, but do not write a stale provider
                // snapshot back over the newer draft.
                toaster.show(
                    context.getString(R.string.setting_provider_page_codex_oauth_signed_in),
                    type = ToastType.Info,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            toaster.show(
                context.getString(R.string.setting_provider_page_codex_oauth_login_failed, e.message ?: e.toString()),
                type = ToastType.Error,
            )
        } finally {
            oauthBusy = false
        }
    }

    val latestAutoStart by rememberUpdatedState(autoStartOAuth)
    val latestOnAutoStartConsumed by rememberUpdatedState(onAutoStartConsumed)
    LaunchedEffect(provider.id, oauthTokensLoaded) {
        if (
            latestAutoStart &&
            provider.authMode == OpenAIAuthMode.CODEX_OAUTH &&
            oauthTokensLoaded &&
            oauthTokens == null &&
            !oauthBusy
        ) {
            latestOnAutoStartConsumed()
            runCodexLogin()
        }
    }

    ProviderLabeledField("API Base URL") {
        ProviderTextField(
            value = OPENAI_CODEX_BACKEND_BASE_URL,
            onValueChange = {},
            mono = true,
            readOnly = true,
        )
    }
    ProviderMonoNote(
        oauthTokens?.let { tokens ->
            val account = listOfNotNull(tokens.email, tokens.planType).joinToString(" · ")
            if (account.isBlank()) {
                stringResource(R.string.setting_provider_page_codex_oauth_signed_in)
            } else {
                stringResource(R.string.setting_provider_page_codex_oauth_signed_in_as, account)
            }
        } ?: stringResource(R.string.setting_provider_page_codex_oauth_not_signed_in)
    )
    if (oauthDeviceCode != null) {
        ProviderLabeledField(stringResource(R.string.setting_provider_page_codex_oauth_device_code)) {
            ProviderTextField(
                value = oauthDeviceCode.orEmpty(),
                onValueChange = {},
                mono = true,
                readOnly = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_codex_oauth_copy_code),
                onClick = {
                    context.writeClipboardText(oauthDeviceCode.orEmpty())
                    toaster.show(
                        context.getString(R.string.setting_provider_page_codex_oauth_code_recopied),
                        type = ToastType.Success,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_codex_oauth_open_page),
                onClick = { oauthVerificationUrl?.let(context::openUrl) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    ProviderCommandButton(
        text = stringResource(R.string.setting_provider_page_codex_oauth_login),
        accent = oauthTokens == null,
        enabled = oauthTokensLoaded && !oauthBusy,
        onClick = { scope.launch { runCodexLogin() } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderCommandButton(
            text = stringResource(R.string.setting_provider_page_codex_oauth_fetch_models),
            enabled = oauthTokensLoaded && !oauthBusy,
            onClick = {
                if (!oauthBusy) {
                    scope.launch {
                        oauthBusy = true
                        try {
                            val fetchGeneration = oauthGeneration
                            val readyProvider = provider.codexOAuthReadyCopy()
                            val fetchedModels = providerCatalog.text(provider)
                                .listModels(readyProvider)
                                .sortedBy { it.modelId }
                            val currentProvider = latestProvider
                            if (!codexOAuthResultIsCurrent(
                                    requestGeneration = fetchGeneration,
                                    currentGeneration = oauthGeneration,
                                    requestKey = readyProvider.modelListRequestKey(),
                                    currentProvider = currentProvider,
                                )
                            ) {
                                return@launch
                            }
                            latestOnModelsFetched(
                                ProviderModelCandidates(
                                    requestKey = readyProvider.modelListRequestKey(),
                                    models = fetchedModels,
                                )
                            )
                            val cleanedModels = currentProvider.models.withoutCodexReviewModels()
                            if (cleanedModels != currentProvider.models) {
                                latestOnCommit(currentProvider.copy(models = cleanedModels))
                            }
                            if (fetchedModels.isEmpty()) {
                                toaster.show(
                                    context.getString(R.string.setting_provider_page_no_models),
                                    type = ToastType.Info,
                                )
                            } else {
                                toaster.show(
                                    context.getString(R.string.setting_provider_page_codex_oauth_models_loaded, fetchedModels.size),
                                    type = ToastType.Success,
                                )
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (e: Exception) {
                            toaster.show(
                                context.getString(R.string.setting_provider_page_codex_oauth_models_failed, e.message ?: e.toString()),
                                type = ToastType.Error,
                            )
                        } finally {
                            oauthBusy = false
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        ProviderCommandButton(
            text = stringResource(R.string.setting_provider_page_codex_oauth_refresh),
            enabled = oauthTokensLoaded && !oauthBusy,
            onClick = {
                if (!oauthBusy) {
                    scope.launch {
                        tokenReadGeneration.value += 1
                        oauthBusy = true
                        try {
                            val refreshed = oauthClient.refresh(provider.id)
                            tokenReadGeneration.value += 1
                            oauthTokens = refreshed
                            toaster.show(
                                context.getString(R.string.setting_provider_page_codex_oauth_refresh_success),
                                type = ToastType.Success,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (e: Exception) {
                            toaster.show(
                                context.getString(R.string.setting_provider_page_codex_oauth_refresh_failed, e.message ?: e.toString()),
                                type = ToastType.Error,
                            )
                        } finally {
                            oauthBusy = false
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        ProviderCommandButton(
            text = stringResource(R.string.setting_provider_page_codex_oauth_logout),
            enabled = oauthTokensLoaded,
            onClick = {
                tokenReadGeneration.value += 1
                oauthGeneration += 1
                latestOnModelCandidatesInvalidated()
                oauthClient.logout(provider.id)
                oauthTokens = null
                oauthDeviceCode = null
                oauthVerificationUrl = null
                toaster.show(
                    context.getString(R.string.setting_provider_page_codex_oauth_logged_out),
                    type = ToastType.Success,
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

private fun ProviderSetting.OpenAI.codexOAuthReadyCopy(): ProviderSetting.OpenAI = copy(
    authMode = OpenAIAuthMode.CODEX_OAUTH,
    baseUrl = OPENAI_CODEX_BACKEND_BASE_URL,
    useResponseApi = true,
)

internal fun List<Model>.withoutCodexReviewModels(): List<Model> =
    filterNot { it.isCodexOAuthReviewModel() }
