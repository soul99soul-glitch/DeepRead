package app.amber.feature.ui.pages.setting.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.amber.agent.R
import app.amber.feature.ui.pages.setting.components.LocalProviderFieldInCard
import app.amber.feature.ui.pages.setting.components.LocalProviderRowInset
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderSegOption
import app.amber.feature.ui.pages.setting.components.ProviderSegmented
import app.amber.feature.ui.pages.setting.components.ProviderToggle
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun ProviderDeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Dialog(onDismissRequest = onDismiss) {
        ProviderCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.confirm_delete),
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = t.ink,
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_delete_dialog_text),
                    style = type.secondary,
                    color = t.ink3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ProviderCommandButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        plain = true,
                        modifier = Modifier.weight(1f),
                    )
                    ProviderCommandButton(
                        text = stringResource(R.string.delete),
                        onClick = onConfirm,
                        danger = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Card row for provider-config fields: 12sp caption label above a borderless in-card value.
 * The label turns accent while any child holds focus.
 */
@Composable
internal fun ProviderLabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .padding(horizontal = LocalProviderRowInset.current, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = type.secondary.copy(fontSize = 12.sp),
            color = if (focused) t.accent else t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalProviderFieldInCard provides true) {
            Box(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** Connected segmented control used inside config cards (36dp). */
@Composable
internal fun <T> ProviderConfigCapsule(
    options: List<ProviderSegOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProviderSegmented(
        options = options,
        selected = selected,
        onSelected = onSelected,
        modifier = modifier.fillMaxWidth(),
        height = 36.dp,
    )
}

/** Card row: 15sp title with a trailing toggle, inset by [LocalProviderRowInset]. */
@Composable
internal fun ProviderSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val inset = LocalProviderRowInset.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            // The 48dp toggle carries its own touch padding; trim the trailing inset to match.
            .padding(start = inset, end = (inset - 6.dp).coerceAtLeast(0.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = type.body.copy(fontSize = 15.sp, lineHeight = 20.sp),
            color = t.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProviderToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}
