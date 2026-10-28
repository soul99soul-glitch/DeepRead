package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Images
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.Wrench
import app.amber.agent.R
import app.amber.feature.ui.components.ui.Tag
import app.amber.feature.ui.components.ui.TagType
import app.amber.core.utils.toDp

/**
 * V3 model-picker.jsx CapIcon —— 14dp monochrome stroke 风的 capability 图标行。
 * 每个模型固定显示 chat，按 abilities 加 tool/sci，按 modalities 加 T>I/I>T 等。
 */
@Composable
private fun V3CapabilityIcons(model: Model, tint: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // chat — 所有 CHAT 模型默认显示
        if (model.type == ModelType.CHAT) {
            Icon(
                imageVector = Lucide.MessageCircle,
                contentDescription = "chat",
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
        // T>T or T>I —— 按 input/output modalities 推断
        val hasImg = model.outputModalities.fastAny { it == Modality.IMAGE } ||
            model.inputModalities.fastAny { it == Modality.IMAGE }
        Icon(
            imageVector = if (hasImg) Lucide.Images else Lucide.Type,
            contentDescription = if (hasImg) "image" else "text",
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
        // tool wrench —— TOOL ability
        if (model.abilities.contains(app.amber.ai.provider.ModelAbility.TOOL)) {
            Icon(
                imageVector = Lucide.Wrench,
                contentDescription = "tool",
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
        // sci/magic —— REASONING ability（设计稿是原子/sci 图标，库里最接近 AiMagic）
        if (model.abilities.contains(app.amber.ai.provider.ModelAbility.REASONING)) {
            Icon(
                imageVector = Lucide.WandSparkles,
                contentDescription = "reasoning",
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
    }
}

@Composable
fun ModelTypeTag(model: Model) {
    Tag(
        type = TagType.INFO
    ) {
        Text(
            text = stringResource(
                when (model.type) {
                    ModelType.CHAT -> R.string.setting_provider_page_chat_model
                    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                    ModelType.IMAGE -> R.string.setting_provider_page_image_model
                }
            )
        )
    }
}

@Composable
fun ModelModalityTag(model: Model) {
    Tag(
        type = TagType.SUCCESS
    ) {
        model.inputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> Lucide.Type
                    Modality.IMAGE -> Lucide.Images
                    Modality.AUDIO -> Lucide.Type
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
        Icon(
            imageVector = Lucide.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
        )
        model.outputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> Lucide.Type
                    Modality.IMAGE -> Lucide.Images
                    Modality.AUDIO -> Lucide.Type
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
    }
}

@Composable
fun ModelAbilityTag(model: Model) {
    model.abilities.fastForEach { ability ->
        when (ability) {
            ModelAbility.TOOL -> {
                Tag(
                    type = TagType.WARNING
                ) {
                    Icon(
                        imageVector = Lucide.Hammer,
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
                    )
                }
            }

            ModelAbility.REASONING -> {
                Tag(
                    type = TagType.INFO
                ) {
                    Icon(
                        painter = painterResource(R.drawable.deepthink),
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp()),
                    )
                }
            }
        }
    }
}

/** Model-family segment labels shared by the top menu and standalone reasoning control. */
internal fun reasoningLevelsForModel(model: Model): List<Pair<app.amber.ai.core.ReasoningLevel, String>> {
    val id = model.modelId.lowercase()
    return when {
        id.contains("deepseek") -> listOf(
            app.amber.ai.core.ReasoningLevel.OFF to "off",
            app.amber.ai.core.ReasoningLevel.HIGH to "high",
            app.amber.ai.core.ReasoningLevel.MAX to "max",
        )
        // Claude (Anthropic) extended thinking + adaptive auto：
        // Anthropic 在 claude-sonnet-4.5 / opus-4.1 起暴露 thinking_budget=auto，模型自己
        // 决定多少 token 用来思考（无人工指定 budget）。设计稿 model-picker.jsx 标 auto
        // 作为首段，方便用户日常聊天直接选 "让 Claude 自己决定" 而不用挑 low/med/high。
        // 段集：auto / low / med / high / xhigh / max (6 段)
        id.contains("claude") -> listOf(
            app.amber.ai.core.ReasoningLevel.AUTO to "auto",
            app.amber.ai.core.ReasoningLevel.LOW to "low",
            app.amber.ai.core.ReasoningLevel.MEDIUM to "med",
            app.amber.ai.core.ReasoningLevel.HIGH to "high",
            app.amber.ai.core.ReasoningLevel.XHIGH to "xhigh",
            app.amber.ai.core.ReasoningLevel.MAX to "max",
        )
        id.contains("gpt") || id.contains("codex") || id.contains("o1") || id.contains("o3") || id.contains("o4") -> listOf(
            app.amber.ai.core.ReasoningLevel.LOW to "low",
            app.amber.ai.core.ReasoningLevel.MEDIUM to "med",
            app.amber.ai.core.ReasoningLevel.HIGH to "high",
            app.amber.ai.core.ReasoningLevel.XHIGH to "xhigh",
        )
        id.contains("kimi") || id.contains("glm") || id.contains("zhipu") -> listOf(
            app.amber.ai.core.ReasoningLevel.OFF to "off",
            app.amber.ai.core.ReasoningLevel.AUTO to "auto",
        )
        else -> listOf(
            app.amber.ai.core.ReasoningLevel.OFF to "off",
            app.amber.ai.core.ReasoningLevel.AUTO to "auto",
        )
    }
}

@Composable
internal fun ThinkingLevelSegment(
    levels: List<Pair<app.amber.ai.core.ReasoningLevel, String>>,
    current: app.amber.ai.core.ReasoningLevel,
    onChange: (app.amber.ai.core.ReasoningLevel) -> Unit,
) {
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3 紧凑段控: 外 padding 1.5dp + 内段 vertical 1dp + 字号 10sp (再压扁一档)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(theme.surface)
            .border(
                BorderStroke(1.dp, theme.hair),
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        levels.forEach { (level, label) ->
            val isActive = level == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (isActive) theme.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onChange(level) }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    lineHeight = 13.sp,
                    color = if (isActive) theme.onAccent else theme.ink,
                    maxLines = 1,
                )
            }
        }
    }
}
