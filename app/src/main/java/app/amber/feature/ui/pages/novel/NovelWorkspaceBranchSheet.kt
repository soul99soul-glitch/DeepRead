package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.GitBranch

/**
 * 分支 sheet：分支列表（当前标出）、「新建分支」（从当前分支分叉）、点选切换。
 * 有活跃批次（running/paused）时切换项禁用并在顶部说明——批次按 job 绑定的分支写盘，
 * 切走后进度/审批/撤销会全部错位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BranchSheet(
    branches: List<NovelWorkspaceBranches.NovelWorkspaceBranchInfo>,
    branchLocked: Boolean,
    busy: Boolean,
    errorMessage: String?,
    onSwitch: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    var showCreate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = LocalAmberTokens.current.raised,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "//",
                    style = type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = chatTheme.accent,
                )
                Text(
                    stringResource(R.string.novel_branches_title),
                    style = type.sessionTitle.copy(fontWeight = FontWeight.Bold),
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.novel_branches_badge),
                    style = type.meta.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                    ),
                    color = LocalAmberTokens.current.ink4,
                )
            }
            if (branchLocked) {
                Text(
                    stringResource(R.string.novel_branch_locked),
                    style = type.meta,
                    color = workspace.red,
                )
            }
            if (errorMessage != null) {
                Text(errorMessage, style = type.meta, color = workspace.red)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = workspace.paper,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Column {
                    branches.forEachIndexed { index, branch ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(workspace.hairline),
                            )
                        }
                        val current = branch.isCurrent
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (current) workspace.row else Color.Transparent)
                                .clickable(enabled = !current && !branchLocked && !busy) {
                                    onSwitch(branch.slug)
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.GitBranch,
                                contentDescription = null,
                                tint = if (current) chatTheme.accent else LocalAmberTokens.current.ink3,
                                modifier = Modifier.size(15.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    branch.title.ifBlank { branch.slug },
                                    style = type.body.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                                    color = workspace.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!branch.isMain && branch.slug != branch.title) {
                                    Text(
                                        branch.slug,
                                        style = type.tinyTag,
                                        color = workspace.faint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (current) {
                                Text(
                                    stringResource(R.string.novel_current),
                                    style = type.tinyTag,
                                    color = chatTheme.accent,
                                )
                            }
                        }
                    }
                }
            }
            // 新建分支同样受批次锁约束：createBranch 复制文件、落 fork commit 与批次
            // Worker 的写盘/提交存在竞态，存储层对任何活跃批次（任何分支）都会拒绝，
            // 这里直接禁用入口而不是等报错。
            PanelCtaButton(
                text = stringResource(R.string.novel_new_branch),
                enabled = !busy && !branchLocked,
                onClick = { showCreate = true },
            )
            Text(
                stringResource(R.string.novel_branch_description),
                style = type.meta,
                color = workspace.muted,
            )
        }
    }

    if (showCreate) {
        NewBranchDialog(
            busy = busy,
            onSubmit = { name ->
                onCreate(name)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
}

/** 新建分支对话框：分支名（从当前分支分叉）。 */
@Composable
private fun NewBranchDialog(
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = workspace.paper,
        title = {
            Text(
                stringResource(R.string.novel_new_branch),
                fontWeight = FontWeight.SemiBold,
                color = workspace.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.novel_new_branch_description),
                    style = type.meta,
                    color = workspace.muted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.novel_branch_name_placeholder),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = { onSubmit(name) },
            ) {
                Text(stringResource(R.string.novel_create), color = workspace.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = workspace.muted)
            }
        },
    )
}
