package app.amber.core.automation

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Package text lookup and verified write/readback; never stores a native node. */
internal class AccessibilityEditableText(private val service: AccessibilityService) {
    fun setFocusedText(text: String): Boolean {
        val node = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 仲裁契约（蓝图 §7.2 P0-5）：读取目标包名输入框的当前文本；找不到返回 null。 */
    fun readTextInPackage(packageName: String): String? {
        val target = locateEditableInPackage(packageName) ?: return null
        return target.text?.toString()
    }

    /** 写入后回读验证：定位→写入→refresh→重读比对。MISMATCH = 写入未被目标接受
     *  （或被其它写入者并发覆盖），调用方据此熔断，不重试不排队。 */
    fun setTextInPackageVerified(packageName: String, text: String): LiveFillOutcome {
        val target = locateEditableInPackage(packageName) ?: return LiveFillOutcome.NOT_FOUND
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return LiveFillOutcome.NOT_FOUND
        }
        val readback = runCatching {
            target.refresh()
            locateEditableInPackage(packageName)?.text?.toString()
        }.getOrNull() ?: return LiveFillOutcome.UNKNOWN
        return if (readback == text) LiveFillOutcome.SUCCESS else LiveFillOutcome.MISMATCH
    }

    private fun locateEditableInPackage(packageName: String): AccessibilityNodeInfo? {
        if (packageName.isBlank()) return null
        for (window in service.windows.orEmpty()) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != packageName) continue
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findFirstEditable(root, depth = 0)
            if (target != null) return target
        }
        return null
    }

    private fun findFirstEditable(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IntArray = intArrayOf(0),
    ): AccessibilityNodeInfo? {
        // 节点数上限（Phase 7 检查建议 1）：目标进程主线程繁忙时每次 getChild 都是
        // binder 调用，无界遍历会阻塞 Amber 主线程。
        if (depth > 80 || visited[0] >= 400) return null
        visited[0]++
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditable(child, depth + 1, visited)?.let { return it }
        }
        return null
    }
}
