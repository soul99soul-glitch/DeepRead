package app.amber.ai.provider.providers.openai

import android.app.Application
import app.amber.ai.provider.providers.TestAndroidKeyStore
import app.amber.ai.provider.providers.TestContext
import app.amber.ai.provider.providers.oauthJsonResponse
import app.amber.ai.provider.providers.tokenResponseClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OpenAICodexOAuthSessionTest {
    @Test
    fun `late refresh cannot resurrect tokens after logout`() = runBlocking {
        val context = TestContext()
        val store = OpenAICodexAuthStore(context)
        val providerId = Uuid.random()
        store.save(providerId, tokens("old-access", "old-refresh", expired = true))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OpenAICodexOAuthClient(refreshHttp(entered, release), store)
        val refresh = async(Dispatchers.Default) { runCatching { client.refresh(providerId) } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            client.logout(providerId)
        } finally {
            release.countDown()
        }

        withTimeout(2_000) { refresh.await() }

        assertNull(OpenAICodexAuthStore(context).get(providerId))
    }

    @Test
    fun `late refresh cannot replace a session installed by another client after logout`() = runBlocking {
        val context = TestContext()
        val store = OpenAICodexAuthStore(context)
        val providerId = Uuid.random()
        store.save(providerId, tokens("old-access", "old-refresh", expired = true))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OpenAICodexOAuthClient(refreshHttp(entered, release), store)
        val otherStore = OpenAICodexAuthStore(context)
        val otherClient = OpenAICodexOAuthClient(OkHttpClient(), otherStore)
        val current = tokens("new-access", "new-refresh")
        val refresh = async(Dispatchers.Default) { runCatching { client.refresh(providerId) } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            otherClient.logout(providerId)
            otherStore.save(providerId, current)
        } finally {
            release.countDown()
        }

        withTimeout(2_000) { refresh.await() }

        assertEquals(current, OpenAICodexAuthStore(context).get(providerId))
    }

    @Test
    fun `late device authorization cannot replace a newer session`() = runBlocking {
        val context = TestContext()
        val store = OpenAICodexAuthStore(context)
        val providerId = Uuid.random()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = tokenResponseClient(
            endpoint = "$OPENAI_CODEX_AUTH_ISSUER/oauth/token",
            entered = entered,
            release = release,
            statusCode = 200,
            responseBody = """{"access_token":"old-authorized","refresh_token":"old-refresh","id_token":"e30.e30.signature"}""",
        ).newBuilder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> oauthJsonResponse(
                    request,
                    """{"device_auth_id":"test-device","user_code":"TEST-CODE","interval":1}""",
                )
                "/api/accounts/deviceauth/token" -> oauthJsonResponse(
                    request,
                    """{"authorization_code":"test-code","code_challenge":"test-challenge","code_verifier":"test-verifier"}""",
                )
                else -> error("Unexpected OAuth request: ${request.url.encodedPath}")
            }
        }.build()
        val client = OpenAICodexOAuthClient(http, store)
        val authorization = client.requestDeviceCode()
        val current = tokens("new-access", "new-refresh")
        val login = async(Dispatchers.Default) { runCatching { client.pollDeviceCode(providerId, authorization) } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            OpenAICodexOAuthClient(OkHttpClient(), OpenAICodexAuthStore(context)).logout(providerId)
            store.save(providerId, current)
        } finally {
            release.countDown()
        }

        withTimeout(2_000) { login.await() }

        assertEquals(current, OpenAICodexAuthStore(context).get(providerId))
    }

    private fun refreshHttp(entered: CountDownLatch, release: CountDownLatch) = tokenResponseClient(
        endpoint = "$OPENAI_CODEX_AUTH_ISSUER/oauth/token",
        entered = entered,
        release = release,
        statusCode = 200,
        responseBody = """{"access_token":"old-refreshed","refresh_token":"old-rotated"}""",
    )

    private fun tokens(access: String, refresh: String, expired: Boolean = false) = OpenAICodexAuthTokens(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMillis = if (expired) 0 else System.currentTimeMillis() + 3_600_000,
    )

    companion object {
        @JvmStatic
        @BeforeClass
        fun installTestKeyStore() = TestAndroidKeyStore.install()

        @JvmStatic
        @AfterClass
        fun restoreTestKeyStore() = TestAndroidKeyStore.restore()
    }
}
