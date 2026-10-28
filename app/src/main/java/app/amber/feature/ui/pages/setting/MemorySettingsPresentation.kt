package app.amber.feature.ui.pages.setting

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.amber.agent.R
import app.amber.core.memory.safety.isSensitiveMemoryContent
import app.amber.core.model.AssistantMemory
import app.amber.core.model.MemoryKind
import app.amber.core.model.MemoryScope
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun memoryScopeLabel(scope: MemoryScope): String = when (scope) {
    MemoryScope.CORE -> stringResource(R.string.setting_agent_memory_scope_core)
    MemoryScope.SHORT_TERM -> stringResource(R.string.setting_agent_memory_scope_short_term)
    MemoryScope.LONG_TERM -> stringResource(R.string.setting_agent_memory_scope_long_term)
}

@Composable
internal fun memoryKindLabel(kind: MemoryKind): String = when (kind) {
    MemoryKind.USER -> stringResource(R.string.setting_agent_memory_kind_user)
    MemoryKind.FEEDBACK -> stringResource(R.string.setting_agent_memory_kind_feedback)
    MemoryKind.PROJECT -> stringResource(R.string.setting_agent_memory_kind_project)
    MemoryKind.REFERENCE -> stringResource(R.string.setting_agent_memory_kind_reference)
    MemoryKind.ROUTINE -> stringResource(R.string.setting_agent_memory_kind_routine)
    MemoryKind.NOTE -> stringResource(R.string.setting_agent_memory_kind_note)
    MemoryKind.TOPIC -> stringResource(R.string.setting_agent_memory_kind_topic)
}

internal fun formatMemoryDate(value: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault(),
    ).format(Date(value))

@Composable
internal fun MemoryRowSubtitle(
    text: String,
    mono: Boolean = true,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = text,
        style = if (mono) {
            type.meta.copy(fontSize = 12.sp, lineHeight = 16.sp)
        } else {
            type.secondary.copy(fontSize = 13.sp, lineHeight = 18.sp)
        },
        color = tokens.ink3,
    )
}

internal fun AssistantMemory.isSummarySensitive(): Boolean {
    return isSensitiveMemoryContent(content)
}

internal fun memoryGroupShape(index: Int, size: Int): RoundedCornerShape = when {
    size <= 1 -> RoundedCornerShape(14.dp)
    index == 0 -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    index == size - 1 -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    else -> RoundedCornerShape(0.dp)
}
