package app.amber.feature.ui.pages.councilroom

import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.feature.modelcouncil.CouncilPhaseMarker
import app.amber.feature.modelcouncil.CouncilRoomMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CouncilTimelineProjectionTest {
    @Test
    fun mergedEntriesKeepMessageAndPhaseKeysWithStableTimestampTies() {
        val later = message("later", "Later member", createdAt = 20L)
        val earlier = message("earlier", "Earlier member", createdAt = 10L)
        val marker = CouncilPhaseMarker("phase", "进入讨论", CouncilRoomMode.EXPLORE, 20L)
        val entries = buildCouncilTimelineEntries(listOf(later, earlier), listOf(marker))

        assertEquals(listOf("earlier", "later", "phase"), entries.map { it.key })
        assertEquals(listOf(10L, 20L, 20L), entries.map { it.sortMs })
        assertEquals(earlier, (entries[0] as TimelineEntry.Message).msg)
        assertEquals(marker, (entries[2] as TimelineEntry.Phase).marker)
    }

    @Test
    fun referenceAuthorsComeFromTargetMessagesWithoutChangingMissingEdges() {
        val reply = message("reply", "被回复的成员", 1L)
        val continuation = message("continued", "被延续的成员", 2L)
        val current = message("current", "当前成员", 3L).copy(
            replyToMessageId = reply.id,
            continuesFromMessageId = continuation.id,
        )
        assertEquals(
            CouncilReferenceAuthors("被回复的成员", "被延续的成员"),
            councilReferenceAuthors(current, listOf(reply, continuation, current)),
        )
        val deletedReply = councilReferenceAuthors(current, listOf(continuation, current))
        assertNull(deletedReply.replyTo)
        assertEquals("被延续的成员", deletedReply.continuation)
        assertEquals(CouncilReferenceAuthors(null, null), councilReferenceAuthors(reply, listOf(current)))
    }

    private fun message(id: String, author: String, createdAt: Long): CouncilMessage = CouncilMessage(
        id = id,
        authorId = "member-$id",
        authorName = author,
        role = "reviewer",
        round = 1,
        mode = CouncilRoomMode.EXPLORE,
        text = "",
        createdAtMs = createdAt,
        status = CouncilMessageStatus.COMPLETED,
    )
}
