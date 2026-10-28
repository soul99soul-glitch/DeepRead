package app.amber.feature.ui.pages.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.agent.R
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2

@Composable
internal fun DeepReadBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(38.dp)
            .background(tokens.surface.copy(alpha = 0.72f), CircleShape)
            .border(1.dp, tokens.line2, CircleShape),
    ) {
        Icon(
            Lucide.ArrowLeft,
            contentDescription = stringResource(R.string.back),
            modifier = Modifier.size(18.dp),
            tint = tokens.ink,
        )
    }
}

@Composable
internal fun DeepReadShareButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(38.dp)
            .background(tokens.surface.copy(alpha = 0.72f), CircleShape)
            .border(1.dp, tokens.line2, CircleShape),
    ) {
        Icon(
            Lucide.Share2,
            contentDescription = stringResource(R.string.share),
            modifier = Modifier.size(17.dp),
            tint = tokens.ink,
        )
    }
}

@Composable
internal fun RunningStageNotice(
    progress: app.amber.feature.board.hotlist.deepread.DeepReadProgressSnapshot,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = tokens.surface.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line2),
        tonalElevation = 0.dp,
    ) {
        Text(
            "${progress.label} ${progress.percent}%",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = LocalAmberType.current.meta.copy(fontSize = 11.sp),
            color = tokens.ink2,
        )
    }
}

@Composable
internal fun DeepReadPartialErrorNotice(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = tokens.surface2,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.42f)),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDeepReadError(error).detail.ifBlank {
                    stringResource(R.string.deep_read_partial_content_preserved)
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            androidx.compose.material3.TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(retryLabel ?: stringResource(R.string.retry), color = tokens.accent)
            }
        }
    }
}

@Composable
internal fun TemplateFallbackNotice(message: String, modifier: Modifier = Modifier) {
    val tokens = LocalAmberTokens.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = tokens.surface2,
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line2),
        tonalElevation = 0.dp,
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = LocalAmberType.current.meta.copy(fontSize = 11.sp),
            color = tokens.ink2,
        )
    }
}

@Composable
internal fun DeepReadConfirmation(
    modifier: Modifier,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    fontScale: Float,
    onConfirm: () -> Unit,
) {
    DeepReadScaledText(fontScale) {
        Box(
            modifier
                .fillMaxSize()
                .background(palette.background)
                .deepReadPaper(
                    night = remember { DeepReadMoments.isNight() },
                    tokens = LocalAmberTokens.current,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    stringResource(R.string.deep_read_cost_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light, color = palette.ink)
                        .withReadingFont(fontFamily),
                )
                Text(
                    stringResource(R.string.deep_read_cost_message),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp, color = palette.muted)
                        .withReadingFont(fontFamily),
                )
                Button(onClick = onConfirm) {
                    Text(stringResource(R.string.deep_read_continue_generation))
                }
            }
        }
    }
}

@Composable
internal fun DeepReadError(
    error: String,
    modifier: Modifier,
    onRetry: () -> Unit,
    retryLabel: String? = null,
) {
    val ui = formatDeepReadError(error)
    val tokens = LocalAmberTokens.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                ui.title,
                style = LocalAmberType.current.sessionTitle,
                color = tokens.ink,
            )
            ui.reason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (ui.detail.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = tokens.surface2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
                    tonalElevation = 0.dp,
                ) {
                    SelectionContainer {
                        Text(
                            ui.detail,
                            modifier = Modifier.padding(14.dp),
                            style = LocalAmberType.current.secondary,
                            color = tokens.ink2,
                        )
                    }
                }
            }
            ui.suggestion?.let {
                Text(
                    it,
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink2,
                )
            }
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text(retryLabel ?: stringResource(R.string.retry), color = tokens.accent)
            }
        }
    }
}

private data class DeepReadErrorUi(
    val title: String,
    val reason: String?,
    val detail: String,
    val suggestion: String?,
)

@Composable
private fun formatDeepReadError(error: String): DeepReadErrorUi {
    val normalized = error.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) {
        return DeepReadErrorUi(
            title = stringResource(R.string.deep_read_generation_failed),
            reason = null,
            detail = stringResource(R.string.deep_read_error_no_details),
            suggestion = stringResource(R.string.deep_read_error_retry_or_switch),
        )
    }
    val lines = error.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
    val firstLine = lines.firstOrNull().orEmpty()
    val httpCode = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE)
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?: Regex("""response\D{0,12}(\d{3})""", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
    val title = when {
        "被拒绝" in firstLine || "rejected" in firstLine.lowercase() || httpCode == "400" ->
            stringResource(R.string.deep_read_error_request_rejected)
        httpCode in setOf("401", "403") -> stringResource(R.string.deep_read_error_auth)
        httpCode == "429" -> stringResource(R.string.deep_read_error_rate_limited)
        httpCode in setOf("500", "502", "503", "504") -> stringResource(R.string.deep_read_error_service)
        else -> stringResource(R.string.deep_read_generation_failed)
    }
    val detail = lines
        .drop(1)
        .takeWhile { !it.startsWith("可能") && !it.startsWith("请") && !it.startsWith("当前") && !it.startsWith("这是") && !it.startsWith("请求") }
        .joinToString("\n")
        .ifBlank { normalized }
        .take(900)
    val suggestion = lines
        .lastOrNull()
        ?.takeIf { it != firstLine && it != detail && looksLikeSuggestion(it) }
        ?: defaultDeepReadErrorSuggestion(httpCode, normalized)
    return DeepReadErrorUi(
        title = title,
        reason = httpCode?.let { "HTTP $it" },
        detail = detail,
        suggestion = suggestion,
    )
}

private fun looksLikeSuggestion(text: String): Boolean =
    listOf("可以", "建议", "请", "可能", "稍后", "切换", "retry", "switch", "check", "may", "later")
        .any { it in text || it in text.lowercase() }

@Composable
private fun defaultDeepReadErrorSuggestion(httpCode: String?, message: String): String? {
    val lower = message.lowercase()
    return when {
        httpCode == "400" && listOf("reject", "rejected", "safety", "policy", "blocked").any { it in lower } ->
            stringResource(R.string.deep_read_error_safety_suggestion)
        httpCode == "400" -> stringResource(R.string.deep_read_error_invalid_request_suggestion)
        httpCode in setOf("401", "403") -> stringResource(R.string.deep_read_error_auth_suggestion)
        httpCode == "429" -> stringResource(R.string.deep_read_error_rate_limit_suggestion)
        httpCode in setOf("500", "502", "503", "504") -> stringResource(R.string.deep_read_error_service_suggestion)
        else -> null
    }
}
