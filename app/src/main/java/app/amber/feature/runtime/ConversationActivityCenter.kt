package app.amber.feature.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 其他对话正在发生、但当前对话界面看不到的事。 */
enum class ConversationActivityKind {
    /** 该对话的生成 run 仍在运行。 */
    RUNNING,
}

/**
 * 一条跨对话活动提示。
 *
 * @param title 对话标题；查不到时回落为 id 前缀，绝不显示空白条目。
 */
data class ConversationActivityNotice(
    val conversationId: String,
    val title: String,
    val kind: ConversationActivityKind,
    val sinceMs: Long,
)

/**
 * 跨对话活动中心：把 [RunOwnershipRegistry] 的只读投影翻成"别处有事"的提示。
 *
 * 只报告**运行中**：失败/完成需要持久化的终态水位（RunTerminalStore），
 * 那是下一步增量，不在本轮冒充。
 *
 * 刻意不缓存标题：run 注册/注销的频率是"每轮一次"，标题又来自本地 DAO，
 * 为了省一次读而引入失效逻辑不划算。
 */
class ConversationActivityCenter(
    private val registry: RunOwnershipRegistry,
    /**
     * 标题解析：注入而不是直接依赖仓库，便于在没有数据库的单测里验证
     * "跳过当前对话 / 同对话去重 / 标题回落"这三条规则。
     */
    private val titleProvider: suspend (conversationId: String) -> String?,
) {
    fun notices(excluding: String?): Flow<List<ConversationActivityNotice>> =
        registry.activeRuns.map { runs ->
            // 同一对话多个 run（主 run + 重试）只留最早注册的一条，避免重复条目。
            val distinct = runs.filter { it.conversationId != excluding }
                .distinctBy { it.conversationId }
            distinct.map { run ->
                ConversationActivityNotice(
                    conversationId = run.conversationId,
                    title = titleFor(run.conversationId),
                    kind = ConversationActivityKind.RUNNING,
                    sinceMs = run.registeredAtMs,
                )
            }
        }

    /** `Flow.map` 的 lambda 本身是挂起的：这里直接 await，不要 runBlocking 阻塞收集方。 */
    private suspend fun titleFor(conversationId: String): String = try {
        titleProvider(conversationId)?.takeIf { it.isNotBlank() }
            ?: conversationActivityFallbackTitle(conversationId)
    } catch (error: kotlinx.coroutines.CancellationException) {
        // 取消必须继续向上传播；吞掉会让被取消的收集协程把剩下的条目也跑完。
        throw error
    } catch (_: Exception) {
        conversationActivityFallbackTitle(conversationId)
    }
}

/** 拿不到标题时的回落：id 前缀，绝不显示空白条目。 */
internal fun conversationActivityFallbackTitle(conversationId: String): String =
    conversationId.take(8)
