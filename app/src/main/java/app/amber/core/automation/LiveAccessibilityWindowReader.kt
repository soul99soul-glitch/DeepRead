package app.amber.core.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.amber.feature.live.LiveScreenSnapshot
import app.amber.feature.live.LiveUiTreeProcessor
import app.amber.feature.live.LiveWindowCandidate

/** Live candidate selection and sanitized projection, separate from action validation. */
internal class LiveAccessibilityWindowReader(private val service: AccessibilityService) {
    fun captureLiveUiSnapshot(
        ownPackageName: String,
        maxNodes: Int = 180,
    ): LiveScreenSnapshot? {
        val windowSnapshots = service.windows.orEmpty().mapNotNull { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                return@mapNotNull null
            }
            val root = window.root ?: return@mapNotNull null
            val bounds = window.boundsOrRootBounds(root)
            val snapshot = captureRootWindow(
                root = root,
                windowTitle = window.title?.toString().orEmpty(),
                maxNodes = maxNodes,
                bounds = bounds,
            )
            val candidate = snapshot.toCandidate(
                ownPackageName = ownPackageName,
                type = window.type,
                layer = window.layer,
                active = window.isActive,
                focused = window.isFocused,
                splitDivider = false,
            )
            snapshot.copy(candidate = candidate, windowId = window.id).takeIf { candidate.isEligible() }
        }

        val bestWindow = windowSnapshots.maxByOrNull { it.candidate.selectionScore() }
        if (bestWindow != null) return bestWindow.toSnapshot()

        return service.rootInActiveWindow
            ?.let { root ->
                val bounds = Rect()
                root.getBoundsInScreen(bounds)
                captureRootWindow(
                    root = root,
                    windowTitle = "",
                    maxNodes = maxNodes,
                    bounds = bounds,
                )
            }
            ?.let { captured ->
                val candidate = captured.toCandidate(
                    ownPackageName = ownPackageName,
                    type = AccessibilityWindowInfo.TYPE_APPLICATION,
                    layer = 0,
                    active = true,
                    focused = true,
                    splitDivider = false,
                )
                captured.copy(candidate = candidate).takeIf { candidate.isEligible() }
            }
            ?.toSnapshot()
    }
    private fun captureRootWindow(
        root: AccessibilityNodeInfo,
        windowTitle: String,
        maxNodes: Int,
        bounds: Rect,
    ): CapturedWindow {
        val lines = mutableListOf<String>()
        val visibleTexts = linkedSetOf<String>()
        var visited = 0
        var title = LiveUiTreeProcessor.sanitizeText(windowTitle)

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= maxNodes) return
            visited++

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = if (node.isPassword) "" else LiveUiTreeProcessor.sanitizeText(node.text?.toString().orEmpty())
            val description = if (node.isPassword) "" else LiveUiTreeProcessor.sanitizeText(node.contentDescription?.toString().orEmpty())
            if (title.isBlank() && text.isNotBlank()) {
                title = text.take(80)
            }
            if (node.isVisibleToUser) {
                listOf(text, description)
                    .filter { it.isNotBlank() && it != "[已隐藏敏感内容]" }
                    .forEach { visibleTexts += it }
            }

            val className = node.className?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val parts = buildList {
                if (className.isNotBlank()) add("class=$className")
                if (viewId.isNotBlank()) add("id=$viewId")
                if (text.isNotBlank()) add("text=$text")
                if (description.isNotBlank()) add("desc=$description")
                add("bounds=${rect.toShortString()}")
                if (node.isClickable) add("clickable=true")
                if (node.isFocused) add("focused=true")
                if (node.isEditable) add("editable=true")
            }
            lines += "${"  ".repeat(depth)}- ${parts.joinToString(" | ")}"

            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child -> visit(child, depth + 1) }
            }
        }

        visit(root, 0)
        val packageName = root.packageName?.toString().orEmpty()
        val uiTree = LiveUiTreeProcessor.sanitizeUiTree(lines.joinToString("\n"))
        val visibleText = visibleTexts.joinToString("\n").take(8_000)
        return CapturedWindow(
            packageName = packageName,
            appLabel = appLabel(packageName),
            title = title,
            uiTree = uiTree,
            visibleText = visibleText,
            contentText = LiveUiTreeProcessor.compressContentText(visibleText, uiTree),
            nodeCount = visited,
            bounds = Rect(bounds),
        )
    }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String {
        if (packageName.isBlank()) return ""
        return runCatching {
            val info = service.packageManager.getApplicationInfo(packageName, 0)
            service.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun CapturedWindow.toSnapshot(): LiveScreenSnapshot =
        LiveScreenSnapshot(
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            uiTree = uiTree,
            visibleText = visibleText,
            contentText = contentText,
            windowDebugLabel = candidate.debugLabel(),
            nodeCount = nodeCount,
            windowId = windowId,
        )

    private fun CapturedWindow.toCandidate(
        ownPackageName: String,
        type: Int,
        layer: Int,
        active: Boolean,
        focused: Boolean,
        splitDivider: Boolean,
    ): LiveWindowCandidate =
        LiveWindowCandidate(
            type = type,
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            area = bounds.area(),
            visibleTextLength = contentText.length,
            visibleTextCount = contentText.lineSequence().count { it.isNotBlank() },
            nodeCount = nodeCount,
            layer = layer,
            active = active,
            focused = focused,
            ownApp = packageName == ownPackageName,
            splitDivider = splitDivider,
            systemLike = isSystemLikeWindow(type, packageName, appLabel, title),
        )

    private fun AccessibilityWindowInfo.boundsOrRootBounds(root: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        if (bounds.area() > 0) return bounds
        root.getBoundsInScreen(bounds)
        return bounds
    }

    private fun Rect.area(): Int =
        width().coerceAtLeast(0) * height().coerceAtLeast(0)

    private fun isSystemLikeWindow(type: Int, packageName: String, appLabel: String, title: String): Boolean {
        if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
        if (type == AccessibilityWindowInfo.TYPE_SYSTEM) return true
        val lower = listOf(packageName, appLabel, title).joinToString(" ").lowercase()
        return packageName in systemWindowPackages ||
            "canvas window" in lower ||
            "分屏分割线" in lower ||
            "split" in lower && "divider" in lower
    }

    private val systemWindowPackages = setOf(
        "android", "com.android.systemui", "com.miui.systemui", "com.miui.home", "com.miui.securitycenter",
    )
}

private data class CapturedWindow(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val uiTree: String,
    val visibleText: String,
    val contentText: String,
    val nodeCount: Int,
    val bounds: Rect,
    /** 候选窗口 id（takeScreenshotOfWindow 用）；fallback 路径为 -1。 */
    val windowId: Int = -1,
    val candidate: LiveWindowCandidate = LiveWindowCandidate(
        type = 0,
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        area = bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0),
        visibleTextLength = contentText.length,
        visibleTextCount = contentText.lineSequence().count { it.isNotBlank() },
        nodeCount = nodeCount,
    ),
)
