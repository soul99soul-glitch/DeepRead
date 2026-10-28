package app.amber.feature.ui.pages.setting

import app.amber.core.model.AssistantMemory
import app.amber.core.model.MemoryKind
import app.amber.core.model.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDocumentProjectionTest {
    @Test
    fun topicDocumentsResolveOnlyLiveMembersAndKeepLooseRecordsSeparate() {
        val core = memory(1, "core", scope = MemoryScope.CORE)
        val member = memory(2, "member")
        val archived = memory(3, "archived").copy(archived = true)
        val loose = memory(4, "loose")
        val topic = memory(10, "topic body", kind = MemoryKind.TOPIC).copy(
            topicTitle = "Project topic",
            memberIds = listOf(member.id, member.id, archived.id, core.id, 99),
        )

        val docs = buildMemoryDocs(listOf(core), listOf(member, archived), listOf(topic, loose))

        assertEquals(listOf(core.id), docs.single { it.id == "core" }.records.map { it.id })
        assertEquals(listOf(member.id), docs.single { it.id == "topic-10" }.records.map { it.id })
        assertEquals(listOf(loose.id), docs.single { it.id == "kind-note" }.records.map { it.id })
        assertFalse(docs.any { doc -> doc.records.any { it.archived } })
    }

    @Test
    fun matchingTopicSlugsRemainDistinctAndTopicsKeepNewestFirst() {
        val older = memory(10, "older body", kind = MemoryKind.TOPIC, updatedAt = 10).copy(
            topicTitle = "Release plan",
        )
        val newer = memory(11, "newer body", kind = MemoryKind.TOPIC, updatedAt = 20).copy(
            topicTitle = "Release---plan",
        )
        val archived = memory(12, "hidden", kind = MemoryKind.TOPIC).copy(archived = true)

        val docs = buildMemoryDocs(emptyList(), listOf(older, archived), listOf(newer))

        assertEquals(listOf("topic-11", "topic-10"), docs.map { it.id })
        assertEquals(listOf("release-plan-11.md", "release-plan-10.md"), docs.map { it.fileName })
        assertTrue(docs.all { it.records.isEmpty() })
    }

    @Test
    fun documentMetadataIncludesTopicAndNewestMemberWithoutReorderingTheInput() {
        val older = memory(1, "old", updatedAt = 5)
        val newer = memory(2, "\n  新内容  \nsecond line", updatedAt = 30)
        val topic = memory(3, "\n Topic overview\nnext", kind = MemoryKind.TOPIC, updatedAt = 10).copy(
            memberIds = listOf(older.id, newer.id),
        )
        val input = listOf(older, newer, topic)

        val doc = buildMemoryDocs(emptyList(), emptyList(), input).single()

        assertEquals(listOf(newer.id, older.id), doc.records.map { it.id })
        assertEquals(30L, doc.updatedAt)
        assertEquals("Topic overview", doc.preview)
        assertEquals(
            older.content.toByteArray().size + newer.content.toByteArray().size + topic.content.toByteArray().size,
            doc.sizeBytes,
        )
        assertEquals(listOf(older.id, newer.id, topic.id), input.map { it.id })
    }

    private fun memory(
        id: Int,
        content: String,
        scope: MemoryScope = MemoryScope.LONG_TERM,
        kind: MemoryKind = MemoryKind.NOTE,
        updatedAt: Long = 1,
    ) = AssistantMemory(id = id, content = content, scope = scope, kind = kind, updatedAt = updatedAt)
}
