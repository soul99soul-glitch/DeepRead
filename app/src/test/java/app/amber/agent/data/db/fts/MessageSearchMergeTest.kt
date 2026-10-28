package app.amber.agent.data.db.fts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * P8-04：搜索会话标题和消息正文。
 */
class MessageSearchMergeTest {

    private fun bodyHit(
        conversationId: String,
        nodeId: String = "node-$conversationId",
        updateAt: Instant = Instant.EPOCH,
    ) = MessageSearchResult(
        nodeId = nodeId,
        messageId = "msg-$conversationId",
        conversationId = conversationId,
        title = "title-$conversationId",
        updateAt = updateAt,
        snippet = "[hit] body snippet",
        hitSource = SearchHitSource.BODY,
    )

    private fun titleHit(
        conversationId: String,
        updateAt: Instant = Instant.EPOCH,
    ) = MessageSearchResult(
        nodeId = null,
        messageId = null,
        conversationId = conversationId,
        title = "title-$conversationId",
        updateAt = updateAt,
        snippet = "[hit] title snippet",
        hitSource = SearchHitSource.TITLE,
    )

    @Test
    fun `only title hit keeps title entry with null node and message ids`() {
        val titleOnly = titleHit("conv-1")

        val merged = mergeMessageSearchResults(bodyHits = emptyList(), titleHits = listOf(titleOnly))

        assertEquals(1, merged.size)
        assertEquals("conv-1", merged[0].conversationId)
        assertEquals(SearchHitSource.TITLE, merged[0].hitSource)
        assertTrue(merged[0].titleMatched)
        assertNull(merged[0].nodeId)
        assertNull(merged[0].messageId)
    }

    @Test
    fun `title and body both hit dedupes to single body entry`() {
        val body = bodyHit("conv-1")
        val title = titleHit("conv-1")

        val merged = mergeMessageSearchResults(bodyHits = listOf(body), titleHits = listOf(title))

        assertEquals(1, merged.size)
        assertEquals(SearchHitSource.BODY, merged[0].hitSource)
        assertTrue(merged[0].titleMatched)
        assertEquals("node-conv-1", merged[0].nodeId)
    }

    @Test
    fun `body hits keep their order and title hits are appended after`() {
        val bodyFirst = bodyHit("conv-1", updateAt = Instant.parse("2026-01-01T00:00:00Z"))
        val bodySecond = bodyHit("conv-2", updateAt = Instant.parse("2026-02-01T00:00:00Z"))
        val titleOnly = titleHit("conv-3", updateAt = Instant.parse("2026-03-01T00:00:00Z"))

        val merged = mergeMessageSearchResults(
            bodyHits = listOf(bodyFirst, bodySecond),
            titleHits = listOf(titleOnly),
        )

        assertEquals(listOf("conv-1", "conv-2", "conv-3"), merged.map { it.conversationId })
        assertEquals(
            listOf(SearchHitSource.BODY, SearchHitSource.BODY, SearchHitSource.TITLE),
            merged.map { it.hitSource },
        )
        assertEquals(listOf(false, false, true), merged.map { it.titleMatched })
    }

    @Test
    fun `dedupe only drops title entries of conversations already hit in body`() {
        val body = bodyHit("conv-1")
        val titleHitForBody = titleHit("conv-1")
        val titleOnly = titleHit("conv-9")

        val merged = mergeMessageSearchResults(
            bodyHits = listOf(body),
            titleHits = listOf(titleHitForBody, titleOnly),
        )

        assertEquals(listOf("conv-1", "conv-9"), merged.map { it.conversationId })
    }

    @Test
    fun `failure in either FTS projection propagates instead of returning a partial merge`() = runTest {
        val bodyFailure = IllegalStateException("body query failed")
        val observedBodyFailure = runCatching {
            queryMessageSearchResults(
                bodyQuery = { throw bodyFailure },
                titleQuery = { error("title query must not run after body failure") },
            )
        }.exceptionOrNull()
        assertSame(bodyFailure, observedBodyFailure)

        val titleFailure = IllegalStateException("title query failed")
        val observedTitleFailure = runCatching {
            queryMessageSearchResults(
                bodyQuery = { listOf(bodyHit("conv-1")) },
                titleQuery = { throw titleFailure },
            )
        }.exceptionOrNull()
        assertSame(titleFailure, observedTitleFailure)
    }

    @Test
    fun `cancellation from an FTS projection is propagated unchanged`() = runTest {
        val cancellation = CancellationException("search cancelled")
        val observed = runCatching {
            queryMessageSearchResults(
                bodyQuery = { throw cancellation },
                titleQuery = { emptyList() },
            )
        }.exceptionOrNull()

        assertSame(cancellation, observed)
    }

    @Test
    fun `jieba dictionary contains CJK entries so title tokenization supports chinese`() {
        val dict = repoFile("src/main/assets/simple_dict/jieba.dict.utf8")
        assertTrue("jieba 词典应存在", dict.isFile)
        val sample = dict.readText(Charsets.UTF_8).take(2_000)
        assertTrue("jieba 词典应包含 CJK 词条（如 一/区块链）", sample.any { it.code > 0x4E00 })
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
