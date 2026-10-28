package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import app.amber.agent.R
import app.amber.core.memory.dream.PersistedMemoryDreamPlan
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun DreamReviewSection(
    plan: PersistedMemoryDreamPlan?,
    running: Boolean,
    canRun: Boolean,
    onPlan: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingSectionTitle(stringResource(R.string.memory_manual_review_title))
        AmberCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (plan == null) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(tokens.surface2)
                            .border(1.dp, tokens.line, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.Sparkles,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = tokens.ink2,
                        )
                    }
                    Text(
                        text = stringResource(R.string.memory_no_manual_plan),
                        style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.ink,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.memory_manual_review_desc),
                        style = LocalAmberType.current.secondary,
                        color = tokens.ink2,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        enabled = !running && canRun,
                        onClick = onPlan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(tokens.surface2)
                            .border(1.dp, tokens.line, RoundedCornerShape(15.dp)),
                    ) {
                        if (running) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Text(stringResource(R.string.memory_generate_suggestion))
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.memory_manual_review_title),
                                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                                color = tokens.ink,
                            )
                            Text(
                                stringResource(R.string.memory_manual_review_desc),
                                style = LocalAmberType.current.secondary,
                                color = tokens.ink2,
                            )
                        }
                        if (running) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }

                    val persisted = plan
                    val current = persisted.plan
                    val summary = stringResource(
                        R.string.memory_dream_summary,
                        current.mergeSuggestions.size,
                        current.promoteMemoryIds.size,
                        current.archiveMemoryIds.size,
                        current.supersedeSuggestions.size,
                        current.ignoreCandidateIds.size,
                        current.topicSuggestions.size,
                    )
                    // Graphite §3: dream-plan summary is a count-dense machine-fact → MONO.
                    Text(
                        summary,
                        style = LocalAmberType.current.meta,
                        color = tokens.ink,
                    )
                    Text(
                        text = stringResource(
                            R.string.memory_dream_source,
                            if (persisted.source.name == "AUTO") {
                                stringResource(R.string.memory_dream_source_auto)
                            } else {
                                stringResource(R.string.memory_dream_source_manual)
                            },
                        ),
                        style = LocalAmberType.current.secondary,
                        color = tokens.ink2,
                    )
                    current.notes.take(6).forEach { note ->
                        Text(
                            text = "• $note",
                            style = LocalAmberType.current.secondary,
                            color = tokens.ink2,
                        )
                    }
                    current.mergeSuggestions.take(5).forEach { suggestion ->
                        // Graphite §3: merge suggestion = #id references → MONO.
                        Text(
                            text = stringResource(
                                R.string.memory_dream_merge,
                                suggestion.targetMemoryId,
                                suggestion.duplicateMemoryIds.joinToString(","),
                            ),
                            style = LocalAmberType.current.meta,
                            color = tokens.ink,
                        )
                    }
                    current.supersedeSuggestions.take(5).forEach { suggestion ->
                        val reasonSuffix = if (suggestion.reason.isNotBlank()) {
                            stringResource(R.string.memory_dream_reason_suffix, suggestion.reason)
                        } else {
                            ""
                        }
                        Text(
                            text = stringResource(
                                R.string.memory_dream_replace,
                                suggestion.oldMemoryIds.joinToString(","),
                                suggestion.newContent,
                                reasonSuffix,
                            ),
                            style = LocalAmberType.current.secondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    current.topicSuggestions.take(4).forEach { suggestion ->
                        val reasonSuffix = if (suggestion.reason.isNotBlank()) {
                            stringResource(R.string.memory_dream_reason_suffix, suggestion.reason)
                        } else {
                            ""
                        }
                        Text(
                            text = stringResource(
                                R.string.memory_dream_topic,
                                suggestion.title,
                                suggestion.memberMemoryIds.joinToString(", ") { "#$it" },
                                reasonSuffix,
                            ),
                            style = LocalAmberType.current.secondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !running && canRun,
                            onClick = onPlan,
                        ) {
                            Text(stringResource(R.string.memory_generate_suggestion))
                        }
                        TextButton(
                            enabled = !running && persisted.plan.hasChanges,
                            onClick = onApply,
                        ) {
                            Text(stringResource(R.string.memory_apply_suggestion))
                        }
                        TextButton(
                            enabled = !running,
                            onClick = onDismiss,
                        ) {
                            Text(stringResource(R.string.memory_clear_suggestion))
                        }
                    }
                }
            }
        }
    }
}
