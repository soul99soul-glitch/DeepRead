package app.amber.agent

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.memory.dream.MemoryDreamScheduler
import app.amber.feature.cron.AgentCronManager
import app.amber.feature.reminder.ReminderScheduler
import app.amber.feature.board.collector.NotificationSignalCollector
import app.amber.feature.board.BoardSignalSourceType
import app.amber.feature.board.hotlist.HotListScheduler
import app.amber.feature.board.worker.BoardScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Process scheduling subscriptions; service providers resolve inside their original jobs. */
internal class AppBackgroundSchedulers(
    private val scope: AppScope,
    private val settings: () -> SettingsAggregator,
    private val cron: () -> AgentCronManager,
    private val reminders: () -> ReminderScheduler,
    private val memory: () -> MemoryDreamScheduler,
    private val notificationCollector: () -> NotificationSignalCollector,
    private val boardScheduler: () -> BoardScheduler,
    private val hotList: () -> HotListScheduler,
) {
    fun rescheduleCronTasks() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                cron().rescheduleAll()
            }.onFailure {
                Log.e("AmberAgentApp", "rescheduleCronTasks failed", it)
            }
        }
    }

    fun rescheduleReminders() {
        scope.launch(Dispatchers.IO) {
            runCatching { reminders().rescheduleAll() }
                .onFailure { Log.e("AmberAgentApp", "rescheduleReminders failed", it) }
        }
    }

    fun syncMemoryDreamTasks() {
        scope.launch(Dispatchers.IO) {
            val scheduler = memory()
            val settingsStore = settings()
            settingsStore.settingsFlow
                .map { it.agentRuntime.memoryWorker }
                .distinctUntilChanged()
                .collect {
                    runCatching {
                        scheduler.sync()
                    }.onFailure { error ->
                        Log.e("AmberAgentApp", "syncMemoryDreamTasks failed", error)
                    }
                }
        }
    }

    fun startBoardNotificationCollector() {
        scope.launch(Dispatchers.IO) {
            val settingsStore = settings()
            val collector = notificationCollector()
            settingsStore.settingsFlow
                .map {
                    val board = it.agentRuntime.todayBoard
                    board.enabled && BoardSignalSourceType.NOTIFICATION in board.enabledSources
                }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        collector.start()
                    } else {
                        collector.stop()
                    }
                }
        }
    }

    fun syncTodayBoardScheduler() {
        scope.launch(Dispatchers.IO) {
            val settingsStore = settings()
            val scheduler = boardScheduler()
            settingsStore.settingsFlow
                .map { it.agentRuntime.todayBoard }
                .distinctUntilChanged()
                .collect {
                    runCatching { scheduler.sync(settingsStore.settingsFlow.value) }
                        .onFailure { Log.e("AmberAgentApp", "syncTodayBoardScheduler failed", it) }
                }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : LifecycleEventObserver {
                private val lastForegroundCompensationMs = java.util.concurrent.atomic.AtomicLong(0L)

                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    if (event != Lifecycle.Event.ON_START) return
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val board = settings().settingsFlow.value.agentRuntime.todayBoard
                            if (!board.enabled) return@runCatching
                            // FOREGROUND_ONLY users explicitly chose no background work —
                            // respect that even for app-start compensation.
                            if (board.backgroundStrategy ==
                                app.amber.feature.board.TodayBoardBackgroundStrategy.FOREGROUND_ONLY
                            ) return@runCatching
                            val now = System.currentTimeMillis()
                            val last = lastForegroundCompensationMs.get()
                            if (now - last < board.foregroundCompensationGapMs) return@runCatching
                            // CAS guards against two rapid ON_START events passing the gap check.
                            if (!lastForegroundCompensationMs.compareAndSet(last, now)) return@runCatching
                            boardScheduler().runOnce()
                        }.onFailure { Log.e("AmberAgentApp", "foreground compensation failed", it) }
                    }
                }
            },
        )
    }

    fun syncHotListScheduler() {
        scope.launch(Dispatchers.IO) {
            val settingsStore = settings()
            val scheduler = hotList()
            settingsStore.settingsFlow
                .map { it.agentRuntime.todayBoard }
                .distinctUntilChanged()
                .collect {
                    runCatching { scheduler.sync(settingsStore.settingsFlow.value) }
                        .onFailure { Log.e("AmberAgentApp", "syncHotListScheduler failed", it) }
                }
        }
    }
}
