package app.amber.feature.ui.pages.chat

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyListState
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MessageNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * 一屏之外的图片提前加载。
 *
 * Coil 只在条目被组合时才开始请求，所以用户第一次滑到某条消息时会看到占位图。
 * 这里在可见范围变化时，把可见窗口前后各 [leadItems] 条消息里的图片 URL 预先
 * 塞进 Coil 的加载队列与内存缓存；滚入时直接命中，不再当场发起网络请求。
 *
 * 只预取窗口内的内容，不遍历整个对话——否则一次打开长会话会把所有历史图片都下载。
 * 已经显示过的图片由 Coil 内存缓存保留，滚出再滚回不会重新请求，因此这里不需要
 * 任何"释放"逻辑。
 */
@Composable
internal fun TimelineImagePrefetch(
    listState: LazyListState,
    plan: ChatTimelinePlan,
    messageNodes: List<MessageNode>,
    leadItems: Int = 8,
) {
    val context = LocalContext.current
    // 已入队过的 URL：滚动抖动会反复触发可见范围变化，重复 enqueue 没有意义。
    val requested = remember { LruCache<String, Boolean>(512) }
    LaunchedEffect(listState, plan, messageNodes) {
        // reverseLayout：index 0 是最新一条，可见范围两端直接可用于窗口计算。
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index
            val last = info.visibleItemsInfo.lastOrNull()?.index
            if (first == null || last == null) -1 to -1 else first to last
        }
            .distinctUntilChanged()
            .collect { (firstVisible, lastVisible) ->
                if (messageNodes.isEmpty()) return@collect
                val lastIndex = plan.entries.lastIndex
                val from = (firstVisible - leadItems).coerceAtLeast(0)
                val to = (lastVisible + leadItems).coerceAtMost(lastIndex)
                if (to < from) return@collect
                val urls = buildList {
                    for (lazyIndex in from..to) {
                        val messageIndex = plan.messageIndexAt(lazyIndex) ?: continue
                        val node = messageNodes.getOrNull(messageIndex) ?: continue
                        addAll(node.prefetchImageUrls())
                    }
                }
                if (urls.isEmpty()) return@collect
                val loader = SingletonImageLoader.get(context)
                withContext(Dispatchers.IO) {
                    urls.forEach { url ->
                        if (requested.put(url, true) == null) {
                            loader.enqueue(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .build(),
                            )
                        }
                    }
                }
            }
    }
}

/**
 * 一条消息里值得预取的图片 URL。
 *
 * 只取 http(s) 与本地文件：`data:` 内联图已经在消息体里，再入队只是复制一份内存。
 */
internal fun MessageNode.prefetchImageUrls(): List<String> {
    val urls = ArrayList<String>()
    messages.getOrNull(selectIndex)?.let { message ->
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Image -> urls += part.url
                is UIMessagePart.Text ->
                    // 正文里的 `![alt](url)` 和 Image part 一样是真实图片。
                    collectMarkdownImages(part.text).forEach { urls += it.url }
                else -> Unit
            }
        }
    }
    return urls.filter { it.isNotBlank() && !it.startsWith("data:") }
}
