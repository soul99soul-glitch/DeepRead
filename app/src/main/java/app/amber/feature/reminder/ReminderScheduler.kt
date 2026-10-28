package app.amber.feature.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Platform-neutral alarm request, allowing JVM tests without a mock framework. */
data class ReminderAlarmRequest(
    val reminderId: String,
    val fireKey: String,
    val triggerAtEpochMs: Long,
)

enum class ReminderSchedulePrecision {
    EXACT,
    APPROXIMATE,
    CANCELLED,
}

data class ReminderScheduleResult(
    val reminderId: String,
    val fireKey: String?,
    val precision: ReminderSchedulePrecision,
    val userMessage: String,
)

interface ReminderAlarmOperations {
    fun scheduleExact(request: ReminderAlarmRequest)
    fun scheduleApproximate(request: ReminderAlarmRequest)
    fun cancel(reminderId: String)
}

/** AlarmManager adapter; it contains no reminder state or recurrence policy. */
class AndroidReminderAlarmOperations(private val context: Context) : ReminderAlarmOperations {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
        ?: error("AlarmManager unavailable")

    override fun scheduleExact(request: ReminderAlarmRequest) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            request.triggerAtEpochMs,
            pendingIntent(request),
        )
    }

    override fun scheduleApproximate(request: ReminderAlarmRequest) {
        val windowMs = 15L * 60L * 1000L
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            request.triggerAtEpochMs,
            windowMs,
            pendingIntent(request),
        )
    }

    override fun cancel(reminderId: String) {
        alarmManager.cancel(pendingIntent(ReminderAlarmRequest(reminderId, "cancel", 0L)))
    }

    private fun pendingIntent(request: ReminderAlarmRequest): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderStore.EXTRA_REMINDER_ID, request.reminderId)
            putExtra(ReminderStore.EXTRA_FIRE_KEY, request.fireKey)
            // Keep the PendingIntent identity stable across fire keys so cancel() can find it.
            data = ReminderReceiver.fireUri(request.reminderId, "stable")
        }
        return PendingIntent.getBroadcast(
            context,
            request.reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Reminder owner scheduler. Repeated calls in one process are idempotent; replacing an
 * alarm with the same PendingIntent is also safe across process restarts.
 */
class ReminderScheduler(
    private val store: ReminderStore,
    private val operations: ReminderAlarmOperations,
    private val exactAlarmAllowed: () -> Boolean,
) {
    private val mutex = Mutex()
    private val scheduled = ConcurrentHashMap<String, ScheduledMarker>()

    suspend fun schedule(snapshot: ReminderSnapshot): ReminderScheduleResult = mutex.withLock {
        // Store mutations complete before their callers can enter this lock. A
        // delayed caller must not replace a newer alarm with its older snapshot.
        scheduleLocked(snapshot.id)
    }

    suspend fun rescheduleAll(): List<ReminderScheduleResult> = mutex.withLock {
        store.list().map { snapshot ->
            // Keep one owner lock for the all-record operation. The inner
            // implementation avoids recursive Mutex acquisition.
            scheduleLocked(snapshot.id)
        }
    }

    suspend fun cancel(id: String) {
        mutex.withLock {
            operations.cancel(id)
            scheduled.remove(id)
        }
    }

    private fun scheduleLocked(id: String): ReminderScheduleResult {
        val snapshot = store.read(id)
        if (snapshot == null || snapshot.isCorrupt || !snapshot.enabled || snapshot.nextFireAtEpochMs == null) {
            operations.cancel(id)
            scheduled.remove(id)
            return ReminderScheduleResult(
                id,
                snapshot?.fireKey(),
                ReminderSchedulePrecision.CANCELLED,
                if (snapshot == null) "提醒已删除" else "提醒已停用或记录损坏",
            )
        }
        val key = snapshot.fireKey() ?: return ReminderScheduleResult(snapshot.id, null, ReminderSchedulePrecision.CANCELLED, "提醒没有下次触发时间")
        val precision = if (exactAlarmAllowed()) ReminderSchedulePrecision.EXACT else ReminderSchedulePrecision.APPROXIMATE
        val marker = ScheduledMarker(key, precision)
        if (scheduled[snapshot.id] != marker) {
            val request = ReminderAlarmRequest(snapshot.id, key, snapshot.nextFireAtEpochMs)
            if (precision == ReminderSchedulePrecision.EXACT) operations.scheduleExact(request) else operations.scheduleApproximate(request)
            scheduled[snapshot.id] = marker
        }
        return ReminderScheduleResult(
            snapshot.id,
            key,
            precision,
            if (precision == ReminderSchedulePrecision.EXACT) "按精确时间提醒" else "精确闹钟权限不可用，将在近似时间提醒",
        )
    }

    internal data class ScheduledMarker(val fireKey: String, val precision: ReminderSchedulePrecision)

    companion object {
        fun exactAlarmAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }
}

