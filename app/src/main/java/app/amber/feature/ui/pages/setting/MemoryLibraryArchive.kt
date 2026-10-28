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
import app.amber.core.model.AssistantMemory
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

internal fun LazyListScope.memoryArchivedSection(
    archivedMemories: List<AssistantMemory>,
    restoringIds: Set<Int>,
    onRestore: (AssistantMemory) -> Unit,
) {
    item("memory_archived_header") {
        SettingSectionTitle(
            stringResource(R.string.memory_archived_title)
        )
    }
    item("memory_archived_gap") { Spacer(Modifier.height(8.dp)) }
    if (archivedMemories.isEmpty()) {
        item("memory_archived_empty") {
            AmberCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.memory_archived_empty),
                    style = LocalAmberType.current.secondary,
                    color = workspaceColors().muted,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        return
    }
    itemsIndexed(
        items = archivedMemories,
        key = { _, memory -> "memory_archived_${memory.id}" },
    ) { index, memory ->
        MemoryArchivedRow(
            memory = memory,
            shape = memoryGroupShape(index, archivedMemories.size),
            restoring = memory.id in restoringIds,
            onRestore = { onRestore(memory) },
        )
    }
}

@Composable
private fun MemoryArchivedRow(
    memory: AssistantMemory,
    shape: RoundedCornerShape,
    restoring: Boolean,
    onRestore: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surface)
            .border(1.dp, tokens.line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = memory.content.trim(),
                style = LocalAmberType.current.body,
                color = tokens.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(
                    memoryKindLabel(memory.kind),
                    memoryScopeLabel(memory.scope),
                    formatMemoryDate(memory.updatedAt),
                ).joinToString(" · "),
                style = LocalAmberType.current.meta,
                color = workspaceColors().muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TextButton(onClick = onRestore, enabled = !restoring) {
            Text(stringResource(R.string.memory_restore))
        }
    }
}
