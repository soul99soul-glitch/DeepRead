package app.amber.feature.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.ai.generative.FlashcardMastery
import app.amber.core.ai.generative.IntelligentUiCardState
import app.amber.core.ai.generative.IntelligentUiComponentNode
import app.amber.core.ai.generative.IntelligentUiOptionItem
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.AmberSans
import app.amber.feature.ui.theme.LocalAmberTokens

private val StudyPositiveColor = Color(0xFF1F8A5B)
private val StudyWarningColor = Color(0xFFC2781E)
private val StudyDangerColor = Color(0xFFC93B2B)

/**
 * Interactive flashcard deck carousel for `study` task cards.
 */
@Composable
internal fun IntelligentUiFlashcardDeckSection(
    flashcards: List<IntelligentUiComponentNode>,
    state: IntelligentUiCardState,
    modifier: Modifier = Modifier,
) {
    if (flashcards.isEmpty()) return
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent

    val safeIndex = state.currentFlashcardIndex.coerceIn(0, flashcards.lastIndex)
    val currentCard = flashcards[safeIndex]
    val isFlipped = state.flippedFlashcards[currentCard.id] == true
    val currentMastery = state.flashcardMastery[currentCard.id] ?: FlashcardMastery.UNSEEN
    val masteredCount = flashcards.count { state.flashcardMastery[it.id] == FlashcardMastery.MASTERED }
    val reviewCount = flashcards.count { state.flashcardMastery[it.id] == FlashcardMastery.REVIEW }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                Text(
                    text = "记忆闪卡",
                    fontFamily = AmberSans,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.raised)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "${safeIndex + 1} / ${flashcards.size}",
                        fontFamily = AmberMono,
                        fontSize = 10.5.sp,
                        color = tokens.ink2,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "已掌握 $masteredCount",
                    fontFamily = AmberMono,
                    fontSize = 11.sp,
                    color = StudyPositiveColor,
                )
                if (reviewCount > 0) {
                    Text(
                        text = "待复习 $reviewCount",
                        fontFamily = AmberMono,
                        fontSize = 11.sp,
                        color = StudyWarningColor,
                    )
                }
            }
        }

        IntelligentUiSingleFlashcardNode(
            node = currentCard,
            state = state,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudyActionPillButton(
                    label = "需复习",
                    active = currentMastery == FlashcardMastery.REVIEW,
                    activeColor = StudyWarningColor,
                    onClick = {
                        state.flashcardMastery[currentCard.id] = FlashcardMastery.REVIEW
                        if (safeIndex < flashcards.lastIndex) {
                            state.currentFlashcardIndex = safeIndex + 1
                        }
                    },
                )
                StudyActionPillButton(
                    label = "已掌握",
                    active = currentMastery == FlashcardMastery.MASTERED,
                    activeColor = StudyPositiveColor,
                    onClick = {
                        state.flashcardMastery[currentCard.id] = FlashcardMastery.MASTERED
                        if (safeIndex < flashcards.lastIndex) {
                            state.currentFlashcardIndex = safeIndex + 1
                        }
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (flashcards.size > 1) {
                    StudyActionPillButton(
                        label = "上一张",
                        active = false,
                        activeColor = accent,
                        enabled = safeIndex > 0,
                        onClick = {
                            state.currentFlashcardIndex = (safeIndex - 1).coerceAtLeast(0)
                        },
                    )
                }
                StudyActionPillButton(
                    label = if (isFlipped && flashcards.size > 1) "下一张" else if (isFlipped) "翻回正面" else "翻面",
                    active = !isFlipped,
                    activeColor = accent,
                    enabled = !isFlipped || flashcards.size == 1 || safeIndex < flashcards.lastIndex,
                    onClick = {
                        if (!isFlipped || flashcards.size == 1) {
                            state.flippedFlashcards[currentCard.id] = !isFlipped
                        } else if (safeIndex < flashcards.lastIndex) {
                            state.currentFlashcardIndex = safeIndex + 1
                        }
                    },
                )
            }
        }
    }
}

/**
 * Single interactive flashcard surface that flips between front and back on tap.
 */
@Composable
internal fun IntelligentUiSingleFlashcardNode(
    node: IntelligentUiComponentNode,
    state: IntelligentUiCardState,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent
    val isFlipped = state.flippedFlashcards[node.id] == true
    val mastery = state.flashcardMastery[node.id] ?: FlashcardMastery.UNSEEN

    val frontText = node.front?.takeIf { it.isNotBlank() }
        ?: node.title?.takeIf { it.isNotBlank() }
        ?: node.body.orEmpty()
    val backText = node.back?.takeIf { it.isNotBlank() }
        ?: node.explanation?.takeIf { it.isNotBlank() }
        ?: node.body?.takeIf { it.isNotBlank() && it != frontText }
        ?: "（暂无背面释义）"

    val borderColor = when {
        mastery == FlashcardMastery.MASTERED -> StudyPositiveColor.copy(alpha = 0.45f)
        mastery == FlashcardMastery.REVIEW -> StudyWarningColor.copy(alpha = 0.45f)
        isFlipped -> accent.copy(alpha = 0.45f)
        else -> tokens.line2
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) {
                state.flippedFlashcards[node.id] = !isFlipped
            }
            .animateContentSize(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)),
        shape = RoundedCornerShape(14.dp),
        color = if (isFlipped) tokens.surface else tokens.surface2,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isFlipped) accent.copy(alpha = 0.14f) else tokens.raised)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (isFlipped) "背面 · 答案解析" else "正面 · 问题提示",
                        fontFamily = AmberMono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isFlipped) accent else tokens.ink3,
                    )
                }

                Text(
                    text = if (isFlipped) "点按翻回正面" else "点按翻看背面",
                    fontFamily = AmberMono,
                    fontSize = 10.5.sp,
                    color = tokens.ink3,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (isFlipped) backText else frontText,
                    fontFamily = AmberSans,
                    fontSize = if (isFlipped) 14.sp else 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = if (isFlipped) FontWeight.Normal else FontWeight.SemiBold,
                    color = tokens.ink,
                )

                val hint = node.hint?.takeIf { it.isNotBlank() } ?: node.subtitle?.takeIf { it.isNotBlank() }
                if (!isFlipped && hint != null) {
                    Text(
                        text = "提示：$hint",
                        fontFamily = AmberSans,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = tokens.ink3,
                    )
                }
            }

            if (mastery != FlashcardMastery.UNSEEN) {
                val badgeColor = if (mastery == FlashcardMastery.MASTERED) StudyPositiveColor else StudyWarningColor
                Text(
                    text = "状态：${mastery.label}",
                    fontFamily = AmberMono,
                    fontSize = 10.5.sp,
                    color = badgeColor,
                )
            }
        }
    }
}

/**
 * Interactive multiple-choice quiz question with instant grading and explanation reveal.
 */
@Composable
internal fun IntelligentUiQuizItemNode(
    node: IntelligentUiComponentNode,
    indexLabel: String?,
    state: IntelligentUiCardState,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent

    val questionText = node.title?.takeIf { it.isNotBlank() } ?: node.body.orEmpty()
    val selectedOptionValue = state.quizSelectedOption[node.id]
    val answered = !selectedOptionValue.isNullOrBlank()
    val isCorrect = answered && state.isQuizQuestionCorrect(node)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)),
        shape = RoundedCornerShape(14.dp),
        color = tokens.surface2,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!indexLabel.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(tokens.raised)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = indexLabel,
                            fontFamily = AmberMono,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = tokens.ink2,
                        )
                    }
                }
                Text(
                    text = questionText,
                    fontFamily = AmberSans,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                    modifier = Modifier.weight(1f),
                )
                if (answered) {
                    val statusColor = if (isCorrect) StudyPositiveColor else StudyDangerColor
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(statusColor.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (isCorrect) "正确" else "答错",
                            fontFamily = AmberMono,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = statusColor,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                node.options.forEachIndexed { idx, option ->
                    val optionSelected = selectedOptionValue != null && (
                        selectedOptionValue.equals(option.id, ignoreCase = true) ||
                            selectedOptionValue.equals(option.value, ignoreCase = true)
                        )
                    val optionIsExpected = isExpectedQuizOption(node, idx, option)

                    val rowBorder = when {
                        !answered -> tokens.line
                        optionSelected && isCorrect -> StudyPositiveColor
                        optionSelected && !isCorrect -> StudyDangerColor
                        !isCorrect && optionIsExpected -> StudyPositiveColor.copy(alpha = 0.65f)
                        else -> tokens.line
                    }
                    val rowBg = when {
                        !answered -> tokens.surface
                        optionSelected && isCorrect -> StudyPositiveColor.copy(alpha = 0.10f)
                        optionSelected && !isCorrect -> StudyDangerColor.copy(alpha = 0.10f)
                        !isCorrect && optionIsExpected -> StudyPositiveColor.copy(alpha = 0.07f)
                        else -> tokens.surface
                    }
                    val prefixLetter = ('A' + (idx % 26)).toString()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(rowBg)
                            .border(1.dp, rowBorder, RoundedCornerShape(10.dp))
                            .clickable(role = Role.RadioButton) {
                                state.quizSelectedOption[node.id] = option.value.ifBlank { option.id }
                            }
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        optionSelected && isCorrect -> StudyPositiveColor
                                        optionSelected && !isCorrect -> StudyDangerColor
                                        answered && optionIsExpected -> StudyPositiveColor.copy(alpha = 0.2f)
                                        optionSelected -> accent
                                        else -> tokens.raised
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = prefixLetter,
                                fontFamily = AmberMono,
                                fontSize = 11.sp,
                                lineHeight = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (optionSelected) Color.White else tokens.ink2,
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = option.label,
                                fontFamily = AmberSans,
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp,
                                color = tokens.ink,
                            )
                            option.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Text(
                                    text = desc,
                                    fontFamily = AmberSans,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    color = tokens.ink3,
                                )
                            }
                        }
                    }
                }
            }

            if (answered) {
                val explanationText = node.explanation?.takeIf { it.isNotBlank() }
                    ?: node.subtitle?.takeIf { it.isNotBlank() }
                if (explanationText != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.surface)
                            .border(0.5.dp, tokens.line2, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "答案解析",
                            fontFamily = AmberMono,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = tokens.ink3,
                        )
                        Text(
                            text = explanationText,
                            fontFamily = AmberSans,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = tokens.ink2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Live study score summary bar showing flashcard mastery, quiz accuracy, and wrong-question retry controls.
 */
@Composable
internal fun IntelligentUiScoreSummarySection(
    flashcards: List<IntelligentUiComponentNode>,
    quizNodes: List<IntelligentUiComponentNode>,
    state: IntelligentUiCardState,
    modifier: Modifier = Modifier,
) {
    if (flashcards.isEmpty() && quizNodes.isEmpty()) return
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    val accent = chatTheme.accent.takeIf { it.isSpecified } ?: tokens.accent

    val answeredCount = quizNodes.count { state.isQuizQuestionAnswered(it) }
    val correctCount = quizNodes.count { state.isQuizQuestionCorrect(it) }
    val wrongCount = (answeredCount - correctCount).coerceAtLeast(0)
    val masteredCards = flashcards.count { state.flashcardMastery[it.id] == FlashcardMastery.MASTERED }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface2,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "学习进度汇总",
                    fontFamily = AmberSans,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink2,
                )
                if (quizNodes.isNotEmpty()) {
                    val accuracyPct = if (answeredCount > 0) (correctCount * 100) / answeredCount else 0
                    Text(
                        text = if (answeredCount > 0) "正确率 $accuracyPct%" else "待作答 ${quizNodes.size} 题",
                        fontFamily = AmberMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (answeredCount > 0 && wrongCount == 0) StudyPositiveColor else accent,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (flashcards.isNotEmpty()) {
                    Text(
                        text = "闪卡已掌握 $masteredCards/${flashcards.size}",
                        fontFamily = AmberMono,
                        fontSize = 11.5.sp,
                        color = tokens.ink2,
                    )
                }
                if (quizNodes.isNotEmpty()) {
                    Text(
                        text = "测验答对 $correctCount/$answeredCount（共 ${quizNodes.size} 题）",
                        fontFamily = AmberMono,
                        fontSize = 11.5.sp,
                        color = tokens.ink2,
                    )
                }
            }

            if (wrongCount > 0 || state.retryWrongOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (wrongCount > 0) {
                        StudyActionPillButton(
                            label = "只练错题 ($wrongCount)",
                            active = state.retryWrongOnly,
                            activeColor = StudyWarningColor,
                            onClick = { state.startRetryWrongQuestions(quizNodes) },
                        )
                    }
                    if (state.retryWrongOnly) {
                        StudyActionPillButton(
                            label = "显示全部题目",
                            active = false,
                            activeColor = accent,
                            onClick = { state.exitRetryWrongQuestions() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyActionPillButton(
    label: String,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val bg = when {
        !enabled -> tokens.surface2
        active -> activeColor.copy(alpha = 0.15f)
        else -> tokens.surface
    }
    val border = when {
        !enabled -> tokens.line
        active -> activeColor.copy(alpha = 0.55f)
        else -> tokens.line2
    }
    val fg = when {
        !enabled -> tokens.ink4
        active -> activeColor
        else -> tokens.ink2
    }

    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = AmberSans,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun isExpectedQuizOption(
    node: IntelligentUiComponentNode,
    index: Int,
    option: IntelligentUiOptionItem,
): Boolean {
    if (node.correctIndex != null && node.correctIndex == index) return true
    val expected = node.correctOption?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return option.id.equals(expected, ignoreCase = true) ||
        option.value.equals(expected, ignoreCase = true) ||
        option.label.equals(expected, ignoreCase = true)
}
