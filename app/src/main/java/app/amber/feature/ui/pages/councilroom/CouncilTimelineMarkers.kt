package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilPhaseMarker
import app.amber.agent.R
import app.amber.feature.ui.pages.chat.LocalChatTheme

@Composable
internal fun CouncilTimelineEmpty(objective: String) {
    val chatTheme = LocalChatTheme.current
    val emptyObjective = stringResource(R.string.council_room_empty_objective)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "amber council",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = chatTheme.ink,
        )
        Text(
            text = objective.ifBlank { emptyObjective },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = chatTheme.ink,
        )
        Text(
            text = stringResource(R.string.council_room_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = chatTheme.inkFaint,
        )
    }
}

@Composable
internal fun PhaseDivider(marker: CouncilPhaseMarker) {
    val chatTheme = LocalChatTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(chatTheme.surfaceEdge),
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = chatTheme.surface,
            border = BorderStroke(1.dp, chatTheme.surfaceEdge),
        ) {
            Text(
                text = marker.label,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = chatTheme.inkFaint,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(chatTheme.surfaceEdge),
        )
    }
}
