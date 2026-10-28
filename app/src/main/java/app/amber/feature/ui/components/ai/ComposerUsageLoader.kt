package app.amber.feature.ui.components.ai

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexOAuthClient
import app.amber.agent.R
import app.amber.core.usage.ProviderUsageClient
import org.koin.compose.koinInject
import okhttp3.OkHttpClient

internal class ComposerUsageLoader(
    private val context: Context,
    private val usageClient: OpenAICodexOAuthClient,
    private val providerUsageClient: ProviderUsageClient,
) {
    var status by mutableStateOf(ComposerUsageStatus())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun refresh(provider: ProviderSetting?, model: Model?) {
        val openAIProvider = provider as? ProviderSetting.OpenAI
        if (openAIProvider == null) {
            status = ComposerUsageStatus()
            error = context.getString(R.string.chat_input_usage_openai_compatible_required)
            return
        }

        loading = true
        error = null
        runCatching {
            if (openAIProvider.authMode == OpenAIAuthMode.CODEX_OAUTH) {
                usageClient.fetchUsage(openAIProvider.id).toComposerUsageStatus(context)
            } else {
                providerUsageClient.fetchUsage(openAIProvider, model).toComposerUsageStatus()
            }
        }.onSuccess {
            status = it
        }.onFailure {
            status = ComposerUsageStatus()
            error = it.toComposerUsageErrorMessage(context)
        }
        loading = false
    }
}

@Composable
internal fun rememberComposerUsageLoader(): ComposerUsageLoader {
    val context = LocalContext.current
    val httpClient: OkHttpClient = koinInject()
    return remember(context, httpClient) {
        ComposerUsageLoader(
            context,
            OpenAICodexOAuthClient(httpClient, OpenAICodexAuthStore(context)),
            ProviderUsageClient(httpClient),
        )
    }
}
