package app.amber.feature.ui.pages.sessionhome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.model.Conversation
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalThemePack
import androidx.compose.material3.Text
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.ScanSearch

/** Home-only title filter; the full message search remains [Screen.MessageSearch]. */
internal fun filterHomeConversations(
    conversations: List<Conversation>,
    query: String,
    untitledLabel: String,
): List<Conversation> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return conversations
    return conversations.filter { conversation ->
        conversation.title.ifBlank { untitledLabel }
            .contains(trimmedQuery, ignoreCase = true)
    }
}

/** Home-only title search with a direct full-message-search escape hatch. */
@Composable
internal fun HomeSearchField(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onOpenFullSearch: () -> Unit,
    onCollapse: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val controlRadius = LocalThemePack.current?.design?.components?.controlRadius?.toFloat()?.dp ?: 12.dp
    val searchShape = remember(controlRadius) {
        androidx.compose.foundation.shape.RoundedCornerShape(controlRadius)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(searchShape)
                    .background(tokens.surface2)
                    .border(1.dp, tokens.line, searchShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.5.sp,
                        lineHeight = 18.sp,
                        color = tokens.ink,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onOpenFullSearch() }),
                    decorationBox = { innerTextField ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.Search,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = tokens.ink3,
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                if (query.isBlank()) {
                                    Text(
                                        text = stringResource(R.string.parity_home_search_sessions),
                                        fontSize = 14.5.sp,
                                        lineHeight = 18.sp,
                                        color = tokens.ink4,
                                    )
                                }
                                innerTextField()
                            }
                        }
                    },
                )
            }
            IconButton(
                onClick = if (query.isBlank()) onCollapse else onClear,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = stringResource(
                        if (query.isBlank()) {
                            R.string.parity_home_search_cancel
                        } else {
                            R.string.parity_home_search_clear
                        }
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = tokens.ink3,
                )
            }
            IconButton(
                onClick = onOpenFullSearch,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Lucide.ScanSearch,
                    contentDescription = stringResource(R.string.parity_home_search_full),
                    modifier = Modifier.size(19.dp),
                    tint = tokens.ink2,
                )
            }
    }
}
