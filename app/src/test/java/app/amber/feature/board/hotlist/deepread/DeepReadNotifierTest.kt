package app.amber.feature.board.hotlist.deepread

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.DEEP_READ_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeepReadNotifierTest {
    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private lateinit var notifier: DeepReadNotifier
    private val topicId = "review-topic"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(
            DEEP_READ_NOTIFICATION_CHANNEL_ID, "Deep read", NotificationManager.IMPORTANCE_LOW,
        ))
        notifier = DeepReadNotifier(context)
    }

    @After
    fun tearDown() = manager.cancelAll()

    @Test
    fun `completion notice survives WorkManager foreground notification removal`() {
        postRunning()
        notifier.notifyCompleted(topicId, "测试阅读", "https://example.com", complete = true)
        removeForegroundNotification()
        assertEquals("completion must remain after foreground cleanup", 1, manager.activeNotifications.size)
        val notification = manager.activeNotifications.single().notification
        assertEquals(context.getString(R.string.deep_read_notification_completed_title), notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(shadowOf(notification.contentIntent).savedIntent.getBooleanExtra(DeepReadNotifier.EXTRA_FROM_HISTORY, false))
    }

    @Test
    fun `failure notice survives WorkManager foreground notification removal`() {
        postRunning()
        notifier.notifyFailed(topicId, "测试阅读", "https://example.com", "来源读取失败")
        removeForegroundNotification()
        assertEquals("failure must remain after foreground cleanup", 1, manager.activeNotifications.size)
        val notification = manager.activeNotifications.single().notification
        assertEquals(context.getString(R.string.deep_read_notification_failed_title), notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("来源读取失败"))
    }

    private fun postRunning() {
        manager.notify(DeepReadNotifier.notificationId(topicId), notifier.buildRunningNotification(topicId, "测试阅读", "https://example.com"))
    }

    private fun removeForegroundNotification() {
        // SystemForegroundDispatcher.onExecuted cancels the exact ID supplied in
        // DeepReadWorker's ForegroundInfo, after doWork has posted its terminal notice.
        manager.cancel(DeepReadNotifier.notificationId(topicId))
    }
}
