package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@Composable
fun MarkdownNew(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
    paragraphSpacing: Dp = 0.dp,
) {
    val syncParseOnMiss = markdownAllowsSyncParse(content)
    var document by remember {
        mutableStateOf(
            if (syncParseOnMiss) parseMarkdownHtmlDocument(content) else null
        )
    }

    val updatedContent by rememberUpdatedState(content)
    val parseDispatcher = LocalMarkdownParseDispatcher.current
    LaunchedEffect(Unit) {
        var lastParsed: String? = if (syncParseOnMiss) content else null
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .catch { it.printStackTrace() }
            .collect { latest ->
                if (latest == lastParsed) return@collect
                try {
                    val parsed = withContext(parseDispatcher) {
                        parseMarkdownHtmlDocument(latest)
                    }
                    lastParsed = latest
                    document = parsed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    error.printStackTrace()
                }
            }
    }

    ProvideTextStyle(style) {
        Column(
            modifier = modifier.padding(start = 4.dp),
            verticalArrangement = if (paragraphSpacing > 0.dp) {
                Arrangement.spacedBy(paragraphSpacing)
            } else {
                Arrangement.Top
            },
        ) {
            val prepared = document
            if (prepared == null) {
                androidx.compose.material3.Text(
                    text = content,
                    modifier = Modifier.clearAndSetSemantics { },
                    style = style,
                    color = Color.Transparent,
                )
            } else {
                prepared.body().childNodes().fastForEach { node ->
                    HtmlBodyNode(node = node, onClickCitation = onClickCitation)
                }
            }
        }
    }
}
