package app.amber.feature.ui.pages.settings

import app.amber.agent.data.files.CasTestFixtures
import app.amber.core.ai.mcp.McpConnectPreflight
import app.amber.core.ai.mcp.McpImportApplyResult
import app.amber.core.ai.mcp.McpImportCandidate
import app.amber.core.ai.mcp.McpImportPreparation
import app.amber.core.ai.mcp.McpImportTransaction
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.files.SkillManager
import app.amber.core.infra.PreferencesKeys
import app.amber.core.utils.JsonInstant
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.ui.pages.extensions.SkillDetailVM
import app.amber.feature.ui.pages.setting.SettingVM
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSettingsPublicationTest : SettingsEditorTestBase() {
    @Test
    fun settingsPageImportCannotApplyOrAuditBeforeItsRealDurableWriteCompletes(): Unit = runBlocking {
        val vm = fixture.retain(SettingVM(fixture.settings))
        val ledger = CasTestFixtures.FakeCasLedger()
        val transaction = settingsPageTransaction(vm, ledger, preflight { emptyList() })
        val raw = batch("notes" to "https://mcp.example.com/a")
        approve(transaction, raw, "settings-await")
        val pause = fixture.dataStore.pauseNextWrite()
        val applying = async(start = CoroutineStart.UNDISPATCHED) { transaction.apply(raw, "settings-await") }
        try {
            withTimeout(5_000) { pause.entered.await() }
            assertFalse("Applied must await SettingVM's actual Preferences write", applying.isCompleted)
            assertTrue(ledger.entries.none { it.toolCallId == "settings-await" && it.outcome == "applied" })
            pause.release.complete(Unit)
            assertTrue(applying.await() is McpImportApplyResult.Applied)
            fixture.awaitWrites(vm)
            assertEquals(listOf("notes"), durableServers().map { it.commonOptions.name })
            assertEquals("applied", ledger.entries.last().outcome)
        } finally {
            pause.release.complete(Unit)
            applying.join()
            fixture.awaitWrites(vm)
        }
    }

    @Test
    fun finalNameConflictFromTheRealSkillPublisherRejectsTheWholeSettingsBatch(): Unit = runBlocking {
        val vm = fixture.retain(SettingVM(fixture.settings))
        val ledger = CasTestFixtures.FakeCasLedger()
        val enteredPreflight = CompletableDeferred<Unit>()
        val releasePreflight = CompletableDeferred<Unit>()
        val transactionA = settingsPageTransaction(vm, ledger, preflight {
            enteredPreflight.complete(Unit)
            releasePreflight.await()
            emptyList()
        })
        val rawA = batch("notes" to "https://mcp.example.com/a", "sidecar" to "https://mcp.example.com/sidecar")
        approve(transactionA, rawA, "settings-a")
        val applyingA = async(start = CoroutineStart.UNDISPATCHED) { transactionA.apply(rawA, "settings-a") }
        val methods = CopyOnWriteArrayList<String>()
        val server = localMcpServer(methods)
        try {
            withTimeout(5_000) { enteredPreflight.await() }
            // This is SkillDetailVM's actual transaction factory and real preflight/publish path.
            val skillVM = fixture.retain(SkillDetailVM(
                fixture.application, SkillManager(fixture.application, fixture.settings), fixture.settings,
                CapabilityPermissionStore(fixture.dataStore),
            ))
            val factory = SkillDetailVM::class.java.getDeclaredMethod("createMcpImportTransaction").apply { isAccessible = true }
            val transactionB = factory.invoke(skillVM) as McpImportTransaction
            val rawB = batch("notes" to "http://127.0.0.1:${server.address.port}/mcp")
            approve(transactionB, rawB, "skill-b")
            assertTrue(withTimeout(10_000) { transactionB.apply(rawB, "skill-b") } is McpImportApplyResult.Applied)
            val publishedByB = durableServers().single()
            assertEquals("notes", publishedByB.commonOptions.name)
            assertTrue(methods.containsAll(listOf("initialize", "tools/list")))

            releasePreflight.complete(Unit)
            val resultA = applyingA.await()
            fixture.awaitWrites(vm)
            assertTrue("A must reject a name published while its preflight was suspended", resultA is McpImportApplyResult.Rejected)
            assertEquals(listOf(publishedByB.id), durableServers().map { it.id })
            assertTrue(ledger.entries.none { it.toolCallId == "settings-a" && it.outcome == "applied" })
        } finally {
            releasePreflight.complete(Unit)
            applyingA.join()
            fixture.awaitWrites(vm)
            server.stop(0)
        }
    }

    @Test
    fun cancelledPreflightCannotVisitAnotherCandidatePublishOrRecordAnOutcome(): Unit = runBlocking {
        val ledger = CasTestFixtures.FakeCasLedger()
        val entered = CompletableDeferred<Unit>()
        val visited = CopyOnWriteArrayList<String>()
        var publishes = 0
        val transaction = McpImportTransaction(
            preflight = preflight { candidate ->
                visited += candidate.serverName
                if (visited.size == 1) { entered.complete(Unit); awaitCancellation() }
                emptyList()
            },
            approvalLedger = ledger,
            existingServerNames = { emptySet() },
            publish = { publishes++ },
        )
        val raw = batch("first" to "https://mcp.example.com/first", "second" to "https://mcp.example.com/second")
        approve(transaction, raw, "cancel")
        val applying = launch(start = CoroutineStart.UNDISPATCHED) { transaction.apply(raw, "cancel") }
        withTimeout(5_000) { entered.await() }
        applying.cancelAndJoin()

        assertTrue(applying.isCancelled)
        assertEquals(listOf("first"), visited.toList())
        assertEquals(0, publishes)
        assertTrue(ledger.entries.none { it.toolCallId == "cancel" && it.outcome != "pending" })
    }

    // Uses the same awaited command as SettingMcpPage; assertions cover its durable owner.
    private fun settingsPageTransaction(
        vm: SettingVM,
        ledger: CasTestFixtures.FakeCasLedger,
        preflight: McpConnectPreflight,
    ) = McpImportTransaction(
        preflight, ledger,
        existingServerNames = { vm.settings.value.mcpServers.map { it.commonOptions.name }.toSet() },
        publish = vm::importMcpServers,
    )

    private fun preflight(block: suspend (McpImportCandidate) -> List<String>) = object : McpConnectPreflight {
        override suspend fun connectAndListTools(server: McpImportCandidate) = block(server)
    }

    private suspend fun approve(transaction: McpImportTransaction, raw: String, session: String) {
        val ready = transaction.prepare(raw) as McpImportPreparation.Ready
        transaction.approve(ready.preview, session)
    }

    private suspend fun durableServers(): List<McpServerConfig> {
        val raw = fixture.dataStore.data.first()[PreferencesKeys.MCP_SERVERS] ?: return emptyList()
        return JsonInstant.decodeFromString(ListSerializer(McpServerConfig.serializer()), raw)
    }

    private fun batch(vararg servers: Pair<String, String>): String = buildJsonObject {
        put("mcpServers", buildJsonObject {
            servers.forEach { (name, url) -> put(name, buildJsonObject { put("type", "streamable_http"); put("url", url) }) }
        })
    }.toString()

    private fun localMcpServer(methods: MutableList<String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                try {
                    val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    val method = request["method"]!!.jsonPrimitive.content
                    methods += method
                    val id = request["id"]
                    val body = if (id == null) "" else buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", id)
                        put("result", when (method) {
                            "initialize" -> buildJsonObject {
                                put("protocolVersion", request["params"]!!.jsonObject["protocolVersion"]!!)
                                put("capabilities", Json.parseToJsonElement("""{"tools":{}}"""))
                                put("serverInfo", Json.parseToJsonElement("""{"name":"settings-fixture","version":"1.0"}"""))
                            }
                            "tools/list" -> Json.parseToJsonElement("""{"tools":[]}""")
                            else -> JsonObject(emptyMap())
                        })
                    }.toString()
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
                } finally { exchange.close() }
            }
            start()
        }
}
