package app.amber.feature.cron

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentCronNotificationTest {
    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private val taskId = "cron-test-task"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(
            CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, "Chat completed", NotificationManager.IMPORTANCE_LOW,
        ))
    }

    @After
    fun tearDown() = manager.cancelAll()

    @Test
    fun `success result remains after WorkManager removes the foreground notification`() {
        assertResultSurvivesForegroundCleanup("Cron succeeded")
    }

    @Test
    fun `failure result remains after WorkManager removes the foreground notification`() {
        assertResultSurvivesForegroundCleanup("Cron failed")
    }

    @Test
    fun `result id is stable and distinct from the foreground id`() {
        assertEquals(cronResultNotificationId(taskId), cronResultNotificationId(taskId))
        assertNotEquals(cronForegroundNotificationId(taskId), cronResultNotificationId(taskId))
    }

    private fun assertResultSurvivesForegroundCleanup(resultTitle: String) {
        manager.notify(cronForegroundNotificationId(taskId), notification("Cron running"))
        manager.notify(cronResultNotificationId(taskId), notification(resultTitle))

        // WorkManager removes the ForegroundInfo id after doWork posts its result.
        manager.cancel(cronForegroundNotificationId(taskId))

        val remaining = manager.activeNotifications
        assertEquals(1, remaining.size)
        assertEquals(resultTitle, remaining.single().notification.extras.getString(Notification.EXTRA_TITLE))
    }

    private fun notification(title: String): Notification =
        NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(title)
            .build()
}
