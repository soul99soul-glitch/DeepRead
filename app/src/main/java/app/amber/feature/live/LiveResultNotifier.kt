package app.amber.feature.live

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.amber.agent.R
import app.amber.core.utils.sendNotification

/** Completion notifications for Companion, separate from Chat/task live updates. */
internal class LiveResultNotifier(private val context: Context) {
    /**
     * 结果通知（蓝图 §7.3 P1-6 / §7.4 P2-2）：仅在气泡显示中（用户在外 App）时通知；
     * 伴随页在前台（Amber 前台时气泡隐藏）不打扰。点击打开伴随页。
     * 通知默认 VISIBILITY_PRIVATE（NotificationUtil 默认），锁屏不暴露第三方摘要。
     * auto=true 时标题区分"伴随建议"（P2 自动建议）与手动"伴随完成"。
     */
    fun notifyDone(actionLabel: String, watching: String, auto: Boolean = false) {
        val intent = Intent(context, app.amber.agent.RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(app.amber.agent.RouteActivity.EXTRA_OPEN_LIVE_COMPANION, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            LIVE_RESULT_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.sendNotification(
            channelId = app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = LIVE_RESULT_NOTIFICATION_ID,
        ) {
            title = context.getString(
                if (auto) R.string.live_notification_suggest_title else R.string.live_notification_result_title,
                actionLabel,
            )
            content = watching.take(120)
            smallIcon = R.drawable.amberagent_live_status_icon
            autoCancel = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = pending
        }
    }

    private companion object {
        const val LIVE_RESULT_NOTIFICATION_ID = 4207
    }
}
