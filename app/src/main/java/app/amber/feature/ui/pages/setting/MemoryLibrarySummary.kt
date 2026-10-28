package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.core.model.AssistantMemory
import app.amber.core.model.MemoryKind
import app.amber.core.model.MemoryScope
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun MemorySummarySection(
    coreMemories: List<AssistantMemory>,
    longTermMemories: List<AssistantMemory>,
    shortTermMemories: List<AssistantMemory>,
    onEditMemory: (AssistantMemory) -> Unit,
) {
    val stableMemories = (coreMemories + longTermMemories)
        .filter { memory ->
            !memory.archived &&
                !memory.isSummarySensitive() &&
                (
                    memory.scope == MemoryScope.CORE ||
                        memory.kind == MemoryKind.USER ||
                        memory.kind == MemoryKind.FEEDBACK ||
                        memory.kind == MemoryKind.ROUTINE ||
                        memory.pinned
                    )
        }
        .distinctBy { it.id }
    val longTermProjects = longTermMemories
        .filter { memory ->
                !memory.archived &&
                !memory.isSummarySensitive() &&
                memory.kind in setOf(
                    MemoryKind.PROJECT,
                    MemoryKind.REFERENCE,
                )
        }
    val currentProjects = shortTermMemories
        .filter { memory ->
            !memory.archived &&
                !memory.isSummarySensitive() &&
                memory.kind == MemoryKind.PROJECT
        }

    val emptyValue = stringResource(R.string.memory_summary_empty)
    val facts = listOf(
        SummaryFact(
            label = stringResource(R.string.memory_summary_stable_preferences),
            value = stableMemories.firstOrNull()?.content ?: emptyValue,
            memory = stableMemories.firstOrNull(),
        ),
        SummaryFact(
            label = stringResource(R.string.memory_summary_long_term_projects),
            value = longTermProjects.firstOrNull()?.content ?: emptyValue,
            memory = longTermProjects.firstOrNull(),
        ),
        SummaryFact(
            label = stringResource(R.string.memory_summary_current_short_term),
            value = currentProjects.firstOrNull()?.content ?: emptyValue,
            memory = currentProjects.firstOrNull(),
        ),
    )

    SettingSectionTitle(
        text = stringResource(R.string.memory_summary_title),
        modifier = Modifier.padding(start = 2.dp, top = 8.dp, end = 2.dp),
    )
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(LocalAmberTokens.current.line),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(fact.memory?.let { memory -> Modifier.clickable { onEditMemory(memory) } } ?: Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .heightIn(min = 64.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = fact.label,
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink3,
                )
                Text(
                    text = fact.value,
                    style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                    color = LocalAmberTokens.current.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class SummaryFact(
    val label: String,
    val value: String,
    val memory: AssistantMemory?,
)
