package app.amber.feature.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.IntelligentUiComponentKind
import app.amber.core.ai.generative.IntelligentUiDocument
import app.amber.core.ai.generative.IntelligentUiOptionItem
import app.amber.core.ai.generative.IntelligentUiStateStore
import app.amber.core.ai.generative.TaskUiKind
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Sparkles

private val CardErrorColor = Color(0xFFC93B2B)
private val CardSuccessColor = Color(0xFF1F8A5B)

/**
 * Top-level adaptive native `task-ui` card rendered directly in the chat message list.
 */
@Composable
internal fun IntelligentUiCard(
    document: IntelligentUiDocument,
    cardKey: String,
    isStreaming: Boolean = false,
    wasStreaming: Boolean = false,
    onActionPrompt: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val reduceMotion = remember(context) { UIGenerationMotion.isReduceMotionEnabled(context) }

    val state = remember(cardKey) {
        IntelligentUiStateStore.stateFor(cardKey, document)
    }
    LaunchedEffect(document) {
        state.syncWithDocument(document)
    }

    val revealState = remember(cardKey) {
        UIGenerationRevealState("task-ui:$cardKey").also {
            if (wasStreaming) {
                it.update(isStreaming = true, complete = false)
            }
        }
    }
    var entranceReady by remember(cardKey) { mutableStateOf(!wasStreaming || reduceMotion) }
    LaunchedEffect(isStreaming, wasStreaming, reduceMotion) {
        if (revealState.update(isStreaming = isStreaming, complete = !isStreaming)) {
            if (!reduceMotion) {
                entranceReady = false
                kotlinx.coroutines.yield()
                entranceReady = true
            }
        } else if (!isStreaming) {
            entranceReady = true
        }
    }
    val entranceAlpha by animateFloatAsState(
        targetValue = if (entranceReady) 1f else 0.88f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "task-ui-entrance-alpha",
    )

    val cardBg = chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: tokens.surface
    val borderColor = chatTheme.surfaceEdge.takeIf { it.isSpecified } ?: tokens.line
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent

    val validationErrors = state.validationErrors(document)
    val canSubmit = validationErrors.isEmpty() && onActionPrompt != null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = entranceAlpha }
            .animateContentSize(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Header
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Lucide.Sparkles,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = accent,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(accent.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = document.kind.displayLabel,
                                fontFamily = AmberMono,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = accent,
                            )
                        }
                    }

                    document.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                        InlineToneBadge(text = badge, color = tokens.ink3)
                    }
                }

                Text(
                    text = document.title,
                    fontFamily = AmberSans,
                    fontSize = 15.5.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                )

                document.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        fontFamily = AmberSans,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = tokens.ink3,
                    )
                }

                document.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(
                        text = summary,
                        fontFamily = AmberSans,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = tokens.ink2,
                    )
                }
            }

            // 2. Top-level decision options (when provided in document.options)
            if (document.options.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    document.options.forEach { option ->
                        TopLevelDecisionOptionRow(
                            option = option,
                            selected = state.selectedDecisionOptionId == option.id || state.selectedDecisionOptionId == option.value,
                            accent = accent,
                            onSelect = {
                                state.selectedDecisionOptionId = option.id
                                state.submittedSummary = null
                            },
                        )
                    }
                }
            }

            // 3. Body Components
            if (document.kind == TaskUiKind.STUDY) {
                val flashcards = document.flashcardNodes()
                val quizNodes = document.quizNodes()
                val hasExplicitScoreSummary = document.allNodes().any { it.kind == IntelligentUiComponentKind.SCORE_SUMMARY }

                if (flashcards.isNotEmpty()) {
                    IntelligentUiFlashcardDeckSection(
                        flashcards = flashcards,
                        state = state,
                    )
                }

                val otherTopNodes = document.components.mapNotNull { it.withoutStudyNodes() }
                otherTopNodes.forEach { node ->
                    IntelligentUiComponentView(
                        node = node,
                        document = document,
                        state = state,
                        onActionPrompt = onActionPrompt,
                    )
                }

                if (quizNodes.isNotEmpty()) {
                    val displayedQuiz = if (state.retryWrongOnly) {
                        quizNodes.filter { state.retryWrongQuestionIds.containsKey(it.id) }
                    } else {
                        quizNodes
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        displayedQuiz.forEach { qNode ->
                            val originalIdx = quizNodes.indexOfFirst { it.id == qNode.id }.coerceAtLeast(0)
                            IntelligentUiQuizItemNode(
                                node = qNode,
                                indexLabel = qNode.badge ?: "Q${originalIdx + 1}",
                                state = state,
                            )
                        }
                    }
                }

                if (!hasExplicitScoreSummary && (flashcards.isNotEmpty() || quizNodes.isNotEmpty())) {
                    IntelligentUiScoreSummarySection(
                        flashcards = flashcards,
                        quizNodes = quizNodes,
                        state = state,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    document.components.forEach { node ->
                        IntelligentUiComponentView(
                            node = node,
                            document = document,
                            state = state,
                            onActionPrompt = onActionPrompt,
                        )
                    }
                }
            }

            // 3b. Optional top-level actions
            if (document.actions.isNotEmpty() && onActionPrompt != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    document.actions.forEach { action ->
                        Box(
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(tokens.surface2)
                                .border(1.dp, tokens.line2, RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) {
                                    onActionPrompt(action.instruction)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = action.label,
                                fontFamily = AmberSans,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = tokens.ink2,
                            )
                        }
                    }
                }
            }

            // 4. Validation error banner (when invalid inputs block submission)
            if (validationErrors.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardErrorColor.copy(alpha = 0.08f))
                        .border(1.dp, CardErrorColor.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    validationErrors.take(3).forEach { err ->
                        Text(
                            text = "• $err",
                            fontFamily = AmberSans,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = CardErrorColor,
                        )
                    }
                }
            }

            // 5. Submitted confirmation banner
            state.submittedSummary?.takeIf { it.isNotBlank() }?.let { submitted ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardSuccessColor.copy(alpha = 0.09f))
                        .border(1.dp, CardSuccessColor.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = CardSuccessColor,
                    )
                    Text(
                        text = "已同步到对话",
                        fontFamily = AmberSans,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CardSuccessColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 6. Action Footer (Reset & Submit)
            HorizontalDivider(thickness = 0.5.dp, color = tokens.line)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(tokens.surface2)
                        .border(1.dp, tokens.line, RoundedCornerShape(9.dp))
                        .clickable(role = Role.Button) { state.reset(document) }
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Lucide.RotateCcw,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = tokens.ink3,
                    )
                    Text(
                        text = document.resetLabel,
                        fontFamily = AmberSans,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.ink2,
                    )
                }

                Row(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (canSubmit) accent else tokens.surface2)
                        .border(1.dp, if (canSubmit) accent else tokens.line, RoundedCornerShape(9.dp))
                        .clickable(enabled = canSubmit, role = Role.Button) {
                            val prompt = state.buildSubmissionPrompt(document)
                            state.submittedSummary = prompt
                            onActionPrompt?.invoke(prompt)
                        }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Send,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (canSubmit) tokens.accentInk else tokens.ink4,
                    )
                    Text(
                        text = document.submitLabel,
                        fontFamily = AmberSans,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canSubmit) tokens.accentInk else tokens.ink4,
                    )
                }
            }
        }
    }
}

private fun app.amber.core.ai.generative.IntelligentUiComponentNode.withoutStudyNodes(): app.amber.core.ai.generative.IntelligentUiComponentNode? {
    if (kind == IntelligentUiComponentKind.FLASHCARD || kind == IntelligentUiComponentKind.QUIZ_ITEM) {
        return null
    }
    val filteredChildren = children.mapNotNull { it.withoutStudyNodes() }
    val filteredSecondary = secondaryChildren.mapNotNull { it.withoutStudyNodes() }
    if (children.isNotEmpty() && filteredChildren.isEmpty() && text.isNullOrBlank()) {
        return null
    }
    return copy(children = filteredChildren, secondaryChildren = filteredSecondary)
}

@Composable
private fun TopLevelDecisionOptionRow(
    option: IntelligentUiOptionItem,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else tokens.surface2)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else tokens.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) accent else tokens.ink3, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = option.label,
                    fontFamily = AmberSans,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                    modifier = Modifier.weight(1f),
                )
                val badge = option.badge ?: if (option.recommended) "推荐" else null
                badge?.takeIf { it.isNotBlank() }?.let { b ->
                    InlineToneBadge(text = b, color = accent)
                }
            }
            option.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    fontFamily = AmberSans,
                    fontSize = 12.sp,
                    lineHeight = 17.5.sp,
                    color = tokens.ink2,
                )
            }
        }
    }
}
