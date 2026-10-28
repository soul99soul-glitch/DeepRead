package app.amber.feature.novelworkspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceExportSnapshotTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val firstPath = "branches/主线/chapters/001-开端.md"
    private val secondPath = "branches/主线/chapters/002-后续.md"
    private val plotPath = "branches/主线/plot/current.md"

    @Test
    fun `zip takes one snapshot before compression and allows owner writes during output`() {
        val directory = installBook()
        val executor = Executors.newSingleThreadExecutor()
        val bytes = ByteArrayOutputStream()
        var ownerCommitted = false
        val output = object : OutputStream() {
            override fun write(value: Int) = bytes.write(value)

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                // ZipOutputStream writes the entry name after the exporter has read its
                // content. Pause at chapter 1, then commit real chapter/plot writes on a
                // different thread. Compression must not keep the owner monitor held.
                if (!ownerCommitted && String(buffer, offset, length, Charsets.UTF_8) == firstPath) {
                    executor.submit { commitRevision(directory, "新版") }.get(5, TimeUnit.SECONDS)
                    ownerCommitted = true
                }
                bytes.write(buffer, offset, length)
            }
        }
        try {
            NovelWorkspaceExchange.exportZip(directory, output)
        } finally {
            executor.shutdownNow()
        }

        assertTrue("the write must actually interleave with zip output", ownerCommitted)
        val exported = NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(bytes.toByteArray())).associateBy { it.path }
        assertEquals("旧版开端。", NovelWorkspaceMarkdown.parseFile(exported.getValue(firstPath).content).body)
        assertEquals("旧版后续。", NovelWorkspaceMarkdown.parseFile(exported.getValue(secondPath).content).body)
        assertEquals("旧版剧情。", NovelWorkspaceMarkdown.parseFile(exported.getValue(plotPath).content).body)
        assertEquals("新版后续。", NovelWorkspaceMarkdown.parseFile(NovelWorkspaceStore(directory).read(secondPath)!!).body)
    }

    @Test
    fun `book export waits for an owner transaction instead of reading its half written tree`() {
        val directory = installBook()
        val executor = Executors.newFixedThreadPool(2)
        val firstChapterWritten = CountDownLatch(1)
        val finishOwnerWrite = CountDownLatch(1)
        val exportStarted = CountDownLatch(1)
        val exportFinished = CountDownLatch(1)
        val writer = executor.submit {
            NovelWorkspaceGhostwriteJobs.withNoActiveBranch(directory, "主线") {
                writeChapter(NovelWorkspaceStore(directory), firstPath, 1, "新版开端。")
                firstChapterWritten.countDown()
                check(finishOwnerWrite.await(5, TimeUnit.SECONDS))
                commitRevision(directory, "新版")
            }
        }
        try {
            assertTrue(firstChapterWritten.await(5, TimeUnit.SECONDS))
            val exported = executor.submit<ByteArray> {
                exportStarted.countDown()
                try {
                    NovelWorkspaceBookExport.exportBytes(directory, NovelWorkspaceBookExport.Format.TXT, "主线")
                } finally {
                    exportFinished.countDown()
                }
            }
            assertTrue(exportStarted.await(5, TimeUnit.SECONDS))
            // The writer deliberately holds the existing transaction monitor. A reader
            // that completes here has observed an uncommitted, mixed chapter revision.
            val escapedOwnerTransaction = exportFinished.await(300, TimeUnit.MILLISECONDS)
            finishOwnerWrite.countDown()
            writer.get(5, TimeUnit.SECONDS)
            val book = String(exported.get(5, TimeUnit.SECONDS), Charsets.UTF_8)
            assertFalse("book export must wait for the owner transaction", escapedOwnerTransaction)
            assertTrue(book.contains("新版开端。"))
            assertTrue(book.contains("新版后续。"))
            assertFalse(book.contains("旧版"))
        } finally {
            finishOwnerWrite.countDown()
            executor.shutdownNow()
        }
    }

    private fun installBook(): File {
        val directory = temporaryFolder.newFolder()
        val store = NovelWorkspaceStore(directory)
        store.write("manifest.yaml", NovelWorkspaceManifestRenderer.render(
            exportedAt = Instant.parse("2026-10-18T00:00:00Z"), sourceProjectID = "P-1",
            sourceProjectRevision = 1, sourceSchemaVersion = 1, mainBranch = "主线",
        ))
        store.write("project.md", NovelWorkspaceMarkdown.render(listOf("kind" to "project", "title" to "测试书"), body = ""))
        store.write("branches/主线/branch.md", NovelWorkspaceMarkdown.render(
            listOf("id" to "B-1", "kind" to "branch", "syncStatus" to "synchronized"), body = "",
        ))
        commitRevision(directory, "旧版")
        return directory
    }

    private fun commitRevision(directory: File, revision: String) = NovelWorkspaceGhostwriteJobs.withNoActiveBranch(directory, "主线") {
        val store = NovelWorkspaceStore(directory)
        writeChapter(store, firstPath, 1, "${revision}开端。")
        writeChapter(store, secondPath, 2, "${revision}后续。")
        store.write(plotPath, NovelWorkspaceMarkdown.render(listOf("kind" to "plot"), body = "${revision}剧情。"))
        val ledger = NovelWorkspaceLedger.load(directory)
        val commit = NovelWorkspaceLedger.makeCommit(
            id = revision, parentId = ledger.heads["B-1"], files = store.fileTree(),
            message = NovelWorkspaceLedger.Message.GHOSTWRITE_REVIEWED, createdAt = Instant.now(),
        )
        NovelWorkspaceLedger.save(NovelWorkspaceLedger.appending(commit, ledger).copy(heads = mapOf("B-1" to commit.id)), directory)
    }

    private fun writeChapter(store: NovelWorkspaceStore, path: String, ordinal: Int, body: String) {
        store.write(path, NovelWorkspaceMarkdown.render(
            listOf("id" to "C-$ordinal", "kind" to "chapter", "title" to "第 $ordinal 章", "ordinal" to ordinal.toString()),
            body = body,
        ))
    }
}
