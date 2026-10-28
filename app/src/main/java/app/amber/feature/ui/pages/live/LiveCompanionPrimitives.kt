package app.amber.feature.ui.pages.live

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.components.ds.AmberSegmentedTrack
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun ToggleRow(
    label: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = type.body.copy(fontWeight = FontWeight.Medium), color = t.ink)
            if (hint != null) Text(hint, style = type.meta, color = t.ink3)
        }
        AmberToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Accent-fill-when-on toggle, line-2 track when off, white knob (design §6.2). */
@Composable
internal fun AmberToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val t = LocalAmberTokens.current
    val trackW = 44.dp
    val trackH = 26.dp
    val knob = 20.dp
    val track by animateColorAsState(if (checked) t.accent else t.line2, label = "toggleTrack")
    val knobOffset by animateDpAsState(if (checked) trackW - knob - 3.dp else 3.dp, label = "toggleKnob")
    // 48dp touch box wraps the visual track; visuals stay per spec.
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .pressable(onClick = { if (enabled) onCheckedChange(!checked) }, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(trackW, trackH)
                .clip(RoundedCornerShape(999.dp))
                .background(track),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = knobOffset)
                    .size(knob)
                    .clip(CircleShape)
                    // white knob is a design-system constant (§6.2 "white knob"), not a theme token.
                    .background(Color(0xFFFFFFFF)),
            )
        }
    }
}

/** Segmented control: surface-2 track, raised active thumb (design §6.2). */
@Composable
internal fun AmberSeg(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(999.dp)
    AmberSegmentedTrack(
        selectedIndex = selectedIndex,
        optionCount = options.size,
        onSelect = onSelect,
        shape = shape,
        trackColor = t.surface2,
        thumbColor = t.raised,
        modifier = Modifier.fillMaxWidth(),
        itemSpacing = 3.dp,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) { index, active ->
        Text(
            text = options[index],
            style = type.secondary.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
            color = if (active) t.ink else t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 填入/复制按钮（蓝图 §7.2 P0-5 仲裁 + §7.3 P1-2 白名单）：CHAT 白名单内显示
 *  "填入"，其余显示"复制草稿"；Manager 仲裁返回 NEEDS_CONFIRM 时切"覆盖"确认态
 *  （5s 无操作自动复位）。Toast 反馈集中在这里。 */
@Composable
internal fun PillButton(text: String, accent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) t.accent else t.surface2)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = if (accent) t.accentInk else t.ink,
        )
    }
}


// ───────────────────────────── state helpers ─────────────────────────────
