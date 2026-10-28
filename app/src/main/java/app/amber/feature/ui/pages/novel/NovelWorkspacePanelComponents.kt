package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType

internal enum class PanelTone { Normal, Accent, Danger, Saved }

/**
 * Project the durable batch stage into the short status line shared by the sheet and
 * the chat banner, including the factual review before a batch polish is saved.
 */
@Composable
internal fun ghostwriteStageLabel(job: NovelMarkdownGhostwriteUi): String {
    val stage = if (job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish) {
        when (job.stage) {
            NovelWorkspaceGhostwriteStage.Reviewing -> stringResource(R.string.novel_batch_stage_polish_review)
            NovelWorkspaceGhostwriteStage.Committing -> stringResource(R.string.novel_saving)
            else -> stringResource(R.string.novel_batch_stage_polish)
        }
    } else {
        when (job.stage) {
            NovelWorkspaceGhostwriteStage.Idle,
            NovelWorkspaceGhostwriteStage.Writing ->
                stringResource(R.string.novel_batch_stage_writing)
            NovelWorkspaceGhostwriteStage.Reviewing ->
                stringResource(R.string.novel_batch_stage_reviewing)
            NovelWorkspaceGhostwriteStage.Rewriting ->
                stringResource(
                    R.string.novel_batch_stage_rewriting,
                    (job.rewriteAttempt + 1).coerceIn(1, 2),
                )
            NovelWorkspaceGhostwriteStage.Committing ->
                stringResource(R.string.novel_batch_stage_committing)
            NovelWorkspaceGhostwriteStage.Planning ->
                stringResource(R.string.novel_batch_stage_planning)
        }
    }
    val ordinal = job.currentChapterOrdinal.takeIf { it > 0 } ?: if (
        job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
    ) {
        (job.startOrdinal + job.written).coerceAtLeast(1)
    } else {
        (job.written + 1).coerceAtLeast(1)
    }
    return stringResource(R.string.novel_batch_stage, ordinal, stage)
}

/** A quiet section title and a single paper group keep project controls readable. */
@Composable
internal fun PanelSection(
    title: String,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = type.secondary.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.muted,
                modifier = Modifier.weight(1f),
            )
            action?.invoke(this)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(workspace.paper)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** Uniform pill action — one geometry for every panel button, tone only changes color. */
@Composable
internal fun PanelPill(
    text: String,
    tone: PanelTone = PanelTone.Normal,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val type = LocalAmberType.current
    val (bg, fg) = when (tone) {
        PanelTone.Accent -> chatTheme.accentSoft to chatTheme.accent
        PanelTone.Danger -> workspace.redContainer to workspace.red
        PanelTone.Saved -> workspace.greenContainer to workspace.green
        PanelTone.Normal -> workspace.row to workspace.ink
    }
    Box(
        Modifier.heightIn(min = 48.dp).novelPressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 36.dp).clip(CircleShape)
                .background(if (enabled) bg else workspace.row)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = type.meta, color = if (enabled) fg else workspace.faint)
        }
    }
}

/** Primary surface is compact while the whole 48dp row remains tappable. */
@Composable
internal fun PanelCtaButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val type = LocalAmberType.current
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .novelPressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (enabled) chatTheme.accent else workspace.row)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) chatTheme.onAccent else workspace.muted,
                maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun PanelRoundIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NovelWorkspaceNavButton(icon = icon, contentDescription = contentDescription,
        enabled = enabled, onClick = onClick)
}

/** Flat multi-line editor: no inner frame — the card is the only box (device feedback). */
@Composable
internal fun PanelLoading() {
    val workspace = workspaceColors()
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = workspace.ink,
        )
    }
}

@Composable
internal fun PanelEditor(
    placeholder: String,
    initial: String,
    text: String,
    enabled: Boolean,
    onSave: (String, () -> Unit) -> Unit,
    onTextChange: (String) -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var savedText by remember(initial) { mutableStateOf<String?>(null) }
    val saved = savedText == text
    val dirty = text != initial
    fun save(value: String) {
        onSave(value) {
            savedText = value
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        if (text.isEmpty()) {
            Text(placeholder, style = type.meta, color = workspace.faint)
        }
        BasicTextField(
            value = text,
            enabled = enabled,
            onValueChange = {
                onTextChange(it)
                savedText = null
            },
            textStyle = type.body.copy(color = workspace.ink),
            cursorBrush = SolidColor(workspace.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        if (text.isNotEmpty()) {
            PanelPill(
                text = stringResource(R.string.clear),
                enabled = enabled,
                onClick = {
                    onTextChange("")
                    save("")
                },
            )
        }
        PanelPill(
            text = if (saved) {
                stringResource(R.string.novel_saved)
            } else {
                stringResource(R.string.chat_page_save)
            },
            tone = if (saved) PanelTone.Saved else PanelTone.Accent,
            enabled = enabled && dirty,
            onClick = {
                save(text)
            },
        )
    }
}
