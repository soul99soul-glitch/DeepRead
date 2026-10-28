package app.amber.feature.ui.pages.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 通知深链请求流：RouteActivity request() 自增版本号 → 本页 collect 消费
 *  （滚动定位到结果卡 + accent 边框脉冲）。已处理水位由单例持有——NavDisplay 只组合
 *  栈顶，页面往返会重建 LaunchedEffect，局部水位会把旧请求重放成"莫名滚动"。 */
object LiveCompanionDeepLink {
    private val _requests = MutableStateFlow(0)
    val requests: StateFlow<Int> = _requests.asStateFlow()

    @Volatile
    private var lastHandled = 0

    fun request() {
        _requests.value++
    }

    /** version 未处理过则标记并返回 true（at-most-once：等待超时也算已处理，不重放）。 */
    fun claim(version: Int): Boolean {
        if (version <= lastHandled) return false
        lastHandled = version
        return true
    }
}
