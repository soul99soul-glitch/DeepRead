package app.amber.feature.ui.pages.live

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Settings
import app.amber.agent.Screen
import app.amber.agent.R
import app.amber.agent.data.db.entity.LiveCardEntity
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.utils.base64Encode
import app.amber.feature.live.LiveAnalysisMode
import app.amber.feature.live.LiveMotion
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.dokar.sonner.TextToastAction
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * AI 伴随 — Terminal × Modern graphite reskin. Layout follows the design handoff:
 * eyebrow header (// COMPANION ● + 标题), a master toggle card (主开关 + 暂停),
 * the last-analysis result section, and a CONFIG card (气泡 / 模型).
 * P1 语义（蓝图 v3 §7.3）：仅手动触发分析；截图分析可选（文字/截屏双模式，
 * 图树同次观察校验，截图即用即删）；草稿仅复制（填入白名单见 P1-C）。
 */
@Composable
fun LiveCompanionPage(vm: LiveCompanionVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val savedCards by vm.savedCards.collectAsStateWithLifecycle()
    val liveSetting = settings.agentRuntime.liveMode
    val tokens = LocalAmberTokens.current
    val scrollState = rememberScrollState()
    val companionModelId = remember(settings, liveSetting.companionModelId) {
        val id = liveSetting.companionModelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        (id?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel())?.modelId
    }

    // 伴随会话由页面主开关/气泡长按显式启停（蓝图 v3 §7.2 P0-1/P0-3）；
    // 离开页面不停止——气泡仍在屏上，Manager 归进程级域 owner。

    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val savedDeletedMessage = stringResource(R.string.live_saved_deleted)
    val savedUndoMessage = stringResource(R.string.live_saved_undo)

    // 深链定位：通知进入时滚动到结果卡锚点并做一次边框脉冲（ChatInput suggestionFillPulse 同款）。
    // 卡片可能还在冷恢复路上，最多等 1.5s。
    var resultTopPx by remember { mutableFloatStateOf(0f) }
    val resultPulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        LiveCompanionDeepLink.requests.collect { version ->
            if (!LiveCompanionDeepLink.claim(version)) return@collect
            // 卡片可能还在冷恢复路上，最多等 1.5s。
            withTimeoutOrNull(1_500L) { vm.state.first { it.card != null } } ?: return@collect
            // 等结果卡完成一次布局，锚点 onGloballyPositioned 才有真实 y。
            delay(200)
            val topOffset = with(density) { 16.dp.toPx() }
            scrollState.animateScrollTo((resultTopPx - topOffset).roundToInt().coerceAtLeast(0))
        resultPulse.snapTo(1f)
        resultPulse.animateTo(0f, tween(LiveMotion.PulseDecayMs, easing = LiveMotion.EaseOut))
        }
    }

    // 已保存行的延迟删除账本：先播离场动画，SAVED_DELETE_COMMIT_MS 后落库；
    // toast Undo 可取消在途删除；已落库则按原 createdAt 重插（位置不变）。
    var pendingDeletes by remember { mutableStateOf(setOf<Long>()) }
    val deleteJobs = remember { mutableStateMapOf<Long, Job>() }
    val onDeleteSaved: (LiveCardEntity) -> Unit = { saved ->
        pendingDeletes = pendingDeletes + saved.id
        deleteJobs.remove(saved.id)?.cancel()
        deleteJobs[saved.id] = scope.launch {
            delay(SAVED_DELETE_COMMIT_MS)
            vm.deleteSavedCard(saved.id)
            // 提交后清账：防 SQLite rowid 复用把"待删 id"错套到之后新保存的卡上（checker P1）。
            pendingDeletes = pendingDeletes - saved.id
        }
        toaster.show(
            message = savedDeletedMessage,
            action = TextToastAction(savedUndoMessage) {
                deleteJobs.remove(saved.id)?.cancel()
                pendingDeletes = pendingDeletes - saved.id
                vm.undoDeleteSavedCard(saved)
            },
        )
    }

    Scaffold(
        topBar = {
            LiveHeader(
                live = state.active && !state.paused && state.error == null,
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Screen.SettingAgentExecution) },
            )
        },
        modifier = Modifier.amberCanvas(),
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            // 区间距由各 section 自带 padding(top) 提供：条件区动画收起后可折叠到 0，不留双倍空隙。
        ) {
            // ── 主开关卡 ──
            MasterCard(
                state = state,
                onMaster = { on -> if (on) vm.start() else vm.stop() },
                onPauseResume = vm::pauseOrResume,
            )

            // ── 阻塞态引导 / 错误 / 进度：互斥优先级单槽（各自非空时成立，实践互斥），
            // 切换走 fade+slide 转场；空槽高度为 0。
            val alert = when {
                state.needsAccessibility -> LiveAlertKind.ACCESSIBILITY
                state.noModelConfigured -> LiveAlertKind.MODEL
                state.error?.isNotBlank() == true -> LiveAlertKind.ERROR
                state.requestedAction.isNotBlank() -> LiveAlertKind.ACTION
                !state.streamingText.isNullOrBlank() -> LiveAlertKind.STREAM
                else -> LiveAlertKind.NONE
            }
            AnimatedContent(
                targetState = alert,
                transitionSpec = {
                    (fadeIn(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)) +
                        slideInVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)) { it / 8 }) togetherWith
                        fadeOut(tween(LiveMotion.FastMs))
                },
                label = "liveAlertZone",
            ) { kind ->
                if (kind != LiveAlertKind.NONE) {
                    Box(modifier = Modifier.padding(top = 20.dp)) {
                        when (kind) {
                            LiveAlertKind.ACCESSIBILITY -> GuidanceCard(
                                title = stringResource(R.string.live_accessibility_required_title),
                                body = stringResource(R.string.live_accessibility_required_body),
                                action = stringResource(R.string.live_open_accessibility_settings),
                                onAction = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            )

                            LiveAlertKind.MODEL -> GuidanceCard(
                                title = stringResource(R.string.live_model_required_title),
                                body = stringResource(R.string.live_model_required_body),
                                action = stringResource(R.string.live_open_model_settings),
                                onAction = { navController.navigate(Screen.SettingModels) },
                            )

                            LiveAlertKind.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                ErrorNote(
                                    title = state.statusText,
                                    error = state.error.orEmpty(),
                                    retrying = state.nextAnalysisAfterMillis > System.currentTimeMillis(),
                                )
                                // 保留合并前的原语义：错误（如 backoff）与"新动作已排队"可同时出现。
                                if (state.requestedAction.isNotBlank()) {
                                    ActionProgressCard(state)
                                }
                            }

                            LiveAlertKind.ACTION -> ActionProgressCard(state)

                            LiveAlertKind.STREAM -> StreamingPreviewCard(state.streamingText.orEmpty())

                            LiveAlertKind.NONE -> {}
                        }
                    }
                }
            }

            // ── 上次分析 ──
            Column(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .onGloballyPositioned { resultTopPx = it.positionInParent().y },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel(stringResource(R.string.live_last_analysis))
                val card = state.card
                if (card != null) {
                    val actionKey = state.resultActionKey()
                    LiveResultCard(
                        card = card,
                        state = state,
                        modelId = companionModelId,
                        actionKey = actionKey,
                        stale = state.requestedAction.isNotBlank() || state.cardStale,
                        pendingAction = state.requestedAction,
                        enabled = state.active && !state.paused && !state.analyzing,
                        highlight = resultPulse.value,
                        onInstruction = vm::submitFocusInstruction,
                        onFillDraft = vm::fillDraft,
                        onSaveCard = {
                            vm.saveCard { saved ->
                                Toast.makeText(
                                    context,
                                    if (saved) R.string.live_saved_toast_saved else R.string.live_saved_toast_nothing,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onSendToChat = {
                            // Screen.Chat.text 的仓内契约是 base64 编码（ChatPage 无条件
                            // base64Decode；shortcuts/通知路径均先编码），原文会直接抛异常。
                            vm.exportCurrentCard()?.let { text ->
                                navController.navigate(
                                    Screen.Chat(
                                        kotlin.uuid.Uuid.random().toString(),
                                        text = text.base64Encode(),
                                    )
                                )
                            }
                        },
                        onRemember = {
                            vm.rememberCurrentCard()
                            Toast.makeText(context, R.string.live_remember_toast, Toast.LENGTH_SHORT).show()
                        },
                        onCopyCard = {
                            vm.copyCurrentCard { copied ->
                                toaster.show(
                                    context.getString(
                                        if (copied) R.string.live_card_copied else R.string.live_saved_toast_nothing,
                                    ),
                                )
                            }
                        },
                    )
                } else {
                    EmptyResultCard(state)
                }
            }

            // ── 已保存（历史区，蓝图 §7.3 P1-3）：整区显隐 + 行出入场动画，删除可撤销 ──
            AnimatedVisibility(
                visible = savedCards.isNotEmpty(),
                enter = fadeIn(tween(LiveMotion.MediumMs)) + expandVertically(tween(LiveMotion.SlowMs, easing = LiveMotion.EaseOut)),
                exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
            ) {
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionLabel(stringResource(R.string.live_saved_section))
                    AmberCard {
                        savedCards.take(5).forEachIndexed { index, saved ->
                            var shown by remember(saved.id) { mutableStateOf(false) }
                            LaunchedEffect(saved.id) { shown = true }
                            AnimatedVisibility(
                                visible = shown && saved.id !in pendingDeletes,
                                enter = fadeIn(tween(LiveMotion.FastMs)) + expandVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
                                exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
                            ) {
                                Column {
                                    // 上方行正在离场（待删）时隐藏本行 hairline，避免悬空线残留在卡片顶缘。
                                    if (index > 0 && savedCards[index - 1].id !in pendingDeletes) Hairline()
                                    SavedCardRow(card = saved, onDelete = { onDeleteSaved(saved) })
                                }
                            }
                        }
                        val hiddenCount = savedCards.size - 5
                        if (hiddenCount > 0) {
                            Hairline()
                            Text(
                                text = stringResource(R.string.live_saved_more, hiddenCount),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = LocalAmberType.current.secondary,
                                color = LocalAmberTokens.current.ink3,
                            )
                        }
                    }
                }
            }

            // ── CONFIG ──
            Column(
                modifier = Modifier.padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel(stringResource(R.string.live_config))
                ConfigCard(
                    aggressive = liveSetting.analysisMode == LiveAnalysisMode.AGGRESSIVE,
                    onSelectMode = { aggressive ->
                        vm.setAnalysisMode(if (aggressive) LiveAnalysisMode.AGGRESSIVE else LiveAnalysisMode.CONSERVATIVE)
                    },
                    bubbleEnabled = liveSetting.bubbleEnabled,
                    onToggleBubble = vm::setBubbleEnabled,
                    modelId = liveSetting.companionModelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                    providers = settings.providers,
                    onClearModel = { vm.setCompanionModel(null) },
                    onSelectModel = { vm.setCompanionModel(it) },
                    currentAppLabel = state.currentAppLabel.ifBlank { state.currentPackage },
                    sceneOverride = liveSetting.sceneOverrides[state.currentPackage],
                    onSelectScene = { scene ->
                        vm.setSceneOverride(state.currentPackage, scene)
                    },
                    autoSuggestEnabled = liveSetting.autoSuggestPackages.contains(state.currentPackage),
                    onToggleAutoSuggest = { vm.setAutoSuggestForCurrentApp(it) },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 条件区（引导/错误/进度/流式）的互斥槽位；NONE 时该区高度为 0。 */
private enum class LiveAlertKind { NONE, ACCESSIBILITY, MODEL, ERROR, ACTION, STREAM }

/** 已保存行离场动画时长，延迟到点后才真正落库删除（给 Undo 留取消窗口）。 */
private const val SAVED_DELETE_COMMIT_MS = 240L
