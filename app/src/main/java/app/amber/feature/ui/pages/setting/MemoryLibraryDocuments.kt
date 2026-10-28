package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.res.pluralStringResource
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Pencil
import app.amber.agent.R
import app.amber.core.model.AssistantMemory
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

internal fun LazyListScope.memoryDocumentsSection(
    title: String,
    emptyText: String,
    docs: List<MemoryDoc>,
    isLoading: Boolean = false,
    infoText: String,
    onInfoClick: (String, String) -> Unit,
    onAddMemory: () -> Unit,
    onOpenDoc: (String) -> Unit,
) {
    item("memory_docs_header") {
        MemoryRecordsHeader(
            title = title,
            infoTitle = title,
            infoText = infoText,
            onInfoClick = onInfoClick,
            onAddMemory = onAddMemory,
        )
    }
    item("memory_docs_gap") { Spacer(Modifier.height(8.dp)) }
    if (docs.isEmpty()) {
        if (isLoading) {
            item("memory_docs_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        } else {
            item("memory_docs_empty") {
                AmberCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = emptyText,
                        style = LocalAmberType.current.secondary,
                        color = workspaceColors().muted,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        return
    }
    itemsIndexed(
        items = docs,
        key = { _, doc -> "memory_doc_${doc.id}" },
    ) { index, doc ->
        MemoryDocRow(
            doc = doc,
            shape = memoryGroupShape(index, docs.size),
            onClick = { onOpenDoc(doc.id) },
        )
    }
}

@Composable
private fun MemoryDocRow(
    doc: MemoryDoc,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surface)
            .border(1.dp, tokens.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = doc.fileName,
                        style = LocalAmberType.current.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = formatMemorySize(doc.sizeBytes),
                        style = LocalAmberType.current.meta,
                        color = workspaceColors().muted,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
                }
                Text(
                    text = formatMemoryDate(doc.updatedAt),
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                )
            }
            Text(
                text = doc.preview.ifBlank { "—" },
                style = LocalAmberType.current.secondary,
                color = workspaceColors().muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = workspaceColors().muted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun MemoryDocDetail(
    doc: MemoryDoc,
    modifier: Modifier,
    onBack: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val countLabel = if (doc.topic != null) {
        pluralStringResource(R.plurals.memory_doc_members, doc.records.size, doc.records.size)
    } else {
        pluralStringResource(R.plurals.memory_doc_entries, doc.records.size, doc.records.size)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = tokens.ink,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.fileName,
                    style = LocalAmberType.current.sessionTitle,
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$countLabel · ${formatMemorySize(doc.sizeBytes)} · ${formatMemoryDate(doc.updatedAt)}",
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The topic record itself is the document — its edit/delete lives
            // on the header, not in the member entries below.
            doc.topic?.let { topic ->
                IconButton(
                    onClick = { onEditMemory(topic) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Lucide.Pencil,
                        contentDescription = stringResource(R.string.edit),
                        tint = workspaceColors().muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onDeleteMemory(topic) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .height(1.dp)
                .background(tokens.line),
        )
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                doc.topic?.let { topic ->
                    item("doc_topic_body") {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            topic.topicTitle?.takeIf { it.isNotBlank() }?.let { title ->
                                Text(
                                    text = title,
                                    style = LocalAmberType.current.sessionTitle,
                                    color = tokens.ink,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                text = topic.content.trim(),
                                style = LocalAmberType.current.body,
                                color = tokens.ink,
                            )
                        }
                    }
                    if (doc.records.isNotEmpty()) {
                        item("doc_members_label") {
                            SectionLabel(
                                text = pluralStringResource(
                                    R.plurals.memory_doc_members,
                                    doc.records.size,
                                    doc.records.size,
                                ),
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = doc.records,
                    key = { _, memory -> "doc_entry_${memory.id}" },
                ) { index, memory ->
                    MemoryDocEntry(
                        memory = memory,
                        onEditMemory = onEditMemory,
                        onDeleteMemory = onDeleteMemory,
                    )
                    if (index < doc.records.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(tokens.line),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryDocEntry(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = memory.content.trim(),
            style = LocalAmberType.current.body,
            color = tokens.ink,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onEditMemory(memory) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Lucide.Pencil,
                    contentDescription = stringResource(R.string.edit),
                    tint = workspaceColors().muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { onDeleteMemory(memory) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MemoryRecordsHeader(
    title: String,
    infoTitle: String?,
    infoText: String?,
    onInfoClick: ((String, String) -> Unit)?,
    onAddMemory: (() -> Unit)?,
) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(
                text = title,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(tokens.line),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onInfoClick != null && infoTitle != null && infoText != null) {
                    val infoDescription = stringResource(R.string.setting_agent_memory_info_content_description)
                    IconButton(
                        onClick = { onInfoClick(infoTitle, infoText) },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = infoDescription
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .border(
                                    width = 1.dp,
                                    color = workspaceColors().hairline,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "?",
                                style = LocalAmberType.current.tinyTag,
                                color = workspaceColors().muted,
                            )
                        }
                    }
                }
                if (onAddMemory != null) {
                    IconButton(
                        onClick = onAddMemory,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Lucide.Plus,
                            contentDescription = stringResource(
                                R.string.setting_agent_memory_add_content_description,
                            ),
                        )
                    }
                }
            }
        }
    }
}
