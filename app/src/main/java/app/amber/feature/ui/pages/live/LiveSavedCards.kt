package app.amber.feature.ui.pages.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import app.amber.agent.R
import app.amber.agent.data.db.entity.LiveCardEntity
import app.amber.core.utils.appLocale
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SavedCardRow(card: LiveCardEntity, onDelete: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val appLocale = LocalContext.current.appLocale()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = formatSavedTime(card.createdAt, appLocale),
            style = type.meta,
            color = t.ink4,
        )
        Text(
            text = card.appLabel.ifBlank { card.packageName },
            style = type.meta,
            color = t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp),
        )
        Text(
            text = card.watching,
            style = type.secondary,
            color = t.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.size(48.dp).pressable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, contentDescription = stringResource(R.string.delete), tint = t.ink4, modifier = Modifier.size(15.dp))
        }
    }
}

internal fun formatSavedTime(timestampMs: Long, locale: Locale): String {
    val zone = java.time.ZoneId.systemDefault()
    val sameDay = java.time.Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate() ==
        java.time.Instant.now().atZone(zone).toLocalDate()
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, locale).format(Date(timestampMs))
}
