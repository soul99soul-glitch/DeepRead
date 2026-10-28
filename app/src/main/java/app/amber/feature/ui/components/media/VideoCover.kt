package app.amber.feature.ui.components.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频首帧封面。
 *
 * 只对本地文件取帧：`MediaMetadataRetriever` 读 http 会把整段视频拉下来，代价远大于
 * 一张缩略图。取帧失败、格式不支持或长度为零时返回 null，调用方回落到图标——
 * 不假装有封面，也不阻塞卡片显示。
 *
 * 进程内缓存按 URL 复用：同一段视频在列表里滚出再滚回、或在卡片与产物架同时出现，
 * 都不会重复解码。
 */
@Composable
fun rememberVideoCover(url: String): ImageBitmap? {
    var cover by remember(url) { mutableStateOf(VideoCoverCache.get(url)) }
    LaunchedEffect(url) {
        if (cover != null) return@LaunchedEffect
        val localPath = url.toLocalVideoPathOrNull() ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(localPath)
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()?.asImageBitmap()
        }
        if (loaded != null) {
            VideoCoverCache.put(url, loaded)
            cover = loaded
        }
    }
    return cover
}

/** `file://` 或绝对路径 → 本地路径；http(s) / content:// 一律不取帧。 */
private fun String.toLocalVideoPathOrNull(): String? = when {
    startsWith("http", ignoreCase = true) -> null
    startsWith("content:", ignoreCase = true) -> null
    startsWith("file://") -> removePrefix("file://")
    startsWith("/") -> this
    else -> null
}

private object VideoCoverCache {
    // 以像素字节计，避免几张 4K 帧就把进程顶爆。
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(url: String): ImageBitmap? = cache.get(url)

    fun put(url: String, bitmap: ImageBitmap) {
        cache.put(url, bitmap)
    }
}
