package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors

internal data class CouncilReferenceAuthors(
    val replyTo: String?,
    val continuation: String?,
)

internal fun councilReferenceAuthors(
    msg: CouncilMessage,
    messages: List<CouncilMessage>,
): CouncilReferenceAuthors = CouncilReferenceAuthors(
    replyTo = msg.replyToMessageId?.let { id -> messages.firstOrNull { it.id == id }?.authorName },
    continuation = msg.continuesFromMessageId?.let { id -> messages.firstOrNull { it.id == id }?.authorName },
)

/**
 * Reference-graph footnotes (reply / continues / invited-by). Only rendered for
 * guest messages with a non-null reference; resolved against the room's message
 * list to show the target author's name.
 */
@Composable
internal fun ReferenceFootnotes(msg: CouncilMessage, authors: CouncilReferenceAuthors, alignEnd: Boolean) {
    val workspace = workspaceColors()
    val deletedAuthor = stringResource(R.string.council_room_deleted_author)
    val replyName = msg.replyToMessageId?.let { authors.replyTo ?: deletedAuthor }
    val continuationName = msg.continuesFromMessageId?.let { authors.continuation ?: deletedAuthor }
    val replyNote = if (replyName != null) {
        stringResource(R.string.council_room_reply_reference, replyName)
    } else {
        null
    }
    val continuationNote = if (continuationName != null) {
        stringResource(R.string.council_room_continuation_reference, continuationName)
    } else {
        null
    }
    val notes = listOfNotNull(replyNote, continuationNote)
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .padding(start = 4.dp, top = 2.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        notes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.faint,
            )
        }
    }
}
