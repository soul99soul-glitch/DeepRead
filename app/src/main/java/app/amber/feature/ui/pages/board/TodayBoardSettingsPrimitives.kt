package app.amber.feature.ui.pages.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide

@Composable
internal fun BoardSectionCard(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("//", style = LocalAmberType.current.eyebrow, color = tokens.accent)
            Text(title, style = LocalAmberType.current.eyebrow, color = tokens.ink2)
            androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = tokens.line)
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = tokens.surface,
            contentColor = tokens.ink,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        footer?.takeIf { it.isNotBlank() }?.let {
            // iOS Form section footer: muted caption under the card.
            Text(
                it,
                style = LocalAmberType.current.secondary,
                color = tokens.ink3,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun BoardDivider() {
    val tokens = LocalAmberTokens.current
    androidx.compose.material3.HorizontalDivider(
        // Align the hairline with the row label: icon(14+32) + gap(12) = 58dp.
        Modifier.padding(start = 58.dp),
        color = tokens.line,
    )
}

@Composable
internal fun DetailNavigationRow(
    title: String,
    description: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            WorkspaceLeadingIcon(icon = it, size = 32.dp, iconSize = 17.dp)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.ink,
            )
            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink3,
                )
            }
        }
        value?.takeIf { it.isNotBlank() }?.let {
            // iOS LabeledContent value — current selection shown beside the chevron.
            Text(
                text = it,
                style = LocalAmberType.current.secondary,
                color = tokens.ink3,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tokens.ink3,
        )
    }
}

@Composable
internal fun SourceSwitch(
    title: String,
    description: String,
    checked: Boolean,
    icon: ImageVector? = null,
    onToggle: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (description.isBlank()) 52.dp else 64.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let {
            WorkspaceLeadingIcon(icon = it, size = 32.dp, iconSize = 17.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = LocalAmberType.current.body.copy(fontWeight = FontWeight.Medium), color = tokens.ink)
            if (description.isNotBlank()) {
                Text(description, style = LocalAmberType.current.secondary, color = tokens.ink2)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            trackColor = tokens.accent,
            trackColorUnchecked = tokens.surface2,
            thumbColor = tokens.accentInk,
            thumbColorUnchecked = tokens.ink2,
        )
    }
}

@Composable
internal fun RadioRow(selected: Boolean, label: String, description: String, onClick: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp).padding(2.dp)) {
            Surface(
                Modifier.fillMaxSize(),
                RoundedCornerShape(50),
                color = if (selected) tokens.accent else tokens.ink3,
                content = {},
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = workspaceColors().ink)
            Text(description, style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        }
    }
}

@Composable
internal fun ChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) tokens.accent else tokens.surface2,
        contentColor = if (selected) tokens.accentInk else tokens.ink2,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) tokens.accent else tokens.line2),
        modifier = modifier
            .heightIn(min = 32.dp)
            .clickable { onClick() },
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = LocalAmberType.current.secondary.copy(fontSize = 13.sp))
    }
}
