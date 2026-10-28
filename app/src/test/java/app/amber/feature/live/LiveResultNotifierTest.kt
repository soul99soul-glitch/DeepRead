package app.amber.feature.live

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveResultNotifierTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun notificationsEnabled() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(
            CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID, "Companion test", NotificationManager.IMPORTANCE_DEFAULT,
        ))
    }

    @Test
    fun `completion uses private notification and the real companion route`() {
        LiveResultNotifier(context).notifyDone("summary", "observed screen", auto = false)
        val posted = manager.activeNotifications.single()
        val notification = posted.notification
        assertEquals(4207, posted.id)
        assertEquals(CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID, notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(context.getString(R.string.live_notification_result_title, "summary"), notification.extras.getString(Notification.EXTRA_TITLE))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(RouteActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(RouteActivity.EXTRA_OPEN_LIVE_COMPANION, false))
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
        assertTrue(notification.contentIntent.isImmutable)
    }

    @Test
    fun `automatic result replaces the previous completion and truncates the summary`() {
        val notifier = LiveResultNotifier(context)
        notifier.notifyDone("first", "first screen")
        notifier.notifyDone("reply", "x".repeat(200), auto = true)
        val notification = manager.activeNotifications.single().notification
        assertEquals(context.getString(R.string.live_notification_suggest_title, "reply"), notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("x".repeat(120), notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }
}
