package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme

/**
 * Answer card for a host ask_user question. Renders the host's question with an
 * accent border + a capsule text input + submit button. On submit, calls
 * [onAnswer], appending the user's answer and un-suspending the council.
 * A missing receiver keeps an unanswered historical card read-only.
 *
 * After the user answers, the room gains a normal USER message right after this
 * card (so the card is historical context) and the council resumes. The card
 * itself stays read-only (it already served its purpose).
 */
@Composable
internal fun CouncilAskUserCard(
    msg: CouncilMessage,
    answered: Boolean,
    onAnswer: ((String) -> Unit)?,
) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    var answer by remember(msg.id) { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = chatTheme.surface,
        contentColor = chatTheme.ink,
        border = BorderStroke(1.dp, chatTheme.accent),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = chatTheme.accentSoft,
                ) {
                    Text(
                        text = stringResource(R.string.council_room_host_question),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = chatTheme.accent,
                    )
                }
            }
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = chatTheme.ink,
            )
            if (!answered && onAnswer != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.TextField(
                        value = answer,
                        onValueChange = { answer = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(stringResource(R.string.council_room_answer_host_hint), color = workspace.faint)
                        },
                        shape = RoundedCornerShape(999.dp),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = chatTheme.surface,
                            unfocusedContainerColor = chatTheme.surface,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedTextColor = chatTheme.ink,
                            unfocusedTextColor = chatTheme.ink,
                        ),
                        maxLines = 4,
                    )
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = CircleShape,
                        color = if (answer.isNotBlank()) chatTheme.accent else chatTheme.surface,
                        contentColor = if (answer.isNotBlank()) chatTheme.surface else workspace.faint,
                        border = BorderStroke(1.dp, if (answer.isNotBlank()) chatTheme.accent else chatTheme.surfaceEdge),
                        enabled = answer.isNotBlank(),
                        onClick = {
                            val text = answer.trim()
                            if (text.isNotEmpty()) {
                                onAnswer(text)
                                // Clear only after submit; if VM rejects, user can
                                // re-type — recovery path now accepts late answers.
                                answer = ""
                            }
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("→", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (answered) {
                Text(
                    text = stringResource(R.string.council_room_answered),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.faint,
                )
            }
        }
    }
}
