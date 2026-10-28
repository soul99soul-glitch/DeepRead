package app.amber.core.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.amber.agent.AppScope
import app.amber.agent.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.ai.ui.UIMessage
import app.amber.core.utils.sendNotification
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/** Android presentation effects; the caller retains run and terminal ownership. */
class ChatRunPresenter(
    private val context: Context,
    private val appScope: AppScope,
    private val activityStore: AgentToolActivityStore,
    private val liveStatusNotifier: AgentLiveStatusNotifier,
    private val agentTaskScheduler: AgentTaskScheduler,
) {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }
    private val lifecycleObserverRegistration = appScope.launch(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() {
        lifecycleObserverRegistration.cancel()
        appScope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        }
    }

    fun updateLiveStatus(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String,
        enabled: Boolean,
        hideSensitive: Boolean,
        runId: String? = null,
    ) {
        if (!enabled) return
        liveStatusNotifier.notifyRunning(
            conversationId = conversationId,
            senderName = senderName,
            messages = messages,
            activity = activityStore.sandboxActivity.value
                ?.takeIf { it.conversationId == conversationId.toString() },
            hideSensitive = hideSensitive,
            launchIntent = { chatGenerationLaunchIntent(context, conversationId, runId) },
            runId = runId,
        )
    }

    fun notifyCompleted(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String,
        hideSensitive: Boolean,
        alert: Boolean,
        runId: String? = null,
    ) {
        liveStatusNotifier.notifyCompleted(
            conversationId = conversationId,
            senderName = senderName,
            messages = messages,
            hideSensitive = hideSensitive,
            alert = alert,
            launchIntent = chatGenerationLaunchIntent(context, conversationId, runId),
        )
    }

    fun notifyFailure(
        conversationId: Uuid,
        senderName: String,
        cause: Throwable,
        runId: String? = null,
    ) {
        liveStatusNotifier.notifyFailure(
            conversationId = conversationId,
            senderName = senderName,
            error = cause,
            launchIntent = chatGenerationLaunchIntent(context, conversationId, runId),
            runId = runId,
        )
    }

    fun cancelLiveStatus(conversationId: Uuid) {
        liveStatusNotifier.cancel(conversationId)
    }

    fun sendGenerationDoneNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String,
        runId: String? = null,
    ) {
        cancelLiveStatus(conversationId)
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = generationDoneNotificationId(conversationId, runId),
        ) {
            title = senderName
            content = messages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = chatGenerationLaunchIntent(context, conversationId, runId)
        }
    }

    suspend fun startGenerationTask(
        conversationId: Uuid,
        runId: String,
        senderName: String,
        modelName: String,
        autoRetry: Boolean,
        maxRetries: Int,
        cancel: suspend () -> Boolean,
    ): String {
        val taskId = "generation-$conversationId"
        val now = System.currentTimeMillis()
        runCatching {
            agentTaskScheduler.start(
                snapshot = AgentTaskSnapshot(
                    taskId = taskId,
                    type = "generation",
                    title = context.getString(R.string.generation_task_title),
                    spec = buildJsonObject {
                        put("run_id", runId)
                        put("sender", senderName)
                        put("model", modelName)
                        put("auto_retry", autoRetry)
                        put("max_retries", maxRetries)
                    },
                    sourceConversationId = conversationId.toString(),
                    sourceToolName = "chat_generation",
                    status = AgentTaskStatus.RUNNING,
                    createdAtMs = now,
                    updatedAtMs = now,
                    lastHeartbeatMs = now,
                    cancelCapability = true,
                    retryPolicy = AgentTaskRetryPolicy(
                        retryable = true,
                        requiresApproval = false,
                        maxRetries = maxRetries,
                        reason = "Temporary network or provider failures retry automatically during the live generation.",
                    ),
                ),
                cancel = cancel,
            )
        }.onFailure { error ->
            Log.w(TAG, "startGenerationTask failed for $conversationId", error)
        }
        return taskId
    }

    suspend fun finishGenerationTask(taskId: String, cause: Throwable?, runId: String) {
        val expectedSpec = agentTaskScheduler.read(taskId)?.spec
            ?.takeIf { it["run_id"]?.jsonPrimitive?.contentOrNull == runId }
            ?: return
        runCatching {
            when {
                cause == null -> agentTaskScheduler.complete(
                    taskId, summary = "Generation completed.", expectedSpec = expectedSpec,
                )
                cause is CancellationException -> agentTaskScheduler.fail(
                    taskId = taskId,
                    message = "Generation cancelled.",
                    code = "cancelled",
                    expectedSpec = expectedSpec,
                )

                else -> agentTaskScheduler.fail(
                    taskId = taskId,
                    message = cause.message ?: cause::class.java.simpleName,
                    code = "generation_failed",
                    expectedSpec = expectedSpec,
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "finishGenerationTask failed for $taskId", error)
        }
    }

    fun startGenerationKeepAlive(conversationId: Uuid, senderName: String, enabled: Boolean) {
        if (!enabled) return
        AgentGenerationForegroundService.start(
            context = context,
            conversationId = conversationId.toString(),
            title = senderName.ifBlank { context.getString(R.string.app_name) },
            content = context.getString(R.string.generation_keepalive_content),
        )
    }

    fun stopGenerationKeepAlive(conversationId: Uuid) {
        AgentGenerationForegroundService.stop(context, conversationId.toString())
    }
}

/** Preserve the notification's immutable conversation/run destination. */
internal fun chatGenerationLaunchIntent(
    context: Context,
    conversationId: Uuid,
    runId: String? = null,
): PendingIntent {
    val intent = Intent(context, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("conversationId", conversationId.toString())
        putExtra("focus", "conversation")
        if (runId != null) {
            putExtra("runId", runId)
        }
    }
    return PendingIntent.getActivity(
        context,
        generationNotificationPendingIntentRequestCode(conversationId, runId),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private const val TAG = "ChatRunPresenter"

/** Stable IDs keep concurrent conversations/runs from replacing each other's notifications. */
internal fun generationDoneNotificationId(conversationId: Uuid, runId: String?): Int =
    stableGenerationNotificationId("completed", conversationId, runId, offset = 30_000)

internal fun generationNotificationPendingIntentRequestCode(
    conversationId: Uuid,
    runId: String?,
): Int = stableGenerationNotificationId("pending-intent", conversationId, runId, offset = 40_000)

private fun stableGenerationNotificationId(
    kind: String,
    conversationId: Uuid,
    runId: String?,
    offset: Int,
): Int {
    val hash = "$kind|$conversationId|${runId.orEmpty()}".hashCode() and Int.MAX_VALUE
    return offset + (hash % 1_000_000)
}
