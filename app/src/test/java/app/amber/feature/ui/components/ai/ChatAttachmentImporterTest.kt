package app.amber.feature.ui.components.ai

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.net.toFile
import androidx.core.net.toUri
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.dao.ManagedFileDAO
import app.amber.agent.data.db.entity.ManagedFileEntity
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.repository.FilesRepository
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.ui.hooks.ChatInputAttachmentImportStatus
import app.amber.feature.ui.hooks.ChatInputAttachmentKind
import app.amber.feature.ui.hooks.ChatInputState
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver

/** Uses provider metadata and real managed files; paused reads use Robolectric's stream registry. */
class ChatAttachmentImporterTest : DurableRuntimeTestBase() {
    private lateinit var appScope: AppScope
    private lateinit var importScope: CoroutineScope
    private lateinit var state: ChatInputState
    private lateinit var filesManager: FilesManager
    private lateinit var importer: ChatAttachmentImporter
    private lateinit var source: File
    private lateinit var provider: PausableSourceProvider
    private val errors = mutableListOf<String>()
    private var completedImports = 0
    private lateinit var initialUploads: Set<String>

    @Before
    fun setUpImporter() {
        appScope = AppScope()
        importScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        filesManager = FilesManager(context, FilesRepository(database.managedFileDao()), appScope)
        state = ChatInputState().apply { bindToConversation("first") }
        importer = ChatAttachmentImporter(state, filesManager, context, importScope, "read failed", { errors.add(it) })
        source = File.createTempFile("composer-import-", ".txt", context.cacheDir).apply {
            writeText("actual attachment bytes")
        }
        provider = PausableSourceProvider(source).also {
            it.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
            ShadowContentResolver.registerProviderInternal(AUTHORITY, it)
        }
        initialUploads = uploadNames()
    }

    @After
    fun tearDownImporter() = runBlocking {
        provider.resumeRead.countDown()
        importScope.cancel()
        importScope.coroutineContext[Job]!!.children.toList().joinAll()
        awaitCleanup()
        appScope.cancel()
        uploadDirectory().listFiles().orEmpty()
            .filter { it.name !in initialUploads }
            .forEach { it.delete() }
        source.delete()
        Unit
    }

    @Test
    fun successfulCopyTransfersItsFileAndManagedRowToTheDraft() = runBlocking {
        importDocument().join()
        val attachment = state.attachmentImports.single()
        assertEquals(ChatInputAttachmentImportStatus.READY, attachment.status)
        val part = attachment.part as UIMessagePart.Document
        val copiedFile = part.url.toUri().toFile()
        assertEquals(source.readText(), copiedFile.readText())
        assertTrue(copiedFile != source)
        assertEquals(1, database.managedFileDao().listByFolder(FileFolders.UPLOAD).first().size)
        assertEquals(1, completedImports)
        assertTrue(errors.isEmpty())

        // Once the draft accepts the URI, cancelling the importing scope cannot reclaim it.
        importScope.cancel()
        awaitCleanup()
        assertTrue(copiedFile.exists())
        assertEquals(listOf(copiedFile.toUri()), state.drainAttachmentFilesForDiscard())
        filesManager.deleteChatFiles(listOf(copiedFile.toUri())).join()
        assertNoNewUploads()
    }

    @Test
    fun removingTheTokenDuringCopyReclaimsOnlyTheUnacceptedPrivateFile() = runBlocking {
        pauseSourceCopy()
        val job = importDocument()
        awaitCopyStarted()
        state.removeAttachmentImport(state.attachmentImports.single().id)
        provider.resumeRead.countDown()
        job.join()
        awaitCleanup()

        assertTrue(state.messageContent.isEmpty())
        assertTrue(state.attachmentImports.isEmpty())
        assertEquals(0, completedImports)
        assertTrue(source.exists())
        assertNoNewUploads()
    }

    @Test
    fun aLateCopyCannotAttachToTheNextConversation() = runBlocking {
        pauseSourceCopy()
        val job = importDocument()
        awaitCopyStarted()
        state.bindToConversation("next")
        state.setMessageText("next draft")
        provider.resumeRead.countDown()
        job.join()
        awaitCleanup()

        assertEquals("next draft", state.textContent.text.toString())
        assertTrue(state.messageContent.isEmpty())
        assertTrue(state.attachmentImports.isEmpty())
        assertEquals(0, completedImports)
        assertTrue(errors.isEmpty())
        assertNoNewUploads()
    }

    @Test
    fun cancellationWaitsForTheRealCopyThenReclaimsItsPrivateFile() = runBlocking {
        pauseSourceCopy()
        val job = importDocument()
        awaitCopyStarted()
        job.cancel()
        assertFalse(job.isCompleted)
        assertTrue(source.exists())
        provider.resumeRead.countDown()
        job.join()
        awaitCleanup()

        assertTrue(job.isCancelled)
        assertEquals(ChatInputAttachmentImportStatus.FAILED, state.attachmentImports.single().status)
        assertTrue(state.messageContent.isEmpty())
        assertEquals(0, completedImports)
        assertNoNewUploads()
    }

    @Test
    fun failedCopyRetainsItsRetryTokenWithoutLeakingAnUpload() = runBlocking {
        provider.failOpen = true
        importDocument().join()
        val failed = state.attachmentImports.single()
        assertEquals(ChatInputAttachmentImportStatus.FAILED, failed.status)
        assertEquals(1, errors.size)
        assertNoNewUploads()

        provider.failOpen = false
        importer.retryAttachmentImport("first", failed)
        importScope.coroutineContext[Job]!!.children.toList().joinAll()
        val ready = state.attachmentImports.single()
        assertEquals(failed.id, ready.id)
        assertEquals(ChatInputAttachmentImportStatus.READY, ready.status)
        val part = ready.part as UIMessagePart.Document
        assertEquals(source.readText(), part.url.toUri().toFile().readText())
        assertEquals(1, database.managedFileDao().listByFolder(FileFolders.UPLOAD).first().size)
    }

    @Test
    fun longPasteUsesTheSameDraftOwnershipAfterMaterialization() = runBlocking {
        val text = "pasted text\n".repeat(100)
        importer.importPastedTextFile("first", text)
        importScope.coroutineContext[Job]!!.children.toList().joinAll()
        val ready = state.attachmentImports.single()
        val part = ready.part as UIMessagePart.Document
        assertEquals(ChatInputAttachmentImportStatus.READY, ready.status)
        assertEquals(text, part.url.toUri().toFile().readText())
        importScope.cancel()
        awaitCleanup()
        assertTrue(part.url.toUri().toFile().exists())
        filesManager.deleteChatFiles(state.drainAttachmentFilesForDiscard()).join()
        assertNoNewUploads()
    }

    @Test
    fun replacingAReadyCopyReleasesItsFileAndRowButPreservesTheReplacementAttachment() = runBlocking {
        importDocument().join()
        val oldPart = state.attachmentImports.single().part as UIMessagePart.Document
        val oldUri = oldPart.url.toUri()
        val oldPath = "${FileFolders.UPLOAD}/${oldUri.toFile().name}"
        val historical = filesManager.createChatTextFile("historical attachment")
        val historicalUri = historical.url.toUri()
        val historicalPath = "${FileFolders.UPLOAD}/${historicalUri.toFile().name}"
        val replacement = listOf(UIMessagePart.Text("historical message"), historical)

        // Same order as the timeline edit action: edit identity, then draft replacement.
        state.editingMessage = kotlin.uuid.Uuid.random()
        val released = state.setContents(replacement)
        assertEquals(listOf(oldUri), released)
        filesManager.deleteChatFiles(released).join()

        assertFalse(oldUri.toFile().exists())
        assertEquals(null, database.managedFileDao().getByPath(oldPath))
        assertTrue(historicalUri.toFile().exists())
        assertTrue(database.managedFileDao().getByPath(historicalPath) != null)
        assertEquals(replacement, state.getContents())
        assertTrue(state.attachmentImports.isEmpty())
        assertTrue(state.drainAttachmentFilesForDiscard().isEmpty())
    }

    @Test
    fun replacementThatRetainsTheReadyCopyDoesNotReleaseItsFileOrRow() = runBlocking {
        importDocument().join()
        val part = state.attachmentImports.single().part as UIMessagePart.Document
        val uri = part.url.toUri()
        val path = "${FileFolders.UPLOAD}/${uri.toFile().name}"
        state.editingMessage = kotlin.uuid.Uuid.random()
        val replacement = listOf(UIMessagePart.Text("same attachment, new owner"), part)

        val released = state.setContents(replacement)
        assertEquals(emptyList<Uri>(), released)
        filesManager.deleteChatFiles(released).join()

        assertTrue(uri.toFile().exists())
        assertTrue(database.managedFileDao().getByPath(path) != null)
        assertEquals(replacement, state.getContents())
        assertTrue(state.attachmentImports.isEmpty())
        assertTrue(state.drainAttachmentFilesForDiscard().isEmpty())
    }

    @Test
    fun replacingTheDraftWhileItsRealCopyRunsRejectsAndReclaimsTheLateResult() = runBlocking {
        val historical = filesManager.createChatTextFile("saved historical bytes")
        val historicalUri = historical.url.toUri()
        val historicalPath = "${FileFolders.UPLOAD}/${historicalUri.toFile().name}"
        val replacement = listOf(UIMessagePart.Text("editing saved message"), historical)
        val replacementUploads = uploadNames()
        pauseSourceCopy()
        val job = importDocument()
        awaitCopyStarted()
        state.editingMessage = kotlin.uuid.Uuid.random()
        state.setContents(replacement)
        provider.resumeRead.countDown()
        job.join()
        awaitCleanup()

        assertEquals(replacement, state.getContents())
        assertTrue(state.attachmentImports.isEmpty())
        assertEquals(0, completedImports)
        assertTrue(source.exists())
        assertTrue(historicalUri.toFile().exists())
        assertEquals(replacementUploads, uploadNames())
        val rows = database.managedFileDao().listByFolder(FileFolders.UPLOAD).first()
        assertEquals(listOf(historicalPath), rows.map { it.relativePath })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun cancellingLongPasteDuringItsManagedWriteReclaimsTheMaterializedFile() = runBlocking {
        val insertStarted = CompletableDeferred<Unit>()
        val resumeInsert = CompletableDeferred<Unit>()
        val realDao = database.managedFileDao()
        val delayedDao = object : ManagedFileDAO by realDao {
            override suspend fun insert(file: ManagedFileEntity): Long {
                insertStarted.complete(Unit)
                resumeInsert.await()
                return realDao.insert(file)
            }
        }
        val pasteImporter = ChatAttachmentImporter(
            state,
            FilesManager(context, FilesRepository(delayedDao), appScope),
            context,
            importScope,
            "read failed",
            { errors.add(it) },
        )
        try {
            pasteImporter.importPastedTextFile("first", "materialized before the managed row")
            withTimeout(10_000) { insertStarted.await() }
            assertEquals(1, (uploadNames() - initialUploads).size)
            val pasteJob = importScope.coroutineContext[Job]!!.children.single()
            pasteJob.cancel()
            assertFalse(pasteJob.isCompleted)
            resumeInsert.complete(Unit)
            pasteJob.join()
            awaitCleanup()

            assertTrue(pasteJob.isCancelled)
            assertTrue(state.messageContent.isEmpty())
            assertTrue(state.attachmentImports.isEmpty())
            assertTrue(errors.isEmpty())
            assertNoNewUploads()
        } finally {
            resumeInsert.complete(Unit)
        }
    }

    private fun importDocument(): Job = importer.importAttachment(
        ownerConversationId = "first",
        sourceUri = Uri.parse("content://$AUTHORITY/source"),
        kind = ChatInputAttachmentKind.DOCUMENT,
        onComplete = { completedImports++ },
    )

    private fun pauseSourceCopy() {
        // ContentResolver rebuilds AssetFileDescriptor, discarding a provider's stream subclass.
        // Register the real file stream at Robolectric's actual openInputStream boundary instead.
        shadowOf(context.contentResolver).registerInputStreamSupplier(
            Uri.parse("content://$AUTHORITY/source"),
        ) { provider.openPausedInputStream() }
    }

    private suspend fun awaitCopyStarted() = withContext(Dispatchers.IO) {
        assertTrue("The actual content stream was not read", provider.readStarted.await(10, TimeUnit.SECONDS))
    }

    private suspend fun awaitCleanup() {
        appScope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    private fun uploadDirectory(): File = context.filesDir.resolve(FileFolders.UPLOAD)
    private fun uploadNames(): Set<String> = uploadDirectory().listFiles()?.map { it.name }?.toSet().orEmpty()

    private suspend fun assertNoNewUploads() {
        assertEquals(initialUploads, uploadNames())
        assertTrue(database.managedFileDao().listByFolder(FileFolders.UPLOAD).first().isEmpty())
    }

    private class PausableSourceProvider(private val source: File) : ContentProvider() {
        var failOpen = false
        val readStarted = CountDownLatch(1)
        val resumeRead = CountDownLatch(1)

        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map<String, Any?> { column -> when (column) {
                    OpenableColumns.DISPLAY_NAME -> source.name
                    OpenableColumns.SIZE -> source.length()
                    else -> null
                } }.toTypedArray())
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            if (failOpen) throw FileNotFoundException("Source is no longer available")
            return AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

        fun openPausedInputStream(): FileInputStream = object : FileInputStream(source) {
            override fun read(buffer: ByteArray): Int {
                readStarted.countDown()
                check(resumeRead.await(10, TimeUnit.SECONDS)) { "Test did not release the content stream" }
                return super.read(buffer)
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "test.composer.attachment.source"
    }
}
