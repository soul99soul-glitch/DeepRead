package app.amber.feature.ui.pages.extensions

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.FileInput
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun PromptDock(
    label: String,
    onImport: () -> Unit,
    onAdd: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            shape = CircleShape,
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.line),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onImport,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Lucide.FileInput,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = tokens.ink2,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(width = 1.dp, height = 20.dp)
                        .background(tokens.line),
                )
                TextButton(
                    onClick = onAdd,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text(
                        text = label,
                        color = tokens.accent,
                        style = LocalAmberType.current.body.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
