package app.amber.feature.ui.pages.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import app.amber.agent.R
import app.amber.ai.provider.ModelType
import app.amber.feature.live.LiveScene
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

@Composable
internal fun ConfigCard(
    aggressive: Boolean,
    onSelectMode: (Boolean) -> Unit,
    bubbleEnabled: Boolean,
    onToggleBubble: (Boolean) -> Unit,
    modelId: Uuid?,
    providers: List<app.amber.ai.provider.ProviderSetting>,
    onClearModel: () -> Unit,
    onSelectModel: (String) -> Unit,
    currentAppLabel: String,
    sceneOverride: String?,
    onSelectScene: (LiveScene?) -> Unit,
    autoSuggestEnabled: Boolean,
    onToggleAutoSuggest: (Boolean) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.live_analysis_mode),
                    style = type.body.copy(fontWeight = FontWeight.Medium),
                    color = t.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (aggressive) {
                        stringResource(R.string.live_analysis_mode_screenshot)
                    } else {
                        stringResource(R.string.live_analysis_mode_text_only)
                    },
                    style = type.meta,
                    color = t.ink3,
                )
            }
            AmberSeg(
                options = listOf(
                    stringResource(R.string.live_mode_conservative),
                    stringResource(R.string.live_mode_aggressive),
                ),
                selectedIndex = if (aggressive) 1 else 0,
                onSelect = { onSelectMode(it == 1) },
            )
        }

        Hairline()
        ToggleRow(
            label = stringResource(R.string.live_bubble),
            hint = stringResource(R.string.live_bubble_hint),
            checked = bubbleEnabled,
            onCheckedChange = onToggleBubble,
        )

        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Lucide.Settings, contentDescription = null, tint = t.ink3, modifier = Modifier.size(17.dp))
            Text(
                stringResource(R.string.live_analysis_model),
                style = type.body.copy(fontWeight = FontWeight.Medium),
                color = t.ink,
                modifier = Modifier.weight(1f),
            )
            ModelSelector(
                modelId = modelId,
                providers = providers,
                type = ModelType.CHAT,
                minimalText = true,
                allowClear = true,
                emptyLabel = stringResource(R.string.live_follow_chat_model),
                onClear = onClearModel,
                onSelect = { model -> onSelectModel(model.id.toString()) },
            )
        }

        // 当前应用场景覆盖（蓝图 §7.3 P1-9）：仅在有观察现场时可配。
        if (currentAppLabel.isNotBlank()) {
            Hairline()
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.live_scene_override_label),
                        style = type.body.copy(fontWeight = FontWeight.Medium),
                        color = t.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(currentAppLabel, style = type.meta, color = t.ink3, maxLines = 1)
                }
                val sceneOptions = listOf(
                    null to stringResource(R.string.live_scene_default),
                    LiveScene.CHAT to stringResource(R.string.live_scene_chat),
                    LiveScene.READING to stringResource(R.string.live_scene_reading),
                    LiveScene.OTHER to stringResource(R.string.live_scene_other),
                )
                val selectedIndex = sceneOptions.indexOfFirst {
                    it.first?.name?.lowercase() == sceneOverride || (it.first == null && sceneOverride == null)
                }.coerceAtLeast(0)
                AmberSeg(
                    options = sceneOptions.map { it.second },
                    selectedIndex = selectedIndex,
                    onSelect = { index -> onSelectScene(sceneOptions[index].first) },
                )
            }
            Hairline()

            // 有限自动建议（蓝图 §7.4 P2）：逐 App 开启，默认关。
            ToggleRow(
                label = stringResource(R.string.live_auto_suggest_label),
                hint = stringResource(R.string.live_auto_suggest_hint),
                checked = autoSuggestEnabled,
                onCheckedChange = onToggleAutoSuggest,
            )
        }
    }
}
