package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.R
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import app.amber.feature.ui.pages.chat.LocalChatTheme

/** Renders a user message's image thumbnails + document chips inside its bubble. */
@Composable
internal fun CouncilBubbleAttachments(attachments: List<UIMessagePart>, modifier: Modifier = Modifier) {
    val chatTheme = LocalChatTheme.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.filterIsInstance<UIMessagePart.Image>().forEach { img ->
            AsyncImage(
                model = img.url,
                contentDescription = stringResource(R.string.council_room_image_attachment),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        attachments.filterIsInstance<UIMessagePart.Document>().forEach { doc ->
            Text(
                text = stringResource(R.string.council_room_file_attachment, doc.fileName),
                style = MaterialTheme.typography.labelMedium,
                color = chatTheme.userBubbleInk,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(chatTheme.userBubbleInk.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
