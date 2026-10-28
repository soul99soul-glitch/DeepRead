package app.amber.feature.ui.pages.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ManagedFileEntity
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Capability
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.NativePathPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.sync.core.DeviceBoundBackupKey
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncRestorePartialCommitException
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.google.GoogleDriveAppDataClient
import app.amber.core.sync.google.GoogleDriveSyncRepository
import app.amber.core.sync.google.GoogleOAuthConfigGate
import app.amber.core.sync.local.LocalBackupRepository
import app.amber.core.sync.provider.LocalFolderSyncProvider
import app.amber.core.sync.provider.PersistedFolderStore
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.sync.provider.SyncSnapshotManifest
import app.amber.core.sync.provider.WebDavSyncProvider
import app.amber.core.sync.provider.encodeSnapshotManifest
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.UiState
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.pages.chat.toChatTheme
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P7-02 两阶段恢复：verify 后取消（dismissVerifiedRestore）必须清理 App
 * 自己创建的归档临时副本 —— 本地导入的 cacheDir/sync-local 拷贝与
 * provider 下载的 cacheDir 拷贝，同时绝不删除用户原始选择的文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupVMRestoreCleanupTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var archiveManager: SyncArchiveManager
    private lateinit var localBackupRepository: LocalBackupRepository
    private lateinit var testRoot: File
    private lateinit var appScope: AppScope
    private lateinit var database: AppDatabase
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val crypto = SyncCrypto(nativeEnabled = false)
    private val server = FakeWebDavServer()
    private val viewModels = mutableListOf<BackupVM>()
    private val restoreWriteGate = SyncRestoreWriteGate()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "backup-vm-cleanup-${System.nanoTime()}").apply { mkdirs() }

        appScope = AppScope()
        val dataStore = PreferenceDataStoreFactory.create(scope = appScope) {
            File(testRoot, "settings.preferences_pb")
        }
        val secretStore = SecretStore(
            backend = object : SecretStoreBackend {
                private val map = mutableMapOf<String, String>()
                override fun get(key: String): String? = map[key]
                override fun put(key: String, value: String) {
                    map[key] = value
                }

                override fun remove(key: String) {
                    map.remove(key)
                }

                override fun keys(): Set<String> = map.keys.toSet()
            },
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
            },
        )
        val secretRedactor = SecretRedactor(secretStore)
        settingsStore = SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, appScope),
            searchPrefs = SearchPrefs(dataStore, appScope, secretStore),
            agentPrefs = AgentPrefs(dataStore, appScope),
            providerPrefs = ProviderPrefs(dataStore, appScope, secretStore),
            chatPrefs = ChatPrefs(dataStore, appScope, secretStore),
            extensionPrefs = ExtensionPrefs(dataStore, appScope, secretStore),
            scope = appScope,
            secretRedactor = secretRedactor,
        )
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        settingsStore.update {
            it.copy(
                webDavConfig = it.webDavConfig.copy(
                    url = "https://dav.example.com",
                    username = "webdav-user",
                    password = "webdav-pass",
                    path = "amber_agent_backups",
                ),
            )
        }

        val nativePathPrefs = NativePathPrefs(dataStore, appScope)
        nativePathPrefs.update { it.copy(syncCrypto = false) }
        withTimeout(5_000) { nativePathPrefs.flow.first { !it.syncCrypto } }

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_fts(
                text TEXT,
                node_id TEXT,
                message_id TEXT,
                conversation_id TEXT,
                title TEXT,
                update_at TEXT
            )
            """.trimIndent()
        )
        recreateTitleFtsTable()
        val filesManager = FilesManager(
            context = context,
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
            restoreWriteGate = restoreWriteGate,
        )
        archiveManager = SyncArchiveManager(
            context = context,
            settingsStore = settingsStore,
            database = database,
            messageFtsManager = app.amber.agent.data.db.fts.MessageFtsManager(database),
            filesManager = filesManager,
            webMountOAuthTokenStore = WebMountOAuthTokenStore(context),
            openAICodexAuthStore = OpenAICodexAuthStore(context),
            googleGeminiAuthStore = GoogleGeminiAuthStore(context),
            json = JsonInstant,
            nativePathPrefs = nativePathPrefs,
            secretRedactor = secretRedactor,
            deviceBoundBackupKey = DeviceBoundBackupKey(secretStore),
            restoreWriteGate = restoreWriteGate,
        )
        localBackupRepository = LocalBackupRepository(context, archiveManager)
    }

    @After
    fun tearDown() {
        runBlocking {
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            if (::appScope.isInitialized) appScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::database.isInitialized) database.close()
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): BackupVM {
        val dataStore = PreferenceDataStoreFactory.create(scope = appScope) {
            File(testRoot, "flags-${viewModels.size}.preferences_pb")
        }
        val capabilityFlags = app.amber.core.settings.CapabilityFlags(dataStore)
        runBlocking { capabilityFlags.setEnabled(Capability.SyncProviderV2, true) }
        val engine = MockEngine { request -> server.handle(this, request) }
        return BackupVM(
            settingsStore = settingsStore,
            localBackupRepository = localBackupRepository,
            googleDriveSyncRepository = GoogleDriveSyncRepository(
                context = context,
                driveClient = GoogleDriveAppDataClient(OkHttpClient(), JsonInstant),
                archiveManager = archiveManager,
            ),
            googleOAuthConfigGate = GoogleOAuthConfigGate(context),
            archiveManager = archiveManager,
            webDavSyncProvider = WebDavSyncProvider(
                context = context,
                settingsStore = settingsStore,
                archiveManager = archiveManager,
                json = JsonInstant,
                httpClient = HttpClient(engine),
            ),
            localFolderSyncProvider = LocalFolderSyncProvider(
                context = context,
                archiveManager = archiveManager,
                folderStore = PersistedFolderStore(context),
                json = JsonInstant,
            ),
            persistedFolderStore = PersistedFolderStore(context),
            capabilityFlags = capabilityFlags,
        ).also(viewModels::add)
    }

    @Test
    fun `verify then dismiss deletes local temp copy but keeps user file`() = runBlocking {
        val vm = buildViewModel()
        val archiveBytes = archiveManager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        // 用户原始选择的文件（不在 cacheDir/sync-local 下）。
        val userFile = File(testRoot, "user-selected.amberbackup").apply { writeBytes(archiveBytes) }

        vm.restoreSession.inspectImport(Uri.fromFile(userFile))
        vm.restoreSession.verify("test-passphrase")
        awaitUntil({ vm.restoreSession.state.value.verification != null }, "本地验证未完成")

        val verification = vm.restoreSession.state.value.verification!!
        val tempCopy = verification.archiveFile
        assertTrue("verify 必须在 cacheDir/sync-local 创建副本", localBackupRepository.isOwnedTempCopy(tempCopy))
        assertTrue("副本应存在", tempCopy.exists())

        vm.restoreSession.dismiss()

        assertFalse("verify 后取消必须删除本地临时副本", tempCopy.exists())
        assertTrue("用户原始选择的文件绝不能删除", userFile.exists())
        assertNull(vm.restoreSession.state.value.verification)
        // cacheDir/sync-local 里不留任何 amber-import-* 残留。
        val syncLocalDir = File(context.cacheDir, "sync-local")
        assertTrue(
            "sync-local 不应残留导入副本",
            syncLocalDir.listFiles().orEmpty().none { it.name.startsWith("amber-import-") },
        )
    }

    @Test
    fun `verify then dismiss deletes provider download copy`() = runBlocking {
        val vm = buildViewModel()
        awaitUntil({ vm.providerV2Enabled.value }, "SyncProviderV2 未启用")

        val archiveBytes = archiveManager.createArchive(
            SyncExportRequest(mode = SyncMode.FULL, passphrase = "test-passphrase")
        )
        server.files["amber_agent_backups/snap-1.amberbackup"] = archiveBytes
        server.files["amber_agent_backups/snap-1.snapshot.json"] = encodeSnapshotManifest(
            JsonInstant,
            SyncSnapshotManifest(
                snapshotId = "snap-1",
                createdAt = 42L,
                mode = SyncMode.FULL,
                sizeBytes = archiveBytes.size.toLong(),
                contentSha256 = crypto.sha256(archiveBytes),
            ),
        ).toByteArray()

        vm.downloadWebDavSnapshot(
            SyncSnapshot(
                providerId = "webdav",
                snapshotId = "snap-1",
                name = "snap-1.amberbackup",
                manifest = SyncSnapshotManifest(snapshotId = "snap-1", createdAt = 42L, mode = SyncMode.FULL),
            )
        )
        awaitUntil({ vm.restoreSession.state.value.preview != null }, "WebDAV 下载未完成")

        vm.restoreSession.verify("test-passphrase")
        awaitUntil({ vm.restoreSession.state.value.verification != null }, "provider 验证未完成")

        val downloaded = vm.restoreSession.state.value.verification!!.archiveFile
        assertTrue("下载副本应存在", downloaded.exists())
        assertTrue(
            "下载副本应在 App 缓存目录，实际：${downloaded.absolutePath}",
            downloaded.absolutePath.contains(File.separator + "sync-webdav" + File.separator),
        )

        vm.restoreSession.dismiss()

        assertFalse("取消后必须删除 provider 下载副本", downloaded.exists())
        assertNull(vm.restoreSession.state.value.verification)
    }

    @Test
    fun `provider A cancel then local B apply owns only B source and completion metadata`() = runBlocking {
        val vm = buildViewModel()
        awaitUntil({ vm.providerV2Enabled.value }, "SyncProviderV2 未启用")
        val archiveA = archiveManager.createArchive(SyncExportRequest(SyncMode.FULL, "source-a-pass"))
        val snapshotA = publishSnapshot("source-a", archiveA)
        vm.downloadWebDavSnapshot(snapshotA)
        awaitUntil({ vm.restoreSession.state.value.preview != null }, "A 下载未完成")
        vm.restoreSession.verify("source-a-pass")
        awaitUntil({ vm.restoreSession.state.value.verification != null }, "A 验证未完成")
        val verifiedA = vm.restoreSession.state.value.verification!!
        val sourceA = verifiedA.archiveFile
        vm.restoreSession.dismiss()
        assertFalse(sourceA.exists())
        assertFalse(verifiedA.payloadFile.exists())
        assertFalse(vm.restoreSession.state.value.source is BackupRestoreSource.Provider)
        assertFalse(vm.restoreSession.state.value.source is BackupRestoreSource.Google)
        assertNull(vm.restoreSession.state.value.source)

        settingsStore.update { it.copy(syncSettings = it.syncSettings.copy(deviceId = "archive-b")) }
        val upload = seedUpload("source-b.txt", "archived B bytes")
        val userFile = userArchive("source-b", "source-b-pass")
        upload.writeText("live bytes before B apply")
        vm.restoreSession.inspectImport(Uri.fromFile(userFile))
        awaitUntil({ vm.restoreSession.state.value.preview?.manifest?.deviceId == "archive-b" }, "B 检查未完成")
        vm.restoreSession.verify("source-b-pass")
        awaitUntil({ vm.restoreSession.state.value.verification != null }, "B 验证未完成")
        val verifiedB = vm.restoreSession.state.value.verification!!
        assertNotSame(verifiedA, verifiedB)
        assertTrue(vm.restoreSession.state.value.source is BackupRestoreSource.Local)
        assertEquals("archive-b", verifiedB.preview.manifest.deviceId)
        assertFalse(vm.restoreSession.state.value.source is BackupRestoreSource.Provider)
        vm.restoreSession.apply(preserveConversations = false, preserveGenMedia = false)
        awaitUntil({ vm.restoreSession.state.value.operation !is UiState.Loading }, "B apply 未完成")
        assertTrue("B must actually commit through the real archive manager", vm.restoreSession.state.value.operation is UiState.Success)
        assertEquals("archived B bytes", upload.readText())
        assertTrue(database.managedFileDao().listByFolder(FileFolders.UPLOAD).first()
            .any { it.relativePath == "${FileFolders.UPLOAD}/${upload.name}" })
        assertTrue(settingsStore.settingsFlow.value.syncSettings.lastDownloadAt > 0L)
        assertEquals(verifiedB.preview.manifest.deviceLabel,
            settingsStore.settingsFlow.value.syncSettings.lastBackupDeviceLabel)
        assertNull(vm.restoreSession.state.value.verification)
        assertNull(vm.restoreSession.state.value.source)
        assertNull(vm.restoreSession.state.value.preview)
        assertFalse(verifiedB.archiveFile.exists())
        assertFalse(verifiedB.payloadFile.exists())
        assertTrue("user-selected archive must survive successful apply", userFile.exists())
    }

    @Test
    fun `partial commit retains encrypted source for fresh verification after selected URI disappears`() = runBlocking {
        val vm = buildViewModel()
        val upload = seedUpload("retry-source.txt", "archived retry bytes")
        val userFile = userArchive("retry-source", "retry-passphrase")
        vm.restoreSession.inspectImport(Uri.fromFile(userFile))
        awaitUntil({ vm.restoreSession.state.value.preview != null }, "检查未完成")
        vm.restoreSession.verify("retry-passphrase")
        awaitUntil({ vm.restoreSession.state.value.verification != null }, "验证未完成")
        val first = vm.restoreSession.state.value.verification!!
        val retainedArchive = first.archiveFile
        // The picker source is external to the restore session; moving it must
        // not invalidate the encrypted copy the app has explicitly retained.
        val movedUserFile = File(testRoot, "externally-moved.amberbackup")
        assertTrue(userFile.renameTo(movedUserFile))
        try {
            upload.writeText("before failed apply")
            database.openHelper.writableDatabase.execSQL("DROP TABLE message_fts")
            database.openHelper.writableDatabase.execSQL("DROP TABLE conversation_title_fts")
            vm.restoreSession.apply(preserveConversations = false, preserveGenMedia = false)
            awaitUntil({ vm.restoreSession.state.value.operation is UiState.Error }, "未得到真实post-commit失败")
            assertTrue((vm.restoreSession.state.value.operation as UiState.Error).error is SyncRestorePartialCommitException)
            assertEquals("archived retry bytes", upload.readText())
            assertNull(vm.restoreSession.state.value.verification)
            assertFalse(first.payloadFile.exists())
            assertFalse(first.encryptedPayloadFile.exists())
            assertTrue("the encrypted source must survive apply failure", retainedArchive.exists())
            val reuseError = runCatching { archiveManager.applyRestore(first, SyncRestoreRequest("retry-passphrase")) }.exceptionOrNull()
            assertTrue("consumed verification cannot be reused", reuseError is IllegalArgumentException)
            recreateMessageFtsTable()
            recreateTitleFtsTable()

            vm.restoreSession.verify("retry-passphrase")
            awaitUntil({ vm.restoreSession.state.value.verification != null || vm.restoreSession.state.value.operation is UiState.Error }, "fresh verify 未结束")
            assertTrue("fresh verify must use the retained encrypted copy, not the unavailable picker URI",
                vm.restoreSession.state.value.verification != null)
            val second = vm.restoreSession.state.value.verification!!
            assertNotSame(first, second)
            vm.restoreSession.apply(preserveConversations = false, preserveGenMedia = false)
            awaitUntil({ vm.restoreSession.state.value.operation !is UiState.Loading }, "retry apply 未完成")
            assertTrue(vm.restoreSession.state.value.operation is UiState.Success)
            assertEquals("archived retry bytes", upload.readText())
            assertFalse(retainedArchive.exists())
            assertTrue("external source bytes remain owned by the user", movedUserFile.exists())
        } finally {
            vm.restoreSession.dismiss()
            assertTrue("restore the external user source even after a failed oracle", movedUserFile.renameTo(userFile))
        }
    }

    @Test
    fun `successful apply shows restart even when no frame observes transient Loading`() = runBlocking {
        val vm = buildViewModel()
        val upload = seedUpload("ui-completion.txt", "archived UI bytes")
        val userFile = userArchive("ui-completion", "ui-completion-pass")
        vm.restoreSession.inspectImport(Uri.fromFile(userFile))
        awaitUntil({ vm.restoreSession.state.value.preview != null }, "检查未完成")
        vm.restoreSession.verify("ui-completion-pass")
        awaitUntil({ vm.restoreSession.state.value.verification != null }, "验证未完成")
        showBackupPage(vm)
        compose.onNodeWithText(context.getString(R.string.backup_overwrite)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_page_restart_desc)).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText(context.getString(R.string.backup_overwrite)).performClick()
            awaitUntil({ vm.restoreSession.state.value.operation !is UiState.Loading }, "真实apply未完成")
            assertTrue("UI oracle requires a real durable restore", vm.restoreSession.state.value.operation is UiState.Success)
            assertEquals("archived UI bytes", upload.readText())
            assertTrue(settingsStore.settingsFlow.value.syncSettings.lastDownloadAt > 0L)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.backup_page_restart_desc)).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            vm.restoreSession.dismiss()
        }
        Unit
    }

    private fun showBackupPage(vm: BackupVM) {
        val tokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))
        compose.setContent {
            CompositionLocalProvider(
                LocalAmberTokens provides tokens,
                LocalChatTheme provides tokens.toChatTheme(),
                LocalNavController provides Navigator(mutableListOf(Screen.SessionHome, Screen.Backup)),
            ) {
                MaterialTheme { BackupPage(vm) }
            }
        }
        compose.waitForIdle()
    }

    private suspend fun seedUpload(name: String, body: String): File {
        val file = File(context.filesDir, "${FileFolders.UPLOAD}/$name").apply { parentFile!!.mkdirs(); writeText(body) }
        database.managedFileDao().insert(ManagedFileEntity(
            folder = FileFolders.UPLOAD,
            relativePath = "${FileFolders.UPLOAD}/$name",
            displayName = name,
            mimeType = "text/plain",
            sizeBytes = file.length(),
            createdAt = 1L,
            updatedAt = 1L,
        ))
        return file
    }

    private suspend fun userArchive(name: String, passphrase: String): File =
        File(testRoot, "$name.amberbackup").apply {
            writeBytes(archiveManager.createArchive(SyncExportRequest(SyncMode.FULL, passphrase)))
        }

    private fun publishSnapshot(id: String, bytes: ByteArray): SyncSnapshot {
        val manifest = SyncSnapshotManifest(
            snapshotId = id,
            createdAt = 42L,
            mode = SyncMode.FULL,
            sizeBytes = bytes.size.toLong(),
            contentSha256 = crypto.sha256(bytes),
        )
        server.files["amber_agent_backups/$id.amberbackup"] = bytes
        server.files["amber_agent_backups/$id.snapshot.json"] = encodeSnapshotManifest(JsonInstant, manifest).toByteArray()
        return SyncSnapshot(providerId = "webdav", snapshotId = id, name = "$id.amberbackup", manifest = manifest)
    }

    private fun recreateMessageFtsTable() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)"
        )
    }

    private fun recreateTitleFtsTable() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS conversation_title_fts(title TEXT, conversation_id TEXT, update_at TEXT)"
        )
    }

    private suspend fun awaitUntil(condition: () -> Boolean, what: String) {
        withTimeout(30_000) {
            while (!condition()) {
                delay(25)
            }
        }
    }

    /** 内存版假 WebDAV 服务器：GET 足够驱动下载 + sidecar 预览。 */
    private class FakeWebDavServer {
        val files = mutableMapOf<String, ByteArray>()

        suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
            val path = request.url.encodedPath.trimStart('/')
            return when (request.method) {
                HttpMethod("PROPFIND") -> scope.respond(emptyMultistatus(), HttpStatusCode.MultiStatus)
                HttpMethod.Get -> {
                    val body = files[path]
                    if (body == null) {
                        scope.respond("", HttpStatusCode.NotFound)
                    } else {
                        scope.respond(body, HttpStatusCode.OK)
                    }
                }
                else -> scope.respond("", HttpStatusCode.MethodNotAllowed)
            }
        }

        private fun emptyMultistatus(): String =
            """<?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/amber_agent_backups/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>amber_agent_backups</D:displayname>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>""".trimIndent()
    }
}
