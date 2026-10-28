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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class WorkspaceDocumentsWriteTest {
    private lateinit var context: Context
    private lateinit var manager: WorkspaceManager
    private lateinit var provider: ExactNameTreeProvider

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        manager = WorkspaceManager(context)
        manager.mirrorDir.deleteRecursively()
        provider = ExactNameTreeProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        manager.mirrorDir.deleteRecursively()
        provider.close()
    }

    @Test
    fun replacingBinaryContentTruncatesThePreviousPayloadAndAppendStillAppends() = runBlocking {
        manager.setWorkspace(treeUri())
        manager.writeBytes("payload.bin", "123456".toByteArray())

        manager.writeBytes("payload.bin", "xy".toByteArray())

        assertEquals("xy", manager.readBytes("payload.bin").decodeToString())
        manager.writeBytes("payload.bin", "z".toByteArray(), append = true)
        assertEquals("xyz", manager.readBytes("payload.bin").decodeToString())
    }

    @Test
    fun crossDirectoryMoveUsesTheExactRequestedNameWhenProviderAddsAnExtension() = runBlocking {
        manager.setWorkspace(treeUri())
        manager.writeText("source.txt", "keep this content")

        val result = manager.move("source.txt", "sub/renamed")

        assertEquals("sub/renamed", result.path)
        assertEquals("renamed", result.name)
        assertEquals("keep this content", manager.readText(result.path))
        assertTrue(runCatching { manager.readText("source.txt") }.isFailure)
        assertEquals(listOf("renamed"), manager.list("sub").map { it.name })
    }

    @Test
    fun crossDirectoryMoveRetainsSourceWhenProviderRefusesTheRequestedName() = runBlocking {
        manager.setWorkspace(treeUri())
        manager.writeText("source.txt", "original content")
        provider.refuseRename = true

        val result = runCatching { manager.move("source.txt", "sub/blocked") }

        assertTrue("A move with an inaccessible requested destination must fail", result.isFailure)
        assertEquals("original content", manager.readText("source.txt"))
        assertFalse(manager.list("sub").any { it.name == "blocked" })
    }

    @Test
    fun sameDirectoryMoveReturnsTheExactRequestedPathWhenRenameIsSupported() = runBlocking {
        manager.setWorkspace(treeUri())
        manager.writeText("source.txt", "original content")

        val result = manager.move("source.txt", "renamed")

        assertEquals("renamed", result.path)
        assertEquals("renamed", result.name)
        assertEquals("original content", manager.readText(result.path))
        assertTrue(runCatching { manager.readText("source.txt") }.isFailure)
    }

    @Test
    fun sameDirectoryMoveRejectsNormalizedRenameAndRestoresTheSource() = runBlocking {
        manager.setWorkspace(treeUri())
        manager.writeText("source.txt", "original content")
        provider.normalizeRename = true

        val result = runCatching { manager.move("source.txt", "renamed") }

        assertTrue("A differently named destination must not be acknowledged", result.isFailure)
        assertEquals("original content", manager.readText("source.txt"))
        assertEquals(listOf("source.txt"), manager.list().map { it.name })
    }

    @Test
    fun sameDirectoryMovePreservesDirectoryContents() = runBlocking {
        manager.setWorkspace(treeUri())
        manager.writeText("source/note.txt", "nested content")
        provider.normalizeRename = true

        val result = manager.move("source", "renamed")

        assertEquals("renamed", result.path)
        assertTrue(result.directory)
        assertEquals("nested content", manager.readText("renamed/note.txt"))
        assertTrue(runCatching { manager.list("source") }.isFailure)
    }

    private fun treeUri(): Uri = Uri.parse("content://$AUTHORITY/tree/${Uri.encode(ROOT_ID)}")

    /** A SAF provider may preserve length for `w` and append a MIME extension on create. */
    private class ExactNameTreeProvider : ContentProvider() {
        private data class Doc(val id: String, val parentId: String?, var name: String, val mime: String)

        private val docs = linkedMapOf(ROOT_ID to Doc(ROOT_ID, null, "root", DocumentsContract.Document.MIME_TYPE_DIR))
        private val backingDir = File.createTempFile("workspace-document-", "").apply { delete(); mkdirs() }
        private var nextId = 1
        var refuseRename = false
        var normalizeRename = false

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = docs[DocumentsContract.getDocumentId(uri)]?.mime

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: COLUMNS
            val cursor = MatrixCursor(columns)
            val documentId = DocumentsContract.getDocumentId(uri)
            val selected = if (uri.pathSegments.lastOrNull() == "children") {
                docs.values.filter { it.parentId == documentId }
            } else {
                listOfNotNull(docs[documentId])
            }
            selected.forEach { doc ->
                cursor.addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> doc.mime
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        DocumentsContract.Document.COLUMN_SIZE -> backingDir.resolve(doc.id).length()
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
                        else -> null
                    }
                }.toTypedArray<Any?>())
            }
            return cursor
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            val uri = extras?.getParcelable<Uri>("uri") ?: return null
            val id = DocumentsContract.getDocumentId(uri)
            val result = when (method) {
                "android:createDocument" -> {
                    val mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty()
                    val requested = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty()
                    val name = mimeAdjustedName(mime, requested)
                    val doc = Doc("doc-${nextId++}", id, name, mime)
                    docs[doc.id] = doc
                    DocumentsContract.buildDocumentUriUsingTree(uri, doc.id)
                }
                "android:renameDocument" -> {
                    if (refuseRename) return null
                    val doc = docs[id] ?: return null
                    val requested = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty()
                    doc.name = if (normalizeRename) mimeAdjustedName(doc.mime, requested) else requested
                    uri
                }
                "android:deleteDocument" -> {
                    docs.remove(id)
                    backingDir.resolve(id).delete()
                    return Bundle()
                }
                else -> return null
            }
            return Bundle().apply { putParcelable("uri", result) }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = DocumentsContract.getDocumentId(uri)
            docs[id] ?: throw FileNotFoundException(id)
            val file = backingDir.resolve(id)
            val flags = when (mode) {
                "r" -> ParcelFileDescriptor.MODE_READ_ONLY
                "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
                "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
                "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                else -> error("Unexpected mode $mode")
            }
            return ParcelFileDescriptor.open(file, flags)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        private fun mimeAdjustedName(mime: String, requested: String): String =
            if (mime == "text/plain" && !requested.endsWith(".txt")) "$requested.txt" else requested

        fun close() { backingDir.deleteRecursively() }
    }

    private companion object {
        const val AUTHORITY = "workspace.documents.write"
        const val ROOT_ID = "root:"
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
