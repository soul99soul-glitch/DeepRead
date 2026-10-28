package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import app.amber.agent.R
import app.amber.core.memory.model.MemoryCandidate
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

internal fun LazyListScope.memoryCandidatesSection(
    candidates: List<MemoryCandidate>,
    onAccept: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onIgnoreLowConfidence: () -> Unit,
) {
    val lowConfidenceCount = candidates.count { it.confidence < LOW_CONFIDENCE_CANDIDATE_THRESHOLD }
    item("memory_candidate_section_title") {
        SettingSectionTitle(stringResource(R.string.memory_candidate_review_title))
    }
    item("memory_candidate_card_gap") { Spacer(Modifier.height(8.dp)) }
    if (candidates.isEmpty()) {
        item("memory_candidate_empty") {
            AmberCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.memory_candidate_empty),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        return
    }

    val groupSize = candidates.size + if (lowConfidenceCount > 0) 1 else 0
    itemsIndexed(
        items = candidates,
        key = { _, candidate -> "memory_candidate_${candidate.id}" },
    ) { index, candidate ->
        MemoryCandidateCard(
            candidate = candidate,
            shape = memoryGroupShape(index, groupSize),
            onAccept = { onAccept(candidate.id) },
            onIgnore = { onIgnore(candidate.id) },
        )
    }
    if (lowConfidenceCount > 0) {
        item("memory_candidate_low_confidence") {
            val tokens = LocalAmberTokens.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(memoryGroupShape(groupSize - 1, groupSize))
                    .background(tokens.surface)
                    .border(1.dp, tokens.line, memoryGroupShape(groupSize - 1, groupSize))
                    .padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.End,
            ) {
                TextButton(onClick = onIgnoreLowConfidence) {
                    Text(stringResource(R.string.memory_ignore_low_confidence, lowConfidenceCount))
                }
            }
        }
    }
}

@Composable
private fun MemoryCandidateCard(
    candidate: MemoryCandidate,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surface)
            .border(1.dp, tokens.line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val reviewSuffix = if (candidate.confidence >= LOW_CONFIDENCE_CANDIDATE_THRESHOLD) {
            stringResource(R.string.memory_recommend_manual_review)
        } else {
            ""
        }
        Text(
            text = stringResource(
                R.string.memory_candidate_meta,
                candidate.scope.wireName,
                candidate.kind.wireName,
                "%.2f".format(candidate.confidence),
                reviewSuffix,
            ),
            // Graphite §3: scope/kind tags + confidence value are machine-facts → MONO.
            style = LocalAmberType.current.meta,
            color = LocalAmberTokens.current.accent,
        )
        Text(
            text = candidate.content,
            style = LocalAmberType.current.body,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (candidate.reason.isNotBlank()) {
            Text(
                text = candidate.reason,
                style = LocalAmberType.current.secondary,
                color = workspaceColors().muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.memory_accept))
            }
            TextButton(onClick = onIgnore) {
                Text(stringResource(R.string.memory_ignore))
            }
        }
    }
}
