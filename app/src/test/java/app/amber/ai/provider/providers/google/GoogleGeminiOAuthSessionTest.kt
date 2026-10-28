package app.amber.ai.provider.providers.google

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import app.amber.ai.provider.providers.TestAndroidKeyStore
import app.amber.ai.provider.providers.TestContext
import app.amber.ai.provider.providers.oauthJsonResponse
import app.amber.ai.provider.providers.tokenResponseClient
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.uuid.Uuid
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GoogleGeminiOAuthSessionTest {
    @Test
    fun `late refresh cannot resurrect tokens after logout`() = runBlocking {
        assertLateWriteRejected(
            endpoint = TOKEN_ENDPOINT,
            response = """{"access_token":"old-refreshed","refresh_token":"old-rotated","expires_in":3600}""",
            installNewSession = false,
        ) { client, _, providerId -> client.refresh(providerId) }
    }

    @Test
    fun `late refresh cannot replace a session installed by another client after logout`() = runBlocking {
        assertLateWriteRejected(
            endpoint = TOKEN_ENDPOINT,
            response = """{"access_token":"old-refreshed","refresh_token":"old-rotated","expires_in":3600}""",
        ) { client, _, providerId -> client.refresh(providerId) }
    }

    @Test
    fun `late authorization exchange cannot replace a newer session`() = runBlocking {
        assertLateWriteRejected(
            endpoint = TOKEN_ENDPOINT,
            response = """{"access_token":"old-authorized","refresh_token":"old-refresh","expires_in":3600}""",
            extraResponses = mapOf(
                "/v1internal:loadCodeAssist" to """{"cloudaicompanionProject":"old-project","currentTier":{"id":"FREE"}}""",
            ),
        ) { client, context, providerId -> client.authorize(context, providerId) }
    }

    @Test
    fun `late load code assist cannot overwrite newer tokens with its old session snapshot`() = runBlocking {
        assertLateWriteRejected(
            endpoint = "$GOOGLE_GEMINI_CODE_ASSIST_BASE_URL/v1internal:loadCodeAssist",
            response = """{"cloudaicompanionProject":"old-project","currentTier":{"id":"FREE"}}""",
        ) { client, _, providerId -> client.ensureOnboarded(providerId) }
    }

    @Test
    fun `late onboard user completion cannot resurrect tokens after logout`() = runBlocking {
        assertLateWriteRejected(
            endpoint = "$GOOGLE_GEMINI_CODE_ASSIST_BASE_URL/v1internal:onboardUser",
            response = """{"done":true,"response":{"cloudaicompanionProject":{"id":"old-project"}}}""",
            installNewSession = false,
            extraResponses = mapOf(
                "/v1internal:loadCodeAssist" to """{"allowedTiers":[{"id":"FREE"}]}""",
            ),
        ) { client, _, providerId -> client.ensureOnboarded(providerId) }
    }

    private suspend fun assertLateWriteRejected(
        endpoint: String,
        response: String,
        installNewSession: Boolean = true,
        extraResponses: Map<String, String> = emptyMap(),
        operation: suspend (GoogleGeminiOAuthClient, Context, Uuid) -> GoogleGeminiAuthTokens,
    ) = kotlinx.coroutines.coroutineScope {
        val context = TestContext()
        val store = GoogleGeminiAuthStore(context)
        val providerId = Uuid.random()
        // Missing project/tier forces the actual onboarding writes; refresh uses the same token owner.
        store.save(providerId, tokens("old-access", "old-refresh"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = tokenResponseClient(endpoint, entered, release, 200, response)
            .newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                oauthJsonResponse(
                    request,
                    checkNotNull(extraResponses[request.url.encodedPath]) {
                        "Unexpected OAuth request: ${request.url.encodedPath}"
                    },
                )
            }.build()
        val client = GoogleGeminiOAuthClient(http, store)
        val browser = CallbackContext(context)
        val current = if (installNewSession) tokens("new-access", "new-refresh").copy(
            projectId = "new-project",
            onboardedTier = "PAID",
        ) else null
        val pending = async(Dispatchers.Default) { runCatching { operation(client, browser, providerId) } }
        try {
            assertTrue("The real OAuth HTTP request must reach the held response", entered.await(3, TimeUnit.SECONDS))
            val otherStore = GoogleGeminiAuthStore(context)
            GoogleGeminiOAuthClient(OkHttpClient(), otherStore).logout(providerId)
            if (current != null) otherStore.save(providerId, current)
        } finally {
            release.countDown()
        }

        withTimeout(3_000) { pending.await() }
        browser.awaitCallbackDelivery()

        // Reconstruct the real encrypted store rather than checking only the client's cached result.
        assertEquals(current, GoogleGeminiAuthStore(context).get(providerId))
    }

    /** Replaces only the browser launch; the production loopback server validates the actual state. */
    private class CallbackContext(base: Context) : ContextWrapper(base) {
        private var callbackThread: Thread? = null
        private val callbackFailure = AtomicReference<Throwable?>()

        override fun startActivity(intent: Intent) {
            val uri = checkNotNull(intent.data)
            val state = checkNotNull(uri.getQueryParameter("state"))
            val redirect = android.net.Uri.parse(checkNotNull(uri.getQueryParameter("redirect_uri")))
            callbackThread = thread(name = "gemini-test-loopback") {
                runCatching {
                    Socket(checkNotNull(redirect.host), redirect.port).use { socket ->
                        socket.soTimeout = 3_000
                        socket.getOutputStream().write(
                            ("GET ${redirect.path}?code=test-code&state=$state HTTP/1.1\r\n" +
                                "Host: 127.0.0.1\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        socket.getOutputStream().flush()
                        val response = socket.getInputStream().bufferedReader().readText()
                        check(response.startsWith("HTTP/1.1 200")) { "The loopback callback was rejected" }
                    }
                }.onFailure { callbackFailure.set(it) }
            }
        }

        fun awaitCallbackDelivery() {
            callbackThread?.join(3_000)
            assertTrue(callbackThread?.isAlive != true)
            assertNull(callbackFailure.get())
        }
    }

    private fun tokens(access: String, refresh: String) = GoogleGeminiAuthTokens(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMillis = System.currentTimeMillis() + 3_600_000,
    )

    companion object {
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        @JvmStatic
        @BeforeClass
        fun installTestKeyStore() = TestAndroidKeyStore.install()

        @JvmStatic
        @AfterClass
        fun restoreTestKeyStore() = TestAndroidKeyStore.restore()
    }
}
