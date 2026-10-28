package app.amber.feature.ui.pages.novel

import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceQueriesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `chapter cache notices a same size edit within the same millisecond`() {
        val root = temporary.newFolder()
        val query = NovelWorkspaceQueries(NovelWorkspaceProjectRepository(root))
        val store = NovelWorkspaceStore(root)
        val path = "branches/main/chapters/001.md"
        val file = File(root, path)
        store.write(path, "---\ntitle: Before\nordinal: 1\n---\nold body")
        val firstStamp = FileTime.from(1_700_000_000_000_100_000L, TimeUnit.NANOSECONDS)
        val secondStamp = FileTime.from(1_700_000_000_000_900_000L, TimeUnit.NANOSECONDS)
        Files.setLastModifiedTime(file.toPath(), firstStamp)
        val cache = mutableMapOf<String, CachedNovelUi<NovelMarkdownChapterUi>>()
        val before = query.chapters(store, "main", cache).single()
        assertSame(before, query.chapters(store, "main", cache).single())

        // Preserve size and the millisecond timestamp: a millisecond-only stamp would be stale.
        file.writeText("---\ntitle: After!\nordinal: 1\n---\nnew body")
        Files.setLastModifiedTime(file.toPath(), secondStamp)
        assertEquals(firstStamp.toMillis(), Files.getLastModifiedTime(file.toPath()).toMillis())
        val after = query.chapters(store, "main", cache).single()
        assertEquals("Before", before.title)
        assertEquals("After!", after.title)
        assertNotSame(before, after)
    }

    @Test
    fun `chapter cache drops removed files and keeps branch queries independent`() {
        val root = temporary.newFolder()
        val query = NovelWorkspaceQueries(NovelWorkspaceProjectRepository(root))
        val store = NovelWorkspaceStore(root)
        val mainPath = "branches/main/chapters/001.md"
        store.write(mainPath, "---\ntitle: Main\nordinal: 1\n---\nmain")
        store.write("branches/other/chapters/001.md", "---\ntitle: Other\nordinal: 1\n---\nother")
        val cache = mutableMapOf<String, CachedNovelUi<NovelMarkdownChapterUi>>()
        assertEquals("Main", query.chapters(store, "main", cache).single().title)
        assertEquals("Other", query.chapters(store, "other").single().title)
        assertTrue(File(root, mainPath).delete())
        assertTrue(query.chapters(store, "main", cache).isEmpty())
        assertTrue(cache.isEmpty())
    }
}
