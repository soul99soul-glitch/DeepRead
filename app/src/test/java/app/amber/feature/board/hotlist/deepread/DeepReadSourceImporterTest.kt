package app.amber.feature.board.hotlist.deepread

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeepReadSourceImporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var provider: ImportSourceProvider
    private lateinit var importCache: File

    @Before
    fun setUp() {
        importCache = temporaryFolder.newFolder("import-cache")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = importCache
        }
        provider = ImportSourceProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @Test
    fun damagedDocxIsNotImportedAsParserDiagnosticText() = runBlocking {
        val file = temporaryFolder.newFile("damaged.docx").apply { writeText("not a ZIP archive") }

        assertFailure(importFile(file, MIME_DOCX))
        assertTemporaryFilesRemoved()
    }

    @Test
    fun damagedPdfIsNotImportedAsParserDiagnosticText() = runBlocking {
        val file = temporaryFolder.newFile("damaged.pdf").apply { writeText("not a PDF document") }

        assertFailure(importFile(file, "application/pdf"))
        assertTemporaryFilesRemoved()
    }

    @Test
    fun pptxWithNoReadableSlidesIsNotImportedAsBody() = runBlocking {
        val file = zipFixture("empty.pptx",
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId r:id="rId1"/></p:sldIdLst></p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="slides/missing.xml" Type="slide"/></Relationships>
            """.trimIndent(),
        )

        assertFailure(importFile(file, MIME_PPTX))
        assertTemporaryFilesRemoved()
    }

    @Test
    fun epubWithNoReadableContentIsNotImportedAsBody() = runBlocking {
        val file = zipFixture("empty.epub",
            "META-INF/container.xml" to """
                <container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>
            """.trimIndent(),
            "content.opf" to "<package><manifest/><spine/></package>",
        )

        assertFailure(importFile(file, "application/epub+zip"))
        assertTemporaryFilesRemoved()
    }

    @Test
    fun readableDocxKeepsItsExtractedBody() = runBlocking {
        val body = "Error parsing is explained in this readable document."
        val file = zipFixture("readable.docx", "word/document.xml" to """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$body</w:t></w:r></w:p></w:body></w:document>
        """.trimIndent())

        val result = importFile(file, MIME_DOCX)

        assertTrue("Readable DOCX must import successfully", result is DeepReadSourceImporter.Result.Success)
        assertEquals(body, (result as DeepReadSourceImporter.Result.Success).source.text.trim())
        assertTemporaryFilesRemoved()
    }

    @Test
    fun plainTextCanContainParserDiagnosticExamples() = runBlocking {
        val body = "Error parsing DOCX file: this is a quoted diagnostic in a tutorial.\n" +
            "No readable slides in PPTX file is another example, not this text file's status."
        val file = temporaryFolder.newFile("diagnostic-examples.txt").apply { writeText(body) }

        val result = importFile(file, "text/plain")

        assertTrue("Plain text must keep diagnostic examples as user content", result is DeepReadSourceImporter.Result.Success)
        assertEquals(body, (result as DeepReadSourceImporter.Result.Success).source.text)
        assertTemporaryFilesRemoved()
    }

    @Test
    fun unknownReportedSizeStillEnforcesActualFileByteLimit() = runBlocking {
        val file = temporaryFolder.newFile("oversized.txt")
        RandomAccessFile(file, "rw").use { output ->
            output.write("Readable file header\n".toByteArray())
            output.setLength(65L * 1024 * 1024)
        }
        provider.reportedSize = null
        shadowOf(context.contentResolver).registerInputStreamSupplier(
            Uri.parse("content://$AUTHORITY/source"),
        ) { provider.openCountingInputStream() }

        val failure = assertFailure(importFile(file, "text/plain"))

        assertEquals(DeepReadSourceImporter.Reason.TOO_LARGE, failure.reason)
        assertEquals("The importer must stop at the first byte over the cap", 64L * 1024 * 1024 + 1, provider.countedBytes)
        assertTemporaryFilesRemoved()
    }

    @Test
    fun openingSourceFailureDoesNotLeaveAnImportTemporaryFile() = runBlocking {
        val file = temporaryFolder.newFile("unavailable.txt")
        provider.failureMode = FailureMode.OPEN

        val failure = assertFailure(importFile(file, "text/plain"))

        assertEquals(DeepReadSourceImporter.Reason.READ_ERROR, failure.reason)
        assertTemporaryFilesRemoved()
    }

    @Test
    fun interruptedSourceReadDoesNotLeaveAPartialImportTemporaryFile() = runBlocking {
        val file = temporaryFolder.newFile("interrupted.txt").apply {
            writeText("The first bytes arrive before the document provider loses the source stream.")
        }
        // ContentResolver rebuilds AssetFileDescriptor; its provider stream subclass is not retained.
        shadowOf(context.contentResolver).registerInputStreamSupplier(
            Uri.parse("content://$AUTHORITY/source"),
        ) { provider.openInterruptedInputStream() }

        val result = importFile(file, "text/plain")

        assertTrue("The actual openInputStream stream must deliver source bytes", provider.bytesReadBeforeFailure > 0)
        assertTrue("The actual source stream must throw on a later read", provider.readInterruptionThrown)
        assertTrue("The interruption must follow a successful read", provider.readCalls >= 2)
        val failure = assertFailure(result)
        assertEquals(DeepReadSourceImporter.Reason.READ_ERROR, failure.reason)
        assertTemporaryFilesRemoved()
    }

    @Test
    fun plainTextKeepsTheFortyThousandCharacterTruncationBoundary() = runBlocking {
        for (length in listOf(40_000, 40_001)) {
            val text = "x".repeat(length)
            val file = temporaryFolder.newFile("text-$length.txt").apply { writeText(text) }
            val result = importFile(file, "text/plain")
            assertTrue(result is DeepReadSourceImporter.Result.Success)
            val source = (result as DeepReadSourceImporter.Result.Success).source
            assertEquals(text.take(40_000), source.text)
            assertEquals(length > 40_000, source.truncated)
            assertTemporaryFilesRemoved()
        }
    }

    @Test
    fun cancelledReadPropagatesAndCleansItsStreamAndTemporaryFile() {
        val file = temporaryFolder.newFile("cancelled.txt").apply { writeText("source text") }
        var streamClosed = false
        shadowOf(context.contentResolver).registerInputStreamSupplier(
            Uri.parse("content://$AUTHORITY/source"),
        ) {
            object : FileInputStream(file) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    throw CancellationException("source cancelled")
                }
                override fun close() {
                    streamClosed = true
                    super.close()
                }
            }
        }
        val failure = assertThrows(CancellationException::class.java) {
            runBlocking { importFile(file, "text/plain") }
        }
        assertEquals("source cancelled", failure.message)
        assertTrue(streamClosed)
        assertTemporaryFilesRemoved()
    }

    private suspend fun importFile(file: File, mime: String): DeepReadSourceImporter.Result {
        provider.file = file
        provider.mime = mime
        return DeepReadSourceImporter.extract(context, Uri.parse("content://$AUTHORITY/source"))
    }

    private fun assertFailure(result: DeepReadSourceImporter.Result): DeepReadSourceImporter.Result.Failure {
        assertTrue("Expected import failure, got ${result::class.simpleName}", result is DeepReadSourceImporter.Result.Failure)
        return result as DeepReadSourceImporter.Result.Failure
    }

    private fun assertTemporaryFilesRemoved() {
        assertTrue("Importer must remove its temporary files: ${importCache.list()?.toList()}", importCache.listFiles().orEmpty().isEmpty())
    }

    private fun zipFixture(name: String, vararg entries: Pair<String, String>): File =
        temporaryFolder.newFile(name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (path, xml) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(xml.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private enum class FailureMode { NONE, OPEN }

    /** Real file-backed content URIs, including providers that cannot report SIZE. */
    private class ImportSourceProvider : ContentProvider() {
        lateinit var file: File
        var mime = "text/plain"
        var reportedSize: Long? = null
        var failureMode = FailureMode.NONE
        var bytesReadBeforeFailure = 0
        var readCalls = 0
        var readInterruptionThrown = false
        var countedBytes = 0L

        override fun onCreate() = true
        override fun getType(uri: Uri): String = mime
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map<String, Any?> { column -> when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> reportedSize
                    else -> null
                } }.toTypedArray())
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            if (failureMode == FailureMode.OPEN) throw FileNotFoundException("Source is no longer available")
            return AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

        fun openCountingInputStream(): FileInputStream = object : FileInputStream(file) {
            override fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { count ->
                    if (count > 0) countedBytes += count
                }
        }

        fun openInterruptedInputStream(): FileInputStream = object : FileInputStream(file) {
            override fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCalls++
                if (bytesReadBeforeFailure > 0) {
                    readInterruptionThrown = true
                    throw IOException("Source read was interrupted")
                }
                return super.read(buffer, offset, minOf(length, 16)).also { count ->
                    if (count > 0) bytesReadBeforeFailure += count
                }
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    companion object {
        private const val AUTHORITY = "test.deepread.import.source"
        private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }
}
