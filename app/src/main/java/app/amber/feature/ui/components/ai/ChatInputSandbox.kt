package app.amber.feature.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amber.feature.runtime.SandboxActivityUiState

// Chat composer activity hosts; state remains in the existing runtime / WebView stores.

@Composable
internal fun SandboxPeekBar(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // V3 convo-tool-result.jsx ToolResultPreview spec:
    //   设计稿 asymmetric padding (start=14 end=32 top=10 bottom=8); 父级 ChatInput
    //   已加 horizontal=8 padding + spacedBy 8dp, 我们只控 horizontal (start=6 end=24).
    //   bottom 给 0 (让卡片紧贴下面的 ChatInput 输入框), top 维持轻量缓冲.
    //   gap=10, align Bottom; 缩略图 72×96 (3:4 竖向); ResultPill 22dp 高 999 圆角
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 24.dp, top = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AgentOperationPreviewPeek(
            activity = activity,
            onOpen = onOpen,
            modifier = Modifier
                .width(72.dp)
                .height(96.dp),
        )
        SandboxStepPeek(
            activity = activity,
            onOpen = onOpen,
            onCancel = onCancel,
            onPrevious = onPrevious,
            onNext = onNext,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SandboxActivitySheet(
    activity: SandboxActivityUiState,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BackHandler { onDismiss() }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.86f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val previewUrl = activity.operationPreviewUrl()
            SandboxSheetHeader(
                activity = activity,
                isWebPreview = previewUrl != null,
                onCancel = onCancel,
                onPrevious = onPrevious,
                onNext = onNext,
            )

            if (previewUrl != null) {
                SandboxWebActivityContent(
                    activity = activity,
                    url = previewUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                SandboxToolActivityContent(
                    activity = activity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
