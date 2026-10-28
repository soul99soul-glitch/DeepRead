package app.amber.core.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Existing bounded text/tree reads used by approved primitive tools. */
internal class AccessibilityLegacyTreeReader(private val service: AccessibilityService) {
    fun activePackageName(): String? =
        service.rootInActiveWindow?.packageName?.toString()
    fun dumpUiTree(maxNodes: Int): String {
        val root = service.rootInActiveWindow ?: return ""
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (lines.size >= maxNodes) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val label = listOfNotNull(
                node.className?.toString(),
                node.viewIdResourceName,
                node.text?.toString()?.takeIf { it.isNotBlank() },
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            ).joinToString(" | ")
            lines += "${"  ".repeat(depth)}- $label bounds=$rect clickable=${node.isClickable} focused=${node.isFocused}"
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child -> visit(child, depth + 1) }
            }
        }
        visit(root, 0)
        return lines.joinToString("\n")
    }

    fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val results = mutableListOf<AccessibilityTextMatch>()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo) {
            if (visited >= maxNodes) return
            visited++
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val haystack = "$text\n$description"
            if (haystack.contains(needle, ignoreCase = true)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                results += AccessibilityTextMatch(
                    text = text,
                    contentDescription = description,
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName,
                    bounds = Rect(rect),
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                )
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(::visit)
            }
        }

        visit(root)
        return results
    }
}
