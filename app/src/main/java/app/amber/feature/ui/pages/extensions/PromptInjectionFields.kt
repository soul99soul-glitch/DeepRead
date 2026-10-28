package app.amber.feature.ui.pages.extensions

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.amber.ai.core.MessageRole
import app.amber.agent.R
import app.amber.core.model.InjectionPosition
import app.amber.feature.ui.components.ui.Select

@Composable
internal fun InjectionPositionSelector(
    position: InjectionPosition,
    onSelect: (InjectionPosition) -> Unit
) {
    Select(
        options = InjectionPosition.entries,
        selectedOption = position,
        onOptionSelected = onSelect,
        optionToString = { getPositionLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun getPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    InjectionPosition.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    InjectionPosition.TOP_OF_CHAT -> stringResource(R.string.prompt_page_position_top_of_chat)
    InjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.prompt_page_position_bottom_of_chat)
    InjectionPosition.AT_DEPTH -> stringResource(R.string.prompt_page_position_at_depth)
}

@Composable
internal fun InjectionRoleSelector(
    role: MessageRole,
    onSelect: (MessageRole) -> Unit
) {
    Select(
        options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
        selectedOption = role,
        onOptionSelected = onSelect,
        optionToString = { getRoleLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun getRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.USER -> stringResource(R.string.prompt_page_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.prompt_page_role_assistant)
    else -> role.name
}
