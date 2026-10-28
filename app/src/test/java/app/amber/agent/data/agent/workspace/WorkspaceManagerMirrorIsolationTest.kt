package app.amber.agent.data.agent.workspace

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import app.amber.feature.workspace.WorkspaceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceManagerMirrorIsolationTest {
    private lateinit var context: Context
    private lateinit var manager: WorkspaceManager
    private lateinit var provider: WorkspaceTreeProvider

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        manager = WorkspaceManager(context)
        manager.mirrorDir.deleteRecursively()

        provider = WorkspaceTreeProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        manager.mirrorDir.deleteRecursively()
    }

    @Test
    fun switchingOrClearingWorkspaceCannotReuseThePreviousMirror() = runBlocking {
        val unboundUpload = manager.mirrorDir.resolve("uploads/shared.txt").apply {
            parentFile?.mkdirs()
            writeText("shared")
        }

        manager.setWorkspace(treeUri("root-a:"))
        assertTrue("first workspace keeps uploads staged before selection", unboundUpload.exists())
        assertTrue(unboundUpload.delete())
        assertTrue(unboundUpload.parentFile?.delete() == true)

        val workspaceAFile = manager.mirrorDir.resolve("only-in-a.txt").apply { writeText("a") }
        manager.setWorkspace(treeUri("root-b:"))
        assertNotNull("switching must flush workspace A before clearing its mirror", provider.findByName("only-in-a.txt"))
        assertFalse("switching workspace must clear workspace A files", workspaceAFile.exists())
        assertTrue(manager.mirrorDir.isDirectory)

        val lease = manager.acquireTerminalMirror(refreshFromWorkspace = false)
        val secondLeaseError = runCatching {
            manager.acquireTerminalMirror(refreshFromWorkspace = false)
        }.exceptionOrNull()
        assertTrue(secondLeaseError?.message.orEmpty().contains("already using"))
        val switchError = runCatching { manager.setWorkspace(treeUri("root-a:")) }.exceptionOrNull()
        assertTrue(switchError?.message.orEmpty().contains("active terminal"))
        lease.release(syncBack = false)

        val workspaceBFile = manager.mirrorDir.resolve("only-in-b.txt").apply { writeText("b") }
        manager.clearWorkspace()
        assertNotNull("clearing must flush workspace B before clearing its mirror", provider.findByName("only-in-b.txt"))
        assertFalse("clearing workspace must clear workspace B files", workspaceBFile.exists())
        assertTrue(manager.mirrorDir.isDirectory)
    }

    @Test
    fun cancelledLeaseOwnerReleasesOnceAndAllowsTheNextTerminal(): Unit = runBlocking {
        val acquired = CompletableDeferred<Unit>()
        val owner = launch {
            val lease = manager.acquireTerminalMirror(refreshFromWorkspace = false)
            acquired.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                lease.release(syncBack = false)
                lease.release(syncBack = false)
            }
        }
        acquired.await()

        owner.cancelAndJoin()

        manager.acquireTerminalMirror(false).release(syncBack = false)
        manager.acquireTerminalMirror(false).release(syncBack = false)
    }

    @Test
    fun refreshPreservesMirrorOnlyFilesAndActiveLeaseRejectsRefreshAndFlush() = runBlocking {
        manager.setWorkspace(treeUri("root-a:"))
        val local = manager.mirrorDir.resolve("mirror-only.txt").apply {
            parentFile?.mkdirs()
            writeText("local content")
        }

        manager.refreshMirrorFromWorkspace()

        assertTrue(local.exists())
        val lease = manager.acquireTerminalMirror(false)
        try {
            assertTrue(runCatching { manager.refreshMirrorFromWorkspace() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { manager.syncFromMirror() }.exceptionOrNull() is IllegalStateException)
        } finally {
            lease.release(syncBack = false)
        }
        manager.syncFromMirror()
        assertNotNull(provider.findByName("mirror-only.txt"))
    }

    @Test
    fun publicDocumentCommandsKeepBoundedReadEditAppendAndSearchContracts() = runBlocking {
        manager.setWorkspace(treeUri("root-a:"))

        manager.writeText("note.md", "first 中文\nsecond line")
        val capped = manager.readTextCapped("note.md", 5)
        assertTrue(capped.truncated)
        assertEquals("first", capped.content)
        manager.editText("note.md", "second", "updated", replaceAll = false)
        manager.writeText("note.md", "\nappended", append = true)

        assertEquals("first 中文\nupdated line\nappended", manager.readText("note.md"))
        val result = manager.search("updated").single()
        assertEquals("note.md", result.path)
        assertEquals(2, result.lineNumber)
        assertTrue(runCatching { manager.readBytesCapped("note.md", 3) }.isFailure)
    }

    @Test
    fun activeTerminalMirrorRejectsSafMutationsBeforeTheyCanBeAcknowledged() = runBlocking {
        manager.setWorkspace(treeUri("root-a:"))
        listOf("note.md", "edit.md", "blob.bin", "move.md", "delete.md").forEach {
            manager.writeText(it, "old")
        }
        val lease = manager.acquireTerminalMirror(refreshFromWorkspace = true)
        try {
            val failures = listOf(
                runCatching { manager.writeText("note.md", "new") }.exceptionOrNull(),
                runCatching { manager.editText("edit.md", "old", "edited", false) }.exceptionOrNull(),
                runCatching { manager.writeBytes("blob.bin", "new".toByteArray()) }.exceptionOrNull(),
                runCatching { manager.move("move.md", "moved.md") }.exceptionOrNull(),
                runCatching { manager.deleteFile("delete.md") }.exceptionOrNull(),
            )
            failures.forEachIndexed { index, failure ->
                assertTrue("SAF mutation $index must reject the active terminal owner; actual=$failure", failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("active terminal"))
            }
        } finally {
            lease.release(syncBack = true)
        }

        assertEquals("old", manager.readText("note.md"))
        manager.editText("note.md", "old", "accepted after job", false)
        assertEquals("accepted after job", manager.readText("note.md"))
    }

    @Test
    fun idleSafMutationsSurviveTheStaleMirrorFlushWhenClearingWorkspace() = runBlocking {
        manager.setWorkspace(treeUri("root-a:"))
        listOf("note.md", "edit.md", "blob.bin", "move.md", "delete.md").forEach {
            manager.writeText(it, "old")
        }
        manager.refreshMirrorFromWorkspace()
        manager.mirrorDir.resolve("local-only.txt").writeText("keep local output")

        manager.writeText("/workspace/note.md", "new")
        manager.editText("edit.md", "old", "edited", false)
        manager.writeBytes("blob.bin", "new binary".toByteArray())
        manager.move("move.md", "moved.md")
        assertTrue(manager.deleteFile("delete.md"))
        manager.clearWorkspace()

        assertEquals("new", provider.contents("note.md"))
        assertEquals("edited", provider.contents("edit.md"))
        assertEquals("new binary", provider.contents("blob.bin"))
        assertEquals("old", provider.contents("moved.md"))
        assertTrue("Moved source must not be recreated by the old mirror", provider.findByName("move.md") == null)
        assertTrue("Deleted file must not be recreated by the old mirror", provider.findByName("delete.md") == null)
        assertEquals("keep local output", provider.contents("local-only.txt"))
    }

    @Test
    fun movingSafDirectoryRelocatesPrivateTerminalOutputWithoutRecreatingItsOldPath() = runBlocking {
        manager.setWorkspace(treeUri("root-a:"))
        manager.writeText("source/saf.txt", "shared SAF file")
        val lease = manager.acquireTerminalMirror(refreshFromWorkspace = true)
        try {
            lease.directory.resolve("source/private/generated.txt").apply {
                parentFile?.mkdirs()
                writeText("unsynced terminal output")
            }
            lease.directory.resolve("unrelated.txt").writeText("keep unrelated output")
        } finally {
            lease.release(syncBack = false)
        }

        manager.move("source", "destination")

        assertEquals("unsynced terminal output", manager.mirrorDir.resolve("destination/private/generated.txt").takeIf { it.exists() }?.readText())
        assertFalse(manager.mirrorDir.resolve("source").exists())
        manager.syncFromMirror()
        assertEquals("shared SAF file", manager.readText("destination/saf.txt"))
        assertEquals("unsynced terminal output", manager.readText("destination/private/generated.txt"))
        assertEquals("keep unrelated output", manager.readText("unrelated.txt"))
        assertFalse(manager.list().any { it.name == "source" })
    }

    @Test
    fun movingSafDirectoryRejectsAnExistingPrivateMirrorDestinationBeforeChangingSaf() = runBlocking {
        manager.setWorkspace(treeUri("root-a:"))
        manager.writeText("source/saf.txt", "shared SAF file")
        manager.refreshMirrorFromWorkspace()
        manager.mirrorDir.resolve("destination/private.txt").apply {
            parentFile?.mkdirs()
            writeText("existing private output")
        }

        val failure = runCatching { manager.move("source", "destination") }.exceptionOrNull()

        assertTrue("A move must not discard private destination output", failure is IllegalArgumentException)
        assertEquals("shared SAF file", manager.readText("source/saf.txt"))
        assertEquals("existing private output", manager.mirrorDir.resolve("destination/private.txt").readText())
        assertFalse(manager.list().any { it.name == "destination" })
    }

    private fun treeUri(rootId: String): Uri =
        Uri.parse("content://$AUTHORITY/tree/${Uri.encode(rootId)}")

    private class WorkspaceTreeProvider : ContentProvider() {
        private data class Doc(val id: String, val displayName: String, val mimeType: String, val parentId: String? = null)

        private val docs = LinkedHashMap<String, Doc>()
        private val backingDir = File.createTempFile("workspace-switch-", "").apply {
            delete()
            mkdirs()
        }
        private val backingFiles = mutableMapOf<String, File>()
        private var nextId = 1

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = DocumentsContract.Document.MIME_TYPE_DIR

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: DEFAULT_COLUMNS
            val cursor = MatrixCursor(columns)
            if (uri.pathSegments.lastOrNull() == "children") {
                val parentId = DocumentsContract.getDocumentId(uri)
                docs.values.filter { it.parentId == parentId }.forEach { cursor.addRow(rowFor(it, columns)) }
            } else {
                val documentId = runCatching { DocumentsContract.getDocumentId(uri) }
                    .getOrElse { DocumentsContract.getTreeDocumentId(uri) }
                val doc = docs[documentId] ?: Doc(
                    id = documentId,
                    displayName = documentId.removeSuffix(":"),
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                )
                cursor.addRow(rowFor(doc, columns))
            }
            return cursor
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (extras == null) return null
            val parentUri = extras.getParcelable<Uri>(EXTRA_URI) ?: return null
            if (method == "android:renameDocument") {
                val id = DocumentsContract.getDocumentId(parentUri)
                val current = docs[id] ?: return null
                docs[id] = current.copy(displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty())
                return Bundle().apply { putParcelable(EXTRA_URI, parentUri) }
            }
            if (method == "android:deleteDocument") {
                val id = DocumentsContract.getDocumentId(parentUri)
                docs.remove(id)
                backingFiles.remove(id)?.delete()
                return Bundle()
            }
            if (method != METHOD_CREATE_DOCUMENT) return null
            val doc = Doc(
                id = "doc-${nextId++}",
                displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty(),
                mimeType = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty(),
                parentId = DocumentsContract.getDocumentId(parentUri),
            )
            docs[doc.id] = doc
            val uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, doc.id)
            return Bundle().apply { putParcelable(EXTRA_URI, uri) }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val documentId = DocumentsContract.getDocumentId(uri)
            docs[documentId] ?: throw FileNotFoundException(documentId)
            val file = backingFiles.getOrPut(documentId) {
                backingDir.resolve("$documentId.bin").apply { createNewFile() }
            }
            val flags = when (mode) {
                "r" -> ParcelFileDescriptor.MODE_READ_ONLY
                "wa" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
                else -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            }
            return ParcelFileDescriptor.open(file, flags)
        }

        fun findByName(name: String): String? = docs.values.firstOrNull { it.displayName == name }?.id

        fun contents(name: String): String? = findByName(name)?.let { backingFiles[it]?.readText() }

        private fun rowFor(doc: Doc, columns: Array<out String>): Array<Any?> = columns.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.displayName
                DocumentsContract.Document.COLUMN_MIME_TYPE -> doc.mimeType
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                DocumentsContract.Document.COLUMN_SIZE -> 0L
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
                else -> null
            }
        }.toTypedArray()
    }

    private companion object {
        const val AUTHORITY = "workspace.switch"
        const val METHOD_CREATE_DOCUMENT = "android:createDocument"
        const val EXTRA_URI = "uri"
        val DEFAULT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
