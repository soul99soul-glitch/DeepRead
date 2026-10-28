package app.amber.feature.tools

import android.app.Application
import android.content.Context
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.workspace.WorkspaceManager
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceArtifactHttpRequestTest {
    private lateinit var server: HttpServer
    private lateinit var http: Tool
    private val received = CopyOnWriteArrayList<Pair<String, String>>()

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        http = WorkspaceArtifactTools(context, WorkspaceManager(context), AgentToolActivityStore())
            .getTools().single { it.name == "http_request" }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/request") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            received += exchange.requestMethod to body
            val response = "ok".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun defaultGetWithBodyIsRejectedBeforeItCanBecomePost() = rejectedReadBody(null, "payload")

    @Test
    fun explicitGetWithEmptyBodyIsRejectedBeforeItCanBecomePost() = rejectedReadBody("GET", "")

    @Test
    fun headWithBodyIsRejectedBeforeConnecting() = rejectedReadBody("HEAD", "payload")

    @Test
    fun explicitPostKeepsItsMethodAndBody() = runBlocking {
        val output = http.execute(request("POST", "payload"))
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("200", result.getValue("status_code").jsonPrimitive.content)
        assertEquals(listOf("POST" to "payload"), received.toList())
    }

    @Test
    fun ordinaryGetKeepsItsReadMethod() = runBlocking {
        http.execute(request(null, null))
        assertEquals(listOf("GET" to ""), received.toList())
    }

    private fun rejectedReadBody(method: String?, body: String) = runBlocking {
        val result = runCatching { http.execute(request(method, body)) }
        assertTrue("Body-bearing reads must fail before transport: ${result.exceptionOrNull()}", result.exceptionOrNull() is IllegalArgumentException)
        assertTrue("Rejected read must not send a request", received.isEmpty())
    }

    private fun request(method: String?, body: String?) = buildJsonObject {
        put("url", "http://127.0.0.1:${server.address.port}/request")
        method?.let { put("method", it) }
        body?.let { put("body", it) }
    }
}
