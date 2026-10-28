package app.amber.feature.live

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.automation.LiveFillOutcome

/** A single service is captured for one fill attempt; read-back remains the service's verified path. */
internal interface LiveFillTarget {
    fun capture(ownPackageName: String, maxNodes: Int): LiveScreenSnapshot?
    fun readText(packageName: String): String?
    fun setTextVerified(packageName: String, text: String): LiveFillOutcome
}

private class AccessibilityLiveFillTarget(private val service: AmberAccessibilityService) : LiveFillTarget {
    override fun capture(ownPackageName: String, maxNodes: Int) = service.captureLiveUiSnapshot(ownPackageName, maxNodes)
    override fun readText(packageName: String) = service.readTextInPackage(packageName)
    override fun setTextVerified(packageName: String, text: String) = service.setTextInPackageVerified(packageName, text)
}

/** Process-local fill confirmation, denylist, fresh observation, verified write and clipboard. */
internal class LiveDraftFiller(
    private val context: Context,
    private val targetProvider: () -> LiveFillTarget? = {
        AmberAccessibilityService.getActiveService()?.let(::AccessibilityLiveFillTarget)
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var pendingFillConfirm: Triple<String, Int, Long>? = null
    private val fillDenylist = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isDenied(packageName: String): Boolean = packageName in fillDenylist

    /**
     * 屏幕写入仲裁（蓝图 §7.2 P0-5 + §7.3 P1-2 白名单填入）。决策核在
     *  [LiveFillPolicy]（纯逻辑，JVM 锁定）；这里做平台探测与执行：
     * 写入前现抓窗口身份与卡片来源比对（封死 runLoop tick 的同包切会话盲窗），
     * 目标框已有非草稿文本 → NEEDS_CONFIRM（5s 内二次点击 = 明确覆盖选择）；
     * 写入后回读不符 → 熔断该包名（进程级）并降级复制。冲突一律拒绝，不排队。
     */
    fun fill(state: LiveModeUiState, liveSetting: LiveModeSetting): LiveFillResult {
        val card = state.card ?: return LiveFillResult.NO_DRAFT
        val draft = card.suggestions.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: card.watching.takeIf { it.isNotBlank() }
            ?: return LiveFillResult.NO_DRAFT
        val targetPackage = state.currentPackage
        val target = targetProvider()
        if (target == null) {
            copyText(draft)
            return LiveFillResult.COPIED
        }

        // 同次观察重校验（Phase 7 检查 #1）：cardStale 依赖 runLoop tick（≤1.5s），
        // 同包切会话的盲窗内 stale 仍为 false——写入前现抓窗口身份兜底比对。
        val fresh = runCatching {
            target.capture(
                ownPackageName = context.packageName,
                maxNodes = liveSetting.maxNodes.coerceIn(40, 260),
            )
        }.getOrNull()
        val existing = target.readText(targetPackage)
        val confirmed = pendingFillConfirm?.let { (pkg, hash, at) ->
            pkg == targetPackage && hash == draft.hashCode() &&
                nowMillis() - at <= FILL_CONFIRM_WINDOW_MS
        } == true
        val decision = LiveFillPolicy.decide(
            cardStale = state.cardStale,
            scene = LiveScenes.classify(targetPackage, liveSetting.sceneOverrides),
            denied = targetPackage in fillDenylist,
            targetPresent = existing != null,
            contextMatches = fresh != null &&
                fresh.packageName == targetPackage && fresh.title == state.currentTitle,
            existingText = existing,
            draft = draft,
            confirmed = confirmed,
        )
        return when (decision) {
            LiveFillPolicy.Decision.COPY_STALE -> {
                copyText(draft)
                LiveFillResult.REJECTED_STALE
            }
            LiveFillPolicy.Decision.COPY -> {
                copyText(draft)
                LiveFillResult.COPIED
            }
            LiveFillPolicy.Decision.CONFIRM -> {
                pendingFillConfirm = Triple(targetPackage, draft.hashCode(), nowMillis())
                LiveFillResult.NEEDS_CONFIRM
            }
            LiveFillPolicy.Decision.FILL -> {
                pendingFillConfirm = null
                when (target.setTextVerified(targetPackage, draft)) {
                    LiveFillOutcome.SUCCESS -> LiveFillResult.FILLED
                    LiveFillOutcome.MISMATCH -> {
                        // 写入未被接受/被并发覆盖：熔断该包名，不自动重试。
                        fillDenylist += targetPackage
                        copyText(draft)
                        LiveFillResult.COPIED
                    }
                    LiveFillOutcome.NOT_FOUND, LiveFillOutcome.UNKNOWN -> {
                        copyText(draft)
                        LiveFillResult.COPIED
                    }
                }
            }
        }
    }

    fun copyText(draft: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("amber-live-draft", draft))
    }

}

/** FILLED=已写入目标框；COPIED=已复制剪贴板；NO_DRAFT=无草稿；
 *  NEEDS_CONFIRM=目标已有文本待用户明确覆盖；REJECTED_STALE=卡片脱离现场已改复制。 */
enum class LiveFillResult { FILLED, COPIED, NO_DRAFT, NEEDS_CONFIRM, REJECTED_STALE }
