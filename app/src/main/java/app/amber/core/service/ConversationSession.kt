package app.amber.core.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.amber.core.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

data class ConversationTimelineLoadState(
    val initialized: Boolean = false,
    val totalNodeCount: Int = 0,
    val loadedNodeCount: Int = 0,
    val oldestLoadedIndex: Int = 0,
    val isFullyLoaded: Boolean = true,
    val prefetchingOlder: Boolean = false,
)

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    val pendingUserMessages: StateFlow<List<PendingUserMessage>> = MutableStateFlow<List<PendingUserMessage>>(emptyList()).asStateFlow(),
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    private val _timelineLoadState = MutableStateFlow(ConversationTimelineLoadState())
    val timelineLoadState: StateFlow<ConversationTimelineLoadState> = _timelineLoadState.asStateFlow()

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()

    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating || pendingUserMessages.value.isNotEmpty()

    // 空闲检查任务
    @Volatile
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    @Synchronized
    fun setJob(job: Job?) {
        _generationJob.value?.cancel()
        _generationJob.value = job
        job?.invokeOnCompletion {
            if (_generationJob.value === job) {
                _generationJob.value = null
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    fun setTimelineLoadState(state: ConversationTimelineLoadState) {
        _timelineLoadState.value = state
    }

    internal fun refreshTimelineNodeCount() {
        _timelineLoadState.update { loadState ->
            if (!loadState.initialized) return@update loadState
            val loadedNodeCount = state.value.messageNodes.size
            loadState.copy(
                loadedNodeCount = loadedNodeCount,
                totalNodeCount = if (loadState.isFullyLoaded) {
                    loadedNodeCount
                } else {
                    maxOf(loadState.totalNodeCount, loadState.oldestLoadedIndex + loadedNodeCount)
                },
                isFullyLoaded = loadState.isFullyLoaded && loadState.oldestLoadedIndex == 0,
            )
        }
    }

    /**
     * Drop only volatile session state after a database restore. This deliberately
     * leaves queue invalidation to PendingMessageQueue, which owns that flow
     * and must not persist its pre-restore snapshot.
     */
    fun invalidateAfterRestore() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        state.value = Conversation.ofId(id = id)
        _timelineLoadState.value = ConversationTimelineLoadState()
        processingStatus.value = null
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
